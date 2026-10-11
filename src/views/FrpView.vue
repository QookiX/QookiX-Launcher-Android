<!--
  FRP 隧道页。

  只做三件事：**登录面板 → 看用户信息与隧道列表 → 启停隧道进程**。
  建隧道、联机房间都在面板网页上做，这里不提供入口（放了也是死按钮）。

  登录用「设备码」：显示一个用户码 + 打开浏览器授权页，用户点一下同意，
  客户端在后台按面板要求的节奏轮询换 Token —— 手机上不用输账号密码。

  隧道真正跑起来靠随包的 frpc（`libfrpc.so`）：它必须从 APK 的原生库目录执行，
  安卓 10+ 不允许执行应用可写目录里的文件。所以页面上有个「自检」能一眼看出
  这台设备到底允不允许 —— 不然用户只会看到「启动失败」而不知道原因。
-->
<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from "vue";
import { openUrl } from "@tauri-apps/plugin-opener";
import { api } from "../api";
import { t as $t } from "../i18n";
import { useMessage } from "../composables/message";
import AppSheet from "../ui/AppSheet.vue";

const message = useMessage();

type Status = Awaited<ReturnType<typeof api.frpStatus>>;
type Tunnel = Awaited<ReturnType<typeof api.frpTunnels>>[number];
type RunningInfo = Awaited<ReturnType<typeof api.frpRunning>>[number];

const status = ref<Status | null>(null);
const userInfo = ref<Record<string, unknown> | null>(null);
const tunnels = ref<Tunnel[]>([]);
const runningInfo = ref<RunningInfo[]>([]);
const loading = ref(false);
const loadError = ref("");

// 面板地址不再由前端持有：它写死在 `frp.rs` 的 `DEFAULT_API_BASE`，界面只负责显示。

// ── 设备码登录 ──────────────────────────────────────────────────────────
const loginShow = ref(false);
const loginUserCode = ref("");
const loginUri = ref("");
const loginState = ref<"idle" | "waiting" | "done" | "failed">("idle");
const loginMsg = ref("");
let loginCancelled = false;

// ── 自检 ────────────────────────────────────────────────────────────────
const probeBusy = ref(false);
const probeResult = ref("");

// ── 隧道操作 ────────────────────────────────────────────────────────────
const busyTunnel = ref<number | null>(null);
const logShow = ref(false);
const logTunnel = ref<Tunnel | null>(null);
const logText = ref("");
const logBusy = ref(false);

const runningMap = computed(() => {
  const m = new Map<number, RunningInfo>();
  for (const r of runningInfo.value) m.set(r.tunnelId, r);
  return m;
});

const balance = computed(() => {
  const v = userInfo.value;
  if (!v) return "";
  for (const k of ["balance", "coppers", "crystal"]) {
    const raw = v[k];
    if (raw !== undefined && raw !== null && `${raw}` !== "") return `${raw}`;
  }
  return "";
});

/**
 * 隧道统计：**从我们自己拉到的隧道列表算**，不用面板 `user_balance.php` 的 `tunnel_stats`。
 *
 * 为什么：那个统计和 `list.php` 的实际列表**对不上**（真机实测：界面显示"共 2·可用 2"、
 * 而列表里只有 1 条；更早一次是统计说 1 条、列表 0 条）——它显然把已删除/已过期的也算进去了。
 * 用户眼睛看到的是列表，统计就该跟列表一致，否则只会让人怀疑"我是不是建重复了"。
 */
const tunnelStats = computed(() => {
  const list = tunnels.value;
  if (!list.length && !status.value?.loggedIn) return "";
  const count = (s: string) => list.filter((t) => t.status === s).length;
  return [
    `${$t("frp.stat-total")} ${list.length}`,
    `${$t("frp.stat-active")} ${count("active")}`,
    `${$t("frp.stat-pending")} ${count("pending")}`,
    `${$t("frp.stat-expired")} ${count("expired")}`,
  ].join(" · ");
});

