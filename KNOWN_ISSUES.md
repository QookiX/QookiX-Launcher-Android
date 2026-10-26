# 已知问题与限制

这里记的是**架构层面已知、短期不会修**的限制，以及它们的规避方式。
真正的 bug（能修、只是还没修）请走 [issue](.github/ISSUE_TEMPLATE/bug_report.yml)，不要堆在这份文件里。

---

## 16 KB 页设备（Android 15+ 部分新机）

**现象**：游戏启动后黑屏/白屏，或日志里出现 `dlopen failed: ... has invalid ELF alignment`，
渲染器加载失败；严重时进程直接退出。

**原因**：Android 15 起，部分设备使用 **16 KB 页**内存（Google Play 已要求新提交的应用支持）。
这类设备要求每个 `.so` 的 `PT_LOAD.p_align` 是 `0x4000`（16 KB）。

**实测现状**（`llvm-readelf -l`，arm64 release APK，2026-10-25）：

| 对齐 | 库 |
|---|---|
| **16 KB ✓** | `libqookix_lib.so`（Rust，链接参数 + CI 校验）、`libpojavexec.so` / `liblinkerhook.so` / `libthreaddump.so`（本地 NDK 构建，`Android.mk` 里加了 `max-page-size=16384`）、`libjnidispatch.so`、`libterracotta.so`、`libc++_shared.so`、`libfrpc.so` |
| **仍是 4 KB ✗** | `libgl4es_114.so`、`libSDL3.so`、`liblwjgl.so`、`liblwjgl_opengl.so`、`liblwjgl_nanovg.so`、`liblwjgl_stb.so`、`liblwjgl_tinyfd.so`、`libopenal.so`、`libfreetype.so`、`libspirv-cross-c-shared.so` |

也就是说：**我们自己编译的库已经全部 16 KB，剩下的是上游预编译产物**。

**当前处理**：启动前用 `renderer_available` 探测渲染器库能否加载，加载失败会给出
「换一个渲染器」的可读提示，而不是静默闪退。整机层面的 16 KB 支持要等上游库重建
（Mesa / LWJGL / GL4ES / SDL3 的预编译产物），这件事不在本仓库的修复范围内。

**怎么判断自己是不是这种情况**：`设置 → 关于 → 日志`，搜 `dlopen` 或 `alignment`。

---

## 卸载重装会丢「共享游戏文件」

**现象**：卸载后重装，实例列表还在（名字、模组、配置都正常），但进实例详情会显示
「游戏文件不完整（已就绪 0/5）· 实例几乎是空的，点「补全游戏文件」重新下载一遍即可」，
点启动也会要求先补全。

**原因**：实例本身（`instance.json` / `mods` / `config` / `saves`）放在 `/sdcard/QookiX/instances`，
卸载不受影响；但 `libraries/ assets/ versions/ Java 运行时/ natives/` 这些**所有实例共享**的文件
留在应用私有目录（`/data/data/com.zhayi.qookix/files`）—— `natives` 要 `dlopen`，
SD 卡多为 noexec 挂载，放外面加载会失败。卸载会连同私有目录一起删掉。

**规避**：

- 升级请**覆盖安装**，不要先卸载；
- 已经丢了就点详情页的「补全游戏文件」重新下载（1~2 GB，走镜像会快一些）；
- 共享文件在多个实例间复用，补全一次后其它实例也会一起恢复。

---

## CurseForge 内容源需要自己配置 API Key

**现象**：在内容中心选 CurseForge 源搜索时，提示
「未配置 CurseForge API Key。请到「设置 → 内容服务」填写自己的 Key，或把数据源切换成 Modrinth」。

**原因**：当前版本起**移除了硬编码的公共 Key**。原因有三条：
1. Key 明文入库，反编译即得，等于把别人的配额公开；
2. 借用第三方 Key 不符合 CurseForge 的第三方 API 条款；
3. 对方轮换 Key 之后，内容中心会**静默失败**，用户分不清是网络问题还是服务挂了。

**规避**：Modrinth 源（默认，免 Key）能满足绝大多数搜索需求；确需 CurseForge 时
到 CurseForge 控制台申请自己的 Key，填进「设置 → 内容服务」。

---

## 陶瓦联机与系统 VPN 冲突

**现象**：手机开着系统 VPN（含某些加速器、代理 App）时，加入/创建陶瓦房间失败。

**原因**：陶瓦联机的数据面走 TUN，系统 VPN 也占 TUN，两者互斥；这不是本项目能绕开的，
属于 Android 的 VPN 单实例限制。

**规避**：联机时关闭系统 VPN / 代理；需要代理时用「设置 → 网络代理」里只作用于
HTTP 请求的 SOCKS 配置（它不走 TUN）。

---

## 渲染器与游戏版本不匹配会白屏

**现象**：某些渲染器 + 某些 MC 版本组合下，游戏启动后白屏卡住，几十秒后被系统收掉。

**已知组合**：1.8.9 + MobileGlues 实测会白屏（见 `renderer_health.rs` 的推荐表）。

**当前处理**：游戏退出后会**自动**检查本次启动日志，发现渲染器失败特征就弹一次
「建议切换渲染器」的提示，可一键切到该版本推荐值。冷启动时也会静默查一次。

**规避**：还拿不准就用默认 GL4ES；26.x（SDL3 窗口层）建议先按设置页的推荐值选。

---

## 后台被 ROM 的省电策略杀掉

**现象**：开服后息屏一会儿服务端没了；或从后台切回来游戏被重载。

**原因**：ColorOS / HyperOS / EMUI 等 ROM 会对后台进程做额外清理，`FOREGROUND_SERVICE`
不等于豁免。

**规避**：「设置 → 游戏」里有「申请电池优化白名单」，点一次并允许；
另外在系统设置里把本应用的「自启动 / 后台运行」打开。

---

## 兼容性矩阵（建设中）

目前**没有**系统化的机型矩阵 —— 项目是单人维护，没有设备池。
下表是已确认可用的组合，欢迎通过 issue 补充（附机型 / SoC / Android 版本 / 渲染器 / 结果）：

| 机型 / SoC | Android | 渲染器 | 结果 | 备注 |
|---|---|---|---|---|
| OnePlus 8（骁龙 865） | 16 | GL4ES / MobileGlues | 待补 | 开发探针机型（3D 预览 readback、联机、开服都在它上面验过） |
| （待补） | | | | |

> 判断标准：能进主菜单 + 能进一个世界 + 触控可用 = 可用；
> 只到主菜单 = 部分可用；黑屏/闪退 = 不可用。
