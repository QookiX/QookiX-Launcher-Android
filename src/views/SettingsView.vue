<script setup lang="ts">
import { t as $t } from "../i18n";
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { fmtMem, fmtSize, fmtTime } from "../utils/format";
import { Cell as VanCell, CellGroup as VanCellGroup } from "vant";
import { useMessage } from "../composables/message";
import { useDialog } from "../composables/dialog";
import { openUrl } from "@tauri-apps/plugin-opener";
import { listen } from "@tauri-apps/api/event";
import { pickFile as open } from "../composables/filePicker";
import { convertFileSrc } from "@tauri-apps/api/core";
import { useMemoryInfo } from "../composables/useMemoryInfo";
import { useSettingsStore } from "../stores/settings";
import { api } from "../api";

/**
 * 「并行下载」两个滑杆的开关。
 *
 * **曾经是关着的**，理由是「后端串行下载、分片没实现，拖了不会有任何效果，
 * 假开关比缺功能更伤」。那个理由**现在已经不成立**：
 *   - 文件级并发：`download::download_files_concurrent`（`index` 那几百个库走它）
 *   - 单文件分片：`download::try_download_chunked`（≥8MB 走 HTTP Range 拆段）
 * 后端也已经改成**读设置**（`download::file_concurrency` / `chunk_threads`），
 * 所以滑杆是真生效的。上限统一成 8：后端做的是 `clamp(1, 8)`，
 * 界面给 32 的话拖到 20 实际只有 8 —— 那又变回假开关了。
 */
const DOWNLOAD_SLIDERS_ENABLED = true;

/**
 * 并发数上限，与后端 `download.rs` 的 `FILE_CONCURRENCY_MAX` / `CHUNK_CONCURRENCY_MAX`
 * **必须一致**（两边各存一份常量，改一处忘了另一处就会出现「拖到底但后端钳住」）。
 *
 * 数值对齐桌面端（文件 32 / 分片 16）：以前两端都卡在 8，用户在设置里拖到最大也
 * 只有桌面的四分之一并发，手机上下大包（几百个库 + 几千个资源对象）一直快不起来。
 */
const FILE_CONCURRENCY_MAX = 32;
const CHUNK_CONCURRENCY_MAX = 16;

/**
 * 「手柄死区」滑杆。
 *
 * 实体手柄已经能用：首个手柄事件会创建 `Gamepad`，把柄上的操作翻译成键鼠
 * （按键走映射表、左摇杆 WASD、右摇杆转视角，菜单里换一套映射）。
 * 原始事件的解析是自己写的 `GamepadInputDispatcher`，不再依赖 JitPack 上的 AAR。
 *
 * 这个滑杆写进 `gamepad_deadzone_scale`，由 `GamepadInputDispatcher` 读：
 * 死区 = 0.15 × 该倍率，范围 0.5~2.0。
 */
const GAMEPAD_DEADZONE_ENABLED = true;
import { useSlidingIndicator } from "../composables/useSlidingIndicator";
import {
  IconChevronLeft,
  IconDownload,
  IconExternal,
  IconFile,
  IconGithub,
  IconGlobe,
  IconHeart,
  IconImage,
  IconList,
  IconPlus,
  IconPackage,
  IconUsers,
  IconRefresh,
  IconPlay,
  IconSliders,
  IconTrash,
  IconChevronDown,
  IconChevronUp,
} from "../components/icons";
import type {
  ControlButtonInfo,
  GameDirOption,
  GameDirState,
  MirrorPreset,
  PluginInfo,
  PluginProgressEvent,
  StorageStats,
} from "../types";
import { getVersion } from "@tauri-apps/api/app";
import {
  checkUpdate,
  downloadUpdate,
  installUpdate,
  updateInfo,
  updateChecking,
  updateDownloading,
  updateError,
  updatePackage,
  updateProgress,
  isAutoCheckEnabled,
  setAutoCheck,
  dismissVersion,
  clearDismissed,
  dismissedVersion as getDismissedVersion,
} from "../updater";
import devWeimoshengUrl from "../assets/dev-weimosheng.jpg";
import devZhayiUrl from "../assets/dev-zhayi.jpg";
import logoUrl from "../assets/logo.png";
import AboutShowcase from "../components/AboutShowcase.vue";
import AppInput from "../ui/AppInput.vue";
import AppSwitch from "../ui/AppSwitch.vue";
import AppSlider from "../ui/AppSlider.vue";
import AppSelect from "../ui/AppSelect.vue";
import AppSeg from "../ui/AppSeg.vue";
import ColorPickerSheet from "../components/ColorPickerSheet.vue";

/* ---- 版本徽章彩蛋：长按 v1.6.0 约 2.5s → 全屏像素烟花 + 制作名单 ----
 * 按住期间徽章脉冲提示「正在积蓄」，松手即取消；触发后任意点击关闭。
 * 烟花 = 预生成的彩色像素方块（CSS 动画向外炸开再淡出，循环）。 */
const VER_HOLD_MS = 2000;
const verHolding = ref(false);
const verEgg = ref(false);
let verHoldTimer = 0;
function verDown() {
  if (verEgg.value) return;
  verHolding.value = true;
  clearTimeout(verHoldTimer);
  verHoldTimer = window.setTimeout(() => {
    verHolding.value = false;
    verEgg.value = true;
  }, VER_HOLD_MS);
}
function verCancel() {
  verHolding.value = false;
  clearTimeout(verHoldTimer);
}
/** 关闭彩蛋层（点击弹层任意处）。 */
function verClose() {
  verEgg.value = false;
  verHolding.value = false;
  clearTimeout(verHoldTimer);
}
const verSparks = Array.from({ length: 56 }, (_, i) => ({
  left: Math.random() * 100,
  top: Math.random() * 100,
  delay: Math.random() * 1.4,
  dur: 0.9 + Math.random() * 1.1,
  size: 4 + Math.round(Math.random() * 7),
  color: ["#7ee787", "#ffd166", "#79c0ff", "#f778ba", "#ffffff"][i % 5],
}));

const settings = useSettingsStore();
const message = useMessage();
const dialog = useDialog();

// 主题 seg 滑动高亮
const themeSegRef = ref<HTMLElement | null>(null);
const { indicatorStyle: themeSegStyle, refresh: refreshThemeSeg } = useSlidingIndicator(
  themeSegRef,
  () => Array.from(themeSegRef.value?.querySelectorAll<HTMLElement>(".seg button") ?? []),
  () => (settings.settings?.theme === "light" ? 1 : 0),
  { axis: "horizontal" }
);
watch(() => settings.settings?.theme, () => nextTick(() => refreshThemeSeg()));

// 主题色预设
const themeColorPresets = [
  "#e89a4b",
  "#ff6b35",
  "#e5534b",
  "#ec4899",
  "#8b5cf6",
  "#5aa2f0",
  "#22d3ee",
  "#4ec9a0",
  "#f5c518",
];
/** 当前主题色是不是某个预设（决定自定义色块里画「＋」还是显示当前颜色） */
const isPresetColor = computed(() => themeColorPresets.includes(settings.settings?.theme_color ?? ""));

/** 自定义取色面板（HSL 三滑杆）—— 系统原生 <input type="color"> 那个对话框跟主题色场景对不上 */
const showColorPicker = ref(false);
function applyCustomColor(hex: string) {
  showColorPicker.value = false;
  void settings.patch({ theme_color: hex });
}

// 下载代理 seg 滑动高亮（系统代理 / 直连 / 自定义）
const proxyModeSegRef = ref<HTMLElement | null>(null);
const proxyModes = [
  { id: "system", label: $t("settings.system") },
  { id: "direct", label: $t("settings.direct") },
  { id: "custom", label: $t("instance-settings.mcreator") },
];
const { indicatorStyle: proxyModeSegStyle, refresh: refreshProxyModeSeg } = useSlidingIndicator(
  proxyModeSegRef,
  () => Array.from(proxyModeSegRef.value?.querySelectorAll<HTMLElement>(".seg button") ?? []),
  () => Math.max(0, proxyModes.findIndex((m) => m.id === settings.settings?.proxy_mode)),
  { axis: "horizontal" }
);
watch(() => settings.settings?.proxy_mode, () => nextTick(() => refreshProxyModeSeg()));

// 系统/VPN 下发的代理地址（原生请求不会自动走它，所以要在界面上告诉用户）
const systemProxy = ref<string | null>(null);
async function refreshSystemProxy() {
  try {
    systemProxy.value = await api.detectSystemProxy();
  } catch {
    systemProxy.value = null;
  }
}

// 屏幕方向 seg 滑动高亮（跟随系统 / 竖屏 / 横屏）
const orientationSegRef = ref<HTMLElement | null>(null);
const orientationModes = [
  { id: "landscape", label: $t("settings.landscape") },
  { id: "portrait", label: $t("settings.portrait") },
  { id: "system", label: $t("settings.gen-sui-xi-tong") },
];
const { indicatorStyle: orientationSegStyle, refresh: refreshOrientationSeg } = useSlidingIndicator(
  orientationSegRef,
  () => Array.from(orientationSegRef.value?.querySelectorAll<HTMLElement>(".seg button") ?? []),
  () =>
    Math.max(
      0,
      orientationModes.findIndex((m) => m.id === (settings.settings?.orientation ?? "system"))
    ),
  { axis: "horizontal" }
);
watch(() => settings.settings?.orientation, () => nextTick(() => refreshOrientationSeg()));

async function selectOrientation(id: string) {
  if ((settings.settings?.orientation ?? "system") === id) return;
  try {
    await settings.patch({ orientation: id });
    // 立即下发到原生层，无需重启应用
    await api.setOrientation(id);
  } catch (e) {
    message.error(String(e));
  }
}

async function selectProxyMode(id: string) {
  if (settings.settings?.proxy_mode === id) return;
  try {
    await settings.patch({ proxy_mode: id });
  } catch (e) {
    message.error(String(e));
  }
}

function onCustomProxyInput() {
  if (settings.settings && settings.settings.proxy_mode !== "custom") {
    settings.settings.proxy_mode = "custom";
  }
}

// 测试代理连接
const testingProxy = ref(false);
async function testProxy() {
  if (testingProxy.value || !settings.settings) return;
  testingProxy.value = true;
  try {
    const { proxy_mode, proxy } = settings.settings;
    // 自定义模式必须填地址，否则后端会退化为直连而误报成功
    if (proxy_mode === "custom" && !(proxy ?? "").trim()) {
      message.warning($t("settings.proxy-required"));
      return;
    }
    const res = await api.testProxy(
      proxy_mode,
      proxy_mode === "custom" ? proxy : null
    );
    message.success($t("settings.connected", { p1: res.ms }));
  } catch (e) {
    message.error($t("settings.connection-failed", { p1: e }));
  } finally {
    testingProxy.value = false;
  }
}

// ---- 内容翻译 ----
const translateOptions = [
  { label: $t("settings.builtin-translate"), value: "default" },
  { label: $t("settings.custom"), value: "custom" },
  { label: $t("settings.baidu-web"), value: "baidu_web" },
];
const testingTranslate = ref(false);

// 测试自定义翻译接口（Key 留空表示用已保存的那把）
async function testTranslate() {
  if (testingTranslate.value || !settings.settings) return;
  const s = settings.settings;
  if (!s.translate_api_base.trim() || !s.translate_api_model.trim()) {
    message.warning($t("settings.api-fields-required"));
    return;
  }
  testingTranslate.value = true;
  try {
    await api.testTranslateApi(s.translate_api_base, s.translate_api_key ?? "", s.translate_api_model);
    message.success($t("settings.api-ok"));
  } catch (e) {
    message.error($t("settings.test-failed", { p1: e }));
  } finally {
    testingTranslate.value = false;
  }
}

const clearingTranslation = ref<"default" | "custom" | null>(null);
async function clearTranslations(service: "default" | "custom") {
  if (clearingTranslation.value) return;
  clearingTranslation.value = service;
  try {
    const freed = await api.clearTranslationCache(service);
    message.success(freed > 0 ? $t("settings.cache-cleared", { p1: Math.round(freed / 1024) }) : $t("settings.cache-empty"));
  } catch (e) {
    message.error(String(e));
  } finally {
    clearingTranslation.value = null;
  }
}

// 下载镜像源
const mirrors = ref<MirrorPreset[]>([]);
/** 每个镜像最近一次测速结果（毫秒）；null 表示不可用 */
const mirrorLatency = ref<Record<string, number | null>>({});
const testingMirror = ref("");

async function loadMirrors() {
  try {
    mirrors.value = await api.listMirrors();
  } catch {
    mirrors.value = [];
  }
}

async function selectMirror(id: string) {
  if (settings.settings?.mirror === id) return;
  try {
    await settings.patch({ mirror: id });
  } catch (e) {
    message.error(String(e));
  }
}

async function testMirror(id: string, base: string) {
  if (testingMirror.value) return;
  testingMirror.value = id;
  try {
    const res = await api.testMirror(base);
    mirrorLatency.value = { ...mirrorLatency.value, [id]: res.ms };
  } catch (e) {
    mirrorLatency.value = { ...mirrorLatency.value, [id]: null };
    message.error(String(e));
  } finally {
    testingMirror.value = "";
  }
}

function onCustomMirrorInput() {
  if (settings.settings && settings.settings.mirror !== "custom") {
    settings.settings.mirror = "custom";
  }
}

/**
 * 当前打开的设置分组；null = 停在「分组列表」（手机一级页）。
 *
 * 关键：它**必须是路由的一部分**（`/settings/:tab`），不能只放在组件状态里。
 * 否则安卓返回手势按路由栈回退，「更多 → 设置 → 常规」会直接退回「更多」，
 * 把设置内部的层级吃掉（用户实测）。做成路径后返回键自然先退子页再退设置。
 */
const route = useRoute();
const router = useRouter();
const tab = computed<string | null>(() => {
  const t = route.params.tab;
  return typeof t === "string" && t ? t : null;
});

/** 打开/关闭某个分组（写进路由历史，返回手势才有层级可退） */
function setTab(next: string | null) {
  if (next) void router.push("/settings/" + next);
  else void router.push("/settings");
}

/** 「默认内存」分段控件选项（文案沿用原有 key） */
const memoryModeOptions = computed(() => [
  { value: "auto", label: $t("instance-settings.auto") },
  { value: "custom", label: $t("settings.manual") },
]);
/**
 * 刷新当前页的滑动指示器。
 * 面板切换有过渡动画（out-in），新面板在 enter 结束后才完成布局，
 * 因此必须在动画结束后测量，否则矩形为 0、指示器位置错乱。
 */
function refreshCurrentPaneIndicators() {
  const val = tab.value;
  if (val === "appearance") {
    refreshThemeSeg();
    refreshOrientationSeg();
  }
  // 「下载代理」seg 位于「内容服务」页，不是「下载」页
  if (val === "content") refreshProxyModeSeg();
}
watch(tab, () => {
  nextTick(refreshCurrentPaneIndicators);
});

const tabs = [
  { key: "general", label: $t("settings.general"), icon: IconSliders },
  { key: "plugins", label: $t("server-detail.plugins"), icon: IconPackage },
  { key: "appearance", label: $t("settings.appearance"), icon: IconImage },
  { key: "download", label: $t("downloads.download"), icon: IconDownload },
  { key: "content", label: $t("settings.content-services"), icon: IconGlobe },
  { key: "game", label: $t("settings.game"), icon: IconPlay },
  { key: "storage", label: $t("utils.categories.storage"), icon: IconRefresh },
  { key: "about", label: $t("settings.about"), icon: IconFile },
];

