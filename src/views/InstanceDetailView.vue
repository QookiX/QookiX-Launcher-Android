<script setup lang="ts">
import { t as $t } from "../i18n";
import { computed, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useMessage } from "../composables/message";
import { Button as VanButton, Progress as VanProgress } from "vant";
import { api } from "../api";
import type { InstanceFileReport } from "../types";
import { useInstancesStore } from "../stores/instances";
import { useAccountsStore } from "../stores/accounts";
import { useTasksStore } from "../stores/tasks";
import { fmtBytes, fmtSpeed, loaderBadge } from "../utils/format";
import AppIcon from "../components/AppIcon.vue";
import ContentTab from "../components/instance/ContentTab.vue";
import SavesTab from "../components/instance/SavesTab.vue";
import SettingsTab from "../components/instance/SettingsTab.vue";
import KeysTab from "../components/instance/KeysTab.vue";
import FileManager from "../components/FileManager.vue";
import LogViewer from "../components/LogViewer.vue";
import CrashAnalyzer from "../components/CrashAnalyzer.vue";
import { IconPlay } from "../components/icons";

const route = useRoute();

const message = useMessage();
const instances = useInstancesStore();
const accounts = useAccountsStore();

const instanceId = String(route.params.id);
const tab = ref("content");
const launching = ref(false);

const tasks = useTasksStore();

/**
 * 这个实例当前有没有「在跑的文件任务」。
 *
 * 创建实例后的后台安装、点「补全游戏文件」、以及内容安装都会把任务挂到
 * `instanceId` 上；只要它还在跑，就不该显示「需要补全游戏文件」那条警告 ——
 * 否则用户刚建完实例点进来，会以为启动器出问题了（其实文件正在下）。
 */
const installTask = computed(
  () => tasks.taskList.find((t) => t.instanceId === instanceId && !t.finished),
);

/** 进度百分比：优先按字节，其次文件数，最后按安装步骤 */
const installPct = computed(() => {
  const t = installTask.value;
  if (!t) return 0;
  if (t.bytesTotal > 0) return Math.min(100, Math.round((t.bytesDone / t.bytesTotal) * 100));
  if (t.fileTotal > 0) return Math.min(100, Math.round((t.fileDone / t.fileTotal) * 100));
  if (t.stepTotal > 0) return Math.min(100, Math.round((t.stepDone / t.stepTotal) * 100));
  return 0;
});

const cancelling = ref(false);
async function cancelInstall() {
  const t = installTask.value;
  if (!t || cancelling.value) return;
  cancelling.value = true;
  try {
    await api.cancelInstall(t.id);
    message.info($t("downloads.cancel-requested"));
  } catch (e) {
    message.error(String(e));
  } finally {
    cancelling.value = false;
  }
}

// 下载结束后重新体检一次：这时才该决定要不要显示「补全」提示
watch(installTask, (now, before) => {
  if (before && !now) void checkFiles();
});

const inst = computed(() => instances.get(instanceId));

/**
 * 游戏文件体检结果（后端 `check_instance_files`，只读、不下载）。
 *
 * null 表示「文件完整」或「还没查出来」——两种情况都不显示提示条。
 * 创建实例时的后台安装一旦失败（网络断流 / 进程被杀 / 磁盘满），实例会停在
 * 「只有 instance.json」的状态：界面看不出原因，也没有补救入口，这里把
 * 「缺什么」和「补全」摆到启动按钮下面。
 */
const fileReport = ref<InstanceFileReport | null>(null);

/**
 * 共享游戏文件的占用（版本目录 / Java 可归属到本实例；库与素材是共享池，只报总量）。
 *
 * 为什么要在「文件」页显示它：游戏本体**不在实例目录里**，刚建的实例目录里只有一个
 * `instance.json` —— 用户看到会以为"什么都没下"（真机反馈过，我也被问到）。
 * 顺带给出「存储」入口，让"占了多少 / 能不能清"有个明确的去处。
 */
const router = useRouter();
const sharedUsage = ref<Awaited<ReturnType<typeof api.sharedGameUsage>> | null>(null);
async function loadSharedUsage() {
  try {
    sharedUsage.value = await api.sharedGameUsage(instanceId);
  } catch {
    // 统计失败不该影响文件页本身（少一张卡而已），静默降级
    sharedUsage.value = null;
  }
}
const repairing = ref(false);

/** 有可修的项才给按钮；全是 `fixable:false`（如加载器没有对应版本）时按钮禁用 */
const canRepair = computed(() => !!fileReport.value?.missing.some((m) => m.fixable));