async function refresh() {
  loading.value = true;
  loadError.value = "";
  try {
    status.value = await api.frpStatus();
    runningInfo.value = await api.frpRunning();
    if (status.value.loggedIn) {
      // 用 Promise.allSettled：用户信息与隧道列表互不依赖，一个失败不该让另一边也空着
      const [info, list] = await Promise.allSettled([api.frpUserInfo(), api.frpTunnels()]);
      userInfo.value = info.status === "fulfilled" ? info.value : null;
      tunnels.value = list.status === "fulfilled" ? list.value : [];
      if (list.status === "rejected") loadError.value = String(list.reason);
    } else {
      userInfo.value = null;
      tunnels.value = [];
    }
  } catch (e) {
    loadError.value = String(e);
  } finally {
    loading.value = false;
  }
}



/** 用浏览器打开官网（面板站点）。建隧道 / 开房间 / 付费都在那边。 */
async function openSite() {
  const url = status.value?.apiBase ?? "";
  if (!url) {
    message.info($t("frp.site-unknown"));
    return;
  }
  try {
    await openUrl(url);
  } catch (e) {
    message.error(String(e));
  }
}

async function probe() {
  if (probeBusy.value) return;
  probeBusy.value = true;
  probeResult.value = "";
  try {
    const v = await api.frpProbe();
    probeResult.value = $t("frp.probe-ok", { p1: v });
  } catch (e) {
    probeResult.value = $t("frp.probe-fail", { p1: String(e) });
  } finally {
    probeBusy.value = false;
  }
}

function openLogin() {
  loginUserCode.value = "";
  loginUri.value = "";
  loginState.value = "idle";
  loginMsg.value = "";
  loginCancelled = false;
  loginShow.value = true;
  void beginLogin();
}

/** 发起设备注册，然后按面板要求的节奏轮询（后端每轮先等 interval 秒） */
async function beginLogin() {
  try {
    const s = await api.frpLoginStart();
    if (loginCancelled) return;
    loginUserCode.value = s.userCode;
    loginUri.value = s.verificationUri;
    loginState.value = "waiting";
    // 手机上的顺路做法：直接把授权页拉起来，用户点一下同意就完事
    if (s.verificationUri) void openUrl(s.verificationUri).catch(() => {});
    // 上限按「有效期 / 间隔 + 少量余量」，超时后如实说重来，别无限转
    const maxRounds = Math.max(3, Math.ceil(s.expiresIn / Math.max(1, s.interval)) + 2);
    for (let i = 0; i < maxRounds; i++) {
      if (loginCancelled) return;
      const r = await api.frpLoginPoll(s.session);
      if (loginCancelled) return;
      if (r.status === "authorized") {
        loginState.value = "done";
        loginMsg.value = $t("frp.login-done", { p1: r.username || "" });
        await refresh();
        setTimeout(() => (loginShow.value = false), 900);
        return;
      }
      if (r.status === "expired" || r.status === "denied") {
        loginState.value = "failed";
        loginMsg.value = r.message || $t("frp.login-failed");
        return;
      }
    }
    loginState.value = "failed";
    loginMsg.value = $t("frp.login-timeout");
  } catch (e) {
    if (loginCancelled) return;
    loginState.value = "failed";
    loginMsg.value = String(e);
  }
}

function cancelLogin() {
  loginCancelled = true;
  loginShow.value = false;
}

async function logout() {
  try {
    await api.frpLogout();
    message.success($t("frp.logout-done"));
    await refresh();
  } catch (e) {
    message.error(String(e));
  }
}

