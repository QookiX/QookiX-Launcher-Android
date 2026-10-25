package com.zhayi.qookix.compose.pages.settings

import com.zhayi.qookix.compose.theme.QkSpacing

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.zhayi.qookix.R

/**
 * 代理设置区（嵌在「下载」子页里）。
 *
 * 对应旧界面 `SettingsView.vue` 的代理分段 + 测试按钮。
 *
 * ── 为什么放在「下载」页 ────────────────────────────────────────────────
 * 代理影响的是**所有网络请求**（版本清单、模组下载、新闻、更新检查），
 * 而其中下载是最常受影响的场景。放这里用户找得到。
 *
 * ── 三种模式 ────────────────────────────────────────────────────────────
 *
 * | 模式 | 行为 |
 * |---|---|
 * | 跟随系统 | 读系统 HTTP 代理（Rust 通过宿主回调拿）|
 * | 直连 | 不走代理 |
 * | 自定义 | 用户填 `http://host:port` 或 `socks5://host:port` |
 *
 * ── 关于「自定义模式必须填地址」────────────────────────────────────────
 * 旧实现的注释里明确记了：**自定义模式但地址为空时，后端会退化为直连
 * 而误报成功** —— 用户以为代理配好了，实际没走。
 * 所以这里在点测试前先拦住。
 */
@Composable
fun ProxySection(
    mode: String,
    address: String,
    detecting: Boolean,
    testing: Boolean,
    result: String?,
    error: String?,
    onModeChange: (String) -> Unit,
    onAddressChange: (String) -> Unit,
    onDetect: () -> Unit,
    onTest: () -> Unit,
) {
    SettingsGroup(stringResource(R.string.settings_proxy_group)) {
        SettingsRow(
            title = stringResource(R.string.settings_proxy_mode),
            subtitle = ProxyMode.entries.firstOrNull { it.id == mode }?.let {
                stringResource(it.hintRes)
            } ?: "",
            column = true,
        ) {
            // 加横向滚动：窄屏下 chip 会溢出，后面的选项点不到（详见 GeneralPane 的说明）
             FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(QkSpacing.xs),
                    verticalArrangement = Arrangement.spacedBy(QkSpacing.xs),
             ) {
                ProxyMode.entries.forEach { m ->
                    FilterChip(
                        selected = mode == m.id,
                        onClick = { onModeChange(m.id) },
                        label = { Text(stringResource(m.labelRes)) },
                    )
                }
            }
        }

        // 自定义模式才显示地址输入 —— 其它模式填了也不生效，
        // 显示出来会让用户以为要填
        if (mode == "custom") {
            Column(modifier = Modifier.padding(horizontal = QkSpacing.md, vertical = 4.dp)) {
                OutlinedTextField(
                    value = address,
                    onValueChange = onAddressChange,
                    label = { Text(stringResource(R.string.settings_proxy_address)) },
                    placeholder = { Text("http://127.0.0.1:7890") },
                    singleLine = true,
                    supportingText = {
                        Text(
                            text = stringResource(R.string.settings_proxy_address_support),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                )
            }
        }

        SettingsRow(
            title = stringResource(R.string.settings_proxy_test_title),
            subtitle = stringResource(R.string.settings_proxy_test_desc),
            showDivider = false,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(QkSpacing.xxs)) {
                // 「跟随系统」时给个探测按钮 —— 用户不知道系统代理是什么，
                // 探测出来能显示实际读到的地址
                if (mode == "system") {
                    TextButton(onClick = onDetect, enabled = !detecting && !testing) {
                        Text(
                            if (detecting) stringResource(R.string.settings_proxy_detecting)
                            else stringResource(R.string.settings_proxy_detect)
                        )
                    }
                }
                // 自定义模式但没填地址：测试必然失败，直接禁用。
                //（注释里早就写着要拦，实现里其实没有 —— 点了只会得到一句网络错误）
                val addressMissing = mode == "custom" && address.isBlank()
                TextButton(onClick = onTest, enabled = !testing && !detecting && !addressMissing) {
                    Text(
                        if (testing) stringResource(R.string.settings_proxy_testing)
                        else stringResource(R.string.settings_proxy_test)
                    )
                }
            }
        }

        // ⚠️ 结果紧跟在按钮所在的 SettingsRow 之后，中间不插别的内容。
        //
        // 实测踩到（2026-10-13）：一开始把 result/error 放在整个 section 的
        // **最底部**，而按钮在中间 —— 点「探测」后结果渲染在屏幕外，
        // 表现就是「点了没反应」，排查花了不少时间。
        //
        // 教训：**反馈要放在触发点的正下方**，不要被其它行隔开。
        result?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = QkSpacing.md, vertical = 6.dp),
            )
        }
        error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = QkSpacing.md, vertical = 6.dp),
            )
        }
    }
}

/**
 * 代理模式。
 *
 * `id` 必须与 Rust `resolve_proxy` 认识的值一致
 * （`custom` / `system` / 其余当直连）。
 */
enum class ProxyMode(
    val id: String,
    @StringRes val labelRes: Int,
    @StringRes val hintRes: Int,
) {
    System(
        id = "system",
        labelRes = R.string.settings_proxy_mode_system,
        hintRes = R.string.settings_proxy_mode_system_hint,
    ),
    Direct(
        id = "direct",
        labelRes = R.string.settings_proxy_mode_direct,
        hintRes = R.string.settings_proxy_mode_direct_hint,
    ),
    Custom(
        id = "custom",
        labelRes = R.string.settings_proxy_mode_custom,
        hintRes = R.string.settings_proxy_mode_custom_hint,
    ),
}




