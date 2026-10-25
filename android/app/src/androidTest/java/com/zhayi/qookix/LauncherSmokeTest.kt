package com.zhayi.qookix

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhayi.qookix.compose.ComposeMainActivity
import com.zhayi.qookix.tauri.TauriBridge
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 冷启动冒烟测试（真机 / 模拟器）。
 *
 * ## 挡住什么
 *
 * 这一类事故在 CHANGELOG 里反复出现，而且**只有真机能暴露**：
 *   · 装完 APK 冷启动后「点了没反应」—— JNI 通道没起来
 *   · `No implementation found for ...TauriBridge.nativeInvoke`
 *     —— APK 里的 .so 不是我们构建的那份（符号找不到）
 *   · 依赖库/实例目录显示「已就绪」，实际命令全部失败
 *
 * 所以测试只做三件事，且**不联网、不下载**（保持秒级、可重复）：
 *   1. 启动真正的启动器 Activity（与用户点图标等价）
 *   2. 等桥就绪（最多 15 秒）
 *   3. 用两条**只读**命令验证「JNI 通道 + Rust 业务层」都活着
 *
 * ## 为什么不断言「数据非空」
 *
 * 全新安装没有实例。这里断言的是**信封格式**（`ok` 字段）而不是内容 ——
 * 有内容时是 `ok:true`，没内容也该是 `ok:true` + 空数组；
 * 只有桥断了/命令没实现才会是失败或抛异常。
 *
 * ## 怎么跑
 *
 * ```
 * cd android
 * ./gradlew :app:connectedArm64DebugAndroidTest     # 需要已连接的设备
 * ```
 * CI 里**只编译不执行**（runner 没有设备），见 release.yml 的
 * 「编译仪器化冒烟测试」步骤。
 */
@RunWith(AndroidJUnit4::class)
class LauncherSmokeTest {

    /** 调一条命令，返回解析后的信封；通道不通时抛 AssertionError。 */
    private fun invoke(cmd: String, payload: String = "{}"): JSONObject {
        val raw = try {
            TauriBridge.nativeInvoke(cmd, payload)
        } catch (t: Throwable) {
            throw AssertionError("JNI 调用 $cmd 抛异常（.so 没加载或符号缺失）：${t.message}", t)
        }
        assertNotNull("$cmd 返回 null（JNI 符号缺失？）", raw)
        return try {
            JSONObject(raw)
        } catch (t: Throwable) {
            throw AssertionError("$cmd 返回的不是 JSON 信封：$raw", t)
        }
    }

    @Test
    fun coldStart_jniChannelAlive() {
        ActivityScenario.launch(ComposeMainActivity::class.java).use {
            // 桥是异步就绪的（Rust 侧要初始化运行时），轮询等它
            var probe: JSONObject? = null
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline) {
                probe = invoke("get_settings")
                if (probe.optBoolean("ok")) break
                Thread.sleep(300)
            }
            assertTrue(
                "冷启动 15 秒内桥仍未就绪，最后一次返回：$probe",
                probe?.optBoolean("ok") == true,
            )
        }
    }

    @Test
    fun listInstances_returnsEnvelopeAndArray() {
        ActivityScenario.launch(ComposeMainActivity::class.java).use {
            // 等桥就绪（同上，但这里允许慢一点：磁盘第一次读可能要建目录）
            var ready = false
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline) {
                if (invoke("get_settings").optBoolean("ok")) {
                    ready = true
                    break
                }
                Thread.sleep(300)
            }
            assertTrue("桥未就绪，跳过后续断言", ready)

            val r = invoke("list_instances")
            assertTrue("list_instances 失败：$r", r.optBoolean("ok"))
            // 信封里 data 必须存在且是数组（空数组也算通过 —— 全新安装就是空）
            val data = r.opt("data")
            assertTrue("list_instances 的 data 不是数组：$data", data is org.json.JSONArray)
        }
    }
}
