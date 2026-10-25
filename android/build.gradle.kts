buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.11.0")
        // ⚠️ 2026-10-23：**退回过** Kotlin 2.2.21 的试验。
        // 升级本身能编过，但真机一进二级页面就崩：
        //   ClassNotFoundException: kotlin.coroutines.jvm.internal.SpillingKt
        // Kotlin 2.2 编出的代码引用了新版 coroutines 的类，而运行时 coroutines 是被
        // Tauri 那条依赖链钉住的旧版 → 切页面时协程一跑就崩。
        // 想再升，需要同时把 kotlinx-coroutines 抬到匹配版本并回归全部页面，单独升 Kotlin 不行。
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21")
        classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.0.21")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

tasks.register("clean").configure {
    delete("build")
}


