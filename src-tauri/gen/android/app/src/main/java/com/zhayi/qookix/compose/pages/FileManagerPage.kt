package com.zhayi.qookix.compose.pages

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.zhayi.qookix.R
import com.zhayi.qookix.compose.ui.SkeletonList

/**
 * 文件管理器（实例文件 / 服务器文件共用）。
 *
 * 对应旧界面 components/FileManager.vue（37 KB）与
 * ServerFileManager.vue（28 KB）—— 两者操作**同一套 FsEntry 结构**
 * （name / rel / size / modified / is_dir / ext，见 `fsutil.rs`），
 * 只是底层命令不同（`*_instance_*` vs `*_hosted_server_*`）。
 *
 * 所以这里做成**一个页面 + 一个 FileOps 抽象**，而不是两份代码 ——
 * 旧界面那两份是复制粘贴出来的，行为已经出现细微差异（注释里提过）。
 *
 * ── 设计（完全重做）─────────────────────────────────────────────────────
 *   ┌──────────────────────────────────────────────────┐
 *   │ [↑]  /  config  /  servers            [新建▾][⋮] │  ← 面包屑 + 操作
 *   ├──────────────────────────────────────────────────┤
 *   │ 📁 world                                         │
 *   │ 📁 plugins                                       │
 *   │ 📄 server.properties          834 B   2 天前     │  ← 列表
 *   │ 📄 eula.txt                    159 B   2 天前     │
 *   └──────────────────────────────────────────────────┘
 *
 * **面包屑可点击回退** —— 比旧界面的「返回上级」按钮更快到任意层。
 * **长按出菜单**（重命名/删除）—— MD3 的列表交互惯例。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileManagerPage(
    state: FileManagerUiState,
    onOpenDir: (String) -> Unit,
    onOpenFile: (FileEntryUi) -> Unit,
    onBreadcrumb: (String) -> Unit,
    onGoUp: () -> Unit,
    onCreate: (name: String, isDir: Boolean) -> Unit,
    onRename: (FileEntryUi, String) -> Unit,
    onDelete: (FileEntryUi) -> Unit,
    onRefresh: () -> Unit,
    /**
     * 外部给的尺寸修饰符。
     *
     * ⚠️ 必须有这个参数：调用方把它放在 `Column` 里时，
     * 需要用 `Modifier.weight(1f)` 才能拿到「剩余高度」。
     * 没有它的话，本页会被上方内容挤成 0 高 ——
     * 表现是「文件列表永远空」，但后端其实正常返回了数据。
     */
    modifier: Modifier = Modifier,
    /**
     * 返回上一层的回调（null = 不显示返回箭头）。
     *
     * ⚠️ 2026-10-17 加：独立文件浏览页原来**完全没有返回入口** ——
     * 顶部只有「上一级」（那是在目录树里往上走，不是退出浏览页）。
     * 用户只能按系统返回键，而系统返回走的是全局 nav handler，
     * 它不知道文件浏览这一层的存在 → 直接跳回首页。
     */
    onBack: (() -> Unit)? = null,
) {
    var showCreate by remember { mutableStateOf(false) }
    var createAsDir by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FileEntryUi?>(null) }
    var deleteTarget by remember { mutableStateOf<FileEntryUi?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        // ── 面包屑 + 操作 ──────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onGoUp,
                enabled = state.path.isNotBlank(),
            ) {
                Icon(Icons.Filled.ArrowUpward, contentDescription = stringResource(R.string.file_up_level))
            }

            // 面包屑：横向可滚（路径深时不会挤爆）
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScrollable(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BreadcrumbChip("/", isLast = state.path.isBlank()) { onBreadcrumb("") }
                var acc = ""
                state.path.split('/').filter { it.isNotBlank() }.forEach { seg ->
                    acc = if (acc.isBlank()) seg else "$acc/$seg"
                    val target = acc
                    Text(
                        text = "/",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(horizontal = 2.dp),
                    )
                    BreadcrumbChip(seg, isLast = target == state.path) { onBreadcrumb(target) }
                }
            }

            IconButton(onClick = { createAsDir = true; showCreate = true }) {
                Icon(Icons.Filled.CreateNewFolder, contentDescription = stringResource(R.string.file_new_folder))
            }
            IconButton(onClick = { createAsDir = false; showCreate = true }) {
                Icon(Icons.Filled.NoteAdd, contentDescription = stringResource(R.string.file_new_file))
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.file_refresh))
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // ── 列表 ──────────────────────────────────────────────────────
        when {
            state.loading && state.entries.isEmpty() ->
                Column(modifier = Modifier.padding(20.dp)) {
                    SkeletonList(rows = 6, rowHeight = 52.dp)
                }

            state.error != null ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = state.error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

            state.entries.isEmpty() ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.file_empty_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(state.entries, key = { it.rel }) { e ->
                    FileRow(
                        entry = e,
                        onClick = { if (e.isDir) onOpenDir(e.rel) else onOpenFile(e) },
                        onRename = { renameTarget = e },
                        onDelete = { deleteTarget = e },
                    )
                }
            }
        }
    }

    // ── 新建对话框 ────────────────────────────────────────────────────
    if (showCreate) {
        NameDialog(
            title = if (createAsDir) stringResource(R.string.file_new_folder) else stringResource(R.string.file_new_file),
            initial = "",
            confirmText = stringResource(R.string.file_create),
            onDismiss = { showCreate = false },
            onConfirm = { name ->
                showCreate = false
                onCreate(name, createAsDir)
            },
        )
    }

    // ── 重命名对话框 ──────────────────────────────────────────────────
    renameTarget?.let { target ->
        NameDialog(
            title = stringResource(R.string.file_rename),
            initial = target.name,
            confirmText = stringResource(R.string.file_rename),
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                renameTarget = null
                onRename(target, name)
            },
        )
    }

    // ── 删除确认 ──────────────────────────────────────────────────────
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.file_delete_title, target.name)) },
            text = {
                Text(
                    stringResource(
                        if (target.isDir) R.string.file_delete_dir_message
                        else R.string.file_delete_file_message
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    onDelete(target)
                }) {
                    Text(stringResource(R.string.file_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.file_cancel)) }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    entry: FileEntryUi,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var menu by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .combinedClickable(
                    onClick = onClick,
                    // 长按出菜单 —— MD3 列表的常规交互
                    onLongClick = { menu = true },
                )
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (entry.isDir) Icons.Filled.Folder
                else Icons.AutoMirrored.Filled.InsertDriveFile,
                contentDescription = null,
                tint = if (entry.isDir) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!entry.isDir && entry.subtitle(ctx).isNotBlank()) {
                    Text(
                        text = entry.subtitle(ctx),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                    )
                }
            }

            IconButton(onClick = { menu = true }) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.file_more),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.file_rename)) },
                onClick = { menu = false; onRename() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.file_delete), color = MaterialTheme.colorScheme.error) },
                onClick = { menu = false; onDelete() },
            )
        }
    }
    HorizontalDivider(
        modifier = Modifier.padding(start = 52.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun BreadcrumbChip(text: String, isLast: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = if (isLast) MaterialTheme.colorScheme.secondaryContainer
        else androidx.compose.ui.graphics.Color.Transparent,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal,
            color = if (isLast) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }

    AlertDialog(
        // ⚠️ 改过内容就别让点外部关掉（2026-10-17 修，同类第 3 处）
        onDismissRequest = { if (value == initial) onDismiss() },
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text(stringResource(R.string.file_name)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (value.isNotBlank()) onConfirm(value.trim()) },
                enabled = value.isNotBlank(),
            ) { Text(confirmText) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.file_cancel)) }
        },
    )
}

