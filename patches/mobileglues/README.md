# MobileGlues 修改说明与源码（LGPL-2.1 合规）

本目录提供 **QookiX Launcher Android 分发的 `libmobileglues.so` 所对应的源码修改**。

> **为什么必须有这个目录**：MobileGlues 以 **GNU LGPL-2.1** 发布，而 LGPL 要求
> 「分发修改后的库时，必须同时提供修改后的源码」。我们分发的 `libmobileglues.so`
> 是**打过补丁的构建**，所以必须把补丁公开 —— 这份文件就是那个义务的履行方式。

## 上游与基线版本

| 项 | 值 |
|---|---|
| 上游仓库 | https://github.com/MobileGL-Dev/MobileGlues |
| 基线提交 | `97558a6`（`Merge pull request #60 from HEBEI77/fix/multidraw-grow-staged-resize`） |
| 许可证 | LGPL-2.1（见上游 `LICENSE`） |
| 被修改文件 | `MobileGlues-cpp/gl/glsl/glsl_for_es.cpp`（+19 −1） |
| 补丁 | [`0001-glsl-uniform-word-boundary.patch`](./0001-glsl-uniform-word-boundary.patch) |

## 补丁做了什么（以及为什么）

`process_uniform_declarations()` 里原本用裸的

```cpp
if (glslCode.compare(scan_pos, 7, "uniform") == 0) {
```

扫描 `uniform` 关键字。这会命中**标识符中间**的 `uniform` 子串：MC 26.x 的着色器全面改用 UBO，
spirv-cross 把 UBO 改写为 `_uniform_00_00` / `_uniform_instance_00_00`，于是

```glsl
if (_uniform_instance_00_00.UseRgss == 1)
```

里的 `uniform` 被误判成关键字，整句被改写成 `if (_uniform _instance_00_00 ;` ——
类型吃掉 `_instance_00_00`、名字为空、`==` 被当成初始化器。结果是地形等核心着色器
全部编译失败，游戏进世界后一片虚无（26.3 实测）。

补丁把它改成**整词匹配**：前后一个字符都不能是 `[A-Za-z0-9_]`。

上游同类问题的修复 PR 已计划提交；在合并之前，这份补丁是分发版本与源码的唯一差异。

## 如何由源码构建出随分发的 `.so`

环境与参数（与构建随包/插件里那份 `libmobileglues.so` 时一致）：

| 项 | 值 |
|---|---|
| NDK | `android-ndk-r27c` |
| ABI | `arm64-v8a`（x86_64 同理，换 `-DANDROID_ABI=x86_64`） |
| `ANDROID_PLATFORM` | `android-24` |
| 构建类型 | `RelWithDebInfo` |
| 生成器 | Ninja + NDK 自带 `android.toolchain.cmake` |

```bash
# 1) 取上游并切到基线提交
git clone https://github.com/MobileGL-Dev/MobileGlues
cd MobileGlues
git checkout 97558a6
git submodule update --init --recursive

# 2) 应用本目录的补丁
git apply /path/to/0001-glsl-uniform-word-boundary.patch

# 3) 构建（NDK 路径按本机调整）
cd MobileGlues-cpp
cmake -B build-android -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-24 \
  -DCMAKE_BUILD_TYPE=RelWithDebInfo
cmake --build build-android

# 4) 产物
#    MobileGlues-cpp/build-android/libmobileglues.so
```

`libmobileglues_info_getter.so` 是上游同一构建产出的配套库，未作修改，
按上面步骤同样会得到。

## 这些二进制随什么分发

| 位置 | 形式 |
|---|---|
| `android/plugin-libs/<abi>/` | 打包插件 zip 的输入（构建期用，不进 APK） |
| 仓库 `plugins` Release 的 `qookix-renderer-mobileglues-<版本>-<abi>.zip` | 用户按需下载的「渲染器插件」`libs/` |
| 安装后的 `<数据目录>/plugins/qookix-renderer-mobileglues/<版本>/libs/` | 运行期实际加载的库 |

## 完整源码的获取

- 上述「基线提交 + 补丁」即为**修改后的完整源码**（补丁之外的每一行都等于上游 `97558a6`）。
- 若需要**未打补丁的完整修改后源码树**（含 `3rdparty` 子模块的固定版本）或构建脚本原件，
  可通过仓库 Issues 提出，作者会一并提供。
