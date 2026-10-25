# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
# ── 注解与泛型信息必须保留 ────────────────────────────────────────────
#
# ⚠️ 2026-10-19 加（release 包「桥就绪但所有命令都没响应」的根因）：
#
# WebView 是靠 `@android.webkit.JavascriptInterface` **注解**来判断
# 哪些方法暴露给 JS 的（`addJavascriptInterface` 时会做反射扫描）。
# R8 默认会剥掉 `RuntimeVisibleAnnotations` 之类的属性，
# 于是方法名还在、注解没了 → WebView 认为这个对象没有任何可调用方法
# → JS 侧 `QookiXNative.onResult(...)` 静默失败。
#
# 表现极隐蔽：桥注入 `ok`、订阅也 `ok`，但每个命令都等不到回调，
# 8 秒后超时（「命令超时无响应」）。debug 包不复现。
#
# `-keepattributes` 这几个都是 wry / tauri 反射与序列化用到的：
#   *Annotation*             —— @JavascriptInterface / @Keep
#   Signature                —— 泛型（Java 反射读 getGenericParameterTypes）
#   InnerClasses / EnclosingMethod —— 内部类名
#   Exceptions               —— 方法抛出的异常声明
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions

# JS 接口类：方法名不能被改（JS 按名字调用 onResult / onEvent / log）
-keepclassmembers class com.zhayi.qookix.compose.bridge.RustBridge$NativeInbox {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.zhayi.qookix.compose.bridge.RustBridge$NativeInbox { *; }

# ── JNI 反查的类必须保留原名 ──────────────────────────────────────────
# 这些类是被 C 层用 FindClass("...") 反查的。release 构建开了 R8（isMinifyEnabled=true），
# 一旦被改名，FindClass 返回 NULL，紧接着的 GetMethodID(NULL, ...) 会直接原生崩溃 ——
# 这正是「debug 能跑、release 崩」的原因（例如 net/kdt/pojavlaunch/Logger$eventLogListener，
# 它自己没有 native 方法，所以不在 AGP 默认的 -keepclasseswithmembernames 规则里）。
-keep class net.kdt.pojavlaunch.** { *; }
-keep class com.kdt.** { *; }
-keep class org.lwjgl.glfw.** { *; }
-keep class com.zhayi.qookix.** { *; }
# SDL（libSDL3.so）：JNI_OnLoad 会反查 org.libsdl.app.SDLActivity 上的 native 方法
# （nativeGetVersion 等）。没这条 keep 时这些方法名会被 R8 改掉 → 启动游戏时
# System.loadLibrary("SDL3") 直接 abort：真机现象就是「点启动 → 退出码 -6」，
# 崩在 GameActivity.onCreate → MinecraftGLSurface.setupSDL。debug 不复现。
-keep class org.libsdl.app.** { *; }
# 陶瓦联机：libterracotta.so 的 JNI_OnLoad 会反查 TerracottaAndroidAPI（及
# VpnServiceCallback / Metadata 等内部类）。没这条 keep 时 release 会把它们改名，
# JNI_OnLoad 里找不到类 → Rust 侧直接 abort：真机现象是「启动游戏 → 退出码 -6」，
# 崩在 :tunnel 进程的 tc-init 线程（也就是 System.loadLibrary("terracotta") 那行）。
-keep class net.burningtnt.terracotta.** { *; }
# Java 层被 JNI 调用的方法名同样要保留（GetMethodID 按名字查找）
-keepclasseswithmembernames class * { native <methods>; }

# ── Tauri / wry 自身的类必须整包保留 ──────────────────────────────────
#
# ⚠️ 2026-10-19 修（release 包「桥就绪但所有命令都没响应、8 秒超时」的根因）
#
# `-keep class com.zhayi.qookix.**` 只保住了**我们自己的**类。
# 但 Tauri 的 Java 层（`app.tauri.**`）与 wry 的注入类被 R8 改名/内联之后，
# WebView → Kotlin → Rust 这条链路会在某个反射点上断掉：
#
#   JS `QookiXNative.onResult(...)`  →  NativeInbox（保留，正常）
#   →  __TAURI_INTERNALS__.ipc(...)  →  wry 的 Ipc.postMessage（保留，正常）
#   →  Rust 侧按 **命令名字符串** 查注册表 → 找不到/拿不到回调
#
# 现象极具欺骗性：桥注入 `ok`、事件订阅 `ok`、连 `WebView 渲染 60fps` 都正常，
# 唯独每个命令都等不到回调，8 秒后统一超时。关掉 `isMinifyEnabled` 立刻恢复。
-keep class app.tauri.** { *; }
-keep class com.zhayi.qookix.Ipc { *; }
-keep class com.zhayi.qookix.RustWebView { *; }
-keep class com.zhayi.qookix.RustWebViewClient { *; }
-keep class com.zhayi.qookix.RustWebChromeClient { *; }
-keep class com.zhayi.qookix.WryActivity { *; }
-keep class com.zhayi.qookix.TauriActivity { *; }
-keep class com.zhayi.qookix.WryActivityKt { *; }

# Tauri 的插件与命令注册大量使用注解 + 反射，这些元数据不能被剥掉
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations,RuntimeInvisibleParameterAnnotations
-keep @app.tauri.annotation.** class * { *; }
-keepclasseswithmembers class * {
    @app.tauri.annotation.Command <methods>;
}
-keepclasseswithmembers class * {
    @app.tauri.annotation.ActivityCallback <methods>;
}
-keepclasseswithmembers class * {
    @app.tauri.annotation.Permission <methods>;
}
-keepclasseswithmembers class * {
    @app.tauri.annotation.PermissionCallback <methods>;
}
-keepclasseswithmembers class * {
    @app.tauri.annotation.InvokeArg <fields>;
}

# 反射调用的构造器/方法不能被裁掉
-keepclassmembers class app.tauri.** {
    public <init>(...);
    public *;
}

-dontwarn app.tauri.**
-dontwarn org.chromium.**
