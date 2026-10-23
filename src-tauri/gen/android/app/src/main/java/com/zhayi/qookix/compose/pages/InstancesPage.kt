package com.zhayi.qookix.compose.pages

import com.zhayi.qookix.compose.theme.QkRadius

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.LocalIndication
import com.zhayi.qookix.compose.motion.pressScale
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zhayi.qookix.R

/**
 * 实例列表页。
 *
 * 对应旧界面 views/InstancesView.vue + components/InstanceCard.vue。
 *
 * 保持等价的行为：
 *   - 「新建实例」按钮放在**页面内容里**（旧实现的注释明确说明：
 *     挂在顶栏会让账号块左右跳，所以改到内容区）
 *   - 实例按分组展示，未归组的单列一节
 *   - 点击进入实例详情
 *   - 空态提示
 *
 * 本页不需要后端调用（数据来自 instances store，已在启动时加载）。
 *
 * ── 2026-10-17 补记：分组接线与「共享占用卡」两条遗留 ──────────────────
 *
 * **① 分组（P2）已接线 —— 但不在本文件，而在 `PageHost.kt`。**
 * 本页的 5 个 group 相关函数/字段（`InstanceSection`、`InstancesUiState.sections`
 * 以及下面 [SectionHeader] 对应的分节渲染）本身是**完备**的 —— 缺的从来不是渲染，
 * 而是上游只塞了一节硬编码的「全部」进去（`PageHost.kt` 原来的
 * `listOf(InstanceSection(key = "all", name = "全部", items = instances))`）。
 *
 * 所以修法在 `PageHost`：拉 `list_instance_groups` + 读 `list_instances` 每项的
 * `group` 字段，拼出「每个分组一节 + 未分组一节」，没有分组时退化成原来的单节。
 * 详见 `PageHost.kt` 里 `instanceSections` 的注释。
 *
 * [InstanceUi.groupId] 就是为此新增的字段 —— **注意它不由 [InstancesRepo] 填充**
 *（那个解析器只取展示字段，且不在本次可改范围内），而是由 `PageHost` 从原始 JSON
 * 映射进来后 `copy()` 上去的。因此在本文件内读到 `groupId == null` 只代表
 * 「未归组或未经 PageHost 装饰」，不要据此断言后端没存分组。
 *
 * **② 共享占用卡（P1）已接线 —— 同样在 `PageHost`，本文件不涉及。**
 * `InstanceDetailPage.kt:145` 的 `sharedUsage: SharedUsageUi? = null` 原先在调用点
 * 从未赋值（有默认值所以能编译），卡片恒不渲染。现由 `PageHost` 的
 * `InstanceDetailRoute` 用 `QookixApi.sharedGameUsage`（后端 `shared_game_usage`，
 * 见 `instance_files.rs:427`）取数并传入。数据源本就齐备，**不是缺数据源**。
 *
 * 记录在此是因为 `InstanceDetailPage.kt` 当时不在可改文件清单里 —— 实际改动
 * 全部落在其**调用点** `PageHost.kt`，本文件仅作留档。
 */
