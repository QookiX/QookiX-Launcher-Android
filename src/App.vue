<script setup lang="ts">
import { t as $t } from "./i18n";
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { convertFileSrc } from "@tauri-apps/api/core";
import { api } from "./api";
import { useSettingsStore } from "./stores/settings";
import { useAccountsStore } from "./stores/accounts";
import { useInstancesStore } from "./stores/instances";
import { useTasksStore } from "./stores/tasks";
import { usePinsStore } from "./stores/pins";
import { DEFAULT_ACCENT, darken, lighten, rgba, ACCENT_ALPHAS } from "./theme";
import { Tabbar as VanTabbar, TabbarItem as VanTabbarItem } from "vant";
import AccountChip from "./components/AccountChip.vue";
import LaunchProgress from "./components/LaunchProgress.vue";
import CrashDialog from "./components/CrashDialog.vue";
import RendererHintDialog from "./components/RendererHintDialog.vue";
import FirstRunSetup from "./components/FirstRunSetup.vue";
import { IconHome, IconGrid, IconCompass, IconDownload, IconMoreVertical, IconUsers } from "./components/icons";

const route = useRoute();
const router = useRouter();
const settings = useSettingsStore();
const accounts = useAccountsStore();
const instances = useInstancesStore();
const tasks = useTasksStore();
const pins = usePinsStore();

const isDark = computed(() => settings.settings?.theme !== "light");
const accent = computed(() => settings.settings?.theme_color || DEFAULT_ACCENT);

watch(isDark, () => document.documentElement.classList.toggle("light", !isDark.value), { immediate: true });

watch(accent, (hex) => {
  const root = document.documentElement.style;
  root.setProperty("--accent", hex);
  root.setProperty("--accent-deep", darken(hex));
  root.setProperty("--accent-hover", lighten(hex));
  root.setProperty("--accent-soft", rgba(hex, 0.14));
  for (const a of ACCENT_ALPHAS) {
    root.setProperty(`--accent-${String(Math.round(a * 100)).padStart(2, "0")}`, rgba(hex, a));
  }
}, { immediate: true });

// 界面缩放：整块 zoom（既有约定：视口单位要除 scale，百分比不用）
watch(() => settings.settings?.ui_scale, (v) => {
  const scale = Math.min(200, Math.max(60, v ?? 100)) / 100;
  document.documentElement.style.setProperty("--ui-scale", String(scale));
  const app = document.getElementById("app");
  if (app) app.style.zoom = scale === 1 ? "" : String(scale);
}, { immediate: true });

watch(() => settings.settings?.background_image, (v) => {
  const root = document.documentElement;
  root.classList.toggle("has-bg", !!v);
  if (v) root.style.setProperty("--bg-image", `url("${convertFileSrc(v)}")`);
  // 清空时必须把变量一起移除：`.app-bg::before` 一直在读 --bg-image，
  // 只去掉 has-bg 类的话旧图片照样画出来（「清除背景」看起来没反应）。
  else root.style.removeProperty("--bg-image");
}, { immediate: true });

watch(() => settings.settings?.orientation, (m) => { if (m) void api.setOrientation(m); }, { immediate: true });

/**
 * 底部导航（手机形态）。
 *
 * 原来是「最多 8 个 tab 平铺」——在 412px 宽的竖屏上每个只剩 ~51px：文字挤压换行，
 * 而且中间那个会被系统手势条压住。手机底栏的经验值是 **5 个**，
 * 所以把高频 4 个留在栏里，其余（多人 / 皮肤 / 新闻 / 设置）收进「更多」面板 ——
 * 入口一个都没少，只是不再挤在一起。
 */
const PRIMARY_TABS = computed(() => [
  { name: "home", label: $t("nav.home"), icon: IconHome, to: "/" },
  { name: "instances", label: $t("nav.instances"), icon: IconGrid, to: "/instances" },
  { name: "browse", label: $t("nav.browse"), icon: IconCompass, to: "/browse" },
  // 「多人」从「更多」提到栏里（用户要求：默认 6 格）。
  // 它是联机类功能的总入口：本地服务器 / 陶瓦联机 / MohuaFrp / 联机房间都在里面。
  { name: "multiplayer", label: $t("nav.multiplayer"), icon: IconUsers, to: "/multiplayer" },
]);

/** 「更多」页收纳的那些路由（用于判断「更多」这格何时点亮） */
// `/multiplayer` 已经提到栏里；`/frp`（MohuaFrp）与 `/frp-rooms`（联机房间）
// 统一从多人页进入，不再挂在「更多」下面。
const MORE_ROUTES = ["/more", "/skins", "/news", "/settings"];

