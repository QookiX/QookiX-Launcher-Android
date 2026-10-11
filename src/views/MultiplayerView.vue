<script setup lang="ts">
import { t as $t } from "../i18n";
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useMessage } from "../composables/message";
import { useDialog } from "../composables/dialog";
import { useRouter } from "vue-router";
import { useServersStore } from "../stores/servers";
import { useAccountsStore } from "../stores/accounts";
import { api } from "../api";
import { Button as VanButton, Empty as VanEmpty } from "vant";
import { IconFolder, IconPlus, IconSettings, IconTrash } from "../components/icons";
import AppPopup from "../ui/AppPopup.vue";
import AppInput from "../ui/AppInput.vue";
import AppSelect from "../ui/AppSelect.vue";

const servers = useServersStore();
const accounts = useAccountsStore();
const message = useMessage();
const dialog = useDialog();
const router = useRouter();

/** 进入服务器详情页（控制面板：状态 / 日志 / 控制台 / 联机地址 / 参数）。
 *  `tab` 可选：卡片上的「文件 / 设置」图标直达对应页签，不用进了详情页再翻。 */
function openDetail(id: string, tab?: string) {
  void router.push(tab ? `/multiplayer/${id}?tab=${tab}` : `/multiplayer/${id}`);
}
const showCreate = ref(false);const form = ref({ name: "", core: "paper", mcVersion: "" });
const saving = ref(false);
const starting = ref("");

/**
 * 服务端核心：**只列后端真的实现了的**（`src-tauri/src/servers.rs` 里只有
 * Paper / Vanilla / Fabric 三条下载链路，其余核心会直接报「还在开发中」✗）。
 * 以前这里 `core` 被写死成 `paper` 且界面上没有入口 ✗ —— 想装原版都没办法。
 */
const SERVER_CORES = [
  { label: "Paper", value: "paper" },
  { label: "Vanilla 原版", value: "vanilla" },
  { label: "Fabric（实验）", value: "fabric" },
];

/**
 * 卡片副标题上用的核心短名。
 *
 * `SERVER_CORES` 那份 label 是给创建表单看的（「Vanilla 原版」「Fabric（实验）」），
 * 塞进副标题太长 —— 副标题要的是「Paper 1.21.4 · 最多 20 名玩家」这种一眼扫完的东西
 * （照竞品 Anvil 的卡片写法）。
 */
const CORE_SHORT: Record<string, string> = {
  paper: "Paper",
  vanilla: "Vanilla",
  fabric: "Fabric",
  neoforge: "NeoForge",
};
function coreShort(core: string) {
  return CORE_SHORT[core] ?? core;
}

/**
 * 可选游戏版本：与「创建实例」用**同一份官方清单**（`api.getVersionManifest()`）。
 * 以前这里是 `app-input` 让用户**手打版本号** ✗ —— 占位符却写着"选择版本" ✗，
 * 打错一个字就变成"Paper 暂无该版本的构建"，而且没法知道有哪些可选。
 */
const versionOptions = ref<{ label: string; value: string }[]>([]);
const versionsLoading = ref(false);
const versionsError = ref("");

/**
 * 可选版本**跟着核心走**：
 * - Paper：只能用 Paper 自己的版本清单。它并不支持所有 MC 版本 —— 1.8 那条线
 *   只有 1.8.8（`1.8.9` 不存在），选 1.8.9 会在装核心时拿到 404。
 * - 原版 / Fabric：用官方 version manifest（与「创建实例」同一份）。
 * 每个核心的清单缓存一份，来回切核心不用重复请求。
 */
const versionCache = ref<Record<string, { label: string; value: string }[]>>({});

async function loadGameVersions() {
  const core = form.value.core;
  const cached = versionCache.value[core];
  if (cached?.length) {
    versionOptions.value = cached;
    ensureVersionValid();
    return;
  }
  versionsLoading.value = true;
  versionsError.value = "";
  try {
    if (core === "paper") {
      versionOptions.value = (await api.listPaperVersions()).map((v) => ({ label: v, value: v }));
    } else {
      const m = await api.getVersionManifest();
      versionOptions.value = m.versions
        .filter((v) => v.type === "release")
        .map((v) => ({ label: v.id, value: v.id }));
    }
    versionCache.value = { ...versionCache.value, [core]: versionOptions.value };
    ensureVersionValid();
  } catch (e) {
    versionsError.value = $t("multiplayer.version-failed", { p1: String(e) });
  } finally {
    versionsLoading.value = false;
  }
}