@Composable
fun InstancesPage(
    state: InstancesUiState,
    onCreate: () -> Unit,
    onOpen: (String) -> Unit,
    /** 直接启动实例（卡片上的播放按钮）。 */
    onLaunch: (String) -> Unit = {},
    /** 删除实例（长按卡片触发菜单后调用）。 */
    onDelete: (InstanceUi) -> Unit = {},
    /** 分组管理（2026-10-21 补：后端命令一直没 UI）。 */
    onCreateGroup: (String) -> Unit = {},
    onRenameGroup: (String, String) -> Unit = { _, _ -> },
    onDeleteGroup: (String) -> Unit = {},
    onMoveGroup: (Int, Int) -> Unit = { _, _ -> }, // (fromIndex, toIndex)
    pinnedIds: Set<String> = emptySet(),
    onTogglePin: (String) -> Unit = {},
) {
    // 长按选中的实例（非空 = 弹出操作菜单）
    var menuTarget by remember { mutableStateOf<InstanceUi?>(null) }
    var deleteTarget by remember { mutableStateOf<InstanceUi?>(null) }
    // 分组管理对话框（2026-10-21 补）
    var showNewGroupDialog by remember { mutableStateOf(false) }
    var renameGroup by remember { mutableStateOf<Pair<String, String>?>(null) } // (id, oldName)
    var deleteGroupId by remember { mutableStateOf<String?>(null) }
    // 真实分组（排除 all / __ungrouped__）的 key 列表，用于判断 SectionHeader 是否显示管理按钮
    val realGroupKeys = state.sections.map { it.key }.filter { it != "all" && it != "__ungrouped__" }
    Column(modifier = Modifier.fillMaxSize()) {
        // 新建按钮：右对齐放在内容区顶部（与旧实现的位置语义一致）
        // ⚠️ 2026-10-18 改：原来「新建实例」按钮占页面内容区一整行（横屏 384dp
        // 高度里白吃掉约 56dp）。现在移到顶栏 actions 里，和标题、账号同一行 ——
        // 用户要求「能不能把它放到标题那一块，这样能省一点位置」。
        // 按钮的渲染在 ComposeMainActivity 的 LauncherShell(actions = ...)。

        if (state.sections.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.instances_empty),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.instances_empty_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 新建分组按钮（2026-10-21 补）
            item(key = "new-group") {
                TextButton(onClick = { showNewGroupDialog = true }) {
                    Icon(Icons.Filled.Add, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.instances_group_new))
                }
            }
            state.sections.forEachIndexed { sIdx, section ->
                val isRealGroup = section.key != "all" && section.key != "__ungrouped__"
                item(key = "h-${section.key}") {
                    SectionHeader(
                        name = section.name,
                        count = section.items.size,
                        canManage = isRealGroup,
                        canMoveUp = isRealGroup && sIdx > 0,
                        canMoveDown = isRealGroup && sIdx < state.sections.lastIndex,
                        onRename = { renameGroup = section.key to section.name },
                        onDelete = { deleteGroupId = section.key },
                        onMoveUp = { onMoveGroup(sIdx, sIdx - 1) },
                        onMoveDown = { onMoveGroup(sIdx, sIdx + 1) },
                    )
                }
                items(section.items, key = { it.id }) { inst ->
                    InstanceCard(
                        inst = inst,
                        onClick = { onOpen(inst.id) },
                        onLongClick = { menuTarget = inst },
                        onLaunch = { onLaunch(inst.id) },
                    )
                }
                item(key = "s-${section.key}") { Spacer(Modifier.height(4.dp)) }
            }
        }
    }

    // ── 长按操作菜单 ──────────────────────────────────────────────────
    //
    // 目前只有「删除」一项，但仍用菜单而不是直接弹确认框：
    //   1. 长按是「我想对这个项做点什么」的意图，先给菜单更符合预期
    //      （万一手滑长按，看到菜单可以直接点别处取消，不会直接进删除确认）
    //   2. 将来加「重命名 / 复制 / 导出」时不用改交互
    menuTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { menuTarget = null },
            title = { Text(target.name) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.instances_action_prompt),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        menuTarget = null
                        onTogglePin(target.id)
                    }) {
                        Text(
                            if (target.id in pinnedIds) stringResource(R.string.instances_unpin)
                            else stringResource(R.string.instances_pin)
                        )
                    }
                    TextButton(onClick = {
                        menuTarget = null
                        onOpen(target.id)
                    }) { Text(stringResource(R.string.instances_open)) }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    menuTarget = null
                    deleteTarget = target
                }) {
                    Text(stringResource(R.string.instances_delete), color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }

    // ── 删除确认 ──────────────────────────────────────────────────────
    //
    // ⚠️ 删除实例是**不可撤销**的，而且会连同该实例的存档一起删。
    // 所以文案里必须说清楚「存档会一起删」—— 用户可能以为只删配置。
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.instances_delete_title, target.name)) },
            text = {
                Text(stringResource(R.string.instances_delete_message))
            },
            confirmButton = {
                TextButton(onClick = {
                    val t = target
                    deleteTarget = null
                    onDelete(t)
                }) {
                    Text(stringResource(R.string.instances_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.instances_cancel)) }
            },
        )
    }

    // ── 分组管理对话框（2026-10-21 补）──────────────────────────────
    if (showNewGroupDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewGroupDialog = false },
            title = { Text(stringResource(R.string.instances_group_new)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.instances_group_name_hint)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) onCreateGroup(name.trim())
                    showNewGroupDialog = false
                }) { Text(stringResource(R.string.instances_group_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showNewGroupDialog = false }) { Text(stringResource(R.string.instances_group_cancel)) }
            },
        )
    }
    renameGroup?.let { pair ->
        val id = pair.first
        val oldName = pair.second
        var name by remember(oldName) { mutableStateOf(oldName) }
        AlertDialog(
            onDismissRequest = { renameGroup = null },
            title = { Text(stringResource(R.string.instances_group_rename)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.instances_group_name_hint)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) onRenameGroup(id, name.trim())
                    renameGroup = null
                }) { Text(stringResource(R.string.instances_group_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { renameGroup = null }) { Text(stringResource(R.string.instances_group_cancel)) }
            },
        )
    }
    deleteGroupId?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteGroupId = null },
            title = { Text(stringResource(R.string.instances_group_delete)) },
            text = { Text(stringResource(R.string.instances_group_delete)) },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteGroup(id)
                    deleteGroupId = null
                }) { Text(stringResource(R.string.instances_group_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteGroupId = null }) { Text(stringResource(R.string.instances_group_cancel)) }
            },
        )
    }
}

