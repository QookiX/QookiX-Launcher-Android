package com.zhayi.qookix.compose.pages

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.annotation.StringRes
import com.zhayi.qookix.R
import com.zhayi.qookix.compose.ui.QkFieldLabel
import com.zhayi.qookix.compose.ui.QkListHeader
import com.zhayi.qookix.compose.ui.QkVersionRow
import com.zhayi.qookix.compose.ui.SkeletonList

/**
 * 新建实例。
 *
 * 对应旧界面 views/CreateInstanceView.vue。
 *
 * ── 设计（完全重做）─────────────────────────────────────────────────────
 * 旧界面是「一个长表单 + 底部按钮」。这里改成横屏下更合理的**左右分栏**：
 *
 *   ┌──────────────────────┬──────────────────────┐
 *   │ 实例名称              │  版本列表（可滚动）   │
 *   │ [____________]       │  ┌────────────────┐  │
 *   │                      │  │ 1.21.4  正式版 │  │
 *   │ 加载器                │  │ 1.21.3  正式版 │  │
 *   │ [原版][Fabric][Forge]│  │ …             │  │
 *   │                      │  └────────────────┘  │
 *   │ 内存                  │                      │
 *   │ [====|=====]  4096MB │                      │
 *   ├──────────────────────┴──────────────────────┤
 *   │                          [取消]  [创建实例] │
 *   └─────────────────────────────────────────────┘
 *
 * 左边填参数、右边选版本 —— 横屏时视线不用上下长距离移动。
 * 竖屏时自然堆叠成单列（Row 换成 Column 由 window size class 决定，
 * 这里先用固定分栏，因为本项目横屏优先）。
 */