/** 界面缩放滑块：立刻改本地值（App.vue 的 watch 会即时应用）并持久化。 */
function onUiScale(v: number) {
  if (!Number.isFinite(v)) return;
  if (settings.settings) settings.settings.ui_scale = v;
  void settings.patch({ ui_scale: v });
}

const { memTotal, memUsed, memAvailable, startPolling, stopPolling } = useMemoryInfo();

// ---------------------------------------------------------------- 游戏内设置
/**
 * 移植过来的 Pojav 控制层设置。
 *
 * 这些项由 `LauncherPreferences` 从安卓 `SharedPreferences("launcher_preferences")`
 * 读取，和 QookiX 自己的 settings.json 是两套东西 —— 之前没有任何界面能改它们，
 * 所以「按钮大小 / 鼠标速度 / 渲染分辨率缩放 / 忽略刘海 / 长按判定 / 陀螺仪…」
 * 全都停在默认值。现在通过 `get_pojav_prefs` / `set_pojav_prefs` 这两个命令读写。
 */
const POJAV_DEFAULTS: Record<string, number | boolean | string> = {
  // 渲染器：opengles2 = GL4ES（默认，兼容性最好）；vulkan_zink = Zink + Turnip（实验性）
  renderer: "opengles2",
  resolutionRatio: 100,
  buttonscale: 100,
  mousescale: 100,
  mousespeed: 100,
  timeLongPressTrigger: 300,
  gamepad_deadzone_scale: 100,
  gyroSensitivity: 100,
  ignoreNotch: false,
  mouse_start: false,
  disableGestures: false,
  disableDoubleTap: false,
  alternate_surface: true,
  sustained_performance: false,
  buttonAllCaps: false,
  enableGyro: false,
  gyroInvertX: false,
  gyroInvertY: false,
};
const pojav = ref<Record<string, number | boolean | string>>({ ...POJAV_DEFAULTS });

/** 渲染后端选项。这三个都是「本包真的带了库」的：
 *  GL4ES = libgl4es_114.so；Zink = libOSMesa.so + libvulkan_freedreno.so（Turnip）；
 *  MobileGlues（MG）= libmobileglues.so —— ZL/FCL 同款渲染器（OpenGL → GLES 3.2）。 */
const rendererOptions = [
  { label: $t("settings.jian-rong-mo-ren"), value: "opengles2" },
  { label: $t("settings.mobileglues"), value: "mobileglues" },
  { label: $t("utils.renderer.vulkan-zink"), value: "vulkan_zink" },
];

/** 渲染器键 → 给人看的名字（插件列表里用，别直接显示 mobileglues 这种键）。 */
const rendererNames: Record<string, string> = {
  opengles2: "GL4ES",
  mobileglues: "MobileGlues",
  vulkan_zink: "Zink",
};

async function loadPojav() {
  try {
    const r = await api.getPojavPrefs();
    pojav.value = { ...POJAV_DEFAULTS, ...(r as Record<string, number | boolean | string>) };
  } catch {
    /* 原生桥未就绪时保持默认值，不打扰用户 */
  }
}

async function savePojav(key: string, value: number | boolean | string) {
  pojav.value[key] = value;
  try {
    await api.setPojavPrefs({ [key]: value });
  } catch (e) {
    message.error(String(e));
  }
}

  // 关于页：许可与版权声明（与桌面端同一种分组方式）。
  // 只列**随包分发**或**直接依赖**的开源项目；许可徽章以各项目官方声明为准。
  type AboutDep = { name: string; version: string; license: string; url: string; licenseUrl: string };
  const aboutDeps: Record<"frontend" | "rust" | "bundled", AboutDep[]> = {
    frontend: [
      { name: "Vue", version: "3.5", license: "MIT", url: "https://vuejs.org", licenseUrl: "https://github.com/vuejs/core/blob/main/LICENSE" },
      { name: "Vue Router", version: "4.4", license: "MIT", url: "https://router.vuejs.org", licenseUrl: "https://github.com/vuejs/router/blob/main/LICENSE" },
      { name: "Naive UI", version: "2.45", license: "MIT", url: "https://www.naiveui.com", licenseUrl: "https://github.com/tusen-design/naive-ui/blob/main/LICENSE" },
      { name: "Pinia", version: "2.2", license: "MIT", url: "https://pinia.vuejs.org", licenseUrl: "https://github.com/vuejs/pinia/blob/v2/LICENSE" },
      { name: "Tauri API", version: "2", license: "MIT/Apache-2.0", url: "https://tauri.app", licenseUrl: "https://github.com/tauri-apps/tauri/blob/dev/LICENSE" },
      { name: "skinview3d", version: "3.4", license: "MIT", url: "https://github.com/bs-community/skinview3d", licenseUrl: "https://github.com/bs-community/skinview3d/blob/master/LICENSE" },
    ],
    rust: [
      { name: "Tauri", version: "2", license: "MIT/Apache-2.0", url: "https://tauri.app", licenseUrl: "https://github.com/tauri-apps/tauri/blob/dev/LICENSE" },
      { name: "Tokio", version: "1", license: "MIT", url: "https://tokio.rs", licenseUrl: "https://github.com/tokio-rs/tokio/blob/master/LICENSE" },
      { name: "reqwest", version: "0.12", license: "MIT/Apache-2.0", url: "https://github.com/seanmonstar/reqwest", licenseUrl: "https://github.com/seanmonstar/reqwest/blob/master/LICENSE" },
      { name: "serde", version: "1", license: "MIT/Apache-2.0", url: "https://serde.rs", licenseUrl: "https://github.com/serde-rs/serde/blob/master/LICENSE" },
      { name: "jni", version: "0.21", license: "MIT/Apache-2.0", url: "https://github.com/jni-rs/jni-rs", licenseUrl: "https://github.com/jni-rs/jni-rs/blob/master/LICENSE" },
    ],
    bundled: [
      // 这些组件随 APK 分发，是启动器能跑起来的地基 —— 按各自协议保留署名。
      { name: "PojavLauncher", version: "", license: "GPL-3.0", url: "https://github.com/PojavLauncherTeam/PojavLauncher", licenseUrl: "https://github.com/PojavLauncherTeam/PojavLauncher" },
      { name: "GL4ES", version: "1.1.5", license: "MIT", url: "https://github.com/ptitSeb/gl4es", licenseUrl: "https://github.com/ptitSeb/gl4es/blob/master/LICENSE" },
      { name: "LWJGL 3", version: "3.3", license: "BSD-3-Clause", url: "https://lwjgl.org", licenseUrl: "https://github.com/LWJGL/lwjgl3/blob/master/LICENSE" },
      { name: "Caciocavallo", version: "", license: "GPL-2.0 + Classpath", url: "https://github.com/PojavLauncherTeam/caciocavallo", licenseUrl: "https://github.com/PojavLauncherTeam/caciocavallo" },
      { name: "OpenJDK", version: "17", license: "GPL-2.0 + Classpath", url: "https://openjdk.org", licenseUrl: "https://openjdk.org/legal/gplv2+ce.html" },
      { name: "Mesa（OSMesa / zink）", version: "", license: "MIT", url: "https://mesa3d.org", licenseUrl: "https://gitlab.freedesktop.org/mesa/mesa/-/blob/main/docs/license.rst" },
      { name: $t("settings.mit"), version: "", license: "MIT", url: "https://gitlab.freedesktop.org/mesa/mesa", licenseUrl: "https://gitlab.freedesktop.org/mesa/mesa/-/blob/main/docs/license.rst" },
      { name: "FreeType", version: "2.13", license: "FreeType/GPL-2.0", url: "https://freetype.org", licenseUrl: "https://gitlab.freedesktop.org/freetype/freetype/-/blob/master/docs/FTL.TXT" },
      { name: "OpenAL Soft", version: "", license: "LGPL-2.1", url: "https://openal-soft.org", licenseUrl: "https://github.com/kcat/openal-soft/blob/master/COPYING" },
      { name: "MobileGlues", version: "", license: "LGPL-2.1", url: "https://github.com/MobileGL-Dev/MobileGlues-release", licenseUrl: "https://github.com/MobileGL-Dev/MobileGlues/blob/main/LICENSE" },
    ],
  };
  const aboutGroupLabels: Record<"frontend" | "rust" | "bundled", string> = {
    frontend: $t("settings.rust"),
    rust: "Rust",
    bundled: $t("settings.bundled"),
  };

function onPojavRange(key: string, v: number) {
  if (!Number.isFinite(v)) return;
  void savePojav(key, v);
}

const effectiveMemory = computed(() => {
  if (settings.settings?.memory_mode === "auto") {
    // Base: 40% of available (min 2048 MB), cap at 75% of available
    const cap = Math.max(512, Math.floor(memAvailable.value * 3 / 4));
    return Math.max(512, Math.min(Math.max(2048, Math.floor(memAvailable.value * 40 / 100)), cap, 8192));
  }
  return settings.settings?.max_memory_mb ?? 4096;
});
const usedPercent = computed(() => {
  if (!memTotal.value) return 0;
  return Math.min(100, Math.round((memUsed.value / memTotal.value) * 100));
});
const allocPercent = computed(() => {
  if (!memTotal.value) return 0;
  return Math.min(100, Math.round((effectiveMemory.value / memTotal.value) * 100));
});
const allocStart = computed(() => usedPercent.value);
const allocWidth = computed(() =>
  Math.max(0, Math.min(allocPercent.value, 100 - usedPercent.value))
);

let saveTimer: ReturnType<typeof setTimeout> | null = null;

async function save() {
  try {
    skipNextSave = true;
    await settings.save();
  } catch (e) {
    message.error(String(e));
  }
}

let skipNextSave = true;
watch(
  () => settings.settings,
  () => {
    if (skipNextSave) {
      skipNextSave = false;
      return;
    }
    if (saveTimer) clearTimeout(saveTimer);
    saveTimer = setTimeout(save, 500);
  },
  { deep: true }
);

async function pickBackground() {
  try {
    const picked = await open({
      multiple: false,
      filters: [{ name: $t("icon-picker-dialog.image"), extensions: ["png", "jpg", "jpeg", "gif", "webp", "bmp"] }],
    });
    if (!picked || typeof picked !== "string") return;
    const path = await api.importBackgroundImage(picked);
    await settings.patch({ background_image: path });
  } catch (e) {
    message.error(String(e));
  }
}

const bgPreviewUrl = computed(() => {
  const p = settings.settings?.background_image;
  return p ? convertFileSrc(p) : "";
});

// ---------------------------------------------------------------------------
// 存储统计
// ---------------------------------------------------------------------------
const stats = ref<StorageStats | null>(null);
const loadingStats = ref(false);
const clearing = ref(false);

const DONUT_COLORS: Record<string, string> = {
  instances: "var(--accent)",
  servers: "#f59e0b",
  libraries: "#5aa2f0",
  assets: "#4ec9a0",
  versions: "#8b5cf6",
  runtime: "#ec4899",
  logs: "#94a3b8",
  other: "#64748b",
  launcher: "#22d3ee",
};
const DONUT_R = 74;
const DONUT_C = 2 * Math.PI * DONUT_R;

function pct(size: number): number {
  if (!stats.value || !stats.value.total) return 0;
  return Math.round((size / stats.value.total) * 1000) / 10;
}

const visibleCats = computed(() => (stats.value?.categories ?? []).filter((c) => c.size > 0));

/** 环形饼图各分段：start 偏移累积，返回 dash/offset 供 SVG stroke-dasharray 使用 */
const donutSegs = computed(() => {
  const total = stats.value?.total ?? 0;
  if (!total) return [];
  let acc = 0;
  return visibleCats.value.map((c) => {
    const frac = c.size / total;
    const dash = Math.max(0, frac * DONUT_C - 1.5);
    const seg = { key: c.key, dash, offset: -acc, color: DONUT_COLORS[c.key] ?? "#64748b" };
    acc += frac * DONUT_C;
    return seg;
  });
});

async function loadStats() {
  loadingStats.value = true;
  try {
    stats.value = await api.getStorageStats();
  } catch (e) {
    message.error($t("settings.stats-load-failed") + String(e));
  } finally {
    loadingStats.value = false;
  }
}

async function refreshStats() {
  loadingStats.value = true;
  try {
    stats.value = await api.refreshStorageStats();
    message.success($t("settings.stats-updated"));
  } catch (e) {
    message.error($t("settings.stats-update-failed") + String(e));
  } finally {
    loadingStats.value = false;
  }
}

function confirmClear() {
  dialog.warning({
    title: $t("settings.clear-cache"),
    content:
      $t("settings.clear-cache-confirm"),
    positiveText: $t("instance-content.positive-text"),
    negativeText: $t("common.cancel"),
    onPositiveClick: async () => {
      clearing.value = true;
      try {
        const res = await api.clearCache();
        message.success($t("settings.on-positive-click", { p1: fmtSize(res.freed) }));
        await refreshStats();
      } catch (e) {
        message.error($t("settings.clear-cache-failed") + String(e));
      } finally {
        clearing.value = false;
      }
    },
  });
}

/* ── 游戏目录（内部 / 应用专属外部 / 自定义）────────────────────────
   路径解析在后端 settings::instances_root()；这里只管选择与迁移。
   自定义目录走系统 SAF 选择器，是**异步**的 → 选完轮询取回结果。 */
const gameDirState = ref<GameDirState | null>(null);
const gameDirOptions = ref<GameDirOption[]>([]);
const gameDirBusy = ref(false);
let gameDirPoll: number | null = null;

const currentGameRoot = computed(() => gameDirState.value?.root ?? "");

async function loadGameDirs() {
  try {
    gameDirState.value = await api.getGameDirState();
  } catch {
    gameDirState.value = null;
  }
  try {
    gameDirOptions.value = await api.getGameDirOptions();
  } catch {
    gameDirOptions.value = [];
  }
}

function stopGameDirPoll() {
  if (gameDirPoll !== null) {
    window.clearInterval(gameDirPoll);
    gameDirPoll = null;
  }
}

async function applyGameDir(root: string | null, migrate: boolean) {
  gameDirBusy.value = true;
  try {
    gameDirState.value = await api.setGameDir(root, migrate);
    message.success($t("settings.game-dir-done"));
    await loadGameDirs();
    await refreshStats();
  } catch (e) {
    message.error($t("settings.game-dir-failed") + String(e));
  } finally {
    gameDirBusy.value = false;
  }
}

/** 选一个候选目录：问一下要不要把现有实例一起搬过去。 */
function chooseGameDir(opt: GameDirOption) {
  if (opt.path === currentGameRoot.value) return;
  dialog.warning({
    title: $t("settings.game-dir-migrate-title"),
    content: $t("settings.game-dir-migrate-text", { p1: opt.label }),
    positiveText: $t("settings.game-dir-migrate"),
    negativeText: $t("settings.game-dir-keep"),
    onPositiveClick: () => applyGameDir(opt.path, true),
    onNegativeClick: () => applyGameDir(opt.path, false),
  });
}

/**
 * 公共目录：放在这里的话，日志 / 存档 / 模组电脑 MTP 和文件管理器都能直接看到。
 *
 * 为什么需要它：默认的「应用专属外部目录」在 `/storage/emulated/0/Android/data/<包名>/` 下，
 * **Android 11+ 单独屏蔽了 Android/data** —— 文件管理器即使有「所有文件访问」也进不去，
 * 电脑 MTP 也看不到。表现就是「日志拿不出来」（MT 报「该文件被设置了私有权限」）。
 */
const PUBLIC_GAME_DIR = "/storage/emulated/0/QookiX";

