package com.zhayi.qookix.compose.pages

import androidx.compose.foundation.interaction.MutableInteractionSource
import com.zhayi.qookix.compose.motion.pressScale
import androidx.compose.foundation.clickable
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.zhayi.qookix.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhayi.qookix.compose.ui.QkImageWithSkeleton
import com.zhayi.qookix.compose.ui.SkeletonList

/**
 * 内容中心（Modrinth / CurseForge 浏览）。
 *
 * ── 设计（完全重做，不参照旧 Vue 界面）──────────────────────────────────
 * 旧界面是「左侧类型栏 + 右侧列表 + 底部安装弹窗」。
 * 这里改成更适合横屏的 MD3 形态：
 *
 *   ┌─────────────────────────────────────────────┐
 *   │ [搜索框……………………]  [模组][整合包][资源包][光影] │  ← 顶栏区
 *   ├─────────────────────────────────────────────┤
 *   │  ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐ │
 *   │  │ 卡片   │ │ 卡片   │ │ 卡片   │ │ 卡片   │ │  ← 网格（横屏宽，多列）
 *   │  └────────┘ └────────┘ └────────┘ └────────┘ │
 *   └─────────────────────────────────────────────┘
 *
 * **为什么用网格而不是列表**：横屏宽度充裕（本机 ~1800px），
 * 列表会让每行拉得很长、信息密度反而低；网格能一屏看到更多项目，
 * 且每个项目有封面图（Coil 加载），视觉上更像「内容商店」。
 *
 * 类型筛选用 MD3 的 `FilterChip`（横向一排），比旧界面的左侧竖栏
 * 更省横向空间，也更符合 MD3 规范。
 */
