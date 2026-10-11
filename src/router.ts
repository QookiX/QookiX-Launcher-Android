import { t as $t } from "./i18n";
import { createRouter, createWebHistory } from "vue-router";
import { useSettingsStore } from "./stores/settings";

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/", name: "home", component: () => import("./views/HomeView.vue"), meta: { title: $t("router.home"), icon: "home" } },
    { path: "/news", name: "news", component: () => import("./views/NewsView.vue"), meta: { title: $t("router.newspaper"), icon: "newspaper" } },
    { path: "/browse", name: "browse", component: () => import("./views/BrowseView.vue"), meta: { title: $t("router.compass"), icon: "compass" } },
    { path: "/downloads", name: "downloads", component: () => import("./views/DownloadsView.vue"), meta: { title: $t("router.download"), icon: "download" } },
    { path: "/instances", name: "instances", component: () => import("./views/InstancesView.vue"), meta: { title: $t("router.grid"), icon: "grid", action: { text: $t("router.plus"), icon: "plus", to: "/create" } } },
    { path: "/instance/:id", name: "instance", component: () => import("./views/InstanceDetailView.vue"), meta: { title: $t("router.shi-li-xiang-qing"), icon: "grid" } },
    { path: "/create", name: "create", component: () => import("./views/CreateInstanceView.vue"), meta: { title: $t("router.chuang-jian-shi-li"), icon: "plus" } },
    { path: "/multiplayer", name: "multiplayer", component: () => import("./views/MultiplayerView.vue"), meta: { title: $t("instance-saves.users"), icon: "users" } },
    { path: "/multiplayer/:id", name: "server-detail", component: () => import("./views/ServerDetailView.vue"), meta: { title: $t("router.users"), icon: "users" } },
    // `:tab?` 让设置子标签进路由历史 —— 否则安卓返回手势会跳过子标签
    // 直接退回上一个页面（用户实测：更多→设置→常规，返回却退到了「更多」）
    { path: "/settings/:tab?", name: "settings", component: () => import("./views/SettingsView.vue"), meta: { title: $t("router.settings"), icon: "settings" } },
    { path: "/skins", name: "skins", component: () => import("./views/SkinView.vue"), meta: { title: $t("router.user"), icon: "user" } },
    { path: "/frp", name: "frp", component: () => import("./views/FrpView.vue"), meta: { title: $t("nav.frp"), icon: "globe" } },
    // XTCP 联机房间：和隧道页分开是因为两种模式的体验不同（房间需要双方都跑 frpc）
    { path: "/frp-rooms", name: "frp-rooms", component: () => import("./views/FrpRoomsView.vue"), meta: { title: $t("nav.frp-rooms"), icon: "globe" } },
    // 手机底栏第 5 格：底栏放不下的入口都收在这里（见 views/MoreView.vue 的注释）
    { path: "/more", name: "more", component: () => import("./views/MoreView.vue"), meta: { title: $t("nav.more"), icon: "more" } },
  ],
});

// 关闭「新闻」后，直接访问 /news 会跳回首页（侧边栏入口本身也已隐藏）。
// 设置尚未加载时按「显示」处理，避免启动瞬间误跳转。
router.beforeEach((to) => {
  if (to.path !== "/news") return true;
  try {
    const s = useSettingsStore();
    if (s.settings && s.settings.show_news === false) return { path: "/", replace: true };
  } catch {
    // store 尚未初始化（Pinia 未激活）时放行
  }
  return true;
});

export default router;