@Composable
fun CreateInstancePage(
    state: CreateUiState,
    onNameChange: (String) -> Unit,
    onVersionChange: (String) -> Unit,
    onLoaderChange: (String) -> Unit,
    onMemoryChange: (Int) -> Unit,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    /** 是否处于「导入整合包」模式（由外层持有，切模式要重建表单）。 */
    importMode: Boolean = false,
    /** 切换「全新创建 / 导入整合包」。 */
    onImportModeChange: (Boolean) -> Unit = {},
    /** 选择整合包文件并导入（`.mrpack` / `.zip`）。 */
    onPickModpack: () -> Unit = {},
    /** 导入进度：当前阶段的文案（来自 install://progress 的 message）。 */
    importStage: String = "",
    /** 导入进度：已完成步数 / 总步数（0 表示未知，用不确定进度条）。 */
    importDone: Int = 0,
    importTotal: Int = 0,
    /** 导入进行中。 */
    importing: Boolean = false,
    /**
     * 加载器版本候选（非 vanilla 时由外层拉取）。
     *
     * ⚠️ 必填：留空创建出来的实例，加载器装不上、依赖也解析不出来，
     * 连「补全游戏文件」都没有可下手的版本（旧注释原话）。
     */
    loaderVersions: List<String> = emptyList(),
    /** 加载器版本加载中。 */
    loaderVersionsLoading: Boolean = false,
    /** 加载器版本加载失败原因（没有可选项时显示）。 */
    loaderVersionsError: String? = null,
    onLoaderVersionChange: (String) -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // ⚠️ 2026-10-17：这一整条标题栏（返回箭头 + 「新建实例」+ 取消 + 创建实例）
        // 已经**删掉**，改由外层 LauncherShell 的 TopAppBar 提供：
        //   - 返回箭头 → TopAppBar.navigationIcon（通过 onBack 传入）
        //   - 标题「新建实例」→ TopAppBar.title
        //   - 「创建实例」按钮 → 移到下方表单底部（见文件末尾）
        //
        // 原因：用户实测截图显示**同一页出现两个「新建实例」** ——
        // 外层 TopAppBar 一个、这里自绘一个，视觉上像重复渲染。

        // ── 模式切换（对应旧 UI 的「全新创建 / 导入整合包」两个 chip）────────
        //
        // 为什么两种模式要分开：整合包**自带** MC 版本与加载器，
        // 让用户再手填一遍只会填错（旧 UI 注释原话）。
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = !importMode,
                onClick = { onImportModeChange(false) },
                label = { Text(stringResource(R.string.create_instance_mode_new)) },
            )
            FilterChip(
                selected = importMode,
                onClick = { onImportModeChange(true) },
                label = { Text(stringResource(R.string.create_instance_mode_import)) },
            )
        }

        if (importMode) {
            // ── 导入整合包模式 ────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.create_instance_import_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.create_instance_import_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
                Button(onClick = onPickModpack, enabled = !importing) {
                    if (importing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        if (importing) stringResource(R.string.create_instance_importing)
                        else stringResource(R.string.create_instance_pick_file),
                    )
                }
                // ⚠️ 导入进度必须**在导入页可见**（2026-10-23）：
                // 之前这里只有一个转圈，而导入本身要走「解压 → 下 1.21.1 → 装加载器 → 下 166 个文件」，
                // 期间界面看不出任何变化；更要命的是当时桥接层对它用了 8 秒超时（已修），
                // 失败时界面只是"退回原样"，用户完全无从判断。
                if (importing) {
                    Spacer(Modifier.height(20.dp))
                    val barMod = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp)
                    // ⚠️ 必须去掉 M3 1.3 的「新样式」装饰，否则长这样：一条细线 + 末尾一个小圆点
                    // （stop indicator + 4dp 断口），完全不是用户认得的安卓进度条。
                    // gapSize = 0.dp 去掉断口、drawStopIndicator = null 去掉圆点、圆头收尾。
                    if (importTotal > 0) {
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { importDone.toFloat() / importTotal },
                            modifier = barMod,
                            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                            gapSize = 0.dp,
                            // 这个版本的参数非空 → 传空 lambda 等于「什么装饰都不画」
                            drawStopIndicator = {},
                        )
                    } else {
                        androidx.compose.material3.LinearProgressIndicator(
                            modifier = barMod,
                            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                            gapSize = 0.dp,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = buildString {
                            append(importStage.ifBlank { "正在导入…" })
                            if (importTotal > 0) append("（$importDone/$importTotal）")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            return@Column
        }

        // ⚠️⚠️ 左列宽度必须是**响应式**，不能固定 360dp（2026-10-17 修，真机实测）。
        //
        // 原来写死 `.width(360.dp)`。竖屏可用宽度只有 384dp 时：
        // 左列吃掉 360dp → **右列（919 个版本）只剩 24dp**，一个都渲染不出来，
        // 而左侧文案还写着"从右侧列表选择" —— 用户彻底无法选版本，建不出想要的实例。
        //
        // 现在按屏宽算：窄屏时左列最多占 42%（384dp 屏 → 约 161dp），
        // 右列保住 58%（约 223dp），两边都可用。
        // 宽屏（横屏 853dp）仍用 360dp 的舒适宽度。
        val screenW = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp
        val leftW = if (screenW < 600.dp) (screenW * 0.42f) else 360.dp

        Row(modifier = Modifier.fillMaxSize()) {
            // ── 左：参数 ──────────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .width(leftW)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 12.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    value = state.name,
                    onValueChange = onNameChange,
                    label = { Text(stringResource(R.string.create_instance_name)) },
                    placeholder = { Text(stringResource(R.string.create_instance_name_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                )

                QkFieldLabel(stringResource(R.string.create_instance_game_version))
                Text(
                    text = state.version.ifBlank { stringResource(R.string.create_instance_pick_from_right) },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (state.version.isBlank()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
                // 预估下载体积（2026-10-21 补：estimateDownload 一直没 UI）
                state.estimatedDownloadMb?.let { mb ->
                    Text(
                        text = stringResource(R.string.create_instance_estimated_download, mb),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                QkFieldLabel(stringResource(R.string.create_instance_loader))
                // 加横向滚动：窄屏下 chip 会溢出，后面的选项点不到（详见 GeneralPane 的说明）
                 FlowRow(
                     horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                 ) {
                    Loader.entries.forEach { l ->
                        FilterChip(
                            selected = state.loader == l.id,
                            onClick = { onLoaderChange(l.id) },
                            label = { Text(stringResource(l.labelRes)) },
                        )
                    }
                }

                // ── 加载器版本（非 vanilla 必填）──────────────────────
                if (state.loader != Loader.Vanilla.id) {
                    QkFieldLabel(stringResource(R.string.create_instance_loader_version))
                    when {
                        loaderVersionsLoading -> Text(
                            stringResource(R.string.create_instance_loading),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        loaderVersions.isNotEmpty() -> LoaderVersionSelect(
                            options = loaderVersions,
                            selected = state.loaderVersion,
                            onSelect = onLoaderVersionChange,
                        )
                        else -> Text(
                            loaderVersionsError ?: stringResource(R.string.create_instance_no_loader_version),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                QkFieldLabel(stringResource(R.string.create_instance_memory_limit))
                MemorySlider(state.memoryMb, onMemoryChange)

                // 「创建实例」按钮（2026-10-17 从页头移到这里）。
                //
                // 页头那条自绘标题栏已删（与外层 TopAppBar 重复），
                // 但创建动作必须有入口 —— 放在表单最后一步，符合「填完 → 提交」的顺序。
                //
                // enabled 条件：名称可以留空（留空则用游戏版本号当名字，见下），
                // 所以只需要「选了版本」且「不在创建中」。
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onCreate,
                    enabled = state.version.isNotBlank() && !state.creating,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.creating) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(16.dp).width(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                    } else {
                        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.height(18.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        if (state.creating) stringResource(R.string.create_instance_creating)
                        else stringResource(R.string.create_instance_create),
                    )
                }
            }

            // ── 右：版本列表 ──────────────────────────────────────────────
            Surface(
                modifier = Modifier
                    // ⚠️ weight(1f) 而非 fillMaxSize()：左边是固定 360dp 的表单栏，
                    // fillMaxSize() 会和它抢宽度（竖屏/大缩放时右栏被压成一条）。
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(end = 20.dp, bottom = 20.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column {
                    QkListHeader(
                        title = stringResource(R.string.create_instance_select_version),
                        trailing = if (state.versions.isNotEmpty()) {
                            stringResource(R.string.create_instance_version_count, state.versions.size)
                        } else {
                            null
                        },
                    )

                    when {
                        // 顺序要紧：先判「加载完但为空」再判「加载中」。
                        // 之前只判 loading，导致**加载失败后骨架屏一直转**
                        // （实测：网络不通时列表永远显示骨架，用户以为在加载）。
                        !state.loadingVersions && state.versions.isEmpty() -> Box(
                            // ⚠️ weight(1f)：见下方 LazyColumn 分支的注释
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = stringResource(R.string.create_instance_versions_failed),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = state.error ?: stringResource(R.string.create_instance_check_network),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 24.dp),
                                )
                            }
                        }
                        // 版本清单加载中 → Android 风格波浪 + 说明文字（2026-10-17）
                        state.loadingVersions -> Box(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            com.zhayi.qookix.compose.ui.QkWavyLoading(
                                text = stringResource(R.string.create_instance_loading_versions),
                                modifier = Modifier.padding(horizontal = 24.dp),
                            )
                        }
                        else -> {
                            // ⚠️ 按类型分组（2026-10-17，用户要求「像桌面版那样」）。
                            //
                            // 以前是**一整条平铺的 919 个版本**，用户想找 1.21.8 得滚很久，
                            // 也看不出哪些是正式版、哪些是快照。桌面版分成「正式/快照/愚人节」三段。
                            //
                            // 分组在 LazyColumn **外面**算好（不在 scope 里做 groupBy）——
                            // Kotlin 2.0.21 的 FIR 编译器对 scope 内的嵌套 lambda + forEach
                            // 会内部崩溃（ClassCastException: FirPropertySymbol → FirEnumEntrySymbol），
                            // 挪到外面就绕开了。
                            val releases = state.versions.filter { it.type == "release" }
                            val aprilFools = state.versions.filter {
                                it.type != "release" && (
                                    it.id.contains("_or_", ignoreCase = true) ||
                                        it.id.contains("april", ignoreCase = true)
                                    )
                            }
                            val snapshots = state.versions.filter {
                                it.type != "release" && !aprilFools.contains(it)
                            }
                            LazyColumn(
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                            ) {
                                if (releases.isNotEmpty()) {
                                    item(key = "hdr_release") {
                                        GroupTitle(stringResource(R.string.create_instance_group_release), releases.size)
                                    }
                                    items(releases, key = { it.id }) { v ->
                                        QkVersionRow(
                                            version = v.id,
                                            selected = v.id == state.version,
                                            trailing = stringResource(versionTypeLabelRes(v.type)),
                                            onClick = { onVersionChange(v.id) },
                                        )
                                    }
                                }
                                if (snapshots.isNotEmpty()) {
                                    item(key = "hdr_snapshot") {
                                        GroupTitle(stringResource(R.string.create_instance_group_snapshot), snapshots.size)
                                    }
                                    items(snapshots, key = { it.id }) { v ->
                                        QkVersionRow(
                                            version = v.id,
                                            selected = v.id == state.version,
                                            trailing = stringResource(versionTypeLabelRes(v.type)),
                                            onClick = { onVersionChange(v.id) },
                                        )
                                    }
                                }
                                if (aprilFools.isNotEmpty()) {
                                    item(key = "hdr_april") {
                                        GroupTitle(stringResource(R.string.create_instance_group_april_fools), aprilFools.size)
                                    }
                                    items(aprilFools, key = { it.id }) { v ->
                                        QkVersionRow(
                                            version = v.id,
                                            selected = v.id == state.version,
                                            trailing = stringResource(versionTypeLabelRes(v.type)),
                                            onClick = { onVersionChange(v.id) },
                                        )
                                    }
                                }
                            }
                        }
                        }
                }
            }
        }
    }
}

/** 版本分组标题（正式版 / 快照版 / 愚人节版）。 */
@Composable
private fun GroupTitle(name: String, count: Int) {
    Text(
        text = stringResource(R.string.create_instance_group_title, name, count),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}


/**
 * 加载器版本下拉（非 vanilla 必填）。
 *
 * 用原生「滚轮/列表弹层」而不是桌面式下拉面板 ——
 * 手机上点开一个下拉面板既难点又挡住页面（与 AppSelect 同理）。
 */
@Composable
private fun LoaderVersionSelect(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { expanded = true },
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = selected.ifBlank { stringResource(R.string.create_instance_select_version) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected.isBlank()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("▾", style = MaterialTheme.typography.labelMedium)
            }
        }
        androidx.compose.material3.DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 320.dp),
        ) {
            options.take(80).forEach { v ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(v, style = MaterialTheme.typography.bodySmall) },
                    onClick = {
                        onSelect(v)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun MemorySlider(valueMb: Int, onChange: (Int) -> Unit) {
    Column {
        androidx.compose.material3.Slider(
            value = valueMb.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = 512f..8192f,
            steps = 14,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "$valueMb MB",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 加载器类型。 */
enum class Loader(val id: String, @StringRes val labelRes: Int) {
    Vanilla("vanilla", R.string.create_instance_loader_vanilla),
    Fabric("fabric", R.string.create_instance_loader_fabric),
    Forge("forge", R.string.create_instance_loader_forge),
    NeoForge("neoforge", R.string.create_instance_loader_neoforge),
}

/**
 * 版本（UI 模型）。
 *
 * ⚠️ 类型标签**不放在这个 data class 里**，用顶层函数 [versionTypeLabel]。
 *
 * 原因：`val typeLabel: String get() = if (type == "release") ...` 这种
 * 「data class 里带 getter 属性、且名字与另一个属性只差前缀」的写法，
 * 会让 Kotlin 2.0.21 的 FIR 编译器内部崩溃：
 *   ClassCastException: FirPropertySymbol cannot be cast to FirEnumEntrySymbol
 * （报错位置指向同文件的 enum class Loader，完全是误导）。
 * 拆成顶层函数后编译正常。
 */
data class VersionUi(
    val id: String,
    val type: String,
)

/** 版本类型的中文标签。覆盖 Mojang 清单里的全部 4 种取值。 */
internal fun versionTypeLabelRes(type: String): Int = when (type) {
    "release" -> R.string.create_instance_type_release
    "snapshot" -> R.string.create_instance_type_snapshot
    "old_beta" -> R.string.create_instance_type_old_beta
    "old_alpha" -> R.string.create_instance_type_old_alpha
    else -> R.string.create_instance_type_snapshot
}
data class CreateUiState(
    val name: String = "",
    val version: String = "",
    // ⚠️ 用字面量而不是 Loader.Vanilla.id：Kotlin 2.0.21 的 FIR 编译器
    // 在处理「data class 默认参数引用同文件 enum」时会内部崩溃
    // （ClassCastException: FirPropertySymbol → FirEnumEntrySymbol，报错行就是这一行）。
    // 值等价（Loader.Vanilla.id == "vanilla"），运行时行为完全一致。
    val loader: String = "vanilla",
    val loaderVersion: String = "",
    val memoryMb: Int = 2048,
    val versions: List<VersionUi> = emptyList(),
    val loadingVersions: Boolean = false,
    val creating: Boolean = false,
    val error: String? = null,
    /** 预估下载体积（MB，2026-10-21 补：estimateDownload 一直没 UI）。 */
    val estimatedDownloadMb: Long? = null,
)













