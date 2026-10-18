# QookiX Launcher Android

**一款免费、纯净、无广告的 Minecraft Java 版安卓启动器**

[![Build](https://github.com/QookiX/QookiX-Launcher-Android/actions/workflows/release.yml/badge.svg)](https://github.com/QookiX/QookiX-Launcher-Android/actions/workflows/release.yml)
[![Downloads](https://img.shields.io/github/downloads/QookiX/QookiX-Launcher-Android/total)](https://github.com/QookiX/QookiX-Launcher-Android/releases)
[![Stars](https://img.shields.io/github/stars/QookiX/QookiX-Launcher-Android)](https://github.com/QookiX/QookiX-Launcher-Android)
[![License](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%207.0%2B%20arm64-3ddc84.svg)](#下载与安装)

在手机上直接运行 **Minecraft: Java Edition**：自带 Java 运行时下载、多实例管理、内容下载与完整的触控操作层。
与 [QookiX Launcher（桌面端）](https://github.com/weimosheng/QookiX-Launcher) 保持一致的界面与使用体验。

---

## 下载与安装

前往 **[Releases](https://github.com/QookiX/QookiX-Launcher-Android/releases)** 页面下载最新的 `arm64` APK 并安装。

| 项目 | 要求 |
|---|---|
| 系统 | Android 7.0（API 24）及以上 |
| 架构 | arm64-v8a（绝大多数现代手机） |
| 网络 | 首次启动需联网下载 Java 运行时与游戏文件（可使用 BMCLAPI 等镜像） |

> 首次进入游戏前，请先在启动器内创建实例、登录账号（微软正版或离线），然后点「启动游戏」。

## 为什么选择它？

- **纯净无广告** —— 没有弹窗、没有充值入口、没有遥测，数据只保存在本机。
- **在手机上玩 Java 版** —— 内置 JVM 启动底座与多套图形翻译后端，把桌面版 Minecraft 跑在 Android 上。
- **多实例管理** —— 每一套「游戏版本 + 模组组合」独立成实例，互不干扰，可分组、可备份。
- **内容一站获取** —— 内置 Modrinth 与 CurseForge 源，直接搜索并安装模组、整合包、光影与资源包。
- **完整的触控操作层** —— 可编辑的虚拟按键布局、摇杆、快捷键、鼠标手势与手势映射，横竖屏都适配。
- **多渲染后端可选** —— GL4ES、MobileGlues、Mesa（Zink + Turnip）三种后端按机型切换，兼顾兼容与性能。

## 功能特性

**启动与实例**
- 多实例（版本 + 加载器 + 模组），支持分组、复制、重命名、删除
- 原版 / Forge / Fabric / Quilt / NeoForge 等加载器安装与版本管理
- 游戏参数、内存分配、JVM 参数、界面缩放等可按实例覆盖

**账号**
- 微软正版登录（设备码流程，令牌仅存本机）
- 离线账号（自定义用户名）
- 皮肤查看与更换

**内容**
- 内置 Modrinth / CurseForge：模组、整合包、光影、资源包搜索安装
- 实例目录文件管理器、日志查看器、崩溃报告与诊断
- 世界备份与恢复

**游戏内**
- 完整触控层：可编辑布局、摇杆、下拉抽屉、快捷设置、快速进入服务器/存档
- 渲染分辨率缩放、陀螺仪视角、鼠标速度等控制项

## 技术栈

| 层 | 说明 |
|---|---|
| 界面 | **Jetpack Compose + Material 3**（Kotlin 2.0.21，Compose BOM 2025.04.01，原生渲染） |
| 外壳与后端 | **Tauri 2** + Rust（IPC 桥与事件总线；下载、实例、Java 管理、文件与备份等约 30 个模块） |
| 游戏内原生层 | Kotlin/Java + C（`libpojavexec.so`：JVM 引导、EGL/Surface 桥、输入桥） |
| 图形后端 | GL4ES（默认）、MobileGlues、Mesa（OSMesa + Zink + Turnip） |

## 构建

环境：Node.js 20+、Rust（含 `aarch64-linux-android` target）、Android SDK（compileSdk 36）、Android NDK。

```bash
# 1) 静态资源（只有 index.html 与 skin3d/*.js —— 那个 index.html 是 Tauri 的
#    IPC 引导页，没有它所有 Rust 命令都会静默失效）
npm ci
npm run build:web-assets

# 2) Rust（注意：只设目标级 CC/CXX/AR，不要设全局，否则宿主 crate 构建失败）
cd src-tauri
export CC_aarch64_linux_android=$NDK/toolchains/llvm/prebuilt/<host>/bin/aarch64-linux-android24-clang
export CXX_aarch64_linux_android=$NDK/toolchains/llvm/prebuilt/<host>/bin/aarch64-linux-android24-clang++
export AR_aarch64_linux_android=$NDK/toolchains/llvm/prebuilt/<host>/bin/llvm-ar
cargo build --target aarch64-linux-android          # 发布用 --release

# 3) 打包
cp target/aarch64-linux-android/debug/libqookix_lib.so \
   gen/android/app/src/main/jniLibs/arm64-v8a/
cd gen/android
./gradlew :app:assembleArm64Debug                   # 发布用 assembleArm64Release
```

**自动发布**：推送 `v*` 标签即触发 [GitHub Actions](.github/workflows/release.yml) —— 构建 arm64 release APK 并以仓库 Secrets 中的密钥签名，随后发布到 Releases。
签名密钥（`QOOKIX_KEYSTORE_BASE64` / `QOOKIX_KEYSTORE_PASSWORD` / `QOOKIX_KEY_ALIAS` / `QOOKIX_KEY_PASSWORD`）仅存于 GitHub Secrets，**不随仓库分发**；未配置时退回 debug 签名。

## 声明

### 非官方项目
本项目是独立的第三方开源项目，与 Mojang Studios、Microsoft、Modrinth、CurseForge 均无隶属、合作或背书关系；不使用官方启动器的专有代码。请遵守 [Minecraft 使用准则](https://www.minecraft.net/zh-hans/usage-guidelines)。

### 商标与版权
Minecraft 是 Microsoft 的商标；Mojang、Modrinth、CurseForge 等名称与图标为各自所有者的商标，在本项目中仅用于描述兼容性。

### 内容与下载
启动器本身只是下载与安装工具，**不托管任何游戏文件**：游戏客户端来自官方渠道，模组/整合包/光影/资源包来自 Modrinth、CurseForge 等第三方平台。请确保你拥有 Minecraft 正版授权，并遵守相应平台的使用条款。

### 账号与数据
微软账号登录通过官方设备码流程完成，访问令牌仅保存在本机，不会上传；本启动器不采集、不上传任何个人数据与使用统计。

### 第三方开源软件
本项目包含以下第三方组件，完整清单与逐文件来源见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)：

| 组件 | 许可 |
|---|---|
| [PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher)（JVM 引导、EGL/Surface 桥、输入桥、游戏内控制层） | LGPL-3.0（见 [`LICENSE-LGPL-3.0.txt`](LICENSE-LGPL-3.0.txt)） |
| [GL4ES](https://github.com/ptitSeb/gl4es)（`libgl4es_114.so`，Copyright © ptitSeb） | MIT |
| [LWJGL 3](https://www.lwjgl.org/)（`liblwjgl*.so` 与 Java 类） | BSD-3-Clause |
| [Mesa](https://gitlab.freedesktop.org/mesa/mesa)（OSMesa、Zink、Turnip） | MIT |
| [Vulkan-ExtensionLayer](https://github.com/KhronosGroup/Vulkan-ExtensionLayer) | Apache-2.0 |
| [Caciocavallo](https://github.com/OpenJDK/caciocavallo)（AWT on Android） | GPL-2.0 with Classpath Exception |
| OpenAL Soft / FreeType / JNA / OpenJDK 组件 | LGPL / FTL 或 GPL-2.0 / LGPL-2.1+ 或 Apache-2.0 / GPL-2.0 with CPE |

PojavLauncher 部分（含修改：包名与 R 引用调整、shim 接线、剥离手柄重映射）仍以 **LGPL-3.0** 提供；该项目与我们的 GPL-3.0 主程序通过**独立动态库** `libpojavexec.so` 交互，用户可自行替换该 `.so` 后重新打包，符合 LGPL-3.0 关于「允许替换库」的要求。

### 免责条款
本软件按「原样」提供，不附带任何明示或暗示的担保。作者与贡献者不对使用本软件造成的任何损失承担责任；使用即视为同意本条款。

## 参考致谢

- [PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher) —— 安卓平台上运行 Java 版 Minecraft 的先驱
- [GL4ES](https://github.com/ptitSeb/gl4es) / [MobileGlues](https://github.com/MobileGLues/MobileGlues) / [Mesa](https://gitlab.freedesktop.org/mesa/mesa) —— 图形翻译与驱动后端
- [LWJGL](https://www.lwjgl.org/) 与 [Caciocavallo](https://github.com/OpenJDK/caciocavallo) —— Java 侧窗口与输入适配
- [Jetpack Compose](https://developer.android.com/jetpack/compose)、[Material 3](https://m3.material.io/)、[Kotlin](https://kotlinlang.org/)、[Tauri](https://tauri.app/) —— 界面与外壳
- [Modrinth](https://modrinth.com/) 与 [CurseForge](https://www.curseforge.com/) —— 内容平台
- [Feather Icons](https://feathericons.com/)（MIT）、[Lucide](https://lucide.dev/)（ISC）—— 图标

## 许可证

本项目以 **GNU General Public License v3.0** 发布，见 [`LICENSE`](LICENSE)；其中来自 PojavLauncher 的部分为 **LGPL-3.0**，见 [`LICENSE-LGPL-3.0.txt`](LICENSE-LGPL-3.0.txt)。

## 开发者 / 贡献

- 官网：<https://www.qookix.cn/>
- 问题反馈：[Issues](https://github.com/QookiX/QookiX-Launcher-Android/issues)
- 桌面端：[weimosheng/QookiX-Launcher](https://github.com/weimosheng/QookiX-Launcher)

欢迎提交 Issue 与 Pull Request；反馈问题时请附上机型、Android 版本、渲染后端与日志（实例页 → 日志）。