async function moveToPublicDir() {
  if (gameDirBusy.value) return;
  if (gameDirState.value && !gameDirState.value.allFilesAccess) {
    message.warning($t("settings.game-dir-need-permission"));
    await ensureAllFilesAccess();
    return;
  }
  // 用项目自带的命令式确认框（naive 形状：content / positiveText / onPositiveClick）
  dialog.warning({
    title: $t("settings.game-dir-public-title"),
    content: $t("settings.game-dir-public-desc", { p1: PUBLIC_GAME_DIR }),
    positiveText: $t("settings.game-dir-migrate"),
    negativeText: $t("common.cancel"),
    onPositiveClick: () => {
      void applyGameDir(PUBLIC_GAME_DIR, true);
    },
  });
}

/** 申请「所有文件访问」并等结果（写公共目录、选自定义目录都需要它）。 */
async function ensureAllFilesAccess() {
  try {
    if (gameDirState.value?.allFilesAccess) return;
    await grantAllFilesAccess();
    for (let i = 0; i < 30; i++) {
      await new Promise((r) => setTimeout(r, 1000));
      await loadGameDirs();
      if (gameDirState.value?.allFilesAccess) return;
    }
    message.warning($t("settings.game-dir-need-permission"));
  } catch (e) {
    message.error(String(e));
  }
}

/** 自定义目录：先弹系统选择器（异步），再轮询取回真实路径。 */
async function pickCustomGameDir() {
  if (gameDirState.value && !gameDirState.value.allFilesAccess) {
    message.warning($t("settings.game-dir-need-permission"));
    return;
  }
  try {
    await api.pickGameDir();
  } catch (e) {
    message.error(String(e));
    return;
  }
  message.info($t("settings.game-dir-picking"));
  stopGameDirPoll();
  let tries = 0;
  gameDirPoll = window.setInterval(async () => {
    tries += 1;
    if (tries > 120) {   // 最多等 60 秒
      stopGameDirPoll();
      return;
    }
    let picked: string | null = null;
    try {
      picked = await api.takePickedGameDir();
    } catch (e) {
      stopGameDirPoll();
      message.error($t("settings.game-dir-failed") + String(e));
      return;
    }
    if (!picked) return;   // 还没选完（或用户取消）
    stopGameDirPoll();
    await applyGameDir(picked, true);
  }, 500);
}

async function grantAllFilesAccess() {
  try {
    await api.requestAllFilesAccess();
  } catch (e) {
    message.error(String(e));
  }
}

/* ── 按键透传（按住这个键时拖动也能转视角）────────────────────────── */
const controlButtons = ref<ControlButtonInfo[]>([]);
/** 按键透传逐键一行，布局大了就是十几行 —— 默认折叠（它属于「调一次就不动」的设置） */
const showPassthru = ref(false);

/**
 * 打开原生按键布局编辑器（就是游戏里那个横屏界面）。
 *
 * 入口原先只在「实例详情 → 按键」页；这里补一个并放在「按键透传」**上方** ——
 * 透传是逐个按键的细项，用户先要能进布局本身去调键位/大小，再看细项才顺。
 * 不传布局名 = 编辑器自己用「当前使用中那份」（与 KeysTab 调法一致）。
 */
async function openKeyLayoutEditor() {
  try {
    await api.openControlLayoutEditor(undefined, false, undefined);
  } catch (e) {
    message.error(String(e));
  }
}
const controlButtonBusy = ref<number | null>(null);

async function loadControlButtons() {
  try {
    controlButtons.value = await api.getControlButtons();
  } catch {
    controlButtons.value = [];
  }
}

async function setPassthru(index: number, enabled: boolean) {
  controlButtonBusy.value = index;
  try {
    controlButtons.value = await api.setControlButtonPassthru(index, enabled);
  } catch (e) {
    message.error(String(e));
  } finally {
    controlButtonBusy.value = null;
  }
}

/* ── 更新（查 GitHub Release → 下 APK → 交给系统装）──────────────────── */
const appVersion = ref("1.0.0");
const autoCheck = ref(isAutoCheckEnabled());
const dismissedVersion = ref<string | null>(getDismissedVersion());

function toggleAutoCheck() {
  autoCheck.value = !autoCheck.value;
  setAutoCheck(autoCheck.value);
}

function dismissUpdate() {
  const version = updateInfo.value?.version;
  if (!version) return;
  dismissVersion(version);
  dismissedVersion.value = version;
  message.success($t("settings.version-ignored"));
}

function restoreDismissed() {
  clearDismissed();
  dismissedVersion.value = null;
  message.success($t("settings.reminder-restored"));
}

/* ── 插件（组件 / 渲染器）─────────────────────────────────────────────
 * 后端约定：每个写操作都返回**最新列表**，这里直接覆盖 `plugins` 即可。
 * 进度走 `plugin://progress`：download 阶段按字节，verify/extract 只有文案。 */
const plugins = ref<PluginInfo[]>([]);
const pluginsLoading = ref(false);
/** 正在安装/卸载的插件 id（同一时刻只允许一个操作，避免并发写同一目录）。 */
const pluginBusy = ref<string | null>(null);
const pluginProgress = ref<PluginProgressEvent | null>(null);
const pluginManifestUrl = ref("");
let unlistenPlugin: (() => void) | null = null;

async function loadPlugins(refresh = false) {
  pluginsLoading.value = true;
  try {
    plugins.value = refresh ? await api.refreshPluginManifest() : await api.getPlugins();
  } catch (e) {
    // 只有用户主动点「刷新」才提示失败；进页面时静默（离线是常态）
    if (refresh) message.warning(String(e));
  } finally {
    pluginsLoading.value = false;
  }
}

async function pluginAction(id: string, fn: () => Promise<PluginInfo[]>, okText: string) {
  pluginBusy.value = id;
  try {
    plugins.value = await fn();
    message.success(okText);
  } catch (e) {
    message.error(String(e));
  } finally {
    pluginBusy.value = null;
    pluginProgress.value = null;
  }
}

const installPlugin = (id: string) =>
  pluginAction(id, () => api.installPlugin(id), $t("settings.plugin-action"));
const togglePlugin = (p: PluginInfo) =>
  pluginAction(p.id, () => api.setPluginEnabled(p.id, !p.enabled), p.enabled ? $t("settings.toggle-plugin") : $t("common.enabled"));
const uninstallPlugin = (p: PluginInfo) =>
  dialog.warning({
    title: $t("settings.uninstall-plugin"),
    content: $t("settings.uninstall-confirm", { p1: p.name }),
    positiveText: $t("common.uninstall"),
    negativeText: $t("common.cancel"),
    onPositiveClick: () => pluginAction(p.id, () => api.uninstallPlugin(p.id), $t("settings.yi-xie-zai")),
  });

/** 本地 zip 安装：离线、内网分发、调试都走这条路（包内需带 plugin.json）。 */
async function installLocalPlugin() {
  const file = await open({
    multiple: false,
    filters: [{ name: $t("settings.plugin-package"), extensions: ["zip"] }],
  });
  if (!file) return;
  pluginBusy.value = "local";
  try {
    plugins.value = await api.installPluginFromFile(file as string);
    message.success($t("settings.plugin-action"));
  } catch (e) {
    message.error(String(e));
  } finally {
    pluginBusy.value = null;
    pluginProgress.value = null;
  }
}

/** 首启该装但还没装的（渲染器 / 驱动 / 组件）—— 给一个不用等首启窗口的入口 */
const missingRecommended = computed(
  () => plugins.value.filter((p) => p.recommended && p.abi_supported && !p.installed_version)
);

/** 一键补齐：把缺的渲染器/驱动/组件按顺序装完（进度走 plugin://progress） */
async function installRecommended() {
  if (pluginBusy.value !== null) return;
  pluginBusy.value = "recommended";
  try {
    const r = await api.installRecommendedPlugins();
    plugins.value = r.plugins;
    if (r.failed.length) message.warning($t("settings.partial-failure", { p1: r.failed.length, p2: r.failed.join("；") }));
    else message.success($t("settings.installed-count", { p1: r.installed }));
  } catch (e) {
    message.error(String(e));
  } finally {
    pluginBusy.value = null;
    pluginProgress.value = null;
  }
}

async function savePluginManifestUrl() {
  try {
    pluginManifestUrl.value = await api.setPluginManifestUrl(pluginManifestUrl.value.trim());
    message.success($t("settings.saved"));
    await loadPlugins(true);
  } catch (e) {
    message.error(String(e));
  }
}

function pluginPercent(p: PluginProgressEvent | null): number {
  if (!p || !p.total) return 0;
  return Math.min(100, Math.round((p.done / p.total) * 100));
}

function fmtBytes(v: number | null | undefined): string {
  return v ? fmtSize(v) : "—";
}

/**
 * 从系统设置返回后刷新权限状态。
 *
 * 「所有文件访问」只能在系统设置里手动打开，我们的 intent 一发出去就返回：用户勾完回到
 * 应用，这个组件**还挂着**（只是 WebView 走 onPause/onResume）。原来只在 `onMounted` 读一次，
 * 于是 `gameDirState.allFilesAccess` 永远是 false —— 按钮一直显示「授权」，点「选择目录」
 * 还被 `game-dir-need-permission` 拦下来，看起来就像「授权了也没用」。
 */
function onVisible() {
  if (document.visibilityState === "visible") void loadGameDirs();
}

onMounted(() => {
  settings.load();
  loadPojav();
  loadMirrors();
  void refreshSystemProxy();
  startPolling();
  loadStats();
  loadGameDirs();
  void getVersion().then((v) => (appVersion.value = v));
  // 打开「关于」页就顺手查一次（静默失败）；打开「游戏内」页读一次控制布局
  watch(tab, (key) => {
    if (key === "about" && !updateInfo.value && !updateChecking.value) {
      void checkUpdate(true);
    }
    if (key === "game") void loadControlButtons();
  });
  void loadPlugins();
  void api.getPluginManifestUrl().then((u) => (pluginManifestUrl.value = u));
  document.addEventListener("visibilitychange", onVisible);
  // 安装进度：下载按字节推进，校验/解压阶段只有文案
  void listen<PluginProgressEvent>("plugin://progress", (e) => {
    pluginProgress.value = e.payload;
  }).then((fn) => {
    unlistenPlugin = fn;
  });
});
onUnmounted(() => {
  stopPolling();
  if (saveTimer) clearTimeout(saveTimer);
  unlistenPlugin?.();
  document.removeEventListener("visibilitychange", onVisible);
});
</script>

