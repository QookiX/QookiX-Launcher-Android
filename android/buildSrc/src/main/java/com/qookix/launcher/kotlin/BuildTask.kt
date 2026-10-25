import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.logging.LogLevel
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

open class BuildTask : DefaultTask() {
    @Input
    var rootDirRel: String? = null
    @Input
    var target: String? = null
    @Input
    var release: Boolean? = null

    /**
     * 这里**故意什么都不做**：Rust 由仓库根的 `scripts/build-android.mjs`
     * （`npm run android`）或 CI 的 `cargo build` 负责，Gradle 只把
     * `app/src/main/jniLibs/<abi>/libqookix_lib.so` 打进 APK。
     *
     * 保留这个空任务是为了让 `merge<Variant>JniLibFolders` 的依赖链不报错
     *（老版本 tauri 模板就是这么接的）。所以提示语不要写「cargo tauri ...」，
     * 那会把人引到一个已经不存在的命令上（Tauri 已随纯 JNI 改造移除）。
     */
    @TaskAction
    fun assemble() {
        project.logger.lifecycle(
            "跳过 Gradle 内的 Rust 编译（Rust 由 npm run android / CI 的 cargo build 负责，" +
                "Gradle 只打包 jniLibs 里现成的 .so）",
        )
    }
}