async function toggleTunnel(t: Tunnel) {
  if (busyTunnel.value !== null) return;
  busyTunnel.value = t.id;
  try {
    if (runningMap.value.has(t.id)) {
      await api.frpStopTunnel(t.id);
      message.success($t("frp.tunnel-stopped", { p1: t.name }));
    } else {
      const r = await api.frpStartTunnel(t.id);
      message.success($t("frp.tunnel-started", { p1: r.name, p2: r.address || "—" }));
    }
    await refresh();
  } catch (e) {
    // 启动失败时后端会把 frpc 的日志尾巴带回来 —— 原样给用户看，比"启动失败"有用得多
    message.error(`${$t("frp.tunnel-failed")}\n${String(e)}`);
  } finally {
    busyTunnel.value = null;
  }
}

async function openLog(t: Tunnel) {
  logTunnel.value = t;
  logShow.value = true;
  logBusy.value = true;
  try {
    logText.value = await api.frpTunnelLog(t.id);
  } catch (e) {
    logText.value = String(e);
  } finally {
    logBusy.value = false;
  }
}

async function copy(text: string) {
  try {
    await navigator.clipboard.writeText(text);
    message.success($t("frp.copied"));
  } catch {
    message.info(text);
  }
}

let timer: ReturnType<typeof setInterval> | null = null;
onMounted(async () => {
  await refresh();
  // 隧道进程可能自己退出（掉线/被面板踢），列表要能反映出来
  timer = setInterval(() => void api.frpRunning().then((r) => (runningInfo.value = r)).catch(() => {}), 4000);
});
onUnmounted(() => {
  if (timer) clearInterval(timer);
  // ⚠️ 必须把还开着的弹层关掉：弹层是 teleport 到 body 的全屏层，组件卸载时
  // 它的遮罩若还挂在 DOM 里，会**吃掉之后所有页面的点击** —— 实测症状就是
  // 「点登录跳浏览器 → 返回 → 切到设置，整页按钮全没反应」，强杀重启才恢复。
  loginCancelled = true;
  loginShow.value = false;
  logShow.value = false;
});
</script>