<template>
  <div v-if="settings.settings" class="settings-view">
    <!-- 手机形态：一级 = 分组列表（整屏），二级 = 某个分组（带返回条）。
         原来是「左侧固定导航 + 右侧卡片区」的桌面双栏（竖屏下导航吃掉 ~120px 宽、
         卡片全被挤扁），现在改成 iOS/安卓设置页的原生形态：列表进、进子页返回。

         注意 Transition 必须**同时包住一级和二级**：如果只包二级（v-else），
         从列表进入分组是「v-if/v-else 换元素」，Transition 是重新挂载的，
         初次渲染不走进场动画 —— 表现就是「点进去没有任何动画」。 -->
    <Transition name="settings-pane" mode="out-in" @after-enter="refreshCurrentPaneIndicators">
    <div v-if="!tab" key="__list__" class="sv-list">
      <van-cell-group inset class="grp">
        <van-cell
          v-for="t in tabs"
          :key="t.key"
          :title="t.label"
          is-link
          center
          @click="setTab(t.key)"
        >
          <template #icon>
            <span class="nav-icon"><component :is="t.icon" /></span>
          </template>
          <template #value>
            <span v-if="t.key === 'about' && updateInfo && updateInfo.available" class="nav-dot"></span>
          </template>
        </van-cell>
      </van-cell-group>
    </div>

    <div v-else :key="tab" class="settings-body">
      <!-- 返回条：单栏下必须显式给出口（标题栏里不放二级返回，保持顶部只有全局标题） -->
      <button class="sv-back" @click="setTab(null)">
        <IconChevronLeft />
        <span>{{ tabs.find((x) => x.key === tab)?.label }}</span>
      </button>
      <!-- 常规 -->
      <div v-show="tab === 'general'" class="settings-pane">
        <!-- 游戏统计已挪到首页英雄卡左下角（用户 2026-09-22 要求） -->
        <div class="grid">
          <div class="card glass">
            <h3>{{ $t("settings.memory-default") }}</h3>
            <div class="mem-mode-row">
              <app-seg
                :value="settings.settings.memory_mode"
                :options="memoryModeOptions"
                size="small"
                @update:value="(v: string | number) => settings.patch({ memory_mode: String(v) as 'auto' | 'custom' })"
              />
            </div>
            <div v-if="settings.settings.memory_mode !== 'auto'" class="mem-row">
              <div>
                <label>{{ $t("settings.max-memory") }}</label>
                <!-- 表单控件一律用 UI 库（naive-ui）组件，别再手搓原生 input -->
                <app-slider
                  v-model:value="settings.settings.max_memory_mb"
                  :min="1024"
                  :max="16384"
                  :step="256" />
                <div class="mem-val">{{ settings.settings.max_memory_mb }} MB</div>
              </div>
            </div>
            <div class="mem-gauge">
              <div class="mem-gauge-track">
                <div class="mem-gauge-used" :style="{ width: usedPercent + '%' }"></div>
                <div
                  class="mem-gauge-alloc"
                  :style="{ left: allocStart + '%', width: allocWidth + '%' }"
                ></div>
              </div>
              <div class="mem-gauge-labels">
                <span><i class="dot used"></i>{{ $t("instance-settings.mem-used", { p1: fmtMem(memUsed), p2: usedPercent }) }}</span>
                <span><i class="dot alloc"></i>{{ $t("instance-settings.mem-assigned", { p1: fmtMem(effectiveMemory), p2: allocPercent }) }}</span>
                <span><i class="dot total"></i>{{ $t("instance-settings.mem-total", { p1: fmtMem(memTotal), p2: fmtMem(memAvailable) }) }}</span>
              </div>
            </div>
          </div>

          <div class="card glass">
            <h3>{{ $t("settings.extra-jvm-args-default") }}</h3>
            <app-input
              v-model:value="settings.settings.jvm_args"
              type="textarea"
              :rows="3"
              :placeholder="$t('settings.jvm-args-example')" />
          </div>

          <div class="card glass">
            <h3>{{ $t("settings.extra-game-args-default") }}</h3>
            <app-input
              v-model:value="settings.settings.game_args"
              :placeholder="$t('instance-settings.game-args-hint')" />
          </div>
        </div>
      </div>

      <!-- 外观 -->
      <div v-show="tab === 'appearance'" class="settings-pane">
        <div class="card glass">
          <h3>{{ $t("common.theme") }}</h3>
          <div class="choice-row">
            <span>{{ $t("common.theme") }}</span>
            <div ref="themeSegRef" class="seg">
              <div class="indicator" :style="themeSegStyle"></div>
              <button
                :class="{ active: settings.settings.theme === 'dark' }"
                @click="settings.patch({ theme: 'dark' })"
              >{{ $t("icon-picker-dialog.dark") }}</button>
              <button
                :class="{ active: settings.settings.theme === 'light' }"
                @click="settings.patch({ theme: 'light' })"
              >{{ $t("settings.light") }}</button>
            </div>
          </div>
          <div class="appearance-divider"></div>
          <div class="choice-row">
            <span>{{ $t("settings.accent-color") }}</span>
            <div class="theme-color-row">
              <button
                v-for="c in themeColorPresets"
                :key="c"
                type="button"
                class="color-swatch"
                :class="{ active: settings.settings.theme_color === c }"
                :style="{ background: c }"
                :title="c" :aria-label="c"
                @click="settings.patch({ theme_color: c })"
              ></button>
              <!-- 自定义色：比预设小一圈 + 一圈彩虹环，一眼看出是「自定义入口」
                   而不是第 10 个预设；点开走自研的 HSL 取色面板。 -->
              <button
                type="button"
                class="color-custom"
                :class="{ active: !isPresetColor }"
                :title="$t('settings.custom-color')"
                :aria-label="$t('settings.custom-color')"
                @click="showColorPicker = true"
              >
                <span
                  class="color-custom-ring"
                  :style="{ background: isPresetColor ? 'var(--panel-hover)' : settings.settings.theme_color }"
                >
                  <IconPlus v-if="isPresetColor" />
                </span>
              </button>
            </div>
          </div>
        </div>
        <div class="card glass">
            <h3>{{ $t("utils.categories.gui") }}</h3>
            <!-- 界面缩放：机型之间可视高度差别很大（853×384 / 792×360），
                 与其为每台机器写死尺寸，不如让用户自己调舒服的密度。 -->
            <div class="choice-row">
              <div class="choice-info">
                <span class="choice-label">{{ $t("settings.ui-scale") }}</span>
                <p class="choice-hint">{{ $t("settings.ui-scale-desc") }}</p>
              </div>
              <div class="scale-ctl">
                <app-slider
                  :value="settings.settings?.ui_scale ?? 100"
                  :min="60"
                  :max="150"
                  :step="5"
                  @update:value="onUiScale" />
                <div class="mem-val">{{ settings.settings?.ui_scale ?? 100 }}%</div>
              </div>
            </div>
          <div class="choice-row">
            <div class="choice-info">
              <span class="choice-label">{{ $t("settings.sidebar-collapse-btn") }}</span>
              <p class="choice-hint">{{ $t("settings.sidebar-collapse-desc") }}</p>
            </div>
            <app-switch
              :value="settings.settings.show_sidebar_collapse_btn"
              @update:value="(v: boolean) => settings.patch({ show_sidebar_collapse_btn: v })" />
          </div>
          <div class="choice-row">
            <div class="choice-info">
              <span class="choice-label">{{ $t("settings.sidebar-news") }}</span>
              <p class="choice-hint">{{ $t("settings.sidebar-news-desc") }}</p>
            </div>
            <app-switch
              :value="settings.settings.show_news ?? true"
              @update:value="(v: boolean) => settings.patch({ show_news: v })" />
          </div>
          <div class="choice-row">
            <div class="choice-info">
              <span class="choice-label">{{ $t("settings.orientation") }}</span>
              <p class="choice-hint">{{ $t("settings.orientation-desc") }}</p>
            </div>
            <div ref="orientationSegRef" class="seg">
              <div class="indicator" :style="orientationSegStyle"></div>
              <button
                v-for="m in orientationModes"
                :key="m.id"
                :class="{ active: (settings.settings.orientation ?? 'system') === m.id }"
                @click="selectOrientation(m.id)"
              >
                {{ m.label }}
              </button>
            </div>
          </div>
        </div>
        <div class="card glass">
          <h3>{{ $t("nav.home") }}</h3>
          <div class="choice-row">
            <div class="choice-info">
              <span class="choice-label">{{ $t("settings.home-downloads") }}</span>
              <p class="choice-hint">{{ $t("settings.home-downloads-desc") }}</p>
            </div>
            <app-switch
              :value="settings.settings.show_home_downloads ?? true"
              @update:value="(v: boolean) => settings.patch({ show_home_downloads: v })" />
          </div>
          <div class="choice-row">
            <div class="choice-info">
              <span class="choice-label">{{ $t("home.recent-played") }}</span>
              <p class="choice-hint">{{ $t("settings.home-recent-desc") }}</p>
            </div>
            <app-switch
              :value="settings.settings.show_home_recent ?? true"
              @update:value="(v: boolean) => settings.patch({ show_home_recent: v })" />
          </div>
          <div class="choice-row">
            <div class="choice-info">
              <span class="choice-label">{{ $t("home.play-stats") }}</span>
              <p class="choice-hint">{{ $t("settings.home-stats-desc") }}</p>
            </div>
            <app-switch
              :value="settings.settings.show_home_stats ?? true"
              @update:value="(v: boolean) => settings.patch({ show_home_stats: v })" />
          </div>
        </div>
        <div class="card glass">
          <h3>{{ $t("settings.background-image") }}</h3>
          <div v-if="settings.settings.background_image" class="bg-preview">
            <img :src="bgPreviewUrl" :alt="$t('settings.background-preview')" />
          </div>
          <div class="choice-row">
            <span>{{ $t("settings.background-image") }}</span>
            <div class="bg-actions">
              <button class="mini-btn" @click="pickBackground">{{ $t("settings.pick-image") }}</button>
              <button
                v-if="settings.settings.background_image"
                class="mini-btn"
                @click="settings.patch({ background_image: null })"
              >{{ $t("instance-content.positive-text") }}</button>
            </div>
          </div>
          <div v-if="settings.settings.background_image" class="tune-block">
            <div class="tune-row">
              <label>{{ $t("settings.background-blur") }}</label>
              <app-slider
                v-model:value="settings.settings.background_blur"
                :min="0"
                :max="50"
                :step="1" />
              <span class="tune-val">{{ settings.settings.background_blur }} px</span>
            </div>
            <div class="tune-row">
              <label>{{ $t("settings.background-dim") }}</label>
              <app-slider
                v-model:value="settings.settings.background_dim"
                :min="0"
                :max="100"
                :step="5" />
              <span class="tune-val">{{ settings.settings.background_dim }}%</span>
            </div>
          </div>
        </div>
        <div class="card glass">
          <h3>{{ $t("settings.glass-cards") }}</h3>
          <div class="tune-row">
            <label>{{ $t("settings.glass-intensity") }}</label>
            <app-slider
              v-model:value="settings.settings.glass_blur"
              :min="0"
              :max="30"
              :step="1" />
            <span class="tune-val">{{ settings.settings.glass_blur }} px</span>
          </div>
          <p class="hint">{{ $t("settings.glass-intensity-desc") }}</p>
        </div>
      </div>

      <!-- 下载 -->
      <div v-show="tab === 'download'" class="settings-pane">
        <div class="grid">
          <!-- 「并行下载」：后端已真实生效（见 DOWNLOAD_SLIDERS_ENABLED 的注释）。
               上限与后端 clamp 保持一致，别给界面更大的值。 -->
          <div v-if="DOWNLOAD_SLIDERS_ENABLED" class="card glass">
            <h3>{{ $t("settings.parallel-download") }}</h3>
            <label class="row-label">{{ $t("settings.concurrent-files", { p1: settings.settings.download_threads }) }}<app-slider
                v-model:value="settings.settings.download_threads"
                :min="1"
                :max="FILE_CONCURRENCY_MAX"
                :step="1" />
            </label>
            <p class="hint">{{ $t("settings.parallel-download-desc") }}</p>
            <label class="row-label" style="margin-top: 16px;">{{ $t("settings.chunk-threads", { p1: settings.settings.download_chunk_threads }) }}<app-slider
                v-model:value="settings.settings.download_chunk_threads"
                :min="1"
                :max="CHUNK_CONCURRENCY_MAX"
                :step="1" />
            </label>
            <p class="hint">{{ $t("settings.chunk-threads-desc") }}</p>
          </div>
          <div class="card glass">
            <h3><IconGlobe />{{ $t("settings.download-source") }}</h3>
            <div class="mirror-list">
              <button
                v-for="m in mirrors"
                :key="m.id"
                type="button"
                class="mirror-item"
                :class="{ active: settings.settings.mirror === m.id }"
                @click="selectMirror(m.id)"
              >
                <span class="mirror-main">
                  <span class="mirror-name">{{ m.label }}</span>
                  <span class="mirror-base">{{ m.base || $t('settings.official-source') }}</span>
                </span>
                <span class="mirror-side">
                  <span
                    v-if="mirrorLatency[m.id] !== undefined"
                    class="mirror-ms"
                    :class="{ bad: mirrorLatency[m.id] === null }"
                  >
                    {{ mirrorLatency[m.id] === null ? $t('settings.unavailable') : `${mirrorLatency[m.id]} ms` }}
                  </span>
                  <span
                    class="mirror-btn"
                    :class="{ disabled: testingMirror === m.id }"
                    @click.stop="testMirror(m.id, m.base)"
                  >
                    {{ testingMirror === m.id ? $t('settings.testing') : $t('settings.speed-test') }}
                  </span>
                </span>
              </button>
              <div
                class="mirror-custom"
                :class="{ active: settings.settings.mirror === 'custom' }"
              >
                <label class="mirror-custom-head" @click="selectMirror('custom')">
                  <!-- 原来的 n-radio 只是「选中」的视觉标记；这一行本来就是可点的，
                       换成自绘圆点即可（手机上少一个 13px 的点击目标） -->
                  <span class="mirror-dot" :class="{ on: settings.settings.mirror === 'custom' }"></span>
                  <span>{{ $t("settings.custom-mirror") }}</span>
                </label>
                <app-input
                  v-model:value="settings.settings.mirror_custom"
                  placeholder="https://your-mirror.example.com"
                  @update:value="onCustomMirrorInput" />
                <span
                  class="mirror-btn"
                  :class="{ disabled: testingMirror === 'custom' || !settings.settings.mirror_custom }"
                  @click="testMirror('custom', settings.settings.mirror_custom)"
                >
                  {{ testingMirror === 'custom' ? $t('settings.testing') : $t('settings.speed-test') }}
                </span>
              </div>
            </div>
            <p class="hint">{{ $t("settings.mirror-desc-a") }}<b>{{ $t("settings.mirror-applies-now") }}</b>{{ $t("settings.mirror-desc-b") }}</p>
          </div>
          <div class="card glass">
            <h3>{{ $t("router.download") }}</h3>
            <p class="hint">{{ $t("settings.downloads-hint") }}</p>
          </div>
        </div>
      </div>

      <!-- 内容服务 -->
      <div v-show="tab === 'content'" class="settings-pane">
        <div class="grid">
          <div class="card glass">
            <h3>CurseForge API Key</h3>
            <app-input
              v-model:value="settings.settings.curseforge_api_key"
              :placeholder="$t('settings.cf-key-apply-hint')" />
            <p class="hint">{{ $t("settings.cf-key-note") }}</p>
          </div>
          <div class="card glass">
            <h3>{{ $t("settings.content-translation") }}</h3>
            <app-select
              v-model:value="settings.settings.translate_provider"
              :options="translateOptions" />
            <template v-if="settings.settings.translate_provider === 'custom'">
              <app-input
                v-model:value="settings.settings.translate_api_base"
                :placeholder="$t('settings.api-base-hint')" />
              <app-input
                v-model:value="settings.settings.translate_api_key"
                type="password"
                show-password-on="click"
                placeholder="API Key" />
              <app-input
                v-model:value="settings.settings.translate_api_model"
                :placeholder="$t('settings.model-hint')" />
              <button
                class="mirror-btn proxy-test-btn"
                :class="{ disabled: testingTranslate }"
                @click="testTranslate"
              >
                {{ testingTranslate ? $t('settings.testing') : $t('settings.test-api') }}
              </button>
            </template>
            <p class="hint">
              <template v-if="settings.settings.translate_provider === 'default'">{{ $t("settings.builtin-translate-desc") }}</template>
              <template v-else-if="settings.settings.translate_provider === 'custom'">{{ $t("settings.custom-translate-desc") }}</template>
              <template v-else>{{ $t("settings.baidu-translate-desc") }}</template>
            </p>
            <div class="proxy-row">
              <button
                class="mirror-btn"
                :class="{ disabled: clearingTranslation === 'default' }"
                @click="clearTranslations('default')"
              >
                {{ clearingTranslation === "default" ? $t('settings.clearing') : $t('settings.clear-builtin-cache') }}
              </button>
              <button
                class="mirror-btn"
                :class="{ disabled: clearingTranslation === 'custom' }"
                @click="clearTranslations('custom')"
              >
                {{ clearingTranslation === "custom" ? $t('settings.clearing') : $t('settings.clear-custom-cache') }}
              </button>
            </div>
            <div class="choice-row">
              <div>
                <p class="choice-hint">{{ $t("settings.autoload-body") }}</p>
              </div>
              <app-switch
                :value="settings.settings.body_translate_auto"
                @update:value="(v: boolean) => settings.patch({ body_translate_auto: v })" />
            </div>
          </div>
          <div class="card glass">
            <h3>{{ $t("settings.proxy") }}</h3>
            <div class="proxy-row">
              <div ref="proxyModeSegRef" class="seg">
                <div class="indicator" :style="proxyModeSegStyle"></div>
                <button
                  v-for="m in proxyModes"
                  :key="m.id"
                  :class="{ active: settings.settings.proxy_mode === m.id }"
                  @click="selectProxyMode(m.id)"
                >
                  {{ m.label }}
                </button>
              </div>
              <button
                class="mirror-btn proxy-test-btn"
                :class="{ disabled: testingProxy }"
                @click="testProxy"
              >
                {{ testingProxy ? $t('settings.testing') : $t('settings.test-connection') }}
              </button>
            </div>
            <app-input
              v-if="settings.settings.proxy_mode === 'custom'"
              v-model:value="settings.settings.proxy"
              :placeholder="$t('settings.proxy-hint')"
              @update:value="onCustomProxyInput" />
            <p class="hint">
              {{
                settings.settings.proxy_mode === "system"
                  ? $t('settings.proxy-system')
                  : settings.settings.proxy_mode === "direct"
                    ? $t('settings.proxy-direct')
                    : $t('settings.proxy-custom-desc')
              }}
            </p>
            <!-- 原生请求不会自动走系统代理：代理软件开着却选了直连时，下载会大面积失败 -->
            <p v-if="systemProxy" class="hint" :class="{ 'hint-warn': settings.settings.proxy_mode === 'direct' }">{{ $t("settings.system-proxy-detected", { p1: systemProxy }) }}<template
                v-if="settings.settings.proxy_mode === 'direct'"
              >{{ $t("settings.proxy-direct-warning") }}</template>
            </p>
            <p v-else class="hint">{{ $t("settings.no-system-proxy") }}</p>
          </div>
        </div>
      </div>

      <!-- 插件 -->
      <div v-show="tab === 'plugins'" class="settings-pane">
        <div class="grid">
          <div class="card glass plugin-card">
            <div class="plugin-head">
              <h3>{{ $t("server-detail.plugins") }}</h3>
              <div class="plugin-head-actions">
                <button
                  v-if="missingRecommended.length || pluginBusy === 'recommended'"
                  class="mini-btn"
                  :disabled="pluginBusy !== null"
                  @click="installRecommended"
                >
                  <IconDownload />{{ $t("settings.install-recommended", { p1: missingRecommended.length ? `（${missingRecommended.length}）` : "" }) }}
                </button>
                <button class="mini-btn" :disabled="pluginsLoading" @click="loadPlugins(true)">
                  <IconRefresh />{{ $t("common.refresh") }}</button>
                <button class="mini-btn" :disabled="pluginBusy !== null" @click="installLocalPlugin">
                  <IconPackage />{{ $t("settings.install-local") }}</button>
              </div>
            </div>

            <div v-if="!plugins.length" class="plugin-empty">{{ $t("settings.no-plugins-hint") }}</div>

            <div v-for="p in plugins" :key="p.id" class="plugin-row">
              <div class="plugin-row-main">
                <div class="plugin-title">
                  <span class="plugin-name">{{ p.name }}</span>
                  <span v-if="p.version" class="plugin-badge">{{ p.version }}</span>
                  <span v-else class="plugin-badge">{{ $t("settings.local") }}</span>
                  <span v-if="p.update_available" class="plugin-badge update">{{ $t("settings.update-available") }}</span>
                  <span v-if="p.recommended && !p.installed_version" class="plugin-badge update">{{ $t("settings.recommended-badge") }}</span>
                  <span v-if="!p.abi_supported" class="plugin-badge warn">{{ $t("settings.no-abi-package", { p1: p.device_abi }) }}</span>
                </div>
                <div class="plugin-summary">{{ p.summary }}</div>
                <div class="plugin-meta">
                  <span>{{ p.installed_version ? $t('settings.installed-version', { p1: p.installed_version }) : $t('settings.not-installed') }}</span>
                  <span v-if="p.size">{{ $t("settings.needs-download", { p1: fmtBytes(p.size) }) }}</span>
                  <span v-if="p.installed_size">{{ $t("settings.size-used", { p1: fmtBytes(p.installed_size) }) }}</span>
                  <span v-if="p.installed_version && !p.enabled">{{ $t("settings.disabled-fallback") }}</span>
                  <span v-if="p.renderers && p.renderers.length">{{ $t("settings.maps-renderer", { p1: p.renderers.map((r) => rendererNames[r] ?? r).join("、") }) }}
                  </span>
                </div>
                <div
                  v-if="pluginBusy === p.id && pluginProgress && pluginProgress.id === p.id"
                  class="plugin-progress"
                >
                  <div class="plugin-bar">
                    <div
                      class="plugin-fill"
                      :class="{ indet: !pluginProgress.total }"
                      :style="{
                        width: pluginProgress.total ? pluginPercent(pluginProgress) + '%' : '100%',
                      }"
                    ></div>
                  </div>
                  <span class="plugin-progress-text">{{ pluginProgress.message }}</span>
                </div>
              </div>
              <div class="plugin-actions">
                <button
                  class="mini-btn primary"
                  :disabled="pluginBusy !== null || !p.abi_supported"
                  @click="installPlugin(p.id)"
                >
                  {{ p.installed_version ? (p.update_available ? $t('settings.update') : $t('settings.reinstall')) : $t('downloads.download') }}
                </button>
                <button
                  v-if="p.installed_version"
                  class="mini-btn"
                  :disabled="pluginBusy !== null"
                  @click="togglePlugin(p)"
                >
                  {{ p.enabled ? $t('settings.disable') : $t('instance-content.enable') }}
                </button>
                <button
                  v-if="p.installed_version"
                  class="mini-btn danger"
                  :disabled="pluginBusy !== null"
                  @click="uninstallPlugin(p)"
                >
                  <IconTrash />{{ $t("common.uninstall") }}</button>
              </div>
            </div>
          </div>

          <div class="card glass">
            <h3>{{ $t("settings.plugin-source") }}</h3>
            <app-input v-model:value="pluginManifestUrl" placeholder="https://…/manifest.json" />
            <div class="plugin-source-actions">
              <button class="mini-btn primary" @click="savePluginManifestUrl">{{ $t("settings.save-and-refresh") }}</button>
            </div>
          </div>
        </div>
      </div>

      <!-- 存储 -->
      <div v-show="tab === 'storage'" class="settings-pane">
        <div class="grid storage-grid">
          <!-- 游戏目录（实例存放位置） -->
          <div class="card glass storage-card">
            <div class="storage-header">
              <h3>{{ $t("settings.game-dir") }}</h3>
              <span class="game-dir-root" :title="currentGameRoot">{{ currentGameRoot }}</span>
            </div>
            <p class="hint">{{ $t("settings.game-dir-hint") }}</p>
            <ul class="instance-storage-list">
              <li v-for="opt in gameDirOptions" :key="opt.path">
                <span class="instance-name" :title="opt.path">{{ opt.label }}</span>
                <span class="legend-size">{{ fmtSize(opt.free) }}</span>
                <span v-if="opt.path === currentGameRoot" class="legend-pct">{{ $t("settings.game-dir-in-use") }}</span>
                <button v-else class="mini-btn" :disabled="gameDirBusy" @click="chooseGameDir(opt)">
                  {{ $t("settings.game-dir-use") }}
                </button>
              </li>
            </ul>
            <div class="storage-footer">
              <button class="mini-btn" :disabled="gameDirBusy" @click="pickCustomGameDir">
                {{ $t("settings.game-dir-pick") }}
              </button>
              <button
                class="mini-btn"
                :disabled="gameDirBusy || currentGameRoot === PUBLIC_GAME_DIR"
                @click="moveToPublicDir"
              >
                {{ $t("settings.game-dir-public") }}
              </button>
              <button
                v-if="gameDirState && !gameDirState.allFilesAccess"
                class="mini-btn"
                @click="grantAllFilesAccess"
              >
                {{ $t("settings.game-dir-grant") }}
              </button>
            </div>
          </div>

          <div class="card glass storage-card">
            <div class="storage-header">
              <h3>{{ $t("settings.storage-stats") }}</h3>
              <div class="storage-actions">
                <span class="hint-inline">
                  <template v-if="stats">{{ stats.cached ? $t('settings.last-scan') : $t('settings.updated') }}：{{ fmtTime(stats.updated_at) }}</template>
                  <template v-else>{{ $t("settings.never-scanned") }}</template>
                </span>
                <button class="mini-btn" :disabled="loadingStats" @click="refreshStats">
                  <IconRefresh class="btn-icon" />
                  {{ loadingStats ? $t('settings.scanning') : $t('settings.update') }}
                </button>
              </div>
            </div>

            <div v-if="stats && stats.total > 0" class="storage-body">
              <div class="donut-wrap">
                <svg viewBox="0 0 200 200" class="donut">
                  <circle
                    v-for="seg in donutSegs"
                    :key="seg.key"
                    cx="100"
                    cy="100"
                    r="74"
                    fill="none"
                    :stroke="seg.color"
                    stroke-width="30"
                    :stroke-dasharray="`${seg.dash} ${DONUT_C - seg.dash}`"
                    :stroke-dashoffset="seg.offset"
                    transform="rotate(-90 100 100)"
                  />
                </svg>
                <div class="donut-center">
                  <span class="donut-total">{{ fmtSize(stats.total) }}</span>
                  <span class="donut-label">{{ $t("settings.total-usage") }}</span>
                </div>
              </div>

              <ul class="storage-legend">
                <li v-for="cat in visibleCats" :key="cat.key">
                  <span class="legend-dot" :style="{ background: DONUT_COLORS[cat.key] ?? '#64748b' }"></span>
                  <span class="legend-name">{{ cat.label }}</span>
                  <span class="legend-size">{{ fmtSize(cat.size) }}</span>
                  <span class="legend-pct">{{ pct(cat.size) }}%</span>
                </li>
              </ul>
            </div>

            <div v-if="stats && stats.instances.length" class="instance-storage">
              <h4 class="instance-storage-title">{{ $t("settings.per-instance") }}<span class="hint-inline">{{ $t("crash-analyzer.count-suffix", { p1: stats.instances.length }) }}</span>
              </h4>
              <ul class="instance-storage-list">
                <li v-for="inst in stats.instances" :key="inst.id">
                  <span class="instance-name" :title="inst.name">{{ inst.name }}</span>
                  <span class="legend-size">{{ fmtSize(inst.size) }}</span>
                  <span class="legend-pct">{{ pct(inst.size) }}%</span>
                </li>
              </ul>
            </div>

            <div v-if="stats && stats.servers.length" class="instance-storage">
              <h4 class="instance-storage-title">{{ $t("settings.per-server") }}<span class="hint-inline">{{ $t("crash-analyzer.count-suffix", { p1: stats.servers.length }) }}</span>
              </h4>
              <ul class="instance-storage-list">
                <li v-for="srv in stats.servers" :key="srv.id">
                  <span class="instance-name" :title="srv.name">{{ srv.name }}</span>
                  <span class="legend-size">{{ fmtSize(srv.size) }}</span>
                  <span class="legend-pct">{{ pct(srv.size) }}%</span>
                </li>
              </ul>
            </div>
            <p v-else-if="!stats?.instances.length" class="hint">{{ stats ? $t('settings.no-stats') : $t('settings.loading-stats') }}</p>

            <div class="storage-footer">
              <button class="mini-btn danger" :disabled="clearing" @click="confirmClear">
                <IconTrash class="btn-icon" />
                {{ clearing ? $t('settings.cleaning') : $t('settings.clear-cache') }}
              </button>
              <span class="hint">{{ $t("settings.clean-cache-desc") }}</span>
            </div>
          </div>
        </div>
      </div>
      <!-- 游戏内（移植自 Pojav 的控制层设置） -->
      <div v-show="tab === 'game'" class="settings-pane">
        <div class="grid">
          <div class="card glass">
            <h3><IconPlay />{{ $t("settings.rendering") }}</h3>
            <div class="choice-row">
              <div class="choice-info">
                <span class="choice-label">{{ $t("instance-settings.renderer") }}</span>
              </div>
              <app-select
                :value="(pojav.renderer as string) ?? 'opengles2'"
                :options="rendererOptions"
                size="small"
                style="width: 190px"
                @update:value="(v: string) => savePojav('renderer', v)" />
            </div>
            <div class="mem-row">
              <div>
                <label>{{ $t("settings.render-scale") }}</label>
                <app-slider
                  :value="Number(pojav.resolutionRatio ?? 100)"
                  :min="50"
                  :max="150"
                  :step="5"
                  @update:value="(v: number) => onPojavRange('resolutionRatio', v)" />
                <div class="mem-val">{{ $t("settings.percent-hint", { p1: pojav.resolutionRatio }) }}</div>
              </div>
            </div>
            <div class="choice-row">
              <div class="choice-info">
                <span class="choice-label">{{ $t("settings.ignore-notch") }}</span>
                <p class="choice-hint">{{ $t("settings.ignore-notch-desc") }}</p>
              </div>
              <app-switch :value="!!pojav.ignoreNotch" @update:value="(v: boolean) => savePojav('ignoreNotch', v)" />
            </div>
            <div class="choice-row">
              <div class="choice-info">
                <span class="choice-label">{{ $t("settings.alt-surface") }}</span>
                <p class="choice-hint">{{ $t("settings.alt-surface-desc") }}</p>
              </div>
              <app-switch
                :value="pojav.alternate_surface !== false"
                @update:value="(v: boolean) => savePojav('alternate_surface', v)" />
            </div>
            <div class="choice-row">
              <div class="choice-info">
                <span class="choice-label">{{ $t("settings.sustained-performance") }}</span>
                <p class="choice-hint">{{ $t("settings.sustained-performance-desc") }}</p>
              </div>
              <app-switch
                :value="!!pojav.sustained_performance"
                @update:value="(v: boolean) => savePojav('sustained_performance', v)" />
            </div>
          </div>

          <!-- 按键布局：进原生横屏编辑器（和游戏里所见即所得那个一模一样）。
               放在「按键透传」上方：先能进布局调键位，再谈逐个按键的透传细项。 -->
          <div class="card glass">
            <h3>{{ $t("settings.key-layout") }}</h3>
            <p class="hint">{{ $t("settings.key-layout-desc") }}</p>
            <button class="mini-btn" @click="openKeyLayoutEditor">{{ $t("settings.key-layout-edit") }}</button>
          </div>

          <div class="card glass">
            <!-- 标题可点：展开/收起逐键列表。默认收起 —— 它属于「调一次就不动」的设置，
                 摊开十几行会把下面真正常用的「操作」组挤到几屏之外。 -->
            <h3
              style="display: flex; align-items: center; gap: 6px; cursor: pointer"
              @click="showPassthru = !showPassthru"
            >
              {{ $t("settings.key-passthrough") }}
              <IconChevronUp v-if="showPassthru" />
              <IconChevronDown v-else />
            </h3>
            <p class="hint">{{ $t("settings.key-passthrough-desc") }}</p>
            <div v-if="!controlButtons.length" class="hint">{{ $t("settings.no-layout-yet") }}</div>
            <template v-if="showPassthru">
              <div v-for="b in controlButtons" :key="b.index" class="choice-row">
                <div class="choice-info">
                  <span class="choice-label">{{ b.name }}</span>
                </div>
                <app-switch
                  :value="b.passThru"
                  :loading="controlButtonBusy === b.index"
                  @update:value="(v: boolean) => setPassthru(b.index, v)" />
              </div>
            </template>
          </div>

          <div class="card glass">
            <h3>{{ $t("settings.controls") }}</h3>
            <div class="mem-row">
              <div>
                <label>{{ $t("settings.button-size") }}</label>
                <app-slider
                  :value="Number(pojav.buttonscale ?? 100)"
                  :min="50"
                  :max="200"
                  :step="10"
                  @update:value="(v: number) => onPojavRange('buttonscale', v)" />
                <div class="mem-val">{{ pojav.buttonscale }}%</div>
              </div>
            </div>
            <div class="mem-row">
              <div>
                <label>{{ $t("settings.mouse-speed") }}</label>
                <app-slider
                  :value="Number(pojav.mousespeed ?? 100)"
                  :min="50"
                  :max="200"
                  :step="10"
                  @update:value="(v: number) => onPojavRange('mousespeed', v)" />
                <div class="mem-val">{{ pojav.mousespeed }}%</div>
              </div>
            </div>
            <div class="mem-row">
              <div>
                <label>{{ $t("settings.long-press-time") }}</label>
                <app-slider
                  :value="Number(pojav.timeLongPressTrigger ?? 300)"
                  :min="150"
                  :max="800"
                  :step="50"
                  @update:value="(v: number) => onPojavRange('timeLongPressTrigger', v)" />
                <div class="mem-val">{{ $t("settings.ms-hint", { p1: pojav.timeLongPressTrigger }) }}</div>
              </div>
            </div>
            <!-- 手柄死区（默认隐藏，见 script 里 GAMEPAD_DEADZONE_ENABLED 的说明） -->
            <div v-if="GAMEPAD_DEADZONE_ENABLED" class="mem-row">
              <div>
                <label>{{ $t("settings.gamepad-deadzone") }}</label>
                <app-slider
                  :value="Number(pojav.gamepad_deadzone_scale ?? 100)"
                  :min="0"
                  :max="200"
                  :step="10"
                  @update:value="(v: number) => onPojavRange('gamepad_deadzone_scale', v)" />
                <div class="mem-val">{{ pojav.gamepad_deadzone_scale }}%</div>
              </div>
            </div>
            <div class="choice-row">
              <div class="choice-info">
                <span class="choice-label">{{ $t("settings.auto-virtual-mouse") }}</span>
                <p class="choice-hint">{{ $t("settings.auto-virtual-mouse-desc") }}</p>
              </div>
              <app-switch :value="!!pojav.mouse_start" @update:value="(v: boolean) => savePojav('mouse_start', v)" />
            </div>
            <div class="choice-row">
              <div class="choice-info">
                <span class="choice-label">{{ $t("settings.swap-hands") }}</span>
                <p class="choice-hint">{{ $t("settings.swap-hands-desc") }}</p>
              </div>
              <app-switch
                :value="!!pojav.disableDoubleTap"
                @update:value="(v: boolean) => savePojav('disableDoubleTap', v)" />
            </div>
            <div class="choice-row">
              <div class="choice-info">
                <span class="choice-label">{{ $t("settings.disable-gestures") }}</span>
                <p class="choice-hint">{{ $t("settings.disable-gestures-desc") }}</p>
              </div>
              <app-switch
                :value="!!pojav.disableGestures"
                @update:value="(v: boolean) => savePojav('disableGestures', v)" />
            </div>
            <div class="choice-row">
              <div class="choice-info">
                <span class="choice-label">{{ $t("settings.uppercase-buttons") }}</span>
                <p class="choice-hint">{{ $t("settings.uppercase-buttons-desc") }}</p>
              </div>
              <app-switch
                :value="!!pojav.buttonAllCaps"
                @update:value="(v: boolean) => savePojav('buttonAllCaps', v)" />
            </div>
          </div>

          <div class="card glass">
            <h3>{{ $t("settings.gyroscope") }}</h3>
            <div class="choice-row">
              <div class="choice-info">
                <span class="choice-label">{{ $t("settings.enable-gyro") }}</span>
                <p class="choice-hint">{{ $t("settings.enable-gyro-desc") }}</p>
              </div>
              <app-switch :value="!!pojav.enableGyro" @update:value="(v: boolean) => savePojav('enableGyro', v)" />
            </div>
            <div class="mem-row">
              <div>
                <label>{{ $t("settings.sensitivity") }}</label>
                <app-slider
                  :value="Number(pojav.gyroSensitivity ?? 100)"
                  :min="50"
                  :max="300"
                  :step="10"
                  @update:value="(v: number) => onPojavRange('gyroSensitivity', v)" />
                <div class="mem-val">{{ pojav.gyroSensitivity }}%</div>
              </div>
            </div>
            <div class="choice-row">
              <div class="choice-info"><span class="choice-label">{{ $t("settings.invert-x") }}</span></div>
              <app-switch :value="!!pojav.gyroInvertX" @update:value="(v: boolean) => savePojav('gyroInvertX', v)" />
            </div>
            <div class="choice-row">
              <div class="choice-info"><span class="choice-label">{{ $t("settings.invert-y") }}</span></div>
              <app-switch :value="!!pojav.gyroInvertY" @update:value="(v: boolean) => savePojav('gyroInvertY', v)" />
            </div>
          </div>
        </div>
      </div>

      <!-- 关于 -->
      <div v-show="tab === 'about'" class="settings-pane">
        <div class="card glass about-showcase">
          <AboutShowcase />
          <div class="about-hero-title">
            <span class="about-name about-hero-name">QookiX Launcher Android</span>
            <span
              class="about-ver"
              :class="{ 'ver-arming': verHolding }"
              :title="$t('settings.hold-hint')"
              @pointerdown="verDown"
              @pointerup="verCancel"
              @pointercancel="verCancel"
              @pointerleave="verCancel"
              @contextmenu.prevent
            >v1.6.0</span>
          </div>
          <p class="about-hero-slogan">{{ $t("settings.tagline") }}</p>
        </div>

        <div class="grid about-grid">
          <!-- 更新 -->
          <div class="card glass updater-card">
            <div class="updater-head">
              <h3>{{ $t("settings.update") }}</h3>
              <div class="updater-head-actions">
                <button
                  class="mini-btn primary"
                  :disabled="updateChecking || updateDownloading"
                  @click="checkUpdate()"
                >
                  <IconRefresh /> {{ updateChecking ? $t('settings.checking') : $t('instance-content.check-updates') }}
                </button>
              </div>
            </div>

            <p v-if="updateError" class="hint updater-error">{{ updateError }}</p>
            <p v-else-if="!updateInfo" class="hint">{{ $t("settings.current-version", { p1: appVersion }) }}</p>
            <template v-else>
              <p v-if="!updateInfo.available" class="hint">{{ $t("settings.up-to-date", { p1: updateInfo.currentVersion }) }}</p>
              <template v-else>
                <p class="updater-new">{{ $t("settings.new-version", { p1: updateInfo.version }) }}<span class="hint">{{ $t("settings.current-badge", { p1: updateInfo.currentVersion }) }}</span>
                </p>

                <div v-if="updateDownloading" class="updater-progress">
                  <div class="plugin-bar">
                    <div
                      class="plugin-fill indet"
                      :style="{
                        width: updateProgress?.total
                          ? Math.round((updateProgress.downloaded / updateProgress.total) * 100) + '%'
                          : '100%',
                      }"
                    ></div>
                  </div>
                  <span class="plugin-progress-text">
                    {{
                      updateProgress?.total
                        ? $t('settings.downloading-progress', { p1: fmtBytes(updateProgress.downloaded), p2: fmtBytes(updateProgress.total) })
                        : $t('settings.downloading')
                    }}
                  </span>
                </div>

                <div class="updater-actions">
                  <button
                    v-if="!updatePackage"
                    class="mini-btn primary"
                    :disabled="updateDownloading"
                    @click="downloadUpdate()"
                  >{{ $t("downloads.download") }}</button>
                  <button v-else class="mini-btn primary" @click="installUpdate()">{{ $t("common.install") }}</button>
                  <button class="mini-btn" @click="dismissUpdate()">{{ $t("settings.ignore-version") }}</button>
                </div>

                <p v-if="updatePackage" class="hint">{{ $t("settings.apk-downloaded", { p1: updatePackage.split("/").pop() }) }}
                </p>
              </template>
            </template>

            <div class="updater-auto">
              <button
                class="mini-btn"
                :class="{ primary: autoCheck }"
                @click="toggleAutoCheck()"
              >
                {{ autoCheck ? $t('settings.auto-check-on') : $t('settings.auto-check-off') }}
              </button>
              <button v-if="dismissedVersion" class="mini-btn" @click="restoreDismissed()">{{ $t("settings.resume-reminder", { p1: dismissedVersion }) }}</button>
            </div>
          </div>
          <div class="card glass about-card">
            <div class="about-devs-title">{{ $t("settings.developers") }}</div>
            <div class="dev-list">
              <div class="dev-line">
                <img class="dev-avatar" :src="devZhayiUrl" alt="ZhaYi" />
                <div class="dev-meta">
                  <div class="dev-head">
                    <span class="dev-name">ZhaYi</span>
                    <button
                      class="dev-github-btn"
                      :aria-label="$t('settings.github-profile')"
                      @click="openUrl('https://github.com/ZhaYi-Miao')"
                    >
                      <IconGithub />
                    </button>
                  </div>
                  <span class="dev-role">{{ $t("settings.developers-desc") }}</span>
                </div>
              </div>
              <div class="dev-line">
                <img class="dev-avatar" :src="devWeimoshengUrl" :alt="$t('settings.author-name')" />
                <div class="dev-meta">
                  <div class="dev-head">
                    <span class="dev-name">{{ $t("settings.author-name") }}</span>
                    <button
                      class="dev-github-btn"
                      :aria-label="$t('settings.github-profile')"
                      @click="openUrl('https://github.com/weimosheng')"
                    >
                      <IconGithub />
                    </button>
                  </div>
                  <span class="dev-role">{{ $t("settings.contributors-desc") }}</span>
                </div>
              </div>
            </div>
          </div>
          <div class="about-links-row">
            <button class="about-link" @click="openUrl('https://www.qookix.cn/')">
              <span class="link-left"><IconGlobe />{{ $t("settings.website") }}</span>
              <span class="link-arrow">→</span>
            </button>
            <button class="about-link" @click="openUrl('https://github.com/ZhaYi-Miao/QookiX-Launcher-Android')">
              <span class="link-left"><IconGithub />{{ $t("settings.github-repo") }}</span>
              <span class="link-arrow">→</span>
            </button>
            <button class="about-link" @click="openUrl('https://github.com/ZhaYi-Miao/QookiX-Launcher-Android/issues')">
              <span class="link-left"><IconExternal />{{ $t("settings.issues") }}</span>
              <span class="link-arrow">→</span>
            </button>
            <button class="about-link" @click="openUrl('https://github.com/ZhaYi-Miao/QookiX-Launcher-Android/blob/master/CHANGELOG.md')">
              <span class="link-left"><IconList />{{ $t("settings.changelog") }}</span>
              <span class="link-arrow">→</span>
            </button>
            <button class="about-link" @click="openUrl('https://qm.qq.com/q/91keQnJ8dy')">
              <span class="link-left"><IconUsers />{{ $t("settings.qq-group") }}</span>
              <span class="link-arrow">→</span>
            </button>
            <button class="about-link" @click="openUrl('https://afdian.com/a/qookix')">
              <span class="link-left"><IconHeart />{{ $t("settings.afdian") }}</span>
              <span class="link-arrow">→</span>
            </button>
          </div>
          <div class="card glass about-license-card">
            <h3>{{ $t("settings.license") }}</h3>
            <p class="license-text">{{ $t("settings.license-desc-a") }}<span class="license-accent">GPL-3.0</span>{{ $t("settings.license-desc-b") }}</p>
            <button class="about-link" @click="openUrl('https://github.com/ZhaYi-Miao/QookiX-Launcher-Android/blob/master/LICENSE')">
              <span class="link-left"><IconFile />{{ $t("settings.gpl-text") }}</span>
              <span class="link-arrow">→</span>
            </button>
          </div>
          <div class="card glass about-deps-card">
            <h3>{{ $t("settings.notices") }}</h3>
            <p class="license-text">{{ $t("settings.thanks-desc") }}</p>
            <div class="deps-groups">
              <div class="deps-group" v-for="(list, group) in aboutDeps" :key="group">
                <div class="deps-group-title">{{ aboutGroupLabels[group] }}</div>
                <div v-for="d in list" :key="d.name" class="about-dep-row">
                  <div class="dep-info">
                    <span class="dep-name">{{ d.name }}<span class="dep-ver" v-if="d.version">v{{ d.version }}</span></span>
                    <span class="dep-license" v-if="d.license">{{ d.license }}</span>
                  </div>
                  <div class="dep-links">
                    <button class="dep-link" @click="openUrl(d.url)">{{ $t("settings.source-link") }}</button>
                    <button class="dep-link" @click="openUrl(d.licenseUrl)">{{ $t("settings.license-link") }}</button>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
    </Transition>
    <!-- 版本彩蛋：全屏像素烟花 + 制作名单，点击任意处关闭。
         Teleport 到 body：脱离 #app 的 zoom/层叠上下文，才能真正盖住底部导航、
         且点击事件不会被设置页的滚动容器吞掉。 -->
    <Teleport to="body">
      <Transition name="veregg">
        <div v-if="verEgg" class="ver-egg" @pointerdown="verClose" @click="verClose">
          <div class="ver-sparks" aria-hidden="true">
            <span
              v-for="(s, i) in verSparks"
              :key="i"
              class="spark"
              :style="{
                left: s.left + '%',
                top: s.top + '%',
                width: s.size + 'px',
                height: s.size + 'px',
                background: s.color,
                animationDelay: s.delay + 's',
                animationDuration: s.dur + 's',
              }"
            ></span>
          </div>
          <div class="ver-credits">
            <div class="ver-credits-inner">
              <img class="vc-logo" :src="logoUrl" alt="" />
              <div class="vc-title">QookiX Launcher Android</div>
              <div class="vc-line">{{ $t("settings.made-by") }}</div>
              <div class="vc-line">{{ $t("settings.thanks") }}</div>
              <div class="vc-line">{{ $t("settings.feedback-invite") }}</div>
            </div>
          </div>
          <div class="ver-hint">{{ $t("settings.click-anywhere-to-close") }}</div>
        </div>
      </Transition>
    </Teleport>

    <color-picker-sheet
      :show="showColorPicker"
      :value="settings.settings.theme_color"
      @update:show="(v: boolean) => (showColorPicker = v)"
      @confirm="applyCustomColor"
    />
  </div>
