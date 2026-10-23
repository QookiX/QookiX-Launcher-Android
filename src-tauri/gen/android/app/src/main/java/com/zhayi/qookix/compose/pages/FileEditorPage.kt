package com.zhayi.qookix.compose.pages

import com.zhayi.qookix.compose.theme.QkSpacing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.zhayi.qookix.R

/**
 * 纯文本文件编辑器。
 *
 * ── 设计取舍 ────────────────────────────────────────────────────────────
 * 旧界面用 `CodeEditor.vue`（14 KB，带语法高亮）。这里**故意不做高亮**：
 *   - 启动器里编辑的都是 `server.properties` / `options.txt` / 配置 JSON，
 *     纯文本足够用
 *   - 高亮要引入完整的语法解析器（体积 + 复杂度），收益不抵成本
 *   - 高亮在手机小屏上反而不如等宽字体好读
 *
 * 保留的实用能力：等宽字体、行号不显示（窄屏更省空间）、
 * 未保存提示、Ctrl+S 式的手动保存按钮。
 */
@Composable
fun FileEditorPage(
    state: FileEditorUiState,
    onContentChange: (String) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // ── 顶栏 ──────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = QkSpacing.sm, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.file_back))
            }

            Column(modifier = Modifier.weight(1f)) {
                val unsavedLabel = stringResource(R.string.file_editor_unsaved)
                Text(
                    text = state.fileName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(fmtBytes(state.size))
                        if (state.dirty) append(" · ").append(unsavedLabel)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.dirty) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.dirty) {
                TextButton(onClick = { /* 放弃修改 = 返回时确认 */ }) {
                    Text(stringResource(R.string.file_editor_modified))
                }
            }

            Button(
                onClick = onSave,
                enabled = state.dirty && !state.saving,
            ) {
                Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (state.saving) stringResource(R.string.file_editor_saving)
                    else stringResource(R.string.file_editor_save),
                )
            }
        }

        // ── 编辑区 ────────────────────────────────────────────────────
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = QkSpacing.sm, vertical = 8.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
        ) {
            when {
                state.loading -> Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                }

                state.error != null -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(QkSpacing.xl),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.file_editor_open_failed),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(QkSpacing.xs))
                    Text(
                        text = state.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> BasicTextField(
                    value = state.content,
                    onValueChange = onContentChange,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(QkSpacing.md),
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

/** 编辑器状态。 */
data class FileEditorUiState(
    val rel: String = "",
    val fileName: String = "",
    val content: String = "",
    /**
     * 打开时的原始内容 —— 与 [content] 比较来判断是否「已修改」。
     *
     * 不能只用一个 `dirty: Boolean`：用户改了又改回来时应该恢复成「未修改」，
     * 否则保存按钮会一直亮着，或者用户以为没改却提示未保存。
     */
    val originalContent: String = "",
    val size: Long = 0L,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val dirty: Boolean = false,
    val error: String? = null,
)