/** 当前选中的版本不在新清单里（多半是刚换过核心）就落到最新那个，别让「创建」一直禁用 */
function ensureVersionValid() {
  if (!versionOptions.value.some((o) => o.value === form.value.mcVersion)) {
    form.value.mcVersion = versionOptions.value[0]?.value ?? "";
  }
}

watch(showCreate, (open) => { if (open) void loadGameVersions(); });
// 换核心要重新取清单：两边的版本集合不一样
watch(() => form.value.core, () => { form.value.mcVersion = ""; void loadGameVersions(); });

async function createServer() {
  // 名称留空是允许的（输入框提示「留空则自动命名」）：后端会兜底成游戏版本号。
  // 之前这里把空名也算作「不可创建」，加上按钮的 disabled，留空就再也点不动了。
  if (!form.value.mcVersion) return;
  saving.value = true;
  try {
    const s = await servers.create(form.value.name.trim(), form.value.core, form.value.mcVersion);
    showCreate.value = false;
    message.success($t("downloads.finished"));
    // 后台装核心：失败必须说出来 —— 原来 `void installCore()` 把错误吞成未处理的
    // Promise 拒绝，用户只看到「创建成功」，进详情页才发现核心没有。
    void servers.installCore(s.id).catch((e) => message.error(String(e)));
  } catch (e) {
    message.error(String(e));
  } finally {
    saving.value = false;
  }
}

async function toggleRun(id: string) {
  starting.value = id;
  try {
    if (servers.isRunning(id)) await servers.stop(id);
    else await servers.start(id);
  } catch (e) {
    message.error(String(e));
  } finally {
    starting.value = "";
  }
}

/**
 * 删除服务器。
 *
 * 必须二次确认：删除会**连整个服务端目录一起删掉**（世界存档、插件、配置全没了），
 * 而手机上的「删除」是个小号行内按钮、紧挨着「启动」，误触代价不可逆。
 */
function confirmRemove(s: { id: string; name: string }) {
  dialog.warning({
    title: $t("multiplayer.delete-server"),
    content:
      $t("multiplayer.delete-server-confirm-a") + s.name + $t("multiplayer.delete-server-confirm-b"),
    positiveText: $t("common.delete"),
    negativeText: $t("common.cancel"),
    onPositiveClick: async () => {
      try {
        await servers.remove(s.id);
        message.success($t("multiplayer.deleted", { p1: s.name }));
      } catch (e) {
        message.error(String(e));
      }
    },
  });
}


// ── 陶瓦联机（Terracotta）──────────────────────────────────────────────
// 隧道跑在独立进程 :tunnel（libterracotta.so 只在那里加载），这里只通过 HTTP 调它，
// 启动器不链接该库 —— 满足上游 AGPL 例外条款，保持 GPL-3.0（署名见页面底部）。
const tcState = ref<"off" | "ready" | "hosting" | "guesting">("off");
const tcRoom = ref("");
const tcRoomInput = ref("");
const tcBusy = ref(false);
let tcTimer: ReturnType<typeof setInterval> | null = null;

const tcHint = computed(() => {
  if (tcState.value === "off") return $t("terracotta.hint-off");
  if (tcState.value === "ready") return $t("terracotta.hint-ready");
  if (tcState.value === "guesting") return $t("terracotta.hint-guesting");
  return tcRoom.value ? $t("terracotta.hint-hosting") : $t("terracotta.hint-scanning");
});

/**
 * 调隧道接口，失败自动重试几次。
 *
 * 冷启动时隧道要 `loadLibrary` + 初始化 EasyTier（手机上还跑着服务器 JVM 时更慢），
 * 头几秒必然连不上。直接把这句错误弹给用户（"隧道还在启动中，请稍后重试"）的话，
 * 用户只能看着干等 —— 自己重试几次，多数情况点一下就成了。
 */
async function tcCall(path: string, tries = 3): Promise<string> {
  let last = "";
  for (let i = 0; i < tries; i++) {
    try {
      return await api.terracottaTunnelRequest(path);
    } catch (e) {
      last = String(e);
      if (i < tries - 1) await new Promise((r) => setTimeout(r, 2000));
    }
  }
  throw last;
}