</template>

<style scoped>
.settings-view {
  display: flex;
  gap: 18px;
  align-items: flex-start;
}
.settings-nav {
  flex-shrink: 0;
  /* 与内容中心 / 实例详情的侧栏共用 `--rail-w`（见 styles.css）：
     三处侧栏宽度一致，且随视口自适应、不写死。
     顺带把原来 188px 收窄了 50px，还给右边卡片（竖屏下卡片最小 340px 更容易放得下）。 */
  width: var(--rail-w);
  position: sticky;
  top: 0;
  padding: 16px 14px;
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: 14px;
  backdrop-filter: blur(var(--glass-blur, 8px));
  -webkit-backdrop-filter: blur(var(--glass-blur, 8px));
}
.nav-list {
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.nav-item {
  display: flex;
  align-items: center;
  gap: 9px;
  text-align: left;
  border: none;
  background: transparent;
  color: var(--text-2);
  padding: 9px 12px;
  border-radius: 9px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  font-family: inherit;
  transition: background 0.12s, color 0.12s;
}
.nav-icon {
  width: 15px;
  height: 15px;
  flex-shrink: 0;
  opacity: 0.85;
}
.nav-item:hover {
  background: var(--panel-hover);
  color: var(--text-1);
}
.nav-item.active {
  background: var(--accent-soft);
  color: var(--accent);
  font-weight: 600;
}
.nav-item.active .nav-icon {
  opacity: 1;
}
/* 「关于」有新版本时的小圆点 */
.nav-dot {
  width: 7px;
  height: 7px;
  margin-left: auto;
  border-radius: 50%;
  background: var(--accent);
}
.settings-body {
  flex: 1;
  min-width: 0;
}
/* 子标签页切换动画 */
.settings-pane-enter-active {
  transition: opacity 0.22s ease, transform 0.26s cubic-bezier(0.22, 1, 0.36, 1);
}
.settings-pane-leave-active {
  transition: opacity 0.13s ease, transform 0.13s ease-in;
}
.settings-pane-enter-from {
  opacity: 0;
  transform: translateY(10px);
}
.settings-pane-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}
.settings-pane {
  display: flex;
  flex-direction: column;
  gap: 18px;
}
/* 二级分组页的左右留白：与其它页面一致（MoreView / InstancesView 等都在页面根上写
   `padding: 4px 16px 16px`），卡片不再顶到屏幕两边。
   以前只在窄屏（container query ≤640px）加 —— 那会儿宽屏还是「左导航 + 右卡片」双栏，
   卡片左边有导航顶着、加了反而挤；现在改成「列表进、子页返回」的单栏形态，
   左边已经没有导航，宽屏同样会贴边，所以留白改成无条件。 */