@Composable
private fun SectionHeader(
    name: String,
    count: Int,
    canManage: Boolean = false,
    canMoveUp: Boolean = false,
    canMoveDown: Boolean = false,
    onRename: () -> Unit = {},
    onDelete: () -> Unit = {},
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
        )
        if (canManage) {
            Spacer(Modifier.weight(1f))
            if (canMoveUp) {
                IconButton(onClick = onMoveUp, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.KeyboardArrowUp, null, modifier = Modifier.size(18.dp))
                }
            }
            if (canMoveDown) {
                IconButton(onClick = onMoveDown, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.KeyboardArrowDown, null, modifier = Modifier.size(18.dp))
                }
            }
            IconButton(onClick = onRename, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.Edit, null, modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.DeleteOutline, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun InstanceCard(
    inst: InstanceUi,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onLaunch: () -> Unit = {},
) {
    // 按下缩放：combinedClickable 必须显式接收这个 source，
    // 否则它内部自建一个，pressScale 永远收不到按压事件
    val pressSource = remember { MutableInteractionSource() }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(pressSource)
            // 长按出操作菜单 —— 与文件管理器的交互保持一致
            // （MD3 列表里「长按 = 更多操作」是通用约定；点按仍是进入）
            .combinedClickable(
                interactionSource = pressSource,
                indication = LocalIndication.current,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 实例图标位（旧实现是图标或首字母色块）
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(QkRadius.medium)),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(QkRadius.medium),
                    color = parseHexColor(inst.colorHex)
                        ?: MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = inst.name.take(1).uppercase(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = inst.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = inst.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }

            IconButton(onClick = onLaunch) {
                Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.instances_launch))
            }
        }
    }
}

/** 解析 "#RRGGBB" 形式的颜色；失败返回 null。 */
private fun parseHexColor(hex: String?): Color? {
    if (hex.isNullOrBlank()) return null
    return try {
        val s = hex.removePrefix("#")
        val v = s.toLong(16)
        when (s.length) {
            6 -> Color(0xFF000000L or v)
            8 -> Color(v)
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * 实例（UI 模型，对应旧 types.ts 的 Instance 关键字段）。
 *
 * [groupId] 是实例所属分组（对应后端 `MinecraftProfile.group`）——
 * 为空表示未归组。它**不由** [InstancesRepo] 填充（那个解析器只取
 * 展示用字段），而是由 `PageHost` 在构造分组时从原始 JSON 取。
 */
data class InstanceUi(
    val id: String,
    val name: String,
    val subtitle: String,
    val colorHex: String? = null,
    val groupId: String? = null,
)

data class InstanceSection(
    val key: String,
    val name: String,
    val items: List<InstanceUi>,
)

data class InstancesUiState(
    val sections: List<InstanceSection> = emptyList(),
)





