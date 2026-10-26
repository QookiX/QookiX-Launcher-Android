package com.zhayi.qookix.compose.pages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.zhayi.qookix.compose.bridge.QookixApi
import com.zhayi.qookix.compose.bridge.RustBridge
import com.zhayi.qookix.compose.bridge.dismissPluginSetup
import com.zhayi.qookix.compose.bridge.optText
import org.json.JSONObject

/**
 * 首启「准备运行环境」的全部接线：查清单 → 自动补齐 → 逐项进度 → 失败可见。
 *
 * 单独成文件是因为 `ComposeMainActivity` 已顶到体量预算上限
 * （`scripts/check-file-size.mjs`），而这段逻辑本身与主界面无关。
 *
 * ── 两个曾经的静默坑（2026-10-26 真机验证）──────────────────────────────
 * 1. 弹窗**不订阅** `plugin://progress`：进度条只在全部装完时一次性写 doneIds，
 *    用户看到的是「0 / 5 下载中…」卡住，随后弹窗自行消失 —— 装没装成不知道。
 * 2. 后端 `failed` 是**字符串数组**（「名字：原因」），前端按对象数组解析，
 *    于是永远读不到失败项、每项都标 ✓。解析收在 [afterInstall] 里。
 */
@Composable
fun FirstRunSetupHost(bridge: RustBridge, bridgeReady: Boolean) {
    var state by remember { mutableStateOf(FirstRunUiState()) }
    // 待装插件的 id → 名字。`plugin://progress` 只带 id，
    // 靠它把进度事件的 done 阶段映射回弹窗里那一行。
    var idToName by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    DisposableEffect(bridge) {
        val sub = bridge.listen("plugin://progress") { payload ->
            val o = runCatching { JSONObject(payload) }.getOrNull()
            if (o?.optString("phase") == "done") {
                val name = idToName[o.optString("id")]
                if (name != null && name !in state.doneIds && !state.finished) {
                    state = state.copy(
                        doneIds = state.doneIds + name,
                        failedIds = state.failedIds - name,
                    )
                }
            }
        }
        onDispose { bridge.unlisten(sub) }
    }

    // 检查 + 自动补齐。启动时跑一次；离线（planned 为空）时「重试」也复用它。
    fun check() {
        val api = QookixApi(bridge)
        state = state.copy(checking = true)
        api.getPluginSetupStatus { r ->
            r.onSuccess { o ->
                val needed = o.optBoolean("needed", false)
                val dismissed = o.optBoolean("dismissed", false)
                val statusError = o.optText("error")
                if (needed && !dismissed) {
                    val missing = o.optJSONArray("missing")
                    val pairs = (0 until (missing?.length() ?: 0)).mapNotNull { i ->
                        val item = missing?.optJSONObject(i) ?: return@mapNotNull null
                        val name = item.optString("name").takeIf { it.isNotBlank() }
                            ?: return@mapNotNull null
                        item.optString("id") to name
                    }
                    idToName = pairs.filter { it.first.isNotBlank() }.toMap()
                    val names = pairs.map { it.second }
                    state = state.copy(
                        visible = true, planned = names, running = true,
                        checking = false, finished = false, error = null,
                    )
                    api.installRecommendedPlugins { ir ->
                        // 失败项是字符串数组（「名字：原因」），见 afterInstall
                        state = state.afterInstall(names, ir)
                        api.getPluginSetupStatus { s ->
                            s.onSuccess { o2 ->
                                val err = o2.optText("error")
                                if (!o2.optBoolean("needed", false) && state.failedIds.isEmpty()) {
                                    // 全齐了：留 1.5 秒让用户看到「全部就绪」再收起
                                    android.os.Handler(android.os.Looper.getMainLooper())
                                        .postDelayed({ state = state.copy(visible = false) }, 1500)
                                } else if (err != null && state.failedIds.isEmpty()) {
                                    // 还缺东西但复核时清单拉不到（刚断网）：
                                    // 别把「查不了」当成「已就绪」直接关弹窗
                                    state = state.copy(error = err)
                                }
                            }
                        }
                    }
                } else if (statusError != null && !dismissed) {
                    // 首启离线：清单都拿不到，后端只能返回 needed=false + error。
                    // 以前这种情况**什么都不弹**，用户后来启动游戏缺渲染器也不知道原因。
                    // 只有「一个插件都没装过」才打扰 —— 装过的人离线启动不该弹窗。
                    api.getPlugins { pr ->
                        val installed = pr.getOrNull()?.length() ?: 0
                        state = if (installed == 0) {
                            state.copy(
                                visible = true, checking = false, running = false,
                                finished = false, planned = emptyList(), error = statusError,
                            )
                        } else {
                            state.copy(checking = false)
                        }
                    }
                } else {
                    state = state.copy(checking = false)
                }
            }
            r.onFailure { e -> state = state.copy(checking = false, error = e.message) }
        }
    }

    LaunchedEffect(bridgeReady) {
        if (bridgeReady) check()
    }

    FirstRunSetup(
        state = state,
        onStart = {
            if (state.planned.isEmpty()) {
                // 上次连清单都没拉到 → 重试要重新检查清单，
                // 而不是对着空列表原地「安装」
                state = state.copy(error = null)
                check()
            } else {
                state = state.copy(
                    running = true, finished = false,
                    doneIds = emptyList(), failedIds = emptyList(),
                    failureDetails = emptyList(), error = null,
                )
                // ⚠️ 2026-10-17 修（审计报告 H-1）：
                // 原来这里传的是**空回调** `{ }` —— running 永远不会复位，
                // 而「稍后」按钮 enabled=!running、「重试」按钮显示条件含 !running，
                // 两个都消失，进度条永远转 → 界面**永久卡死**，只能杀进程。
                val api = QookixApi(bridge)
                val planned = state.planned
                api.installRecommendedPlugins { ir ->
                    state = state.afterInstall(planned, ir)
                }
            }
        },
        onDismiss = {
            QookixApi(bridge).dismissPluginSetup { }
            state = state.copy(visible = false, error = null)
        },
    )
}