async function checkFiles() {
  try {
    const r = await api.checkInstanceFiles(instanceId);
    fileReport.value = r.can_launch ? null : r;
  } catch {
    // 实例不存在 / 配置损坏：交给原本的报错路径，这里不弹提示条
    fileReport.value = null;
  }
}

async function repair() {
  if (!canRepair.value || repairing.value) return;
  repairing.value = true;
  // 这里原先弹 `message.loading(...)` —— 那是个**全屏遮罩**（forbidClick），
  // 补全大包要好几分钟，期间用户既看不到进度条、也点不了「取消」，只能干等。
  // 补全本身已经有进度条 + 取消按钮（走 install://progress → 下载中心那条），
  // 按钮上也有 `:loading="repairing"`，够用了，不要再盖一层。
  try {
    await api.repairInstanceFiles(instanceId);
    await instances.load(true);
    await checkFiles();
    // 还有残留（例如加载器版本号为空这种补不回来的）就直接把后端的话给用户看
    if (fileReport.value) message.warning(fileReport.value.advice);
    else message.success($t("instance-detail.repair-done"));
  } catch (e) {
    message.error(String(e));
  } finally {
    repairing.value = false;
  }
}

onMounted(checkFiles);
// 共享占用与「缺文件」检查各走各的：一个失败不该让另一个也不显示
onMounted(loadSharedUsage);

/**
 * 内容页签里的分类：模组 / 资源包 / 光影。
 *
 * 为什么做成页签内的分段而不是各占一个顶部页签：手机上顶部页签已经 7 个（横屏时是一整列，
 * 再多会把启动按钮挤没）；而这三类共用同一套「列表 + 工具栏 + 操作面板」，
 * 换个 `kind` 就行（`ContentTab` 本来就按 `kind` 工作，只是之前被写死成 `mod`）。
 * 文案复用内容中心已有的三个键，不另造重复词。
 */
const contentKind = ref<"mod" | "resourcepack" | "shader">("mod");
const CONTENT_KINDS = [
  { value: "mod", label: $t("browse.mods") },
  { value: "resourcepack", label: $t("browse.group") },
  { value: "shader", label: $t("utils.categories.shader") },
] as const;

const TABS = [
  { key: "content", label: $t("nav.browse") },
  { key: "saves", label: $t("instance-detail.group") },
  { key: "keys", label: $t("instance-detail.keys") },
  { key: "settings", label: $t("router.settings") },
  { key: "files", label: $t("instance-detail.files") },
  { key: "logs", label: $t("common.logs") },
  { key: "crash", label: $t("crash-dialog.crash") },
];

async function launch() {
  if (!inst.value) return;
  if (!accounts.accounts.length) {
    message.warning($t("home.add-account-hint"));
    accounts.showManager = true;
    return;
  }
  launching.value = true;
  try {
    const res = await instances.launch(instanceId);
    if (res) message.success($t("home.launched", { p1: inst.value.name }));
  } catch (e) {
    message.error(String(e));
    // 启动失败常见原因就是文件没下全，顺手复查一次，把提示条亮出来
    void checkFiles();
  } finally {
    launching.value = false;
  }
}
</script>

