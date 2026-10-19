import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Kotlin 2.0 起 Compose 编译器由官方插件提供
    id("org.jetbrains.kotlin.plugin.compose")
    id("rust")
}

val tauriProperties = Properties().apply {
    val propFile = file("tauri.properties")
    if (propFile.exists()) {
        propFile.inputStream().use { load(it) }
    }
}

android {
    compileSdk = 36
    namespace = "com.zhayi.qookix"
    defaultConfig {
        manifestPlaceholders["usesCleartextTraffic"] = "false"
        applicationId = "com.zhayi.qookix"
        minSdk = 24
        targetSdk = 36
        versionCode = tauriProperties.getProperty("tauri.android.versionCode", "1").toInt()
        versionName = tauriProperties.getProperty("tauri.android.versionName", "1.0")
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
    rootDirRel = "../../../"
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
    androidTestImplementation("androidx.test.ext:junit:1.1.4")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.0")

    // ── Jetpack Compose + Material 3（启动器界面重构）────────────────────────
    // Compose 编译器随 Kotlin 版本走（本工程 = 2.0.21，由 plugin.compose 提供）。
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
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

apply(from = "tauri.build.gradle.kts")