/** 拉一次隧道状态。Terracotta 返回形如 {"index":1,"state":"host-scanning"} */
async function tcRefresh() {
  try {
    const raw = await api.terracottaTunnelRequest("/state");
    const s = JSON.parse(raw) as { state?: string; room?: string; code?: string };
    const st = s.state ?? "";
    if (st.includes("host")) {
      tcState.value = "hosting";
      tcRoom.value = String(s.room ?? s.code ?? "");
    } else if (st.includes("guest")) {
      tcState.value = "guesting";
    } else {
      tcState.value = "ready";
      tcRoom.value = "";
    }
  } catch {
    tcState.value = "off";
    tcRoom.value = "";
  }
}

/**
 * 开房前确保拿到「VPN」系统授权（陶瓦的数据面要走 TUN，否则客人连不进来）。
 * 详见 ServerDetailView 里的同名函数。
 */
async function ensureTcVpn(): Promise<boolean> {
  try {
    if (await api.terracottaVpnGranted()) return true;
    await api.terracottaRequestVpn();
    for (let i = 0; i < 30; i++) {
      await new Promise((r) => setTimeout(r, 1000));
      if (await api.terracottaVpnGranted()) return true;
    }
    message.warning($t("terracotta.vpn-denied"));
    return false;
  } catch {
    return await api.terracottaVpnGranted();
  }
}

async function startHosting() {
  tcBusy.value = true;
  try {
    // 先探一次活：隧道可能刚被系统回收（端口文件是上次留下的，照着发请求只会
    // 撞上「连接被拒」这种看不懂的报错）。这里给它一次自愈的机会。
    await tcRefresh();
    if (tcState.value === "off") {
      message.warning($t("terracotta.hint-off"));
      return;
    }
    // 没 VPN 授权就别开房：否则房间码出得来、朋友却怎么都连不上
    if (!(await ensureTcVpn())) return;
    const name = accounts.current?.username ?? "player";
    const raw = await tcCall("/host?player=" + encodeURIComponent(name));
    // 隧道会回 {"ok":false,"error":"…"}（比如状态不对），原来不看就往下走，
    // 用户只看到「已等待，请先在游戏里开放局域网世界」，永远不知道自己被拒了
    if (!raw.includes('"ok":true')) {
      const err = raw.match(/"error":"([^"]*)"/)?.[1];
      message.error(err ? `陶瓦联机：${err}` : $t("terracotta.hint-off"));
      return;
    }
    for (let i = 0; i < 8; i++) {
      await new Promise((r) => setTimeout(r, 1500));
      await tcRefresh();
      if (tcRoom.value) break;
    }
    message.success(tcRoom.value ? $t("terracotta.hosted") : $t("terracotta.waiting-world"));
  } catch (e) {
    message.error(String(e));
  } finally {
    tcBusy.value = false;
  }
}

async function joinRoom() {
  const code = tcRoomInput.value.trim();
  if (!code) return;
  tcBusy.value = true;
  try {
    const name = accounts.current?.username ?? "player";
    const raw = await tcCall(
      "/guest?room=" + encodeURIComponent(code) + "&player=" + encodeURIComponent(name),
    );
    if (raw.includes('"ok":true')) {
      message.success($t("terracotta.joining"));
      await tcRefresh();
    } else {
      message.error($t("terracotta.bad-room"));
    }
  } catch (e) {
    message.error(String(e));
  } finally {
    tcBusy.value = false;
  }
}

async function stopHosting() {
  try {
    await api.terracottaTunnelRequest("/waiting");
    tcRoom.value = "";
    await tcRefresh();
  } catch (e) {
    message.error(String(e));
  }
}

async function copyRoom() {
  try {
    await navigator.clipboard.writeText(tcRoom.value);
    message.success($t("terracotta.copied"));
  } catch {
    message.error($t("terracotta.copy-failed"));
  }
}

/** 延迟启动的那个 timeout 句柄；`onBeforeUnmount` 要能取消它 */
let tcBoot: ReturnType<typeof setTimeout> | null = null;
/** 组件是否已卸载 —— 延迟回调可能在卸载之后才跑（见下面注释） */
let mpDisposed = false;

