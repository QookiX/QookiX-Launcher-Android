# QookiX Launcher Android {{VERSION}}

发布于 {{DATE}} · [完整提交记录]({{CHANGELOG}})

## 本次更新

<!--
  这里是默认模板（安装 / 说明 / 反馈这些固定内容），「## 本次更新」那一节会被
  scripts/generate-release-notes.mjs 用 CHANGELOG.md 里对应 tag 的更新日志替换。

  想给某个版本写整篇手写说明，就新增 .github/release-notes/<tag>.md（例如 v1.1.0.md），
  内容整篇即 Release 正文，优先级最高。
-->

- 待补充：请在仓库根目录 `CHANGELOG.md` 的 `[Unreleased]` 里写下改动，发版时把标题改成 `## [版本号] - 日期`。

## 安装

1. 下载对应架构的安装包：`QookiX-Launcher-Android-{{VERSION}}-arm64.apk`（绝大多数手机）或 `-x86_64.apk`（模拟器 / 平板）
2. 从旧版本升级可直接覆盖安装（同一签名）；若之前装的是 debug 版，请先卸载
3. 首次启动需联网下载 Java 运行时与游戏文件

| 项目 | 要求 |
|---|---|
| 系统 | Android 7.0（API 24）及以上 |
| 架构 | arm64-v8a / x86_64 |
| 网络 | 首次启动需联网（可用 BMCLAPI 等镜像加速） |

## 说明

- 下载后可对照 `SHA256SUMS.txt` 校验完整性
- 本项目为独立第三方开源项目，与 Mojang / Microsoft / Modrinth / CurseForge 无隶属关系；
  启动器不托管任何游戏文件，请确保拥有 Minecraft 正版授权
- 许可证：GPL-3.0（含 LGPL-3.0 的 PojavLauncher 组件，详见 `THIRD_PARTY_NOTICES.md`）

## 反馈

遇到问题请到 [Issues](https://github.com/QookiX/QookiX-Launcher-Android/issues) 反馈，
并附上机型、Android 版本、渲染后端与日志（实例页 → 日志）。