.settings-body {
  padding: 4px 16px 16px;
}
.btn {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  border: none;
  border-radius: 10px;
  padding: 10px 18px;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  font-family: inherit;
  transition: all 0.15s;
}
.btn.primary {
  background: linear-gradient(135deg, var(--accent), var(--accent-deep));
  color: #1a1208;
}
.btn.primary:hover:not(:disabled) {
  filter: brightness(1.08);
}
.btn:disabled {
  opacity: 0.6;
}
.settings-pane > .grid {
  margin-top: 0;
}
.grid {
  display: grid;
  /* `min(340px, 100%)` 是本轮修 P1-13 的关键：
     原来写死 `minmax(340px, 1fr)`，容器比 340px 窄时（手机竖屏内容区只有 230px）
     列宽仍按 340px 算 → **横向溢出**，卡片右侧的开关要横向滚才够得到。
     加上 min() 之后列宽永远不会超过容器：宽容器下 min(340,100%) == 340px，
     行为与原来完全一致；窄容器下退化成 100%，不再溢出。
     （全仓还有 10 处同形状的 `repeat(auto-fill, minmax(Npx, 1fr))`，一并加了 min()） */
  grid-template-columns: repeat(auto-fill, minmax(min(340px, 100%), 1fr));
  gap: 16px;
  margin-top: 14px;
}
.card {
  padding: 18px;
}
.card h3 {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 14px;
  font-size: 14px;
}
.card h3 svg {
  color: var(--accent);
}
.text-input {
  flex: 1;
  width: 100%;
  min-width: 0;
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid var(--border);
  border-radius: 9px;
  color: var(--text-1);
  padding: 8px 12px;
  font-size: 13px;
  font-family: inherit;
  outline: none;
  transition: border-color 0.12s;
}
.text-input:focus {
  border-color: var(--accent-05);
}
textarea.text-input {
  resize: vertical;
  width: 100%;
}
.mini-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 13px;
  border-radius: 8px;
  border: 1px solid var(--border);
  background: rgba(255, 255, 255, 0.04);
  color: var(--text-2);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  font-family: inherit;
  white-space: nowrap;
}
.mini-btn:hover {
  background: rgba(255, 255, 255, 0.08);
}
.mini-btn:disabled {
  opacity: 0.5;
}
.mini-btn.danger {
  border-color: rgba(229, 83, 75, 0.35);
  color: #e5534b;
  background: rgba(229, 83, 75, 0.08);
}
.mini-btn.danger:hover {
  background: rgba(229, 83, 75, 0.16);
}
.btn-icon {
  width: 14px;
  height: 14px;
}
.storage-grid {
  /* minmax(0,1fr) 而不是 1fr：1fr 的最小尺寸是内容 min-content，
     卡里那一组 nowrap 的图例+按钮会把它顶到 438px 宽（容器 352px）→ 右侧出血。 */
  grid-template-columns: minmax(0, 1fr);
}
.storage-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  /* 手机：图例 + 操作区原来 nowrap，硬撑到 438px（容器只有 352px），
     右侧「更新」按钮被顶出屏幕。放不下就换行。 */
  flex-wrap: wrap;
  margin-bottom: 14px;
}
.storage-header h3 {
  margin: 0;
}
.game-dir-root {
  /* 路径很长（/storage/emulated/0/Android/data/…），必须允许断行，
     否则会把卡片撑破、右侧出血。 */
  flex: 1 1 100%;
  min-width: 0;
  font-size: 12px;
  color: var(--text-3);
  word-break: break-all;
}
.storage-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  /* 同上：窄屏下这一组（上次更新 / 刷新 / 更新）自己也要能换行，
     并允许收缩，否则会把卡片撑宽。 */
  flex-wrap: wrap;
  min-width: 0;
}
.storage-footer {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 18px;
  padding-top: 16px;
  border-top: 1px solid var(--border);
  flex-wrap: wrap;
}
.storage-footer .hint {
  margin: 0;
  flex: 1;
  min-width: 200px;
}
.storage-body {
  display: flex;
  align-items: center;
  gap: 26px;
}
/* 窄屏：圆环和图例改上下排。原来左右排时图例那一列被压得只剩「大小 + 百分比」，
   分组名称（游戏实例 / 库文件 / 资源文件…）被挤成 0 宽，完全看不见。 */