onMounted(async () => {
  void servers.load();
  // 延迟 1.5s 再拉一次：等陶瓦服务自己起来。
  // **这个 timeout 必须记下来**：以前没记 —— 如果用户在 1.5 秒内就返回上一页（手机上很常见），
  // onBeforeUnmount 执行时 interval 还没被建立（只能清个 null），随后回调才把 interval
  // 建起来：组件已经销毁，却从此永久每 5 秒轮询一次后端。
  tcBoot = setTimeout(async () => {
    tcBoot = null;
    if (mpDisposed) return;
    await tcRefresh();
    if (mpDisposed) return;
    tcTimer = setInterval(() => void tcRefresh(), 5000);
  }, 1500);
});

onBeforeUnmount(() => {
  mpDisposed = true;
  if (tcBoot) clearTimeout(tcBoot);
  tcBoot = null;
  if (tcTimer) clearInterval(tcTimer);
  tcTimer = null;
});
</script>

<template>
  <div class="mp">
    <!-- 联机类功能的**总入口**都在这一页：本地服务器与陶瓦联机在下面两个分区，
         这里再加「MohuaFrp」与「联机房间」两项 —— 它们同属一个内网穿透服务，
         联机房间只是它的另一种模式（XTCP：双方都要跑 frpc），所以并排放一起。 -->
    <section class="sec">
      <div class="sec-head">
        <h2 class="sec-title">{{ $t("multiplayer.sec-online") }}</h2>
      </div>
      <!-- 只放一个入口：联机房间是 MohuaFrp 内部的另一种模式，已经在那一页里了 -->
      <button class="hub-row glass" @click="router.push('/frp')">
        <span class="hub-name">{{ $t("nav.frp") }}</span>
        <span class="hub-desc">{{ $t("multiplayer.mohua-frp-desc") }}</span>
      </button>
    </section>
    <!-- 手机：这个页面只有「本地服务器」一件事，原来那个「Minecraft 新闻」tab 里
         只有两句提示、没有真实内容（新闻已经有独立的页面），属于多余入口，去掉。 -->
    <!-- 「创建服务器」按钮必须**常驻**：以前它只长在下面的 van-empty 里，
         于是列表一旦有了一台服务器，空状态消失、按钮跟着消失，
         就再也点不出第二台的创建入口了。 -->
    <!-- ── 我的服务器 ──────────────────────────────────────────────────
       分区标题照竞品 Anvil 的做法：一页拆成「服务器」和「陶瓦联机」两个明确的区，
       各带自己的标题行，不再混在一列里。 -->
    <section class="sec">
      <div class="sec-head">
        <h2 class="sec-title">{{ $t("multiplayer.sec-servers") }}</h2>
        <van-button size="small" type="primary" @click="showCreate = true">
          <IconPlus /> {{ $t("title-bar.new-server") }}
        </van-button>
      </div>

      <div v-if="servers.servers.length" class="list">
        <div
          v-for="s in servers.servers"
          :key="s.id"
          class="srv glass"
          role="button"
          tabindex="0"
          @click="openDetail(s.id)"
          @keydown.enter="openDetail(s.id)"
        >
          <!-- 状态行：一个点 + 文字，一眼看出在不在跑 -->
          <div class="srv-state">
            <span class="srv-dot" :class="{ on: servers.isRunning(s.id) }"></span>
            {{ servers.isRunning(s.id) ? $t("multiplayer.running") : $t("multiplayer.offline") }}
          </div>
          <div class="srv-name">{{ s.name }}</div>
          <!-- 副标题：「核心 版本 · 最多 N 名玩家」（照竞品 Anvil 的卡片）。
               人数来自 server.properties 的 max-players，后端每次返回配置时现读现挂；
               读不到（还没生成过 properties）就退回显示端口，不留空。 -->
          <div class="srv-meta">
            {{ coreShort(s.core) }} {{ s.mc_version }} ·
            {{ s.max_players ? $t("multiplayer.card-players", { n: s.max_players }) : `:${s.port}` }}
          </div>
          <!-- 操作行：**视觉权重 = 操作频率**（照 Anvil 的卡片逻辑）——
               启动给大面积，文件/设置收成图标，删除用最弱对比。
               ⚠️ 内部按钮必须 .stop：整张卡可点进详情，不拦的话点「启动」会顺带跳页。 -->
          <div class="srv-acts">
            <van-button
              class="run"
              size="small"
              :type="servers.isRunning(s.id) ? 'default' : 'primary'"
              :loading="starting === s.id"
              @click.stop="toggleRun(s.id)"
            >
              {{ servers.isRunning(s.id) ? $t("multiplayer.stop") : $t("instance-card.launch") }}
            </van-button>
            <button class="srv-ico" :aria-label="$t('instance-detail.files')" @click.stop="openDetail(s.id, 'files')">
              <IconFolder />
            </button>
            <button class="srv-ico" :aria-label="$t('router.settings')" @click.stop="openDetail(s.id, 'config')">
              <IconSettings />
            </button>
            <button class="srv-ico weak" :aria-label="$t('common.delete')" @click.stop="confirmRemove(s)">
              <IconTrash />
            </button>
          </div>
        </div>
      </div>
      <van-empty v-else :description="$t('instance-saves.no-servers')">
        <van-button type="primary" @click="showCreate = true"><IconPlus /> {{ $t("title-bar.new-server") }}</van-button>
      </van-empty>
    </section>

    <!-- ── 陶瓦联机（Terracotta）：和朋友异地联机 ──────────────────────
         .so 跑在独立进程 :tunnel，主进程只通过 localhost HTTP 通信（不链接它，
         这样启动器保持 GPL-3.0，署名见本页底部与设置→第三方声明）。 -->
    <section class="sec">
      <div class="sec-head">
        <h2 class="sec-title">{{ $t("multiplayer.sec-terracotta") }}</h2>
      </div>
      <section class="tc glass">
      <div class="tc-head">
        <span class="tc-title">{{ $t("terracotta.title") }}</span>
        <span class="tc-dot" :class="tcState"></span>
      </div>
      <p class="tc-hint">{{ tcHint }}</p>

      <template v-if="tcRoom">
        <div class="tc-room">
          <span class="tc-room-label">{{ $t("terracotta.room-code") }}</span>
          <span class="tc-room-code">{{ tcRoom }}</span>
        </div>
        <div class="tc-actions">
          <van-button size="small" @click="copyRoom">{{ $t("terracotta.copy") }}</van-button>
          <van-button size="small" @click="stopHosting">{{ $t("terracotta.stop") }}</van-button>
        </div>
      </template>

      <template v-else>
        <div class="tc-actions">
          <van-button size="small" type="primary" :loading="tcBusy" @click="startHosting">
            {{ $t("terracotta.host") }}
          </van-button>
        </div>
        <div class="tc-join">
          <app-input
            v-model:value="tcRoomInput"
            :placeholder="$t('terracotta.room-placeholder')"
            maxlength="12"
          />
          <van-button size="small" :loading="tcBusy" @click="joinRoom">{{ $t("terracotta.join") }}</van-button>
        </div>
      </template>

      <p class="tc-credit">
        {{ $t("terracotta.credit") }}
      </p>
      </section>
    </section>

    <app-popup :show="showCreate" position="bottom" round @update:show="(v: boolean) => (showCreate = v)">
      <div class="sheet">
        <div class="sheet-title">{{ $t("title-bar.new-server") }}</div>
        <app-input v-model:value="form.name" :placeholder="$t('multiplayer.server-name-hint')" maxlength="40" />
        <app-select v-model:value="form.core" :options="SERVER_CORES" :placeholder="$t('multiplayer.server-core')" />
        <app-select
          v-if="versionOptions.length"
          v-model:value="form.mcVersion"
          :options="versionOptions"
          :placeholder="$t('create-instance.select-version')"
        />
        <!-- 只在拿不到版本清单时出现；直接内联样式，免得为一行提示再去改样式块 -->
        <div v-else class="mp-hint" style="padding: 10px 4px; font-size: 13px; opacity: 0.7">{{ versionsLoading ? $t("multiplayer.version-loading") : versionsError }}</div>
        <van-button block type="primary" :disabled="!form.mcVersion" :loading="saving" @click="createServer">{{ $t("multiplayer.create") }}</van-button>
      </div>
    </app-popup>
  </div>