<template>
  <div class="dv">
    <div class="head glass">
      <div class="ico"><AppIcon :name="inst?.icon" /></div>
      <div class="info">
        <div class="nm">{{ inst?.name ?? "" }}</div>
        <div class="mt">
          <span class="badge">{{ loaderBadge(inst?.loader ?? "") }}</span>
          <span>{{ inst?.mc_version }}</span>
        </div>
      </div>
      <van-button type="primary" class="go" :loading="launching" @click="launch"><IconPlay /></van-button>
    </div>

    <!-- 游戏文件正在下载：显示进度 + 取消。
         这种时候**不能**显示下面那条「需要补全游戏文件」——用户刚建完实例点进来
         看到「缺 4 项，点补全」，会以为启动器坏了（其实后台正在下）。 -->
    <div v-if="installTask" class="guard guard-run">
      <div class="guard-txt">
        <div class="guard-title">{{ installTask.message || installTask.stage }}</div>
        <van-progress :percentage="installPct" :show-pivot="false" />
        <div class="guard-advice run-meta">
          <span v-if="installTask.bytesTotal">{{ fmtBytes(installTask.bytesDone) }} / {{ fmtBytes(installTask.bytesTotal) }}</span>
          <span v-else-if="installTask.fileTotal">{{ installTask.fileDone }} / {{ installTask.fileTotal }}</span>
          <span v-if="installTask.speed > 0">{{ fmtSpeed(installTask.speed) }}</span>
        </div>
      </div>
      <van-button size="small" class="guard-btn" :loading="cancelling" @click="cancelInstall">
        {{ $t("common.cancel") }}
      </van-button>
    </div>

    <div v-else-if="fileReport" class="guard">
      <div class="guard-txt">
        <div class="guard-title">
          {{ $t("instance-detail.files-incomplete", { p1: fileReport.ok, p2: fileReport.total }) }}
        </div>
        <div class="guard-advice">{{ fileReport.advice }}</div>
      </div>
      <van-button
        size="small"
        type="primary"
        class="guard-btn"
        :loading="repairing"
        :disabled="!canRepair"
        @click="repair"
      >{{ $t("instance-detail.repair-files") }}</van-button>
    </div>

    <div class="tabs">
      <button v-for="t in TABS" :key="t.key" class="tb" :class="{ on: tab === t.key }" @click="tab = t.key">{{ t.label }}</button>
    </div>

    <div class="body">
      <div v-if="tab === 'content'" class="kind-wrap">
        <div class="kind-seg">
          <button
            v-for="k in CONTENT_KINDS"
            :key="k.value"
            class="ks"
            :class="{ on: contentKind === k.value }"
            @click="contentKind = k.value"
          >
            {{ k.label }}
          </button>
        </div>
        <!-- `:key` 换 kind 时强制重建：组件内部的状态（搜索词、更新检查结果、
             展开的面板）都是按某一类内容算的，带过去只会串味 -->
        <ContentTab :key="contentKind" :instance-id="instanceId" :kind="contentKind" />
      </div>
      <SavesTab v-else-if="tab === 'saves'" :instance-id="instanceId" />
      <KeysTab v-else-if="tab === 'keys'" :instance-id="instanceId" />
      <SettingsTab v-else-if="tab === 'settings'" :instance-id="instanceId" />
      <div v-else-if="tab === 'files'" class="files-pane">
        <!-- 游戏本体（版本 / 库 / 素材 / Java）是**全局共享**的，不在这个实例目录里：
             刚建的实例里只有一个 instance.json，不说明白会让人以为"什么都没下"。 -->
        <div v-if="sharedUsage" class="shared-card glass">
          <div class="shared-title">{{ $t("instance-detail.shared-title") }}</div>
          <div class="shared-line">
            {{ $t("instance-detail.shared-version", { p1: sharedUsage.mcVersion, p2: fmtBytes(sharedUsage.versionBytes) }) }}
          </div>
          <div class="shared-line">
            {{ $t("instance-detail.shared-java", { p1: `Java ${sharedUsage.javaVersion}`, p2: fmtBytes(sharedUsage.javaBytes) }) }}
          </div>
          <div class="shared-line">
            {{ $t("instance-detail.shared-pool", { p1: fmtBytes(sharedUsage.librariesBytes), p2: fmtBytes(sharedUsage.assetsBytes) }) }}
          </div>
          <div class="shared-hint">{{ $t("instance-detail.shared-hint") }}</div>
          <button class="shared-go" @click="router.push('/settings/storage')">
            {{ $t("instance-detail.shared-manage") }}
          </button>
        </div>
        <FileManager :instance-id="instanceId" />
      </div>
      <LogViewer v-else-if="tab === 'logs'" :instance-id="instanceId" />
      <CrashAnalyzer v-else-if="tab === 'crash'" :instance-id="instanceId" />
    </div>
  </div>
</template>