@container page (max-width: 640px) {
  .storage-body {
    flex-direction: column;
    align-items: stretch;
    gap: 16px;
  }
  .donut-wrap {
    align-self: center;
  }
}
.donut-wrap {
  position: relative;
  flex: none;
  width: 190px;
  height: 190px;
}
.donut {
  width: 100%;
  height: 100%;
}
.donut-center {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  pointer-events: none;
}
.donut-total {
  font-size: 20px;
  font-weight: 700;
  color: var(--text-1);
  line-height: 1.2;
}
.donut-label {
  font-size: 12px;
  color: var(--text-3);
  margin-top: 2px;
}
.storage-legend {
  list-style: none;
  margin: 0;
  padding: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 9px;
  min-width: 0;
}
.storage-legend li {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  /* 数值+单位不许折行（原来「26.3 KB」会断成两行，右列参差不齐） */
  white-space: nowrap;
}
.legend-dot {
  flex: none;
  width: 10px;
  height: 10px;
  border-radius: 50%;
}
.legend-name {
  color: var(--text-2);
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.legend-size {
  color: var(--text-1);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.legend-pct {
  color: var(--text-3);
  width: 48px;
  text-align: right;
  font-variant-numeric: tabular-nums;
}
.instance-storage {
  margin-top: 18px;
  border-top: 1px solid var(--border);
  padding-top: 12px;
}
.instance-storage-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 10px;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-2);
}
.instance-storage-list {
  list-style: none;
  margin: 0;
  padding: 0;
  max-height: 220px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.instance-storage-list li {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
}
.instance-name {
  color: var(--text-1);
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mem-row {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.mem-row label {
  display: block;
  font-size: 13px;
  color: var(--text-2);
  margin-bottom: 8px;
}
.mem-val {
  font-size: 13px;
  color: var(--accent);
  font-weight: 600;
  margin-top: 4px;
}
.mem-mode-row {
  display: flex;
  gap: 16px;
  gap: 6px;
  font-size: 13px;
  color: var(--text-2);
  cursor: pointer;
  user-select: none;
}
.radio-label input {
  accent-color: var(--accent);
  cursor: pointer;
}
.radio-label.active {
  color: var(--accent);
  font-weight: 600;
}
.mem-gauge {
  margin-top: 14px;
}
.mem-gauge-track {
  position: relative;
  height: 10px;
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.08);
  overflow: hidden;
}
.mem-gauge-used,
.mem-gauge-alloc {
  position: absolute;
  top: 0;
  bottom: 0;
  height: 100%;
  transition: width 0.2s, left 0.2s;
}
.mem-gauge-used {
  left: 0;
  background: linear-gradient(90deg, #5a8ef0, #8ab4ff);
}
.mem-gauge-alloc {
  background: linear-gradient(90deg, #e89a4b, #f2c079);
}
.mem-gauge-labels {
  display: flex;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 4px;
  font-size: 11px;
  color: var(--text-3);
  margin-top: 6px;
}
.mem-gauge-labels span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
}
.dot.used {
  background: #8ec4ff;
}
.dot.alloc {
  background: #e89a4b;
}
.dot.total {
  background: #9aa4b2;
}
.range {
  width: 100%;
  accent-color: var(--accent);
}
.row-label {
  display: block;
  font-size: 13px;
  color: var(--text-2);
  margin-bottom: 10px;
}
.choice-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  margin-bottom: 14px;
  font-size: 13px;
  color: var(--text-2);
}
.choice-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
/* 竖屏（默认方向）下分段控件 / 单选组 / 选择器这类「宽控件」一行放不下：
   5 个选项的挖孔组会把左侧说明挤成一列竖排字（实测「挖 孔 / 刘 海」每个字一行）。
   这里让这类行折成两行：说明一行、控件一行铺满 —— 也是安卓设置项的常见排法。
   `:has()` 万一不被支持，只是少掉这一段规则，不会连带废掉同组其它选择器。 */
.choice-row:has(.seg),
.choice-row:has(.n-radio-group),
.choice-row:has(.app-select) {
  flex-wrap: wrap;
  row-gap: 10px;
}
.choice-row:has(.seg) .choice-info,
.choice-row:has(.n-radio-group) .choice-info,
.choice-row:has(.app-select) .choice-info {
  flex: 1 1 100%;
}
.choice-row:has(.seg) .seg,
.choice-row:has(.n-radio-group) .n-radio-group,
.choice-row:has(.app-select) .app-select {
  flex: 1 1 100%;
  min-width: 0;
}
.choice-label {
  color: var(--text-1);
  font-weight: 500;
}
.choice-hint {
  font-size: 12px;
  color: var(--text-3);
  margin: 0;
  line-height: 1.5;
}
.seg {
  position: relative;
  display: flex;
  /* 不被同行说明文字挤压，否则「横屏/跟随系统」这类两字标签会被竖着折行 */
  flex-shrink: 0;
  /* 但也不能超出所在行：选项多时（挖孔/刘海 3 项）要横滑，而不是把最后一个
     选项顶到屏幕外（实测 401px 塞进 315px，右侧按钮被裁掉一半）。 */
  max-width: 100%;
  overflow-x: auto;
  scrollbar-width: none;
  background: rgba(255, 255, 255, 0.05);
  border-radius: 9px;
  padding: 3px;
}
.seg .indicator {
  position: absolute;
  top: 3px;
  bottom: 3px;
  border-radius: 7px;
  background: var(--accent-soft);
  pointer-events: none;
}
.seg button {
  border: none;
  background: transparent;
  color: var(--text-3);
  /* 手机：6px 上下内边距只有 29px 高，抬到 38px 与其它控件的触控标准一致 */
  min-height: 38px;
  padding: 0 15px;
  border-radius: 7px;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  font-family: inherit;
  white-space: nowrap;
}
.seg button.active {
  color: var(--accent);
}
.theme-color-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.color-swatch {
  /* 手机：色板是常点的目标，22px 手指点不准 —— 直接放大到 34px。
     （不用「透明 padding 撑点击区」那套：那样选中圆环会跟着变成 40px，观感不对。） */
  width: 34px;
  height: 34px;
  border-radius: 50%;
  border: 2px solid transparent;
  background: transparent;
  cursor: pointer;
  padding: 0;
  position: relative;
  transition: transform 0.12s ease, border-color 0.12s ease;
}
.color-swatch:hover {
  transform: scale(1.12);
}
.color-swatch.active {
  border-color: var(--text-1);
  box-shadow: 0 0 0 2px var(--accent-soft);
}
.color-custom {
  width: 28px;
  height: 28px;
  padding: 3px;
  border: none;
  border-radius: 50%;
  cursor: pointer;
  /* 彩虹环 = 「自定义」的通用语言，跟 9 个纯色预设区分开 */
  background: conic-gradient(from 210deg, #ff5f6d, #ffc371, #7ad08a, #4ecdc4, #5aa2f0, #c78aff, #ff5f6d);
  -webkit-tap-highlight-color: transparent;
}
.color-custom-ring {
  width: 100%;
  height: 100%;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-2);
}
.color-custom-ring svg {
  width: 12px;
  height: 12px;
}
.about-license-card {
  grid-column: 1 / -1;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.about-license-card h3 {
  margin: 0;
  font-size: 15px;
  font-weight: 700;
  color: var(--text-1);
}
.license-text {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--text-3);
}
.license-accent {
  color: var(--accent);
  font-weight: 600;
}
/* 许可与版权声明：开源项目清单（与桌面端同一种排版） */
.about-deps-card {
  grid-column: 1 / -1;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.about-deps-card h3 {
  margin: 0;
  font-size: 15px;
  font-weight: 700;
  color: var(--text-1);
}
.deps-groups {
  display: grid;
  /* minmax(0,1fr)：条目里有 nowrap 的版本号/按钮，`1fr` 的最小尺寸是内容
     min-content，窄屏上会把这一列顶宽、右侧出血（同 .storage-grid 的坑）。 */
  grid-template-columns: minmax(0, 1fr);
  gap: 16px;
  margin-top: 4px;
}
.deps-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.deps-group-title {
  font-size: 11px;
  font-weight: 600;
  color: var(--text-3);
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin-bottom: 2px;
}
.about-dep-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 7px 10px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--panel);
  -webkit-backdrop-filter: blur(var(--glass-blur, 8px));
  backdrop-filter: blur(var(--glass-blur, 8px));
  transition: border-color 0.15s;
  flex-wrap: wrap;
}
.about-dep-row:hover {
  border-color: var(--accent);
}
.dep-info {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  flex: 1;
}
.about-dep-row .dep-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--text-1);
  display: flex;
  align-items: baseline;
  gap: 5px;
  white-space: nowrap;
}
.dep-ver {
  font-size: 11px;
  color: var(--text-3);
  font-weight: 400;
}
.dep-license {
  font-size: 10px;
  color: var(--accent);
  background: var(--accent-soft);
  padding: 2px 6px;
  border-radius: 5px;
  font-weight: 500;
  white-space: nowrap;
}
.dep-links {
  display: flex;
  gap: 6px;
  flex-shrink: 0;
}
.dep-link {
  font-size: 11px;
  font-family: inherit;
  /* 手机：这两个字的小胶囊原来只有 23px 高，手指点不准 → 抬到 30px */
  min-height: 30px;
  padding: 0 10px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: transparent;
  color: var(--text-3);
  cursor: pointer;
  transition: all 0.15s;
  white-space: nowrap;
}
.dep-link {
  display: inline-flex;
  align-items: center;
}
.dep-link:hover {
  border-color: var(--accent);
  color: var(--text-1);
}
.about-grid {
  /* 更新卡与开发者卡并排同一行；容器真的放不下两列时才退成一列，
     避免硬撑两列把卡片内容挤到互相压。写法与 .grid 一致（min() 兜住窄容器）。 */
  grid-template-columns: repeat(auto-fit, minmax(min(240px, 100%), 1fr));
}
.about-card {
  display: flex;
  flex-direction: column;
}
/* 顶部展示卡：画布与卡片保持留白，桌布做斜向动画 + 曲奇咬口 */
.about-showcase {
  padding: 16px;
}
.about-showcase .about-show-wrap {
  height: 200px;
  border-radius: 10px;
  overflow: hidden;
  border: 1px solid var(--border);
}
.about-hero-title {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  margin-top: 14px;
}
.about-hero-name {
  font-size: 20px;
}
.about-hero-slogan {
  text-align: center;
  margin: 6px 0 2px;
  font-size: 13px;
  color: var(--text-2);
  letter-spacing: 0.2px;
}
.about-name {
  font-size: 18px;
  font-weight: 700;
  color: var(--text-1);
}
.about-ver {
  font-size: 13px;
  font-weight: 600;
  color: var(--accent);
  background: var(--accent-soft);
  padding: 2px 8px;
  border-radius: 6px;
  cursor: pointer;
  user-select: none;
  -webkit-user-select: none;
  touch-action: none; /* 安卓 WebView：长按手势别被页面滚动掐断 */
}
/* 长按积蓄中：脉冲提示「别松手」 */
.about-ver.ver-arming {
  animation: ver-pulse 0.6s ease-in-out infinite;
}
@keyframes ver-pulse {
  0%,
  100% {
    transform: scale(1);
    filter: brightness(1);
  }
  50% {
    transform: scale(1.12);
    filter: brightness(1.35);
  }
}

/* ---- 版本彩蛋全屏层 ---- */
.ver-egg {
  position: fixed;
  inset: 0;
  z-index: 2000;
  background: rgba(4, 6, 10, 0.94);
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  cursor: pointer;
}
.ver-sparks {
  position: absolute;
  inset: 0;
}
.spark {
  position: absolute;
  image-rendering: pixelated;
  animation-name: spark-burst;
  animation-timing-function: cubic-bezier(0.2, 0.7, 0.3, 1);
  animation-iteration-count: infinite;
  opacity: 0;
}
@keyframes spark-burst {
  0% {
    opacity: 0;
    transform: scale(0.4) rotate(0deg);
  }
  12% {
    opacity: 1;
  }
  100% {
    opacity: 0;
    transform: scale(1.7) rotate(140deg) translateY(-36px);
  }
}
/* 名单：逐行浮入（不再用滚动+遮罩 —— WebView 里遮罩不生效，文字会硬切出现）。
 * 所有行共享同一 8s 周期，靠 animation-delay 依次出现，结尾一起淡出、循环。 */