</template>

<style scoped>
/* 联机入口卡（MohuaFrp / 联机房间）：整行可点，标题 + 一行说明 */
.hub-row {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
  width: 100%;
  padding: 12px 14px;
  margin-top: 8px;
  border: 1px solid var(--border);
  border-radius: 12px;
  background: var(--panel);
  color: var(--text-1);
  font-family: inherit;
  text-align: left;
}
.hub-name {
  font-size: 14px;
  font-weight: 600;
}
.hub-desc {
  font-size: 12px;
  line-height: 1.5;
  color: var(--text-3);
  white-space: normal;
}

.mp {
  padding: 4px 16px 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
/* ── 分区（照 Anvil 的「一页两区」逻辑：服务器 / 陶瓦联机）── */
.sec {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.sec-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.sec-title {
  margin: 0;
  font-size: 15px;
  font-weight: 700;
  color: var(--text-1);
}
.srv {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 6px;
  padding: 12px 14px;
}
/* 整张卡可点进详情：加按压反馈，否则用户不知道这块能点 */
.srv {
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
}
.srv:active {
  opacity: 0.7;
}
/* 状态行：一个点 + 文字（离线灰点、运行中主题色点带光晕） */
.srv-state {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--text-3);
}
.srv-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--text-3);
}
.srv-dot.on {
  background: var(--accent);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--accent) 25%, transparent);
}
/* 操作行：启动占大头，文件/设置/删除收成图标按钮 */
.srv-acts {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
}
.srv-acts .run {
  flex: 1;
}
.srv-ico {
  width: 36px;
  height: 36px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--border);
  border-radius: 9px;
  background: transparent;
  color: var(--text-2);
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
}
.srv-ico:active {
  opacity: 0.6;
}
/* 删除：最弱对比 —— 它是破坏性操作，但不该和「启动」抢注意力 */
.srv-ico.weak {
  color: var(--text-3);
  border-color: transparent;
}
.srv-ico svg {
  width: 16px;
  height: 16px;
}
.srv-name {
  font-weight: 600;
  font-size: 14px;
}
.srv-meta {
  font-size: 12px;
  color: var(--text-3);
}
.hint {
  margin: 0 0 8px;
  font-size: 13px;
  color: var(--text-3);
}
.online {
  padding: 20px 16px;
}
.sheet {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px;
  padding-bottom: calc(16px + env(safe-area-inset-bottom, 0px));
}
.sheet-title {
  font-size: 16px;
  font-weight: 600;
}
/* ── 陶瓦联机卡片 ───────────────────────────────────────────────── */
.tc {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px;
}
.tc-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.tc-title {
  font-size: 15px;
  font-weight: 700;
  color: var(--text-1);
}
.tc-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: var(--text-3);
  flex-shrink: 0;
}
.tc-dot.ready { background: #4ecdc4; }
.tc-dot.hosting { background: var(--accent); }
.tc-dot.guesting { background: #ffd166; }
.tc-hint {
  margin: 0;
  font-size: 13px;
  color: var(--text-3);
  line-height: 1.5;
}
.tc-room {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 12px 14px;
  border-radius: 12px;
  background: var(--accent-soft);
  border: 1px solid var(--accent);
}
.tc-room-label {
  font-size: 13px;
  color: var(--text-2);
}
.tc-room-code {
  font-size: 22px;
  font-weight: 700;
  color: var(--accent);
  letter-spacing: 2px;
}
.tc-actions {
  display: flex;
  gap: 8px;
}
.tc-actions :deep(.van-button) {
  flex: 1;
  min-height: 40px;
}
.tc-join {
  display: flex;
  gap: 8px;
  align-items: center;
}
.tc-join > :first-child {
  flex: 1;
  min-width: 0;
}
.tc-join :deep(.van-button) {
  min-height: 44px;
  padding: 0 16px;
}
/* 署名：AGPL 例外条款要求在界面明显处标识版权 */
.tc-credit {
  margin: 0;
  font-size: 11px;
  color: var(--text-3);
  line-height: 1.5;
}

/* ── 手机横屏（矮窗口）：服务器卡片并排两张 ─────────────────────────────
 * 横屏只有 ~360px 高，卡片一列排下去要滚好几屏；而宽度有 800px 以上空着。 */
@media (orientation: landscape) and (max-height: 560px) {
  .list {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    align-items: start;
  }
}
</style>
