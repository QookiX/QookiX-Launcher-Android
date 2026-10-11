<script setup lang="ts">
/**
 * 「更多」页（手机底栏的第 5 格）。
 *
 * 底栏 8 个 tab 在竖屏上每个只剩 ~51px，标签挤压、还会被系统手势条压住；
 * 手机底栏的经验值是 5 个。所以高频 4 个留在底栏，其余入口收到这里 ——
 * **入口一个都没少**，只是不再挤在一起。
 *
 * 这里刻意做成「真实页面」而不是底栏上弹一个面板：面板要处理
 * 选中态回退、事件穿透、动画与返回键，而一个普通页面天生有这些语义
 * （返回键能回、深链能进、选中态由路由决定）。
 */
import { t as $t } from "../i18n";
import { computed } from "vue";
import { useRouter } from "vue-router";
import { useSettingsStore } from "../stores/settings";
import { IconNewspaper, IconSettings, IconUser } from "../components/icons";

const router = useRouter();
const settings = useSettingsStore();

/** 入口清单：与底栏「更多」的含义一致（去掉底栏已有的首页/实例/内容/下载） */
const entries = computed(() => {
  const list = [
    // 「多人」已经提到侧栏；MohuaFrp 与联机房间都从多人页进 —— 这里不再重复挂入口
    { name: "skins", label: $t("nav.skins"), icon: IconUser, to: "/skins" },
  ];
  if (settings.settings?.show_news ?? true) {
    list.push({ name: "news", label: $t("nav.news"), icon: IconNewspaper, to: "/news" });
  }
  list.push({ name: "settings", label: $t("nav.settings"), icon: IconSettings, to: "/settings" });
  return list;
});
</script>

<template>
  <div class="more-page">
    <div class="more-group glass">
      <button v-for="e in entries" :key="e.name" class="more-row" @click="router.push(e.to)">
        <span class="more-ic"><component :is="e.icon" /></span>
        <span class="more-label">{{ e.label }}</span>
        <span class="more-chev">›</span>
      </button>
    </div>
  </div>
</template>

<style scoped>
.more-page {
  display: flex;
  flex-direction: column;
  gap: 14px;
  /* 与设置页等其它页一致的左右留白：卡片不再顶着屏幕边 */
  padding: 4px 16px 16px;
  height: 100%;
  min-height: 0;
  overflow-y: auto;
}
.more-group {
  display: flex;
  flex-direction: column;
  border-radius: 14px;
  overflow: hidden;
}
/* 分组列表：一行一项、整行可点、右侧箭头 —— iOS/安卓设置页的原生形态 */
.more-row {
  display: flex;
  align-items: center;
  gap: 14px;
  /* 手机触控底线 */
  min-height: 56px;
  padding: 0 16px;
  border: none;
  border-bottom: 1px solid var(--border);
  background: transparent;
  color: var(--text-1);
  font-family: inherit;
  font-size: 15px;
  text-align: left;
}
.more-row:last-child {
  border-bottom: none;
}
.more-row:active {
  background: var(--panel);
}
.more-ic {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  flex-shrink: 0;
  color: var(--text-3);
}
.more-ic :deep(svg) {
  width: 20px;
  height: 20px;
}
.more-label {
  flex: 1;
  min-width: 0;
}
.more-chev {
  color: var(--text-3);
  font-size: 20px;
  line-height: 1;
}

/* ── 手机横屏（矮窗口）：入口排两列 ── */
@media (orientation: landscape) and (max-height: 560px) {
  .more-group {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