<template>
  <div class="frp">
    <!-- 联机房间：它**不是**另一个功能，而是 MohuaFrp 的另一种模式（XTCP：房主开房、
         客人加入，双方都要跑客户端），所以入口在这一页里面，而不是丢到侧栏或「更多」。 -->
    <div class="card glass">
      <h3>{{ $t("nav.frp-rooms") }}</h3>
      <p class="hint">{{ $t("multiplayer.rooms-desc") }}</p>
      <div class="btn-row">
        <button class="mini-btn" @click="$router.push('/frp-rooms')">{{ $t("nav.frp-rooms") }}</button>
      </div>
    </div>

    <!-- 状态与面板地址 -->
    <div class="card glass">
      <h3>{{ $t("frp.panel-title") }}</h3>
      <div class="row">
        <span class="k">{{ $t("frp.base") }}</span>
        <!-- 面板地址**写死**（`frp.rs` 的 `DEFAULT_API_BASE`）：这个客户端只对接这一家面板，
             让用户能改只会带来"地址填错 → 连不上 → 又不知道错在哪"的麻烦。 -->
        <span class="v addr text-ellipsis">{{ status?.apiBase ?? "…" }}</span>
      </div>
      <div class="btn-row">
        <button class="mini-btn" :disabled="probeBusy" @click="probe">
          {{ probeBusy ? $t("frp.probing") : $t("frp.probe") }}
        </button>
        <!-- 官网（就是面板站点）：建隧道、开房间、充值都在网页上做，所以给个直接入口。
             地址取后端返回的基址 —— 它写死在 frp.rs，前端不另存一份，避免两处不一致。 -->
        <button class="mini-btn" @click="openSite">{{ $t("frp.open-site") }}</button>
      </div>
      <p v-if="probeResult" class="hint">{{ probeResult }}</p>
      <p v-else-if="status && !status.frpc.ready" class="hint warn">{{ status.frpc.detail }}</p>
    </div>

    <!-- 未登录：登录入口 -->
    <div v-if="status && !status.loggedIn" class="card glass">
      <h3>{{ $t("frp.login-title") }}</h3>
      <p class="hint">{{ $t("frp.login-desc") }}</p>
      <button class="btn primary" @click="openLogin">{{ $t("frp.login") }}</button>
    </div>

    <!-- 已登录：账号信息 -->
    <div v-else-if="status" class="card glass">
      <h3>{{ $t("frp.account-title") }}</h3>
      <div class="row">
        <span class="k">{{ $t("frp.account-user") }}</span>
        <span class="v">{{ status.username || userInfo?.username || "—" }}</span>
      </div>
      <div v-if="userInfo?.email" class="row">
        <span class="k">{{ $t("frp.account-email") }}</span>
        <span class="v text-ellipsis">{{ userInfo.email }}</span>
      </div>
      <div v-if="balance" class="row">
        <span class="k">{{ $t("frp.account-balance") }}</span>
        <span class="v">{{ balance }}</span>
      </div>
      <div v-if="tunnelStats" class="row">
        <span class="k">{{ $t("frp.account-tunnels") }}</span>
        <span class="v">{{ tunnelStats }}</span>
      </div>
      <div class="btn-row">
        <button class="mini-btn" :disabled="loading" @click="refresh">{{ $t("frp.refresh") }}</button>
        <button class="mini-btn" @click="logout">{{ $t("frp.logout") }}</button>
      </div>
    </div>

    <p v-if="loadError" class="err">{{ loadError }}</p>

    <!-- 隧道列表 -->
    <template v-if="status?.loggedIn">
      <h2 class="sec">{{ $t("frp.tunnels-title") }}</h2>
      <p v-if="loading && !tunnels.length" class="hint">{{ $t("file-manager.loading") }}</p>
      <p v-else-if="!tunnels.length" class="hint">{{ $t("frp.tunnels-empty") }}</p>
      <div v-for="t in tunnels" :key="t.id" class="tun glass">
        <div class="tun-head">
          <span class="tun-name text-ellipsis">{{ t.name }}</span>
          <span class="badge" :class="t.status">{{ t.status }}</span>
        </div>
        <div class="tun-meta text-ellipsis">
          {{ t.protocol }} · {{ t.localPort }} → {{ t.address || "—" }}
          <template v-if="t.nodeName"> · {{ t.nodeName }}</template>
        </div>
        <div v-if="runningMap.get(t.id)?.alive" class="tun-addr">
          <span class="addr text-ellipsis">{{ runningMap.get(t.id)?.address || t.address }}</span>
          <button class="mini-btn" @click="copy(runningMap.get(t.id)?.address || t.address)">
            {{ $t("frp.copy") }}
          </button>
        </div>
        <p v-if="runningMap.get(t.id) && !runningMap.get(t.id)?.alive" class="hint warn">
          {{ $t("frp.tunnel-exited", { p1: runningMap.get(t.id)?.exit ?? "" }) }}
        </p>
        <div class="btn-row">
          <button
            class="mini-btn"
            :class="{ danger: runningMap.get(t.id)?.alive }"
            :disabled="busyTunnel === t.id"
            @click="toggleTunnel(t)"
          >
            {{ runningMap.get(t.id)?.alive ? $t("frp.stop") : $t("frp.start") }}
          </button>
          <button v-if="runningMap.get(t.id)" class="mini-btn" @click="openLog(t)">
            {{ $t("frp.view-log") }}
          </button>
        </div>
      </div>
    </template>

    <!-- 设备码登录面板 -->
    <app-sheet v-model:show="loginShow" :title="$t('frp.login-title')" @mask-click="cancelLogin">
      <div class="login">
        <p class="hint">{{ $t("frp.login-step1") }}</p>
        <div v-if="loginUserCode" class="code">{{ loginUserCode }}</div>
        <p v-if="loginUri" class="login-uri">
          <a @click="openUrl(loginUri)">{{ loginUri }}</a>
        </p>
        <p v-if="loginState === 'waiting'" class="hint">{{ $t("frp.login-waiting") }}</p>
        <p v-else-if="loginMsg" class="hint" :class="{ warn: loginState === 'failed' }">{{ loginMsg }}</p>
        <div class="btn-row">
          <button class="mini-btn" @click="cancelLogin">{{ $t("common.cancel") }}</button>
        </div>
      </div>
    </app-sheet>

    <!-- frpc 日志 -->
    <app-sheet v-model:show="logShow" :title="$t('frp.log-title', { p1: logTunnel?.name ?? '' })">
      <div class="logbox">
        <p v-if="logBusy" class="hint">{{ $t("file-manager.loading") }}</p>
        <pre v-else>{{ logText || $t("frp.log-empty") }}</pre>
      </div>
    </app-sheet>
  </div>