/** 横向滚动的小包装。
 *
 * ⚠️ 必须标 @Composable 且标 @ReadOnlyComposable 之外无副作用 ——
 * 它内部调用 rememberScrollState()，那是个 composable 函数，
 * 放进普通扩展函数会报「Functions which invoke @Composable functions
 * must be marked with the @Composable annotation」。
 */
@Composable
private fun Modifier.horizontalScrollable(): Modifier =
    this.horizontalScroll(rememberScrollState())

/** 文件/目录条目（UI 模型，对应 `fsutil.rs::FsEntry`）。 */
data class FileEntryUi(
    val name: String,
    val rel: String,
    val size: Long,
    val modified: Long,
    val isDir: Boolean,
    val ext: String,
) {
    /** 副标题：「834 B · 2 天前」。 */
    /** 副标题：「834 B · 2 天前」（需要 Context，所以在 UI 层调用）。 */
    fun subtitle(ctx: android.content.Context): String = buildString {
        append(fmtBytes(size))
        val ago = fmtRelative(ctx, modified)
        if (ago.isNotBlank()) append(" · ").append(ago)
    }
}

/** 文件管理器的 UI 状态。 */
data class FileManagerUiState(
    val title: String = "",
    val path: String = "",
    val entries: List<FileEntryUi> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * 相对时间（「刚刚」「3 小时前」「2 天前」）。
 *
 * ⚠️ **参数是「秒」不是「毫秒」** —— 这是本项目所有时间字段的统一单位。
 *
 * 实测踩到（2026-10-13）：文件列表显示「690 个月前」。
 * 原因是我按毫秒写（`System.currentTimeMillis() - millis`），
 * 而 Rust 侧 `fsutil::modified_secs` 返回的是**秒**
 * （`meta.modified()` 的 `duration_since(UNIX_EPOCH).as_secs()`）。
 *
 * 两者相减 ≈ 1.79e12「毫秒」≈ 57 年 → 显示成 690 个月。
 *
 * **本项目所有时间戳都是「秒」**：
 *   - `fsutil::modified_secs` → 文件修改时间
 *   - `MinecraftProfile::last_played` / `created` / `total_play_time`
 *   - `Account::created`
 * 旧前端也是按秒处理的（`new Date(sec * 1000)`）。
 */
internal fun fmtRelative(ctx: android.content.Context, seconds: Long): String {
    if (seconds <= 0L) return ""
    val diff = System.currentTimeMillis() / 1000 - seconds
    if (diff < 0) return ""
    val min = diff / 60
    return when {
        min < 1 -> ctx.getString(R.string.rel_just_now)
        min < 60 -> ctx.getString(R.string.rel_minutes, min)
        min < 60 * 24 -> ctx.getString(R.string.rel_hours, min / 60)
        min < 60 * 24 * 30 -> ctx.getString(R.string.rel_days, min / (60 * 24))
        min < 60 * 24 * 365 -> ctx.getString(R.string.rel_months, min / (60 * 24 * 30))
        else -> ctx.getString(R.string.rel_years, min / (60 * 24 * 365))
    }
}






