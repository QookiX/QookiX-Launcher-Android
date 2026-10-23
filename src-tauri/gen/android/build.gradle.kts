buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.11.0")
        // ⚠️ 2026-10-23（分支 exp/kotlin-upgrade 试验）：
        // Kotlin 2.0.21 → 2.2.21 + compose-bom → 2026.09.00
        // 目的：拿到 material3 1.4.x 的 `MaterialExpressiveTheme`（M3 Expressive）。
        // 这条分支只做验证；master 仍是 Kotlin 2.0.21 的稳定组合。
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.21")
        classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.2.21")
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


