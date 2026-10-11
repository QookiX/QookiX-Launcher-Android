import { t as $t } from "../i18n";
import { defineStore } from "pinia";
import { api } from "../api";
import type { Instance, InstanceFileReport, InstanceGroup } from "../types";
import { autoRendererFor, effectiveRendererKey, rendererLabel } from "../utils/renderer";
import { fmtBytes } from "../utils/format";
// 命令式弹窗：这个 store 要在「删实例」之后问一句共享文件要不要清，
// 用 vant 的命令式 API 就不必给每个调用方都接一遍对话框（调用方零改动）。
import { showConfirmDialog, showToast } from "vant";

/** 启动前的渲染器确认（由全局弹窗消费），见 `rendererGuard`。 */
export interface RendererGuard {
  instanceId: string;
  mcVersion: string;
  /** 当前会用的渲染器键 */
  used: string;
  usedName: string;
  /** 版本推荐的渲染器键 */
  recommended: string;
  recommendedName: string;
  world?: string;
  server?: string;
}

/**
 * 本地「已经问过」标记。两套 key：
 *  - `warned`：启动后（日志里发现失败证据）已经问过；
 *  - `guardOk`：启动前确认弹窗里用户已经选过「仍用 x 启动」。
 * 分开记是因为两者的确认语义不同：启动前点「仍用」= 每次启动就这么跑；
 * 事后点「暂不切换」≠ 允许以后每次启动都再白屏一次。
 */
function rendererWarnKey(instanceId: string, renderer: string) {
  return `qk.rendererWarned.${instanceId}.${renderer}`;
}
function rendererGuardKey(instanceId: string, renderer: string) {
  return `qk.rendererGuardOk.${instanceId}.${renderer}`;
}
function readFlag(key: string): boolean {
  try {
    return localStorage.getItem(key) === "1";
  } catch {
    return false;
  }
}
function writeFlag(key: string): void {
  try {
    localStorage.setItem(key, "1");
  } catch {
    /* 隐私模式下写不了就算了，最多多问一次 */
  }
}

export function hasRendererWarned(instanceId: string, renderer: string): boolean {
  return readFlag(rendererWarnKey(instanceId, renderer));
}
export function markRendererWarned(instanceId: string, renderer: string): void {
  writeFlag(rendererWarnKey(instanceId, renderer));
}
/** 用户在启动前确认里选了「仍用该渲染器启动」，记下来别再问。 */
export function markRendererGuardOk(instanceId: string, renderer: string): void {
  writeFlag(rendererGuardKey(instanceId, renderer));
}

