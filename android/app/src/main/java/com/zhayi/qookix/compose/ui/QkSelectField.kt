package com.zhayi.qookix.compose.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow

/** 下拉选项：key 给回调，label 给人看。 */
data class QkSelectOption(val key: String, val label: String)

/**
 * 下拉选择框（只读输入框 + 展开菜单）。
 *
 * 2026-10-27：用户明确说「不要做成一堆一堆按钮，要下拉选择框那种感觉」——
 * 安装目标、版本类型、游戏版本原来各是一排 FilterChip，三排堆在一起像控制面板。
 * 现在统一成这个控件，一处实现（内容中心的安装对话框和详情页面板共用）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QkSelectField(
    label: String,
    /** 当前选中项的显示文本（空 = 未选）。 */
    value: String,
    options: List<QkSelectOption>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = open,
        onExpandedChange = { if (enabled && options.isNotEmpty()) open = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier
                // menuAnchor 让菜单贴着这个输入框展开
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(
                    text = { Text(o.label) },
                    onClick = {
                        onSelect(o.key)
                        open = false
                    },
                )
            }
        }
    }
}
