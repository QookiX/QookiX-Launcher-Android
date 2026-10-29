plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Kotlin 2.0 起 Compose 编译器由官方插件提供
    id("org.jetbrains.kotlin.plugin.compose")
    id("rust")
}

// ⚠️ 2026-10-25：原来这里读 `tauri.properties` 取 versionName/versionCode，
// 纯 JNI 改造后该文件不再生成（原来是 `cargo tauri android build` 写的）。
// 版本号现在是下面 `defaultConfig` 里的字面量，由 `scripts/update-version.mjs` 同步。

android {
    compileSdk = 36
    namespace = "com.zhayi.qookix"
    defaultConfig {
        manifestPlaceholders["usesCleartextTraffic"] = "false"
        applicationId = "com.zhayi.qookix"
        minSdk = 24
        targetSdk = 36
        versionCode = 20151
        versionName = "0.2.1-rc2"

        // ⚠️ 2026-10-25 真机验证时发现漏配：不写这一行，AGP 会用默认的
        // `android.test.InstrumentationTestRunner`，而我们写的 `LauncherSmokeTest`
        // 是 JUnit4 的 —— 真机上 `am instrument` 直接报
        // INSTRUMENTATION_FAILED，测试一条都跑不起来。
        // CI 只编译不执行，所以这个洞在本地一直是隐形的。
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // 签名：密钥经环境变量注入（QOOKIX_KEYSTORE 等 4 个；CI 由 GitHub Secrets 提供）。
    // **debug 也复用同一把钥匙是有意的**：这样 debug 包能直接 `adb install -r`
    // 覆盖已装的 release 包，不必先卸载 —— 卸载会连带清掉 /data 下的实例与存档。
    // 没有注入密钥时两边都退回默认 debug 签名（本机新环境/他人克隆仓库行为不变）。
    val ciKeyStorePath = System.getenv("QOOKIX_KEYSTORE") ?: ""
    signingConfigs {
        if (ciKeyStorePath.isNotEmpty() && file(ciKeyStorePath).exists()) {
            create("ci") {
                storeFile = file(ciKeyStorePath)
                storePassword = System.getenv("QOOKIX_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("QOOKIX_KEY_ALIAS")
                keyPassword = System.getenv("QOOKIX_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("debug") {
            manifestPlaceholders["usesCleartextTraffic"] = "true"
            isDebuggable = true
            isJniDebuggable = true
            isMinifyEnabled = false
            packaging {                jniLibs.keepDebugSymbols.add("*/arm64-v8a/*.so")
                jniLibs.keepDebugSymbols.add("*/armeabi-v7a/*.so")
                jniLibs.keepDebugSymbols.add("*/x86/*.so")
                jniLibs.keepDebugSymbols.add("*/x86_64/*.so")
            }
            signingConfigs.findByName("ci")?.let { signingConfig = it }
        }
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(
                *fileTree(".") { include("**/*.pro") }
                    // ⚠️ 用 **proguard-android.txt**（不含 -optimize）。
                    //
                    // `proguard-android-optimize.txt` 会额外做方法内联、
                    // 类合并、跨类静态化等激进优化。实测这份 App 开它之后：
                    // 桥注入 ok、事件订阅 ok，但**每一个 Tauri 命令都收不到回调**
                    // （8 秒超时），实例列表/新闻/设置全是空的；关掉 minify
                    // 立刻恢复正常，说明就是优化阶段破坏了 wry 的 IPC 反射链路。
                    //
                    // 非 optimize 版本只做「压缩 + 混淆 + 资源裁剪」，
                    // 体积差别很小（实测 <5%），但保住了反射行为。
                    .plus(getDefaultProguardFile("proguard-android.txt"))
                    .toList().toTypedArray()
            )
            signingConfig = signingConfigs.findByName("ci") ?: signingConfigs.getByName("debug")
        }
    }
    // 原生库压缩打包：APK 里的 .so 默认是**不压缩**存储的（为了直接 mmap），
    // 实测这几坨库能压到 30%~51%，是体积最大的一块肉。
    // 代价是安装时系统会把 .so 解压到应用目录 —— 下载变小、装完占用变大，
    // 对手机用户来说前者更要紧。
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        buildConfig = true
        // ── Jetpack Compose（启动器界面重构）────────────────────────────────
        // 目标：把启动器 UI 从 WebView(Vue) 换成原生 Compose + Material 3。
        // 编译器由 org.jetbrains.kotlin.plugin.compose 提供（Kotlin 2.0+ 不再用
        // composeOptions.kotlinCompilerExtensionVersion）。
        compose = true
    }
    // 第二个 assets 源目录：放「游戏组件」这类大体积随包文件（LWJGL 3.4.1 jar/natives、
    // SDL3 等）。**不能**放 src/main/assets —— 那个目录是前端（vite）的 outDir，
    // 每次 `npm run build` 都会按 `emptyOutDir: true` 整目录清空，
    // 组件会被静默删掉（表现为「换了新版本却还是老 LWJGL」）。
    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets-components")
        }
    }
    // Pojav 版 JNI 核心（libpojavexec.so）：Surface→EGL 桥 + GL 函数转发 + 输入注册。
    // 源码来自 PojavLauncher，模块名必须保持 pojavexec。
    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }
}