<style scoped>
/* 「文件」页顶部的共享文件占用卡 */
.files-pane {
  display: flex;
  flex-direction: column;
  gap: 10px;
  flex: 1;
  min-height: 0;
}
.shared-card {
  padding: 12px 14px;
  border-radius: 14px;
  flex-shrink: 0;
}
.shared-title {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 6px;
}
.shared-line {
  font-size: 12px;
  color: var(--text-2);
  line-height: 1.7;
}
.shared-hint {
  margin-top: 6px;
  font-size: 11px;
  line-height: 1.6;
  color: var(--text-3);
}
.shared-go {
  margin-top: 8px;
  min-height: 34px;
  padding: 6px 14px;
  border-radius: 17px;
  border: 1px solid var(--accent);
  background: transparent;
  color: var(--accent);
  font-family: inherit;
  font-size: 13px;
}
.dv {
  height: 100%;
  display: flex;
  flex-direction: column;
  padding: 4px 16px 0;
  gap: 10px;
}
.head {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  flex-shrink: 0;
}
.ico {
  width: 52px;
  height: 52px;
  border-radius: 14px;
  overflow: hidden;
  background: linear-gradient(135deg, var(--accent-25), var(--accent-08));
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--accent);
  flex-shrink: 0;
}
.info {
  flex: 1;
  min-width: 0;
}
.nm {
  font-size: 16px;
  font-weight: 700;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mt {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
  font-size: 12px;
  color: var(--text-3);
}
.badge {
  background: var(--accent-16);
  color: var(--accent);
  border-radius: 6px;
  padding: 1px 7px;
  font-weight: 600;
}
.go {
  flex-shrink: 0;
  min-height: 44px;
  min-width: 52px;
}
.guard {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 12px;
  border: 1px solid var(--accent-25);
  background: var(--accent-08);
  flex-shrink: 0;
}
.guard-txt {
  flex: 1;
  min-width: 0;
}
.guard-title {
  font-size: 13px;
  font-weight: 600;
}
.guard-advice {
  margin-top: 3px;
  font-size: 12px;
  line-height: 1.4;
  color: var(--text-3);
}
.guard-btn {
  flex-shrink: 0;
}
/* 内容分类（模组/资源包/光影）：作为内容页内部的一行分段控件 */
.kind-wrap {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}
.kind-seg {
  display: flex;
  gap: 8px;
  padding-bottom: 8px;
  flex-shrink: 0;
}
.ks {
  min-height: 34px;
  padding: 6px 14px;
  border-radius: 17px;
  border: 1px solid var(--border);
  background: var(--panel);
  color: var(--text-2);
  font-family: inherit;
  font-size: 13px;
}
.ks.on {
  border-color: var(--accent);
  color: var(--accent);
  font-weight: 600;
}
.tabs {
  display: flex;
  gap: 8px;
  overflow-x: auto;
  scrollbar-width: none;
  flex-shrink: 0;
  padding-bottom: 2px;
}
.tb {
  flex-shrink: 0;
  min-height: 36px;
  padding: 6px 14px;
  border-radius: 18px;
  border: 1px solid var(--border);
  background: var(--panel);
  color: var(--text-2);
  font-family: inherit;
  font-size: 13px;
  white-space: nowrap;
}
.tb.on {
  border-color: var(--accent);
  color: var(--accent);
  background: var(--accent-08);
}
.body {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}
.body > * {
  height: 100%;
  overflow-y: auto;
  -webkit-overflow-scrolling: touch;
}

/* ── 手机横屏（矮窗口）：页签挪到左侧竖排 ─────────────────────────────────
 *
 * 横过来之后视口约 923×411（Pixel_9 1080×2424 / 420dpi 实测）。横向一条页签
 * 要吃掉 ~40px 高度，也就是 10% 的可用高度换一个「切页签」控件 —— 而横屏最不缺的
 * 就是宽度。竖排到左边之后，高度全省下来，页签本身也从「一条」变成「一栏」更好点。
 *
 * 判据用 `max-height` 而不是只看方向：平板横屏高度 600~800px，那套横向页签没问题。
 */
@media (orientation: landscape) and (max-height: 560px) {
  .dv {
    display: grid;
    /* 页签栏宽度：原来 124px 太宽 —— 最长的文案「崩溃分析」13px 下才 ~52px，
       按钮又是 width: 100%，右边常年空一大截，看着比 App 自己在横屏下的整列导航
       （84px，见 App.vue `.shell`）还长。收到 96px 后按钮贴着文字，多出的宽度还给内容区。 */
    grid-template-columns: 96px minmax(0, 1fr);
    grid-template-rows: auto auto minmax(0, 1fr);
    /* ⚠️ 模板区域必须把 `.guard`（下载进度 / 「需要补全游戏文件」提示条）也算进去：
       它是第 2 个子元素，没有区域可放就会被自动摆到隐式行里 —— 那一行高度由它的内容
       决定（实测 469px），把 tabs 那两行挤成 78px，页签栏直接塌了。 */
    grid-template-areas:
      "tabs head"
      "tabs guard"
      "tabs body";
    gap: 8px;
  }
  .guard {
    grid-area: guard;
    min-height: 0;
  }
  .head {
    grid-area: head;
    padding: 8px 12px;
  }
  .tabs {
    grid-area: tabs;
    flex-direction: column;
    align-items: stretch;
    overflow-x: hidden;
    overflow-y: auto;
    padding-bottom: 0;
    gap: 6px;
  }
  .tb {
    width: 100%;
    min-height: 40px;
    border-radius: 10px;
    padding: 6px 12px;
    text-align: left;
  }
  .body {
    grid-area: body;
  }
}
</style>
