package com.zhayi.qookix.compose.pages.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhayi.qookix.R

/**
 * 设置页的通用对话框组件。
 *
 * ── 为什么集中在一处 ────────────────────────────────────────────────────
 * 设置页有 8 个子页、几十个「点一下弹个框改个值」的交互
 * （JVM 参数、游戏参数、Java 路径、清单地址、翻译 API 配置…）。
 * 每个都手写一遍 AlertDialog 会产生大量重复，且行为容易不一致
 * （有的能取消、有的不能；有的显示原值、有的显示空）。
 *
 * 这里抽三个：
 *   - [TextInputDialog]  —— 单行/多行文本编辑
 *   - [InfoDialog]       —— 只读结果展示（存储统计、测试结果）
 *   - [ConfirmDialog]    —— 二次确认（清缓存、卸载插件这类破坏性操作）
 *
 * ── 关于「破坏性操作为什么要二次确认」────────────────────────────────────
 * 清缓存、卸载插件、迁移游戏目录都是**不可撤销**的，而且用户在设置页
 * 是「随便点点」的心态。少一次确认就可能让人丢掉刚下好的整合包。
 */

/**
 * 单行/多行文本编辑对话框。
 *
 * @param title 标题
 * @param initial 初始值
 * @param label 输入框标签
 * @param multiline 是否多行（JVM 参数、游戏参数用多行更合适）
 * @param confirmText 确认按钮文案
 * @param helperText 输入框下方的说明（可选）
 */
@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    label: String,
    confirmText: String = "",
    multiline: Boolean = false,
    helperText: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    // 空串 = 用本地化的「保存」。不用 `= stringResource(...)` 作默认值，
    // 免得依赖「@Composable 默认参数表达式」的编译器细则。
    val confirmLabel = confirmText.ifBlank { stringResource(R.string.dialog_save) }
    // remember(initial)：换目标时重置（同一个对话框复用不同字段时很重要）
    var value by remember(initial) { mutableStateOf(initial) }

    AlertDialog(
        // ⚠️ 有未保存的修改时，不允许点外部/返回键直接关掉（2026-10-17 修）。
        //
        // 这个对话框编辑的是 JVM 参数、游戏参数、别名这类**可能很长**的文本，
        // 原来是 `onDismissRequest = onDismiss` —— 手指一滑点到外面，
        // 刚敲的一大段参数全丢，而且没有任何提示。
        //
        // 现在：没改过就直接关（无损失）；改过就要求用户用「取消」明确放弃。
        onDismissRequest = { if (value == initial) onDismiss() },
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(label) },
                    singleLine = !multiline,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (multiline) {
                                Modifier
                                    .heightIn(min = 120.dp, max = 260.dp)
                                    .verticalScroll(rememberScrollState())
                            } else Modifier
                        ),
                    textStyle = if (multiline) {
                        MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                        )
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                )
                if (helperText.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = helperText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.trim()) }) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}

/**
 * 只读信息对话框（存储统计、测试结果这类）。
 *
 * 内容用等宽字体 —— 这些结果大多是「键: 值」的清单，
 * 等宽能让数字对齐，比比例字体好读。
 */
@Composable
fun InfoDialog(
    title: String,
    body: String,
    loading: Boolean = false,
    error: String? = null,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            when {
                loading -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.width(28.dp).height(28.dp))
                    Spacer(Modifier.width(14.dp))
                    Text(
                        text = stringResource(R.string.dialog_reading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                error != null -> Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )

                else -> Text(
                    text = body.ifBlank { stringResource(R.string.dialog_no_content) },
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    ),
                    modifier = Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_close)) }
        },
    )
}

/**
 * 二次确认对话框（破坏性操作用）。
 *
 * `danger = true` 时确认按钮标红 —— 视觉上给用户一个「这不可撤销」的提示。
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    // ⚠️ 没有默认值 —— 强制调用方写明动作（「删除」「清除」「覆盖恢复」…）。
    // 默认「确定」会让用户看不清这一下到底会做什么，是危险操作里的坏实践。
    confirmText: String,
    danger: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirmText,
                    color = if (danger) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                    fontWeight = if (danger) FontWeight.Bold else FontWeight.Normal,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}

/** 把 JSON 对象格式化成等宽可读的多行文本（给 InfoDialog 用）。 */
fun formatJsonForDisplay(json: org.json.JSONObject, indent: Int = 0): String {
    val pad = "  ".repeat(indent)
    return buildString {
        json.keys().asSequence().sorted().forEach { k ->
            val v = json.opt(k)
            when (v) {
                is org.json.JSONObject -> {
                    append(pad).append(k).append(":\n")
                    append(formatJsonForDisplay(v, indent + 1))
                }
                is org.json.JSONArray -> {
                    append(pad).append(k).append(": ").append(v.toString()).append('\n')
                }
                else -> append(pad).append(k).append(": ").append(v?.toString() ?: "—").append('\n')
            }
        }
    }.trimEnd()
}

/** 字节数的中文可读格式（存储统计用）。 */
fun humanBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) {
        v /= 1024
        i++
    }
    return if (i == 0) "${bytes} B" else String.format("%.1f %s", v, units[i])
}