rust {
    // android/app → 仓库根：上两级（原来是 src-tauri/gen/android/app，上三级）
    rootDirRel = "../../"
}

dependencies {
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-process:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.core:core-ktx:1.13.1")

    // ── Pojav 控制层（net.kdt.pojavlaunch.customcontrols.*）所需 ──────────────
    // 游戏内菜单抽屉：activity_basemain.xml 的根布局是 DrawerLayout
    implementation("androidx.drawerlayout:drawerlayout:1.1.1")
    // 控制编辑器/日志浮层：activity_custom_controls.xml、view_logger.xml 用 ConstraintLayout
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    // 控制布局 JSON（ControlData/CustomControls 的序列化格式，字段名即磁盘格式）
    implementation("com.google.code.gson:gson:2.10.1")
    // Pojav 的 res/values/styles.xml 里 PreferenceThemeOverlay.v14.Material / preferenceTheme
    // 来自 androidx.preference（上游同样声明了它）
    implementation("androidx.preference:preference:1.2.0")
    // exp4j：控制按钮动态位置表达式求值（${margin} * 2 + ${width} 这类）
    // 该 jar 来自 PojavLauncher/app_pojavlauncher/libs（PojavLauncherTeam 的 exp4j fork）；
    // 本机无法访问 Maven 仓库，故以本地 jar 形式内置。
    implementation(files("libs/exp4j-0.4.9-SNAPSHOT.jar"))

    testImplementation("junit:junit:4.13.2")
    // runner 是 AndroidJUnitRunner 的宿主包：ext:junit 会传递依赖，但显式声明
    // 才不会被某个版本的依赖裁剪掉（真机上少这个类就是 INSTRUMENTATION_FAILED）
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.4")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.0")

    // ── Jetpack Compose + Material 3（启动器界面重构）────────────────────────
    // Compose 编译器随 Kotlin 版本走（本工程 = 2.0.21，由 plugin.compose 提供）。
    // 2026-10-23：随 Kotlin 一起退回 2025.04.01（原因见根 build.gradle.kts 的注释：
    // Kotlin 2.2 编出的代码需要新版 coroutines，运行时是旧版 → 切页面闪退）。
    // ⚠️ 2026-10-27 更正：这里原来写「material3 1.4.0 要 compileSdk 37 + AGP 9」是**误判**。
    // 实测：BOM 2025.09.01（material3 1.4.0）在本项目的 compileSdk 36 + 现有 AGP 下
    // 全量编译**零错误**，不需要动工具链。
    //
    // 那条注释想说的其实是另一件事：`MaterialExpressiveTheme` / `MotionScheme` /
    // `ExperimentalMaterial3ExpressiveApi` 在 1.4.0 里确实是 `internal`
    //（`@Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")` 能强行引用，但那是绑
    // internal API，每次升 material3 都可能断），而 `MaterialShapes` 根本不存在。
    //
    // 结论：Expressive 的动效自己写 4 组 SpringSpec 就行（几十行），别绑 internal。
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // 从种子色生成整套 M3 色板（MIT 许可，纯算法无 UI）。
    // 只调它的 dynamicColorScheme()，不复制它的实现 —— ZL2 用的是同一个库。
    implementation("com.materialkolor:material-kolor:2.1.1")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material:material-icons-extended")
    // 自适应布局：ListDetailPaneScaffold 等（横屏/平板分栏用）
    implementation("androidx.compose.material3.adaptive:adaptive")
    implementation("androidx.compose.material3.adaptive:adaptive-layout")
    // 导航（Compose 侧路由）
    implementation("androidx.navigation:navigation-compose:2.8.9")
    // ViewModel + Compose 互操作
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    // 宿主 Activity：enableEdgeToEdge + setContent
    implementation("androidx.activity:activity-compose:1.10.1")

    // ── 局域网同步：扫码配对 ──────────────────────────────────────────────
    // CameraX：相机预览 + 帧分析（AndroidX Java 库，无 Kotlin metadata 兼容问题）
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    // ZXing：二维码解码核心（纯 Java，无 Kotlin 依赖）
    implementation("com.google.zxing:core:3.5.3")

    // ── 生态库：让界面「活起来」的那些 ──────────────────────────────────────
    // ⚠️ 版本选择受一个硬约束：本工程编译器是 **Kotlin 2.0.21**，
    //    读不了用更新 Kotlin 编译的库的 metadata（报
    //    "binary version of its metadata is 2.3.0, expected 2.0.0"）。
    //    所以下面每个库的版本都必须是 **Kotlin ≤ 2.0.x 编译**的。
    //    实测：shimmer 1.4.0 / reorderable 2.4.3 都不行（2.3.20 编的），
    //    已降到可用版本。将来升级 Kotlin 后可同步升级这些。
    //
    // 图片加载（实例图标 / 皮肤缩略图 / 新闻配图）：内存+磁盘缓存、占位图、淡入
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")
    // 加载骨架屏（shimmer 扫光）
    implementation("com.valentinilk.shimmer:compose-shimmer:1.3.2")
    // 列表拖拽排序
    implementation("sh.calvin.reorderable:reorderable:2.3.3")
    // Lottie 动画
    implementation("com.airbnb.android:lottie-compose:6.6.2")

    // Markdown 渲染（内容详情的简介就是 Markdown）：表格/代码块/图片/链接都齐，
    // 免去自己写解析器再把表格图片退化成纯文本。MIT。
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.29.0")
    // Markdown 里的图片：默认 transformer 是 NoOp（图片直接不显示），
    // 用 Coil3 那份实现 —— 与本工程已有的 coil3 3.0.4 同版本，不额外拉依赖。
    implementation("com.mikepenz:multiplatform-markdown-renderer-coil3:0.29.0")

    debugImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    debugImplementation("androidx.compose.ui:ui-tooling")

    // ── 兜底：把 kotlin-stdlib 钉在本工程的编译器版本上 ─────────────────────
    // 为什么必须钉：部分生态库（实测 shimmer 1.4.0、reorderable 2.4.3）
    // 是用**更新的 Kotlin**（2.3.20）编的，会把 stdlib 抬到 2.3.20；
    // 而本工程编译器是 2.0.21 —— 编译器读不了更新版本的 metadata，报
    // FileAnalysisException / "Module was compiled with an incompatible Kotlin"。
    //
    // ⚠️ 用 configurations.all + resolutionStrategy.force，**不能**用 constraints：
    //   这些库的 POM 里是 `strictly 2.3.20`，constraints 优先级压不过它；
    //   force 是 Gradle 里唯一能覆盖 strictly 的手段。
    configurations.all {
        resolutionStrategy {
            force(
                "org.jetbrains.kotlin:kotlin-stdlib:2.0.21",
                "org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.0.21",
                "org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.0.21",
                "org.jetbrains.kotlin:kotlin-stdlib-common:2.0.21",
            )
        }
    }
}

// 阶段 4（2026-10-25）：tauri-android 依赖已移除（原来的 rust 构建挂载由手动 cargo build + jniLibs 承担）