export const useInstancesStore = defineStore("instances", {
  state: () => ({
    instances: [] as Instance[],
    groups: [] as InstanceGroup[],
    loading: false,
    installingId: null as string | null,
    launchingId: null as string | null,
    installStage: "",
    installDone: 0,
    installTotal: 0,
    /** 最近一次成功拉取的时间戳，用于短 TTL 内跳过重复请求 */
    lastLoadedAt: 0,
    /** 等用户决定的「渲染器不推荐」确认（弹窗消费后置 null） */
    pendingRendererGuard: null as RendererGuard | null,
    /** 全局渲染器（Pojav 偏好，懒加载缓存） */
    globalRenderer: null as string | null,
  }),
  getters: {
    /** 分组名 -> 分组对象，便于 UI 快速取色 */
    groupMap(state): Record<string, InstanceGroup> {
      const map: Record<string, InstanceGroup> = {};
      for (const g of state.groups) map[g.id] = g;
      return map;
    },
    /** 未分组实例 */
    ungrouped(state): Instance[] {
      return state.instances.filter((i) => !i.group);
    },
    /** 取某个分组下的实例（保持 instances 的默认排序） */
    inGroup: (state) => (groupId: string) =>
      state.instances.filter((i) => i.group === groupId),
  },
  actions: {
    /** 后台静默刷新（不改动 loading），失败保留旧数据 */
    async refresh() {
      try {
        const [list, groups] = await Promise.all([api.listInstances(), api.listGroups()]);
        this.instances = list;
        this.groups = groups;
        this.lastLoadedAt = Date.now();
      } catch {
        /* 后台刷新失败保留旧数据 */
      }
    },
    /**
     * 拉取实例列表。
     * - 已有数据时采用 stale-while-revalidate：立即返回旧数据，后台静默刷新替换。
     * - `force=true` 时始终前台拉取并设 loading（用于写操作后强制刷新）。
     */
    async load(force = false) {
      const now = Date.now();
      // 3 秒内刚拉过，直接跳过，避免切换页面时重复请求
      if (!force && this.lastLoadedAt && now - this.lastLoadedAt < 3000) return;
      const hasData = this.instances.length > 0 || this.groups.length > 0;
      if (hasData && !force) {
        void this.refresh();
        return;
      }
      this.loading = true;
      try {
        const [list, groups] = await Promise.all([api.listInstances(), api.listGroups()]);
        this.instances = list;
        this.groups = groups;
        this.lastLoadedAt = Date.now();
      } finally {
        this.loading = false;
      }
    },
    get(id: string) {
      return this.instances.find((i) => i.id === id);
    },
    groupById(id: string | null | undefined): InstanceGroup | null {
      if (!id) return null;
      return this.groups.find((g) => g.id === id) ?? null;
    },
    // ---- 分组管理 ----
    async loadGroups() {
      this.groups = await api.listGroups();
    },
    async createGroup(name: string, color?: string | null) {
      const g = await api.createGroup(name, color ?? null);
      await this.loadGroups();
      return g;
    },
    async renameGroup(id: string, name: string, color?: string | null) {
      const g = await api.renameGroup(id, name, color ?? null);
      await this.loadGroups();
      return g;
    },
    async deleteGroup(id: string) {
      await api.deleteGroup(id);
      // 组内实例被后端移回未分组，重新拉取保持一致
      await this.load(true);
    },
    async reorderGroups(ids: string[]) {
      this.groups = await api.reorderGroups(ids);
    },
    /** 移动实例到分组（groupId 为空表示移出分组） */
    async moveToGroup(instanceId: string, groupId: string | null) {
      const inst = await api.updateInstance({ id: instanceId, group: groupId ?? "" });
      const idx = this.instances.findIndex((i) => i.id === inst.id);
      if (idx >= 0) this.instances[idx] = inst;
      return inst;
    },
    async create(name: string, mc: string, loader: string, loaderVersion: string | null) {
      const inst = await api.createInstance(name, mc, loader, loaderVersion);
      await this.load(true);
      return inst;
    },
    async patch(patch: Record<string, unknown>) {
      const inst = await api.updateInstance(patch);
      const idx = this.instances.findIndex((i) => i.id === inst.id);
      if (idx >= 0) this.instances[idx] = inst;
      return inst;
    },
    /**
     * 删除实例。
     *
     * 删完顺带问一句：**这套共享文件要不要一起清掉**。
     * 游戏本体（`versions/` `libraries/` `assets/` `runtimes/`）是全局共享的，
     * **删实例不会回收它** —— 几百 MB 会一直留着，而用户在界面上完全看不出来
     * （真机反馈：明明只建过一个实例，空间却被占着，还以为是启动器下错了）。
     * 只有「没有别的实例在用同一版本 / 同一 Java 大版本」时才打扰用户。
     */
    async remove(id: string) {
      await api.deleteInstance(id);
      try {
        const plan = await api.planInstanceCleanup(id);
        if (plan.totalBytes > 0) {
          await showConfirmDialog({
            title: $t("instances.cleanup-title"),
            message: $t("instances.cleanup-msg", {
              p1: fmtBytes(plan.totalBytes),
              p2: plan.mcVersion,
            }),
            confirmButtonText: $t("instances.cleanup-confirm"),
            cancelButtonText: $t("common.cancel"),
          });
          const r = await api.cleanupInstanceShared(id);
          showToast($t("instances.cleanup-done", { p1: fmtBytes(r.freedBytes) }));
        }
      } catch {
        // 用户点了取消，或统计/清理失败：都不影响"实例已删除"这个事实，静默跳过
      }
      await this.load(true);
    },
    async installGame(id: string) {
      this.installingId = id;
      this.installStage = $t("stores.instances.actions");
      this.installDone = 0;
      this.installTotal = 0;
      try {
        await api.installGame(id);
        await this.load(true);
      } finally {
        this.installingId = null;
      }
    },
    /**
     * 取消安装的任务请走「下载中心」卡片上的取消按钮
     * （`DownloadsView` → `api.cancelInstall(taskId)`）。
     *
     * 这里原来有个 `cancelInstall()`：它调用的是被列为「未实现」的命令，
     * 唯一效果是把 `installingId` 清空 —— 一个没人读的状态字段，纯 UI 假动作，
     * 后端那次安装其实照跑不误。已删除。
     */
    /**
     * 启动前检查游戏文件完整性，缺了**自动补全**再启动。
     *
     * 背景：创建实例时的后台安装一旦中断（断网 / 进程被杀 / 磁盘满），实例会停在
     * 「只有 instance.json」的状态 —— 这时点启动只会得到一句
     * `INSTANCE_NOT_INSTALLED`，用户根本不知道该点哪里补。现在默认自动补。
     *
     * 实例设置里可以关掉（`check_files_on_launch === false`），缺省视为开启。
     * 遇到补不了的项（例如加载器版本号为空，得换游戏版本）抛出后端给的 `advice`。
     */
    async ensureFilesBeforeLaunch(id: string) {
      let inst = this.get(id);
      // 冷启动时列表可能还没加载（从实例详情页点启动 / 用 qookix:// 协议唤起）
      if (!inst) {
        await this.load(true);
        inst = this.get(id);
      }
      // 读不到实例就不拦，交给后面的启动命令去报错，别在这里吞掉真正的错误
      if (!inst) return;
      // undefined / null（旧实例）都算开启，只有显式 false 才跳过
      if (inst.check_files_on_launch === false) return;

      let report: InstanceFileReport;
      try {
        report = await api.checkInstanceFiles(id);
      } catch {
        // 检查本身失败不该挡住启动（比如后端读不了磁盘），让它按原来的路走
        return;
      }
      if (report.can_launch) return;

      // 一项都补不了就别白等：直接把后端那句建议抛给用户
      if (!report.missing.some((m) => m.fixable)) throw new Error(report.advice);

      this.installingId = id;
      this.installStage = $t("stores.instances.fixing-files");
      this.installDone = 0;
      this.installTotal = 0;
      try {
        // 后端复用 install_game：逐文件校验、缺的才下，重复调用安全。
        // 下载进度走「下载中心」的任务卡片，不在这里另做一套。
        await api.repairInstanceFiles(id);
      } finally {
        this.installingId = null;
        this.installStage = "";
      }
      // 后补完会变更 installed，后台刷新一次即可；不用前台 loading，避免列表闪
      void this.refresh();
    },

    /**
     * 把后端抛出的「错误码」翻成人话。
     *
     * 后端很多失败用的是 `INSTANCE_NOT_INSTALLED` 这类码，直接 message.error(String(e))
     * 用户只会看到一串英文常量。所有启动入口都走这里，翻译一次到处受益。
     */
    async launch(id: string, world?: string, server?: string, opts?: { force?: boolean }) {
      // ── 启动前拦一道：渲染器和版本推荐不一致时先问一句 ──────────────────
      // 这是**秒级**判断，不用等游戏跑到一半再发现渲染器不行（1.8.9 + MG 会白屏卡住，
      // 等系统把 Activity 收掉要几十秒）。点「仍用 xxx 启动」会记住，不再重复问。
      if (!opts?.force) {
        const guard = await this.rendererGuard(id);
        if (guard) {
          this.pendingRendererGuard = { ...guard, world, server };
          return null;
        }
      }
      this.launchingId = id;
      try {
        // 用户已经决定启动，这时才去补文件（见 ensureFilesBeforeLaunch）。
        // 放在渲染器确认之后：那是「要不要换个渲染器」的一次选择，
        // 万一用户选了「不用了」，没必要先替他下几百 MB。
        await this.ensureFilesBeforeLaunch(id);
        const res = await api.launchInstance(id, world, server);
        return res;
      } catch (e) {
        const raw = String(e);
        if (raw.includes("INSTANCE_NOT_INSTALLED")) {
          throw new Error($t("stores.instances.not-installed"));
        }
        if (raw.includes("GAME_ALREADY_RUNNING")) {
          throw new Error($t("stores.instances.already-running"));
        }
        if (raw.includes("INSTANCE_CONFIG_CORRUPT")) {
          throw new Error($t("stores.instances.corrupt-config"));
        }
        throw e;
      } finally {
        this.launchingId = null;
      }
    },
    async stop() {
      await api.stopGame();
    },

    /** 读全局渲染器（存在安卓 SharedPreferences 里，只有后端读得到），带缓存。 */
    async ensureGlobalRenderer(): Promise<string> {
      if (this.globalRenderer) return this.globalRenderer;
      try {
        const p = await api.getPojavPrefs();
        const r = p?.renderer;
        this.globalRenderer = typeof r === "string" && r ? r : "opengles2";
      } catch {
        this.globalRenderer = "opengles2";
      }
      return this.globalRenderer ?? "opengles2";
    },

    /**
     * 启动前的渲染器判断：会用的 ≠ 版本推荐的 → 返回一份待确认信息（否则 null）。
     *
     * `auto`（缺省）不会触发 —— 它本身就是推荐值；只有「跟随全局」和手动指定才可能跑偏。
     */
    async rendererGuard(id: string): Promise<RendererGuard | null> {
      let inst = this.get(id);
      // 冷启动时列表可能还没加载（例如从实例详情页点启动），先拉一次再判断
      if (!inst) {
        await this.load(true);
        inst = this.get(id);
      }
      if (!inst) return null;
      const key = inst.renderer ?? "auto";
      if (key === "auto" || key === "") return null;
      const globalKey = key === "global" ? await this.ensureGlobalRenderer() : null;
      const used = effectiveRendererKey(key, inst.mc_version, globalKey);
      const recommended = autoRendererFor(inst.mc_version);
      if (used === recommended) return null;
      if (readFlag(rendererGuardKey(id, used))) return null;
      return {
        instanceId: id,
        mcVersion: inst.mc_version,
        used,
        usedName: rendererLabel(used),
        recommended,
        recommendedName: rendererLabel(recommended),
      };
    },
  },
});