.ver-credits {
  position: relative;
  width: min(560px, 84vw);
  text-align: center;
}
.ver-credits-inner {
  display: flex;
  flex-direction: column;
  align-items: center;
}
.vc-logo {
  width: 64px;
  height: 64px;
  margin-bottom: 16px;
  filter: drop-shadow(0 4px 12px rgba(0, 0, 0, 0.55));
  animation: vc-line 8s ease infinite;
}
.vc-title {
  font-size: 30px;
  font-weight: 800;
  color: #fff;
  letter-spacing: 1px;
  margin-bottom: 18px;
}
.vc-line {
  font-size: 15px;
  color: #cfd6e4;
  margin: 10px 0;
}
/* 依次浮入：logo → 标题 → 三行文字，间隔 0.55s */
.vc-logo,
.vc-title,
.vc-line {
  opacity: 0;
  animation-name: vc-line;
  animation-duration: 8s;
  animation-timing-function: ease;
  animation-iteration-count: infinite;
}
.vc-title {
  animation-delay: 0.55s;
}
.vc-title + .vc-line {
  animation-delay: 1.1s;
}
.vc-title + .vc-line + .vc-line {
  animation-delay: 1.65s;
}
.vc-title + .vc-line + .vc-line + .vc-line {
  animation-delay: 2.2s;
}
@keyframes vc-line {
  0% {
    opacity: 0;
    transform: translateY(18px);
  }
  9% {
    opacity: 1;
    transform: translateY(0);
  }
  80% {
    opacity: 1;
    transform: translateY(0);
  }
  90%,
  100% {
    opacity: 0;
    transform: translateY(-12px);
  }
}
.vc-small {
  font-size: 13px;
  color: #8a93a6;
}
.ver-hint {
  position: absolute;
  bottom: 26px;
  left: 0;
  right: 0;
  text-align: center;
  font-size: 12px;
  color: #6d7688;
}
/* 进出场：淡入 + 轻微放大 */
.veregg-enter-active,
.veregg-leave-active {
  transition: opacity 0.3s ease;
}
.veregg-enter-from,
.veregg-leave-to {
  opacity: 0;
}
.dev-role {
  font-size: 12px;
  color: var(--text-3);
  line-height: 1.3;
}
/* 开发者区块：一人一行，不套卡片 */
.about-devs-title {
  font-size: 12px;
  font-weight: 700;
  color: var(--text-3);
  margin: 0 0 10px;
  letter-spacing: 0.3px;
}
.dev-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 18px;
}
.dev-line {
  display: flex;
  align-items: center;
  gap: 12px;
}
.dev-meta {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.dev-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.dev-avatar {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  object-fit: cover;
  border: 2px solid var(--accent-35);
  flex-shrink: 0;
}
.dev-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-1);
}
.dev-github-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  padding: 0;
  border: 1px solid var(--border);
  border-radius: 7px;
  background: transparent;
  color: var(--text-3);
  font-size: 13px;
  cursor: pointer;
  transition: color 0.14s, border-color 0.14s, background 0.14s;
}
.dev-github-btn:hover {
  color: var(--accent);
  border-color: var(--accent-35);
  background: var(--accent-soft);
}
.about-links-row {
    grid-column: 1 / -1;
    display: grid;
    /* 手机：一行两个（3 个会让「GitHub 仓库」这类长文字换行，很难看）。
       minmax(0,1fr)：这一行的链接名是长文字，`1fr` 会被内容顶宽 → 右侧出血。 */
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 10px;
    }
    .about-links-row .about-link {
    justify-content: flex-start;
    padding: 14px 14px;
    min-height: 52px;
    font-size: 14px;
    /* 文字绝不换行：放不下就省略号 */
    white-space: nowrap;
    }
    .about-links-row .link-left {
    overflow: hidden;
    text-overflow: ellipsis;
    }
.about-links-row .about-link {
  justify-content: flex-start;
  padding: 18px 16px;
  min-height: 56px;
  font-size: 14px;
}
.about-link {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: 9px;
  /* 与 .card.glass 一致的磨砂背景，避免和卡片质感不一致 */
  background: var(--panel);
  -webkit-backdrop-filter: blur(var(--glass-blur, 8px));
  backdrop-filter: blur(var(--glass-blur, 8px));
  color: var(--text-2);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  font-family: inherit;
  margin-bottom: 8px;
  transition: all 0.15s;
}
.about-link:hover {
  border-color: var(--accent);
  background: var(--accent-soft);
  color: var(--text-1);
}
.link-left {
  display: flex;
  align-items: center;
  gap: 8px;
}
.link-left :deep(svg) {
  font-size: 15px;
  color: var(--accent);
  flex-shrink: 0;
}
.link-arrow {
  color: var(--text-3);
  font-size: 14px;
  transition: transform 0.15s;
}
.about-link:hover .link-arrow {
  transform: translateX(3px);
  color: var(--accent);
}
.appearance-divider {
  height: 1px;
  background: var(--border);
  margin: 4px 0 14px;
}
.bg-preview {
  margin-bottom: 12px;
  border-radius: 10px;
  overflow: hidden;
  border: 1px solid var(--border);
}
.bg-preview img {
  display: block;
  width: 100%;
  height: 92px;
  object-fit: cover;
}
.bg-actions {
  display: flex;
  gap: 8px;
}
.tune-block {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 14px;
}
.tune-row {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 13px;
  color: var(--text-2);
}
.tune-row label {
  flex-shrink: 0;
  width: 96px;
}
.tune-row .range {
  flex: 1;
}
.tune-val {
  flex-shrink: 0;
  width: 46px;
  text-align: right;
  font-size: 12px;
  color: var(--text-3);
  font-variant-numeric: tabular-nums;
}
.mirror-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.mirror-item,
.mirror-custom {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.03);
  color: var(--text-2);
  cursor: pointer;
  font-family: inherit;
  font-size: 13px;
  text-align: left;
  transition: border-color 0.15s, background 0.15s;
}
.mirror-item:hover,
.mirror-custom:hover {
  border-color: var(--accent-05);
}
.mirror-item.active,
.mirror-custom.active {
  border-color: var(--accent);
  background: var(--accent-soft);
}
.mirror-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.mirror-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-1);
}
.mirror-base {
  font-size: 11px;
  color: var(--text-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mirror-side {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}
.mirror-ms {
  font-size: 12px;
  color: var(--accent);
  font-variant-numeric: tabular-nums;
}
.mirror-ms.bad {
  color: #e5534b;
}
.mirror-btn {
  font-size: 12px;
  color: var(--text-2);
  /* 手机：原来 27px 高，抬到 34px 与其它小按钮一致 */
  min-height: 34px;
  padding: 0 12px;
  border-radius: 7px;
  border: 1px solid var(--border);
  flex-shrink: 0;
  transition: color 0.15s, border-color 0.15s;
}
.mirror-btn:hover {
  color: var(--accent);
  border-color: var(--accent);
}
.mirror-btn.disabled {
  opacity: 0.5;
  pointer-events: none;
}
/* 下载代理「测试连接」：跟随主题色 */
.proxy-test-btn {
  color: var(--accent);
  border-color: var(--accent-35);
  background: var(--accent-soft);
  font-weight: 600;
  transition: color 0.15s, border-color 0.15s, background 0.15s;
}
.hint-warn {
  color: #e5534b;
}
.proxy-test-btn:hover:not(.disabled) {
  background: var(--accent);
  border-color: var(--accent);
  color: #1a1208;
}
.proxy-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.proxy-row .seg {
  flex: 1;
  min-width: 0;
}
.proxy-row + .text-input {
  margin-top: 8px;
}
.mirror-custom {
  flex-wrap: wrap;
}
.mirror-custom-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-1);
  cursor: pointer;
  flex-shrink: 0;
}
.mirror-custom-head input {
  accent-color: var(--accent);
  margin: 0;
}
.mirror-custom .text-input {
  flex: 1;
  min-width: 150px;
}

/* 设置页是「左导航 + 右内容」双栏（与实例页的竖向导航栏同一套思路）。
   注意不能改成纵向 flex：那样 268px 高的导航会把内容区挤成 0。 */
.settings-view {
  min-height: 0;
  /* 根样式里是 align-items: flex-start，子项按内容高度撑开，
     结果 .settings-body 永远拿不到可视高度、滚动条不出现。必须拉伸。 */
  align-items: stretch;
}
.settings-body {
  min-height: 0;
  overflow-y: auto;
}

/* ── 设置页左侧导航在矮视口下会被裁 ──────────────────────────────
   7 个导航项（常规/外观/下载/内容服务/游戏内/存储/关于）+ 容器内边距原本约 268px，
   而 OnePlus Ace 5 的内容区只有 240px → 末尾的「存储 / 关于」被裁掉、点不到。
   压紧项高并允许滚动。 */
@media (max-width: 1100px), (pointer: coarse) {
  /* 导航项高度/字号按**视口高度**伸缩：360px 高的机器上 7 项刚好放得下，
     高屏则保持原来的手感；再给 overflow 兜底（项数将来变多也不会被裁）。 */
  .settings-nav {
    padding: clamp(6px, calc(1.8vh / var(--ui-scale, 1)), 16px) clamp(6px, 1.6vw, 14px);
    overflow-y: auto;
  }
  .settings-nav .nav-item {
    padding: clamp(4px, calc(1.7vh / var(--ui-scale, 1)), 9px) 10px;
    font-size: clamp(11.5px, calc(3.4vh / var(--ui-scale, 1)), 13px);
  }
}
/* ── 窄屏（典型是手机竖屏）：左侧导航改成横向 tab 条 ────────────────────
   设置页提供「屏幕方向 → 竖屏」，所以竖屏布局必须可用。
   竖屏 384px 下实测：导航占 112px（`--rail-w` 的下限）、内容只剩 230px，
   双栏在这个宽度下没有意义。这里改成纵向：导航变成一条可横向滚的 tab 条，
   内容独占整宽。

   用 `max-width` 而不是 `orientation: portrait`：平板竖屏（~800px）用双栏完全没问题，
   按方向切会把那里也改坏。 */
@media (max-width: 600px) {
  .settings-view {
    flex-direction: column;
    gap: 10px;
  }
  .settings-nav {
    width: 100%;
    /* 上面 position: sticky 在纵向布局里没有意义，还可能盖住内容 */
    position: static;
    padding: 6px 8px;
    /* 覆盖 (pointer: coarse) 那条里的 overflow-y: auto —— 现在是横向条 */
    overflow-y: visible;
  }
  .settings-nav .nav-list {
    flex-direction: row;
    gap: 4px;
    overflow-x: auto;
    overscroll-behavior-x: contain;
    scrollbar-width: none;
  }
  /* 横向滚动条在手机上只是视觉噪音（内容可滑） */
  .settings-nav .nav-list::-webkit-scrollbar {
    display: none;
  }
  .settings-nav .nav-item {
    flex-shrink: 0;
    white-space: nowrap;
  }
}

/* 界面缩放滑块：固定一个舒服的宽度，别被 flex 挤成一条线 */
.scale-ctl {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 4px;
  flex-shrink: 0;
  width: clamp(120px, 26vw, 200px);
}

/* ---- 插件页 ---- */
.plugin-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
}
.plugin-head-actions {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.plugin-empty {
  padding: 14px 0 4px;
  font-size: 13px;
  color: var(--text-3);
}
.plugin-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 0;
  border-top: 1px solid var(--border);
}
.plugin-row-main {
  min-width: 0;
  flex: 1;
}
.plugin-title {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.plugin-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-1);
}
.plugin-badge {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 5px;
  color: var(--text-2);
  background: var(--border);
}
.plugin-badge.update {
  color: var(--accent);
  background: var(--accent-soft);
}
.plugin-badge.warn {
  color: #ffb27a;
  background: rgba(255, 178, 122, 0.15);
}
.plugin-summary {
  margin-top: 4px;
  font-size: 12px;
  color: var(--text-2);
  line-height: 1.5;
}
.plugin-meta {
  margin-top: 4px;
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  font-size: 11px;
  color: var(--text-3);
}
.plugin-actions {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  justify-content: flex-end;
  flex-shrink: 0;
}
.plugin-progress {
  margin-top: 8px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.plugin-bar {
  flex: 1;
  height: 4px;
  border-radius: 2px;
  overflow: hidden;
  background: var(--border);
}
.plugin-fill {
  height: 100%;
  width: 0;
  border-radius: 2px;
  background: var(--accent);
  transition: width 0.2s ease;
}
/* 校验/解压阶段没有字节总数：用「走动的条纹」表示仍在进行，别显示假的 0% */
.plugin-fill.indet {
  animation: plugin-indet 1.1s ease-in-out infinite;
}
@keyframes plugin-indet {
  0%,
  100% {
    opacity: 0.35;
  }
  50% {
    opacity: 1;
  }
}
.plugin-progress-text {
  font-size: 11px;
  color: var(--text-2);
  flex-shrink: 0;
}
.plugin-source-actions {
  margin-top: 10px;
  display: flex;
  justify-content: flex-end;
}
/* 竖屏：操作按钮换到下一行，别把文字挤成一条 */
@media (max-width: 640px) {
  .plugin-row {
    flex-direction: column;
  }
  .plugin-actions {
    justify-content: flex-start;
  }
}

/* ══ 手机壳（一级分组列表 / 二级分组页）══════════════════════════════
   页面从「左导航 + 右卡片」的桌面双栏改成单栏两段式：
   没选分组时是整屏分组列表，选了分组就换成该分组的卡片 + 顶部返回条。
   下面这些规则只负责壳与窄屏下的排布，卡片内部沿用原有样式。 */
.settings-view {
  flex-direction: column;
  gap: 10px;
  align-items: stretch;
  height: 100%;
  min-height: 0;
}
/* 一级：分组列表（Vant 的 cell-group，inset 自带左右留白与圆角） */
.sv-list {
  padding-top: 4px;
}
.sv-list .nav-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  margin-right: 10px;
  color: var(--text-3);
}
.sv-list .nav-icon :deep(svg) {
  width: 20px;
  height: 20px;
}
/* 「关于」有更新时的小红点（原来在左侧导航项上的那个） */
.nav-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #e5534b;
  display: inline-block;
}
/* 二级：返回条。单栏下这是唯一的返回入口，触控高度给足 */
.sv-back {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  min-height: 44px;
  /* 不再自带左右内边距：父级 .settings-body 已给 16px 装订线，
     返回条自己再加就会比下面的卡片多缩进 6px、左沿对不齐 */
  padding: 0;
  margin-bottom: 10px;
  border: none;
  background: transparent;
  color: var(--text-2);
  font-family: inherit;
  font-size: 15px;
  font-weight: 600;
  text-align: left;
}
.sv-back :deep(svg) {
  width: 18px;
  height: 18px;
}
.settings-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}
/* 窄屏下「标签+说明 / 控件」从左右排改成上下排：控件独占一行才好点 */
@container page (max-width: 640px) {
  .choice-row {
    flex-direction: column;
    align-items: stretch;
    gap: 8px;
  }
  .choice-row .choice-info {
    width: 100%;
  }
  .scale-ctl {
    width: 100%;
  }
  /* 设置页里所有「标签/数值 + 滑杆」的行（内存、磨砂强度、下载并发、游戏内各滑杆…）
     在窄屏一律上下排：滑杆独占整行才好拖（原来挤成 ~100px，手指一滑就过头）。
     `row-label` 那种把滑杆放进 <label> 里的写法也一并覆盖。 */
  .tune-row,
  .mem-row,
  .row-label {
    flex-direction: column;
    align-items: stretch;
    gap: 6px;
  }
  .tune-row .app-slider-wrap,
  .mem-row .app-slider-wrap,
  .row-label .app-slider-wrap,
  .tune-block .app-slider-wrap {
    width: 100%;
  }
  .tune-val,
  .mem-val {
    text-align: right;
  }
}
/* 自定主题色的原生取色器：铺满那个圆环，点哪都能唤起系统调色盘 */
.mirror-dot {
  width: 16px;
  height: 16px;
  border-radius: 50%;
  border: 2px solid var(--text-3);
  flex-shrink: 0;
}
.mirror-dot.on {
  border-color: var(--accent);
  background: var(--accent);
  box-shadow: inset 0 0 0 3px var(--bg-1, #111);
}
</style>