/** 当前页对应的入口名（Vant Tabbar 的选中态来源） */
const activeTab = computed(() => {
  const p = route.path;
  if (p === "/") return "home";
  // 实例详情 / 创建实例都算「实例」这一格
  if (p.startsWith("/instance") || p === "/create") return "instances";
  if (p.startsWith("/downloads")) return "downloads";
  if (p.startsWith("/more")) return "more";
  // 「更多」里的二级页（多人 / 皮肤 / 新闻 / 设置，含 /settings/xxx 子标签）也点亮「更多」
  if (MORE_ROUTES.some((r) => p === r || p.startsWith(r + "/"))) return "more";
  return PRIMARY_TABS.value.find((t) => t.to !== "/" && p.startsWith(t.to))?.name ?? "";
});

/**
 * Tabbar 的双向绑定值（可写）。
 *
 * 不能直接把 `activeTab`（只读 computed）绑给 v-model：点任何一项 Vant 都会回写它，
 * 对只读 computed 写入会报错；而「实例」一格又对应多个路由，
 * 也需要一个本地值来承接。所以用本地 ref 跟路由同步。
 *
 * 注意 `more` 也要同步：「更多」现在是**真实路由页**（/more 及其二级页），
 * 用户停在多人/皮肤/设置时，「更多」格就应该亮着。
 * （这行 `if (v !== "more")` 的跳过逻辑是「更多=内联弹层」时代的遗物，已删——
 *  它会让底栏停在上一个 tab 上，比如停在皮肤页时高亮的却是「首页」。）
 */
const tabModel = ref("home");
watch(
  activeTab,
  (v) => {
    tabModel.value = v || "home";
  },
  { immediate: true }
);

/**
 * 点底栏 = 回到这一格的主页。
 *
 * Vant 的 `TabbarItem` 在**已选中**时内部会跳过跳转（`onClick` 里 `if (!active.value) setActive(...)`），
 * 而「实例」「更多」这两格各自对应多个路由（实例详情 / 皮肤 / 设置 / 新闻…）：
 * 进了二级页以后再点这一格完全没反应，只能按系统返回键。这里自己补一次 push。
 */
function onTabClick(to: string) {
  if (route.path !== to) void router.push(to);
}

const downloadCount = computed(() => tasks.activeCount);
const pageTitle = computed(() => (route.meta.title as string) || "");

onMounted(async () => {
  try { await settings.load(); } catch { /* 不阻塞启动 */ }
  await Promise.all([instances.load().catch(() => {}), accounts.load().catch(() => {})]);
  tasks.init();
  await pins.init().catch(() => {});
});

let unlisten: (() => void) | null = null;
onMounted(async () => {
  try {
    const { listen } = await import("@tauri-apps/api/event");
    const { notifyError } = await import("./composables/notify");
    unlisten = await listen<{ instanceId: string; error: string }>("share-import://game-install-failed", (ev) => {
      notifyError($t("app.notify-error", { p1: ev.payload.error }));
    });
  } catch { /* 忽略 */ }
});
onBeforeUnmount(() => { unlisten?.(); unlisten = null; });
</script>

<template>
  <!-- 顶层不再需要任何 UI 库的 provider：
       naive 的 message/dialog 需要 provider 上下文，现在换成了不依赖上下文的 Vant Toast
       （见 composables/message.ts），主题也早就由 CSS 变量 + vant.css 承担。
       少一层包裹 = 少一处「弹层挂在 provider 里导致 zoom 脱节」的风险。 -->
  <div class="shell app-bg">
    <!-- Vant 弹层挂载点 #van-layer 已预置在 index.html 的 #app 里
         （必须在 Vue 挂载前就存在，否则首帧组件里的 Teleport 会静默失败）。
         别把它挪回这个模板 —— 模板里渲染的节点要等整个应用挂载完才进文档，来不及。 -->
    <!-- 顶栏右侧固定是账号入口（头像+名称，点开账号面板）。
         页面级操作（新建实例 / 上传皮肤 / 切换实例）一律**放页面内容里** ——
         之前它们挂在这个位置，切页时按钮出现/消失会把账号块挤得左右跳，很难看。 -->
    <header class="top">
      <h1 class="title">{{ pageTitle }}</h1>
      <AccountChip />
    </header>

    <main class="page">
      <router-view v-slot="{ Component }">
        <Transition name="page" mode="out-in">
          <component :is="Component" />
        </Transition>
      </router-view>
    </main>

    <van-tabbar v-model="tabModel" :fixed="false" :safe-area-inset-bottom="true" class="tabs">
      <van-tabbar-item v-for="t in PRIMARY_TABS" :key="t.name" :name="t.name" :to="t.to" @click="onTabClick(t.to)">
        <template #icon><component :is="t.icon" class="tab-icon" /></template>
        {{ t.label }}
      </van-tabbar-item>
      <van-tabbar-item
        name="downloads"
        to="/downloads"
        :badge="downloadCount > 0 ? String(downloadCount) : ''"
        @click="onTabClick('/downloads')"
      >
        <template #icon><IconDownload class="tab-icon" /></template>
        {{ $t("nav.downloads") }}
      </van-tabbar-item>
      <!-- 「更多」：手机底栏只放 5 个，其余入口收到 /more 页（入口一个都没少）。
           做成真实路由页而不是底栏内联弹层 —— 底栏项天生就是「切换页面」，
           返回键/深链/选中态都由路由管，不用自己处理面板的开关与回退。 -->
      <van-tabbar-item name="more" to="/more" @click="onTabClick('/more')">
        <template #icon><IconMoreVertical class="tab-icon" /></template>
        {{ $t("nav.more") }}
      </van-tabbar-item>
    </van-tabbar>

    <LaunchProgress />
    <CrashDialog />
    <RendererHintDialog />
    <FirstRunSetup />
  </div>