</template>

<style scoped>
.frp {
  padding: 8px 16px 24px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
/*
  按钮样式必须在本组件里定义：`mini-btn` / `btn` 都是**别的组件的 scoped 类**
  （scoped 样式不跨组件生效），靠它们会退化成浏览器默认按钮 —— 白底，
  而全局按钮文字色是浅色，于是白底白字，看起来就是「几个空白方块」。
*/
.mini-btn {
  min-height: 34px;
  padding: 6px 14px;
  border-radius: 17px;
  border: 1px solid var(--border);
  background: var(--panel);
  color: var(--text-1);
  font-family: inherit;
  font-size: 13px;
}
.mini-btn.danger {
  color: #e5534b;
  border-color: #e5534b;
}
.mini-btn:disabled {
  opacity: 0.5;
}
.btn.primary {
  width: 100%;
  min-height: 44px;
  border: none;
  border-radius: 12px;
  background: var(--accent);
  color: #1a1206;
  font-family: inherit;
  font-size: 15px;
  font-weight: 600;
}
.card {
  padding: 14px;
  border-radius: 14px;
}
.card h3 {
  margin: 0 0 10px;
  font-size: 14px;
}
.sec {
  margin: 6px 2px 0;
  font-size: 13px;
  color: var(--text-3);
}
.hint {
  margin: 8px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-3);
}
.hint.warn {
  color: #e5a13b;
}
.err {
  margin: 0;
  font-size: 12px;
  color: #e5534b;
  word-break: break-all;
}
.row {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 32px;
  font-size: 13px;
}
.row .k {
  color: var(--text-3);
  flex-shrink: 0;
}
.row .v {
  color: var(--text-1);
  min-width: 0;
}
.addr {
  font-family: ui-monospace, monospace;
  font-size: 12px;
}
.base-edit {
  flex: 1;
  min-width: 0;
}
.btn-row {
  display: flex;
  gap: 8px;
  margin-top: 10px;
  flex-wrap: wrap;
}
.tun {
  padding: 12px 14px;
  border-radius: 14px;
}
.tun-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.tun-name {
  font-size: 14px;
  font-weight: 600;
  min-width: 0;
}
.badge {
  margin-left: auto;
  font-size: 11px;
  padding: 1px 8px;
  border-radius: 999px;
  border: 1px solid var(--border);
  color: var(--text-3);
}
.badge.active {
  color: var(--accent);
  border-color: var(--accent);
}
.tun-meta {
  margin-top: 4px;
  font-size: 12px;
  color: var(--text-3);
}
.tun-addr {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
}
.tun-addr .addr {
  flex: 1;
  min-width: 0;
  color: var(--accent);
}
.login {
  padding: 4px 2px 8px;
}
.code {
  margin: 10px 0;
  text-align: center;
  font-size: 26px;
  letter-spacing: 3px;
  font-weight: 600;
  font-family: ui-monospace, monospace;
  color: var(--accent);
}
.login-uri {
  margin: 0;
  font-size: 12px;
  text-align: center;
  word-break: break-all;
}
.login-uri a {
  color: var(--accent);
}
.logbox pre {
  margin: 0;
  max-height: 55vh;
  overflow: auto;
  font-size: 11px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
  color: var(--text-2);
}
</style>