@Composable
@kotlin.OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun BrowsePage(
    state: BrowseUiState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onTypeChange: (String) -> Unit,
    onOpenProject: (ProjectUi) -> Unit,
    /** 打开安装对话框（装模组 / 资源包 / 光影 / 插件）。 */
    onInstall: (ProjectUi) -> Unit = {},
    /**
     * 滑到底部时请求下一页（追加，不替换已有结果）。
     *
     * ⚠️ 这个回调以前**声明了但函数体里零引用** —— 用户现象是
     * 「搜索结果永远只有前 20 条，往下滑不会加载更多」。
     * 现在由 [LazyVerticalGrid] 的滚动位置驱动（见下面的 `paging`）。
     */
    onLoadMore: () -> Unit,
    /** 加载下一页中（底部转圈；也在滚动回调里防重入）。 */
    loadingMore: Boolean = false,
    /**
     * 是否还有下一页。false 时**不再**触发 onLoadMore。
     *
     * 由上层根据「上一页返回条数是否满页」判断 —— 这一层拿不到 total
     *（BrowseRepo 只解析 hits，丢掉了 total），所以不能自己算。
     */
    hasMore: Boolean = true,
    onLoaderChange: (String) -> Unit = {},
    /** 排序方式变更（downloads / updated / newest / follows）。 */
    onSortChange: (String) -> Unit = {},
    /** 紧凑模式切换（2026-10-17 新增，用户要求「更紧凑的展示方式」）。 */
    onCompactChange: (Boolean) -> Unit = {},
    /** 筛选面板是否展开（状态提升到 PageHost，因为面板触发按钮在顶栏）。 */
    filterOpen: Boolean = false,
    /** 关闭筛选面板。 */
    onDismissFilter: () -> Unit = {},
    /**
     * 整页翻译（B1）：把当前页所有项目的简介批量翻成中文。
     * 旧界面的 `translatePage()` —— 迁移时漏了，只剩安装对话框里能单个翻。
     */
    onTranslatePage: () -> Unit = {},
    /** 整页翻译进行中（按钮转圈 + 防重入）。 */
    translatingPage: Boolean = false,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        BrowseFilterSheet(
            visible = filterOpen,
            onDismiss = onDismissFilter,
            type = state.type,
            onTypeChange = onTypeChange,
            loader = state.loader,
            onLoaderChange = onLoaderChange,
            hideLoader = state.type == BrowseType.Plugin.id,
        )
        // 工具栏（搜索 + 筛选 + 排序）已移到**顶栏**（2026-10-17）。
        //
        // 原来它占页面内一整行（约 60dp），横屏 384dp 高度里很浪费。
        // 现在放进 LauncherShell 的 TopAppBar.title 槽位 —— 和「内容」标题同一行，
        // 完全省下那一行。顶栏里的布局见 BrowseToolbar。
        when {
            state.loading && state.items.isEmpty() -> {
                SkeletonList(rows = 3, rowHeight = 160.dp)
            }

            state.items.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (state.query.isBlank()) stringResource(R.string.browse_search_hint)
                            else stringResource(R.string.browse_no_results),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (state.error != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = state.error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            else -> {
                // ── 触底加载下一页 ──────────────────────────────────────
                //
                // ⚠️ 为什么用「在组合里读 state + snapshotFlow」而不是
                // `LaunchedEffect(gridState)`：后者的 block **不带接收者**，
                // 里面读 snapshot 状态不会被跟踪，effect 永远不会重跑，
                // 滚动位置变化根本推不出来（这是个经典坑）。
                // 在 Composable 作用域里读，才能建立快照订阅。
                //
                // 判定「接近底部」用可见项做比较（不是写死数字）：
                // 最后可见项的 index 进到倒数第 3 项以内就算触底。
                val gridState = rememberLazyGridState()
                val paging by remember {
                    derivedStateOf {
                        val total = gridState.layoutInfo.totalItemsCount
                        val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                        if (total <= 0 || last < 0) false else last >= total - 3
                    }
                }
                LaunchedEffect(gridState, hasMore, loadingMore) {
                    snapshotFlow { paging }
                        .distinctUntilChanged()
                        .filter { it }
                        .collect {
                            // hasMore/loadingMore 在这里读的是**本次组合的值**；
                            // 它们变化会重启 effect（键里有），所以不会用到旧值。
                            if (hasMore && !loadingMore) onLoadMore()
                        }
                }

                LazyVerticalGrid(
                    // 紧凑模式：列宽收到 320dp（横屏 2400px ≈ 800dp 能放 2 列，
                    // 每行卡片高度从 ~200dp 降到 ~72dp，一屏能看 5~6 个项目）。
                    // 卡片模式：保持 220dp 自适应网格。
                    columns = GridCells.Adaptive(minSize = if (state.compact) 320.dp else 220.dp),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(if (state.compact) 8.dp else 12.dp),
                    verticalArrangement = Arrangement.spacedBy(if (state.compact) 6.dp else 12.dp),
                ) {
                    // 整页翻译按钮（跨整行）
                    item(key = "__browse_translate__", span = { GridItemSpan(maxLineSpan) }) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (translatingPage) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            TextButton(onClick = onTranslatePage, enabled = !translatingPage) {
                                Icon(
                                    imageVector = Icons.Filled.Translate,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.browse_translate_page))
                            }
                        }
                    }
                    items(state.items, key = { it.id }) { p ->
                        if (state.compact) {
                            ProjectRowCompact(p, onClick = { onOpenProject(p) }, onInstall = { onInstall(p) })
                        } else {
                            ProjectCard(p, onClick = { onOpenProject(p) }, onInstall = { onInstall(p) })
                        }
                    }
                    // 底部状态：还有下一页就转圈，没有就明确说「到底了」——
                    // 否则用户滑到底看到空白，不知道是加载中还是没了。
                    item(key = "__browse_tail__") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (loadingMore) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else if (!hasMore) {
                                Text(
                                    text = stringResource(R.string.browse_no_more),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 项目卡片：封面 + 标题 + 描述 + 下载量。 */
@Composable
private fun ProjectCard(p: ProjectUi, onClick: () -> Unit, onInstall: () -> Unit) {
    val pressSource = remember { MutableInteractionSource() }
    Card(
        onClick = onClick,
        interactionSource = pressSource,
        modifier = Modifier.pressScale(pressSource),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column {
            // 封面（Coil 加载，带骨架）
            QkImageWithSkeleton(
                model = p.iconUrl.takeIf { it.isNotBlank() },
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp),
                cornerRadius = 0.dp,
            )

            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = p.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = p.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = p.author,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = fmtCount(p.downloads),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.width(8.dp))
                    // 「安装」—— 装模组的入口。
                    // 整卡点击是「用浏览器打开项目页」，安装是独立按钮
                    //（否则用户在浏览器里看一圈回来还得自己找）。
                    Button(
                        onClick = onInstall,
                        modifier = Modifier.height(28.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) {
                        Text(stringResource(R.string.browse_install), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}


/** 下载量格式化：1234 → 1.2k，1234567 → 1.2M。 */
internal fun fmtCount(n: Long): String = when {
    n < 1000 -> n.toString()
    n < 1_000_000 -> String.format("%.1fk", n / 1000.0)
    else -> String.format("%.1fM", n / 1_000_000.0)
}

/** 内容类型（对应旧界面的左侧类型栏）。 */
enum class BrowseType(val id: String, @androidx.annotation.StringRes val labelRes: Int) {
    Mod("mod", R.string.browse_type_mod),
    Modpack("modpack", R.string.browse_type_modpack),
    Resourcepack("resourcepack", R.string.browse_type_resourcepack),
    Shader("shader", R.string.browse_type_shader),

    /**
     * 服务器插件 —— 走 PaperMC 的 Hangar 平台（不是 Modrinth/CurseForge）。
     *
     * ⚠️ 选了它以后**安装目标从「实例」变成「服务器」**：
     * 插件装进服务器的 plugins 目录，跟客户端的 mod 不是一回事。
     * 旧 UI 的这个入口漏了会导致「无法从内容中心给服务器装插件」。
     */
    Plugin("plugin", R.string.browse_type_plugin),
}

/** 项目（UI 模型）。 */
data class ProjectUi(
    val id: String,
    val provider: String,
    val title: String,
    val description: String,
    val author: String,
    val iconUrl: String,
    val downloads: Long,
)

data class BrowseUiState(
    val query: String = "",
    // ⚠️ 字面量 "mod" 而不是 BrowseType.Mod.id —— Kotlin 2.0.21 的 FIR 编译器
    // 在处理「data class 默认参数引用同文件 enum」时会内部崩溃
    // （ClassCastException: FirPropertySymbol → FirEnumEntrySymbol，报错行就是这一行）。
    // 值等价（BrowseType.Mod.id == "mod"）。这和 CreateInstancePage 里）
    // Loader.Vanilla.id 那个是同一个 bug。
    val type: String = "mod",
    /**
     * 加载器筛选（fabric / forge / neoforge / quilt / vanilla）。
     *
     * 空串 = 全部（不过滤）。取值与 [BrowseRepo.LoaderOption.id] 一致，
     * 落到请求前会经 [BrowseRepo.loaderFacet] 映射（vanilla → 空串）。
     */
    val loader: String = "",
    /** 排序方式（默认按下载量）。取值见 [BrowseRepo.SORTS]。 */
    val sort: String = "downloads",
    /**
     * 紧凑模式（2026-10-17 新增，用户要求）。
     *
     * false = 卡片网格（封面 110dp + 标题 + 2 行描述 + 作者/下载量 + 安装按钮）
     * true  = 紧凑列表（封面 40dp + 标题 + 1 行描述 + 右侧安装图标）
     *
     * 紧凑模式下同样面积能多看 3~4 倍的项目 —— 想快速扫一眼有什么可装的时很有用。
     */
    val compact: Boolean = false,
    val items: List<ProjectUi> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)








/**
 * 内容中心的**顶栏工具栏**：搜索框 + 筛选 + 排序 + 紧凑切换。
 *
 * ⚠️ 2026-10-17 新增（用户要求「把搜索条放到『内容』那两个字的同一行」）。
 *
 * 为什么放顶栏：
 * 横屏可用高度只有 384dp，原来这行在页面内单独占约 60dp。
 * 塞进 TopAppBar 的 title 槽位后，**和标题共用同一行**，这 60dp 全部还给列表。
 *
 * 四个元素宽度分配：
 *   - 搜索框 `weight(1f)`：占满剩余
 *   - 筛选 / 排序 / 紧凑：固定 40dp 图标按钮
 * 窄屏（竖屏 384dp）时搜索框会自动收窄，但四个按钮始终可见。
 */
@kotlin.OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun BrowseToolbar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onOpenFilter: () -> Unit,
    activeFilterCount: Int,
    sort: String,
    onSortChange: (String) -> Unit,
    compact: Boolean,
    onCompactChange: (Boolean) -> Unit,
    /** 页面标题（2026-10-17：用户要求「内容」两个字必须保留）。空串 = 用默认资源文案。 */
    title: String = "",
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // ── 标题（必须保留）────────────────────────────────────────
        //
        // 2026-10-17 用户明确要求：「内容这两个字当然要留下来啊，这个标题不能给它扣掉」。
        //
        // 取舍：搜索框宽度收窄约 90dp（正常人不会在这打很多字），
        // 换来标题始终可见 —— 用户一眼知道自己在哪一页。
        Text(
            text = title.ifBlank { stringResource(R.string.browse_title) },
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            modifier = Modifier.padding(end = 12.dp),
        )

        // 搜索框：高度压到 40dp，塞进 56dp 的 TopAppBar 里不顶格
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .weight(1f)
                .height(44.dp),
            placeholder = {
                Text(stringResource(R.string.browse_search), style = MaterialTheme.typography.bodySmall)
            },
            leadingIcon = {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
            singleLine = true,
            shape = MaterialTheme.shapes.extraLarge,
            textStyle = MaterialTheme.typography.bodySmall,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        )

        // 筛选（有激活条件时显示数字徽标）
        FilterBadge(count = activeFilterCount) {
            IconButton(onClick = onOpenFilter, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Filled.Tune,
                    contentDescription = stringResource(R.string.browse_filter),
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        // 排序
        var sortOpen by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { sortOpen = true }, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Filled.Sort,
                    contentDescription = stringResource(R.string.browse_sort),
                    modifier = Modifier.size(20.dp),
                )
            }
            DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                BrowseRepo.SORTS.forEach { s ->
                    DropdownMenuItem(
                        text = { Text(stringResource(s.labelRes)) },
                        onClick = {
                            sortOpen = false
                            onSortChange(s.id)
                        },
                        trailingIcon = {
                            if (sort == s.id) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        },
                    )
                }
            }
        }

        // 紧凑模式切换
        IconButton(onClick = { onCompactChange(!compact) }, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = if (compact) Icons.Filled.ViewAgenda else Icons.Filled.GridView,
                contentDescription = if (compact) stringResource(R.string.browse_switch_to_cards)
                else stringResource(R.string.browse_switch_to_compact),
                modifier = Modifier.size(20.dp),
                tint = if (compact) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 紧凑列表行（2026-10-17 新增，用户要求「加一个更紧凑的展示方式」）。
 *
 * 对比 [ProjectCard]（约 200dp 高：110dp 封面 + 标题 + 2 行描述 + 作者行 + 安装按钮）：
 * 这里只有 **72dp**：40dp 缩略图 + 标题 + 单行描述 + 右侧安装图标。
 *
 * 横屏 1080px 高度下：
 *   - 卡片模式：一屏约 2 行 × 2 列 = 4 个项目
 *   - 紧凑模式：一屏约 5~6 行 × 2 列 = 10~12 个项目
 *
 * 想「快速扫一眼有什么可装的」时紧凑模式明显更合适；
 * 想「看清封面和简介再决定」时用卡片模式。
 */
@Composable
private fun ProjectRowCompact(p: ProjectUi, onClick: () -> Unit, onInstall: () -> Unit) {
    val pressSource = remember { MutableInteractionSource() }
    Card(
        onClick = onClick,
        interactionSource = pressSource,
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .pressScale(pressSource),
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 40dp 缩略图（比卡片模式的 110dp 小得多，但还能认出是哪个项目）
            QkImageWithSkeleton(
                model = p.iconUrl.takeIf { it.isNotBlank() },
                contentDescription = null,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(6.dp)),
                cornerRadius = 6.dp,
            )

            Spacer(Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = p.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = p.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${p.author} · ${fmtCount(p.downloads)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.width(8.dp))

            // 安装：紧凑模式下用图标按钮（省宽度给标题）
            IconButton(
                onClick = onInstall,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.browse_install),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
