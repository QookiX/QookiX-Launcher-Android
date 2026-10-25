package com.zhayi.qookix

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhayi.qookix.compose.ComposeMainActivity
import com.zhayi.qookix.tauri.TauriBridge
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 「游戏目录指到外部存储后，既有实例能被读回来」的回归测试。
 *
 * ## 挡住什么
 *
 * 2026-10-25 真机上真实踩到的事故：**卸载重装后实例列表全空**，而数据好好地
 * 躺在 `/sdcard/QookiX/instances/` 里 —— 用户以为存档被删了。
 * 根因是启动器只在应用私有目录找实例，而「游戏目录」这个设置存在私有目录里，
 * 卸载时一起没了。改一次设置才能恢复，但没人告诉用户该改哪儿。
 *
 * 这条测试把那条恢复路径**变成可执行的**：
 *   1. 设备上存在外部实例时，用**命令**把游戏目录指过去（不走 UI）；
 *   2. 再拉一次列表，断言实例回来了、且 `game_dir` 确实指向外部。
 *
 * 顺带解决「自动化没法设置目录」的问题：`set_game_dir` 是普通命令，
 * 测试可以直接调，不用去点那个不响应脚本滑动的 SAF 选择器。
 *
 * ## 为什么不联网 / 不下载
 *
 * 与 `LauncherSmokeTest` 一样：只碰本地目录，秒级可重复。
 *
 * ## 对 CI
 *
 * CI **只编译不执行**（runner 没有设备）。真机执行：
 * ```
 * cd android
 * ./gradlew :app:connectedArm64DebugAndroidTest
 * ```
 */
@RunWith(AndroidJUnit4::class)
class GameDirRecoveryTest {

    /** 常见的旧数据目录（用户自己指定过的游戏目录）。 */
    private val legacyRoots = listOf(
        "/sdcard/QookiX",
        "/storage/emulated/0/QookiX",
    )

    private fun invoke(cmd: String, payload: String = "{}"): JSONObject {
        val raw = try {
            TauriBridge.nativeInvoke(cmd, payload)
        } catch (t: Throwable) {
            throw AssertionError("JNI 调用 $cmd 抛异常：${t.message}", t)
        }
        return JSONObject(raw)
    }

    /** 设备上是否有「带 instance.json 的实例目录」。 */
    private fun findInstancesOnDisk(): Pair<String, Int>? {
        for (root in legacyRoots) {
            val dir = File(root, "instances")
            val n = dir.listFiles()?.count { File(it, "instance.json").isFile } ?: 0
            if (n > 0) return root to n
        }
        return null
    }

    @Test
    fun externalGameDir_instancesComeBack() {
        val onDisk = findInstancesOnDisk()
        // 全新设备 / CI 没有外部实例 —— 跳过，不算失败
        assumeTrue("设备上没有外部实例目录，跳过", onDisk != null)
        val (root, count) = onDisk!!

        ActivityScenario.launch(ComposeMainActivity::class.java).use {
            // 等桥就绪
            var ready = false
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline) {
                if (invoke("get_settings").optBoolean("ok")) {
                    ready = true
                    break
                }
                Thread.sleep(300)
            }
            assertTrue("桥未就绪", ready)

            // migrate=false：只改指针，**不搬数据**（测试机上不能乱动用户的存档）
            val set = invoke(
                "set_game_dir",
                JSONObject().put("root", root).put("migrate", false).toString(),
            )
            assertTrue(
                "set_game_dir 失败（常见原因：没有「所有文件访问」权限）：$set",
                set.optBoolean("ok"),
            )

            // 列表应该回来了
            val list = invoke("list_instances")
            assertTrue("list_instances 失败：$list", list.optBoolean("ok"))
            val arr = list.getJSONArray("data")
            assertTrue(
                "指回 $root 后实例数为 0（盘上有 $count 个）",
                arr.length() > 0,
            )

            // 而且它们的 game_dir 确实指向那个外部目录
            val first = arr.getJSONObject(0)
            assertTrue(
                "game_dir 没指向外部目录：${first.optString("game_dir")}",
                first.optString("game_dir").startsWith(root),
            )
        }
    }
}