</template>

<style scoped>
.shell {
  height: calc(100vh / var(--ui-scale, 1));
  display: flex;
  flex-direction: column;
  color: var(--text-1);
}
.top {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: calc(10px + env(safe-area-inset-top, 0px)) 16px 8px;
}
.title {
  flex: 1;
  min-width: 0;
  margin: 0;
  font-size: 18px;
  font-weight: 700;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.top-act {
  flex-shrink: 0;
  min-height: 36px;
  padding: 8px 14px;
  border-radius: 10px;
  border: 1px solid var(--border);
  background: var(--panel);
  color: var(--accent);
  font-family: inherit;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}
/* 页面区：唯一收口处，页面自己滚（容器查询的参照物） */
.page {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  container-type: inline-size;
  container-name: page;
}
.page > * {
  height: 100%;
  overflow-y: auto;
  -webkit-overflow-scrolling: touch;
}
.tab-icon {
  width: 20px;
  height: 20px;
}
.tabs {
  flex-shrink: 0;
  /* 手机：系统手势条（底部那条 pill）悬浮在应用之上，而本 WebView 读不到
     env(safe-area-inset-bottom)（实测只给 1px），所以给一点固定内边距把标签
     抬离手势区。之前是 10px，用户反馈「底栏上方空太多」→ 收到 2px：
     标签下沿距屏幕底约 11px，既不被手势条压住，也不显得空。 */
  padding-bottom: 2px;
}
.tabs :deep(.van-tabbar-item) {
  /* 底栏是最重要的触控目标，整格高度锁下限 */
  min-height: 52px;
}
.page-enter-active { transition: opacity .22s ease, transform .22s ease; }
.page-leave-active { transition: opacity .14s ease; }
.page-enter-from { opacity: 0; transform: translateY(8px); }
.page-leave-to { opacity: 0; }

/* ── 手机横屏（矮窗口）：底栏换成左侧导航栏 ───────────────────────────────
 *
 * 手机横过来之后视口约 923×411（Pixel_9 1080×2424 / 420dpi 实测）。411px 的高度里
 * 底栏 + 手势区要吃掉 ~60px，等于 **15% 的可用高度**被一个「换页」控件占着 ——
 * 而横屏最不缺的就是宽度。所以这一档把底栏改到左边：宽度全省给内容。
 *
 * 为什么判据是 `max-height` 而不是 `orientation`：平板横屏高度 600~800px，
 * 那套「宽而高」的窗口用底栏完全没问题，按方向切会把平板也一起改坏。
 *
 * 用 grid 重排而不是改模板：`.shell` 里本来就只有 顶栏 / 页面区 / 底栏 三块，
 * 给它们指定 grid-area 就够了，不用动 DOM 顺序（DOM 顺序还牵着焦点与读屏顺序）。
 */
@media (orientation: landscape) and (max-height: 560px) {
  .shell {
    display: grid;
    grid-template-columns: 84px minmax(0, 1fr);
    grid-template-rows: auto minmax(0, 1fr);
    grid-template-areas:
      "rail top"
      "rail page";
  }
  .top {
    grid-area: top;
    /* 横屏高度紧张：顶栏压到 44px 上下（竖屏那套 10px+8px 的留白在这里太贵） */
    padding: 6px 12px 4px;
  }
  .title {
    font-size: 16px;
  }
  .page {
    grid-area: page;
  }
  .tabs {
    grid-area: rail;
    /* Vant 的 `.van-tabbar` 自带 `height: 50px`，不覆盖它，导航栏就只有 50px 高
       （`align-self: stretch` 压不过固定高度）—— 实测左侧只占顶部一小块。 */
    height: 100%;
    flex-direction: column;
    align-self: stretch;
    padding-bottom: 0;
    border-right: 1px solid var(--border);
  }
  /* Vant 的底栏自带一条顶部细线，竖着放就成了一条怪线 */
  .tabs::after {
    display: none;
  }
  .tabs :deep(.van-tabbar-item) {
    min-height: 0;
    height: auto;
    flex: 1;
    justify-content: center;
    gap: 2px;
    font-size: 11px;
  }
  /* 栏宽只有 84px：「下载」这类两字标签一旦允许换行就会被拆成竖排字 */
  .tabs :deep(.van-tabbar-item__text) {
    white-space: nowrap;
  }
  .tab-icon {
    width: 22px;
    height: 22px;
  }
}
</style>
