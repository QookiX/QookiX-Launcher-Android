package com.zhayi.qookix.compose.pages

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.zhayi.qookix.compose.motion.pressScale
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.zhayi.qookix.R
import com.zhayi.qookix.compose.ui.SkeletonList

/**
 * 皮肤页。
 *
 * 对应旧界面 `SkinView.vue`（291 行）。
 *
 * ── 关于 3D 预览的降级决定 ──────────────────────────────────────────────
 * 旧界面用 `skinview3d`（WebGL）做 3D 旋转预览。Compose 没有等价物，
 * 而为了它保留一个 WebView 或写一套 OpenGL ES 渲染都不划算（见 SkinRepo 注释）。
 *
 * 本实现改成 **2D 二视图预览**：把 64×64 的皮肤贴图裁出
 *   - 正面（头 + 身体）
 *   - 背面
 * 并排显示。看皮肤主要就是看这两面，信息量足够，成本极低。
 *
 * 布局：
 *   ┌──────────────────────────────────────────────────┐
 *   │  预览区                                          │
 *   │  ┌──────┐  ┌──────┐      名称：Steve             │
 *   │  │ 正面 │  │ 背面 │      [应用到账号]  [删除]     │
 *   │  └──────┘  └──────┘                              │
 *   ├──────────────────────────────────────────────────┤
 *   │  皮肤库（网格）                    [+ 导入]      │
 *   │  ┌────┐ ┌────┐ ┌────┐ ┌────┐                     │
 *   └──────────────────────────────────────────────────┘
 */
@Composable
fun SkinPage(
    state: SkinUiState,
    onSelect: (SkinEntryUi) -> Unit,
    onApply: (SkinEntryUi) -> Unit,
    onDelete: (SkinEntryUi) -> Unit,
    onImport: () -> Unit,
    /** 按正版玩家名获取皮肤（输入的名字）。 */
    onFetchPlayer: (String) -> Unit = {},
    // ── 3D 预览（复用隐形 WebView 跑 skinview3d）────────────────────────
    /** 当前 3D 帧。null = 还没渲染出来。 */
    preview3d: ImageBitmap? = null,
    /** 3D 渲染中。 */
    preview3dRendering: Boolean = false,
    /** 3D 渲染失败原因。 */
    preview3dError: String? = null,
    /** 当前动画：idle / walk / run / none */
    preview3dAnimation: String = "idle",
    /** 是否纤细模型。 */
    preview3dSlim: Boolean = false,
    onPreview3dAnimation: (String) -> Unit = {},
    onPreview3dModel: (Boolean) -> Unit = {},
    onPreview3dReset: () -> Unit = {},

    // ── 拖拽旋转 / 自动旋转 / 披风 ────────────────────────────────────
    /** 拖拽旋转：把手指位移（像素）交给 3D 层走 OrbitControls。 */
    onPreview3dDrag: (Float, Float) -> Unit = { _, _ -> },
    /** 自动旋转是否开启（模型自转）。 */
    preview3dAutoRotate: Boolean = false,
    onPreview3dAutoRotate: (Boolean) -> Unit = {},

    /** 披风列表（仅正版账号有）。 */
    capes: List<CapeUi> = emptyList(),
    /** 正在加载披风列表。 */
    capesLoading: Boolean = false,
    /** 当前穿着的披风 id（null = 未穿）。 */
    selectedCapeId: String? = null,
    onSelectCape: (CapeUi?) -> Unit = {},
    /** 账号是否为正版（离线账号没有披风）。 */
    accountOnline: Boolean = false,

    /**
     * 3D 预览渲染方式：`compose`（省电，像素搬回 Compose，~25fps）
     * 或 `webview`（流畅，让那个 WebView 直接显示，~60fps）。
     *
     * 两种模式**同时生效**（WebView 一直是隐形的，只是渲染位置不同），
     * 所以切模式不需要重建 3D 场景，也不会中断正在转的动画。
     */
    previewRenderMode: String = "compose",
    /** 预览框位置回调（webview 模式用），由 SkinRoute 接 Activity。 */
    onPreviewBoxChanged: ((Boolean?, Int, Int, Int, Int) -> Unit)? = null,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // 输入的正版玩家名（本地状态，不需要提升到外层）
    var playerName by remember { mutableStateOf("") }

    // ⚠️ 顶层可滚动，但**内部不能再有"无限高"的滚动组件**（2026-10-16 修）。
    //
    // 竖屏/横屏可用高度都只有 ~384dp，而这一页内容（3D 预览 + 动画/模型
    // chips + 账号 + 皮肤库 + 导入 + 按正版名获取）远超一屏，所以顶层必须能滚。
    //
    // ── 但加 `verticalScroll` 时踩了一个必崩的坑 ──────────────────────
    // 皮肤库用的是 `LazyVerticalGrid`。Compose **禁止**把惰性滚动组件放进
    // `Column(verticalScroll)` —— 两者都要求"无限高度"，冲突时直接抛：
    //
    //   IllegalStateException: Vertically scrollable component was measured
    //   with an infinity maximum height constraints, which is disallowed.
    //     at LazyGridKt$rememberLazyGridMeasurePolicy$1$1.measure
    //     at CheckScrollableContainerConstraintsKt.check...
    //
    // 表现是"一进皮肤页就闪退回桌面"（实为崩溃重启），很容易误判成
    // 3D 渲染崩了。
    //
    // ── 解法 ──────────────────────────────────────────────────────────
    // 给网格一个**有界高度**（屏幕高度的一半），它就能在约束内正常测量，
    // 自身滚动；外层继续滚其余内容。
    val screenH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
    val gridMaxH = (screenH * 0.5f).coerceIn(160.dp, 420.dp)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // ── 预览区 ────────────────────────────────────────────────────
        //
        // ⚠️ 窄屏改**纵向堆叠**（2026-10-16 修）。
        //
        // 原来是 `Row { 预览列 ; Column(weight(1f)){名称/按钮} }`。
        // 竖屏（411dp）下预览框宽度 = previewH * 0.86 ≈ 165dp，加上两侧
        // padding 与间距后，右侧信息列只剩**几十 dp** —— 实测截图里
        // 「245 B · 2分钟前」被压成逐字竖排，**「应用到账号」和删除按钮
        // 完全被挤出屏幕**（点都点不到）。
        //
        // 窄屏纵向排：预览在上、信息在下，两边都有完整宽度。
        val screenW = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp
        val stack = screenW < 560.dp
        val previewBlock: @Composable () -> Unit = {
            SkinPreview3d(
                bitmap = preview3d,
                rendering = preview3dRendering,
                error = preview3dError,
                animation = preview3dAnimation,
                slim = preview3dSlim,
                autoRotate = preview3dAutoRotate,
                onAnimation = onPreview3dAnimation,
                onModel = onPreview3dModel,
                onReset = onPreview3dReset,
                onDrag = onPreview3dDrag,
                onAutoRotate = onPreview3dAutoRotate,
                renderMode = previewRenderMode,
                onPreviewBoxChanged = onPreviewBoxChanged,
            )
        }
        val infoBlock: @Composable () -> Unit = {
            Column(modifier = Modifier.weight(1f)) {
                val sel = state.selected
                Text(
                    text = sel?.name ?: stringResource(R.string.skin_none_selected),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (sel != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = sel.subtitle(ctx),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }

                Spacer(Modifier.height(16.dp))

                if (state.accountName.isNotBlank()) {
                    Text(
                        text = if (state.accountOnline) {
                            stringResource(R.string.skin_current_account_online, state.accountName)
                        } else {
                            stringResource(R.string.skin_current_account_offline, state.accountName)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { sel?.let(onApply) },
                        enabled = sel != null &&
                            state.accountUuid.isNotBlank() &&
                            state.applying.isBlank(),
                    ) {
                        Icon(Icons.Filled.Check, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            when {
                                state.applying.isNotBlank() -> stringResource(R.string.skin_applying)
                                state.accountOnline -> stringResource(R.string.skin_upload)
                                else -> stringResource(R.string.skin_apply)
                            }
                        )
                    }
                    if (sel != null) {
                        IconButton(onClick = { onDelete(sel) }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.skin_delete),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }

        if (stack) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
            ) {
                previewBlock()
                Spacer(Modifier.height(16.dp))
                // 纵向时不需要 weight —— 它在 Column 里会撑开，
                // 用 fillMaxWidth 让内容自然占满宽度
                Box(modifier = Modifier.fillMaxWidth()) { infoBlock() }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                previewBlock()
                infoBlock()
            }
        }


        // ── 披风（仅正版账号）────────────────────────────────────────
        //
        // 旧 UI **没有**这个区（全项目搜 `cape` 只找到 useSkinRenderer 里
        // 未被调用的 loadCape），但 Rust 侧 `fetch_player_capes` /
        // `apply_cape_to_account` 是完整可用的 —— 所以按"补齐后端已有能力"做。
        //
        // 离线账号没有披风（Mojang 侧才有），所以那里显示一句说明而不是空列表。
        if (accountOnline) {
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.skin_capes),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                if (capesLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.height(6.dp))

            if (capes.isEmpty()) {
                Text(
                    text = if (capesLoading) stringResource(R.string.skin_capes_loading)
                    else stringResource(R.string.skin_capes_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            } else {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = selectedCapeId == null,
                        onClick = { onSelectCape(null) },
                        label = { Text(stringResource(R.string.skin_cape_none)) },
                    )
                    capes.forEach { cape ->
                        FilterChip(
                            selected = selectedCapeId == cape.id,
                            onClick = { onSelectCape(cape) },
                            label = { Text(cape.name.ifBlank { cape.id }) },
                        )
                    }
                }
            }
        }

        // ── 皮肤库 ────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.skin_library),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(onClick = onImport) {
                Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.skin_import))
            }
        }

        // ── 按正版玩家名获取 ──────────────────────────────────────────
        //
        // 用途：想把朋友的皮肤拿来用、或自己正版号的皮肤在这台设备上没同步时。
        // 正版账号的皮肤保存在官方服务器上，本地那份是空的 —— 所以要按名字去拉。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = playerName,
                onValueChange = { playerName = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.skin_player_name)) },
                singleLine = true,
                enabled = state.fetchingPlayer.isBlank(),
            )
            Button(
                onClick = { onFetchPlayer(playerName) },
                enabled = playerName.isNotBlank() && state.fetchingPlayer.isBlank(),
            ) {
                if (state.fetchingPlayer.isNotBlank()) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(stringResource(R.string.skin_fetch))
            }
        }

        state.fetchMessage?.let { msg ->
            Text(
                text = msg,
                style = MaterialTheme.typography.bodySmall,
                color = if (msg.startsWith("获取失败")) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        Spacer(Modifier.height(10.dp))

        when {
            state.loading && state.skins.isEmpty() ->
                Column(modifier = Modifier.padding(20.dp)) {
                    SkeletonList(rows = 2, rowHeight = 120.dp)
                }

            state.skins.isEmpty() ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(R.string.skin_empty_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.skin_empty_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 120.dp),
                // 有界高度 —— 外层是 verticalScroll 的 Column，
                // 用 fillMaxSize() 会让网格收到无限高约束而崩溃。
                // 见 SkinPage 函数开头关于"必崩的坑"的注释。
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = gridMaxH),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.skins, key = { it.filename }) { s ->
                    SkinCard(
                        skin = s,
                        selected = s.filename == state.selected?.filename,
                        imageUrl = if (s.filename == state.selected?.filename) {
                            state.imageDataUrl
                        } else "",
                        onClick = { onSelect(s) },
                    )
                }
            }
        }
    }
}

/**
 * 2D 二视图预览。
 *
 * 从 64×64 的皮肤贴图里裁出正面与背面 —— 这是 Minecraft 皮肤的标准布局：
 *   - 头部正面：矩形 (8, 8, 16, 16)
 *   - 身体正面：矩形 (20, 20, 44, 52)
 *   - 头部背面：矩形 (24, 8, 32, 16)
 *   - 身体背面：矩形 (32, 20, 56, 52)
 *
 * 用「裁 + 放大」而不是直接显示整张贴图：整张 64×64 贴图对人眼是
 * 一堆错位的色块，看不出皮肤长什么样。
 */
/**
 * 皮肤预览（**3D**）。
 *
 * ── 为什么从 2D 展开图改回 3D ──────────────────────────────────────────
 * 我一度把旧 UI 的 3D 预览降级成 2D 正面/背面展开图，理由是
 * 「3D 旋转属于锦上添花，不值得为它保留 WebView」—— **这个判断是错的**：
 *
 *   1. 那个 WebView **本来就在**（承载 Tauri IPC 注入），复用它不新增进程
 *   2. 3D 预览是旧 UI 的**实际功能**，不是装饰
 *   3. 真机探针证实它 WebGL2 可用（Adreno 650）
 *
 * 现在的做法：复用那个隐形 WebView 跑 skinview3d 离屏渲染，
 * 用 `gl.readPixels` 把画面读回来（`skin3d.js` 里手写了 PNG 编码器 ——
 * 因为这个 WebView 里 `canvas.toDataURL` 返回空白，探针实测过）。
 *
 * @param bitmap     当前帧（由外层驱动 Skin3dHost 渲染后传进来）
 * @param rendering  是否正在渲染
 * @param error      渲染失败原因
 */
@Composable
private fun SkinPreview3d(
    bitmap: ImageBitmap?,
    rendering: Boolean,
    error: String?,
    animation: String,
    slim: Boolean,
    autoRotate: Boolean,
    onAnimation: (String) -> Unit,
    onModel: (Boolean) -> Unit,
    onReset: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onAutoRotate: (Boolean) -> Unit,
    /** 渲染方式：compose（默认，像素搬回 Compose）/ webview（WebView 直接显示）。 */
    renderMode: String = "compose",
    /**
     * 预览框位置变化时回调 —— webview 模式下用它把 WebView 挪到本框位置。
     * 第一个参数为 null 表示"收回隐形"。
     */
    onPreviewBoxChanged: ((Boolean?, Int, Int, Int, Int) -> Unit)? = null,
) {
    Column {
        // ── 3D 画面 ────────────────────────────────────────────────────
        //
        // ⚠️ 高度要**随屏幕收缩**（2026-10-16 修）。
        //
        // 原来写死 300dp，而横屏可用高度只有 ~384dp —— 光这一个框就吃掉
        // 大半屏，下面的动画/模型/皮肤库/导入/获取全被挤出视野。
        // 实测表现：整页只剩预览框和「未选择皮肤」两行，看起来像功能没了。
        //
        // 取「屏幕高度的一半」并夹在 140–300dp：
        //   - 384dp 屏 → 192dp（留出足够空间给下面的内容）
        //   - 大屏平板 → 最多 300dp（不至于大得空洞）
        val screenH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
        val previewH = (screenH * 0.5f).coerceIn(140.dp, 300.dp)

        // ── 拖拽旋转 ────────────────────────────────────────────────────
        //
        // 旧 UI 是 `<canvas>` + skinview3d 自带的 OrbitControls，**可以直接拖**。
        // 我们的画面是离屏读回来的静态帧，摸不到 canvas，所以要把手势
        // **翻译成位移量**再交给 JS 里的 controls：
        //
        //   手指移动 → accumulate 位移 → onDrag(dx, dy) → JS orbit() → 重取帧
        //
        // 用 `detectDragGestures`（而不是 detectHorizontalDragGestures）——
        // 后者会抢走垂直手势，导致页面滚不动。
        val dragAccum = remember { androidx.compose.runtime.mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

        // ── 「直接渲染」模式：把 WebView 挪到这个框的位置 ──────────────
        //
        // compose 模式（默认）：这里画 Compose 的 Image，画面是从 WebView
        //   搬回来的像素，~25fps。
        // webview 模式：这里**不画**（保持透明让下面的 WebView 露出来），
        //   并把 WebView 挪到本框的位置，~60fps。
        //
        // 用 `onGloballyPositioned` 拿本框在屏幕上的真实矩形，
        // 再交给 Activity 调整 WebView 的位置/尺寸。
        // 记录预览框在窗口里的位置与大小（webview 模式要把 WebView 挪过来）
        var boxLayout by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }

        // density 要在 Composable 层取 —— LaunchedEffect 块里没有 Composable 上下文
        val densityNow = androidx.compose.ui.platform.LocalDensity.current
        LaunchedEffect(renderMode, boxLayout) {
            if (renderMode != "webview") {
                // 离开 webview 模式 → 把 WebView 收回隐形
                onPreviewBoxChanged?.invoke(null, 0, 0, 0, 0)
                return@LaunchedEffect
            }
            val coords = boxLayout ?: return@LaunchedEffect
            // ⚠️ LayoutCoordinates 的 positionInWindow()/size **本身就是像素**，
            // 不能再乘 density —— 乘了会放大 2.8 倍（真机实测：预览框 725px
            // 被算成 2041px，WebView 铺满整屏、把别的控件都盖住了）。
            val x = coords.positionInWindow().x.toInt()
            val y = coords.positionInWindow().y.toInt()
            val wpx = coords.size.width
            val hpx = coords.size.height
            onPreviewBoxChanged?.invoke(true, x, y, wpx, hpx)
        }

        // ── 🔴 离开本页时必须收回 WebView ────────────────────────────
        //
        // ⚠️⚠️ 2026-10-16 修（用户报的 bug：切到设置页后皮肤还在那里跑动画）。
        //
        // 原来只靠上面 `LaunchedEffect` 的 else 分支收回 —— **那是错的**：
        // `LaunchedEffect` 在离开组合时会被取消，但**取消不执行任何清理**，
        // 而离开皮肤页时 `renderMode` 和 `boxLayout` 都没变（组件正在销毁，
        // 不会重新测量），所以 effect 根本不会重跑 →
        // **WebView 一直留在屏幕上，盖着别的页面**。
        //
        // 收回必须用 `DisposableEffect` 的 `onDispose` —— 组件离开组合时
        // 它**一定会**被调用。
        DisposableEffect(renderMode) {
            onDispose {
                // 无论什么模式，离开时都确保收回（幂等，重复调用无害）
                onPreviewBoxChanged?.invoke(null, 0, 0, 0, 0)
            }
        }
        Surface(
            modifier = Modifier
                .width(previewH * 0.86f)
                .height(previewH)
                .onGloballyPositioned { boxLayout = it }
                .pointerInput(bitmap) {
                    detectDragGestures { _, dragAmount ->
                        // 累积到一定阈值再提交，避免每个像素都触发一次
                        // evaluateJavascript + 截图（那会造成大量 binder 流量）
                        val acc = dragAccum.value + dragAmount
                        if (kotlin.math.abs(acc.x) >= 12f || kotlin.math.abs(acc.y) >= 12f) {
                            onDrag(acc.x, acc.y)
                            dragAccum.value = androidx.compose.ui.geometry.Offset.Zero
                        } else {
                            dragAccum.value = acc
                        }
                    }
                },
            shape = MaterialTheme.shapes.medium,
            // webview 模式下让这个框透明 —— 露出下面正在显示 3D 的 WebView
            color = if (renderMode == "webview")
                androidx.compose.ui.graphics.Color.Transparent
            else MaterialTheme.colorScheme.surfaceContainerLowest,
        ) {
            Box(contentAlignment = Alignment.Center) {
                when {
                    // webview 模式下画面由下面的 WebView 直接显示，这里不画 
                    renderMode == "webview" -> Unit
                    bitmap != null -> androidx.compose.foundation.Image(
                        bitmap = bitmap,
                        contentDescription = stringResource(R.string.skin_preview_3d),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    )
                    rendering -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.skin_rendering),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    error != null -> Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                    else -> Text(
                        text = stringResource(R.string.skin_select_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // ── 动画（旧 UI 的四种）────────────────────────────────────────
        // 加横向滚动：窄屏下 chip 会溢出，后面的选项点不到（详见 GeneralPane 的说明）
         FlowRow(
             horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
         ) {
            listOf(
                "idle" to R.string.skin_anim_idle,
                "walk" to R.string.skin_anim_walk,
                "run" to R.string.skin_anim_run,
                "none" to R.string.skin_anim_none,
            ).forEach { (id, labelRes) ->
                FilterChip(
                    selected = animation == id,
                    onClick = { onAnimation(id) },
                    label = { Text(stringResource(labelRes)) },
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        // ── 模型（经典 / 纤细）+ 重置视角 + 自动旋转 ──────────────────
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(
                selected = !slim,
                onClick = { onModel(false) },
                label = { Text(stringResource(R.string.skin_model_classic)) },
            )
            FilterChip(
                selected = slim,
                onClick = { onModel(true) },
                label = { Text(stringResource(R.string.skin_model_slim)) },
            )
            FilterChip(
                selected = autoRotate,
                onClick = { onAutoRotate(!autoRotate) },
                label = { Text(stringResource(R.string.skin_auto_rotate)) },
            )
            TextButton(onClick = onReset) { Text(stringResource(R.string.skin_reset_view)) }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            // 静态帧摸不到 canvas，用户不可能自己发现"能拖" —— 必须写出来
            text = stringResource(R.string.skin_drag_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun SkinView(img: ImageBitmap, label: String, front: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            modifier = Modifier
                .width(110.dp)
                .height(150.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
        ) {
            // 用 Canvas 手绘：把头/身/腿按标准布局拼成一个「平面人形」
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val px = w / 16f          // 头宽 8 像素 → 占半宽，所以 1 px = w/16
                val cx = w / 2f

                // 头（8×8）
                val headY = h * 0.02f
                drawSkinPart(img, this, if (front) 8 else 24, 8, 8, 8, cx - px * 4, headY, px * 8, px * 8)
                // 身体（8×12）
                val bodyY = headY + px * 8 + px * 0.06f
                drawSkinPart(img, this, if (front) 20 else 32, 20, 8, 12, cx - px * 4, bodyY, px * 8, px * 12)
                // 腿（4×12 ×2）
                val legY = bodyY + px * 12 + px * 0.06f
                drawSkinPart(img, this, if (front) 4 else 20, 20, 4, 12, cx - px * 4, legY, px * 4, px * 12)
                drawSkinPart(img, this, if (front) 20 else 4, 20, 4, 12, cx, legY, px * 4, px * 12)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/**
 * 把贴图的一块画到画布上（放大）。
 *
 * 用 `drawImage` 的 srcOffset/srcSize 做裁剪 —— Compose 的 DrawScope
 * 支持直接从源图取样，不需要先切成 Bitmap。
 */
private fun drawSkinPart(
    img: ImageBitmap,
    scope: androidx.compose.ui.graphics.drawscope.DrawScope,
    srcX: Int, srcY: Int, srcW: Int, srcH: Int,
    dstX: Float, dstY: Float, dstW: Float, dstH: Float,
) {
    // IntSize 版本的 drawImage 在 compose-ui 1.6+ 可用
    scope.drawImage(
        image = img,
        srcOffset = androidx.compose.ui.unit.IntOffset(srcX, srcY),
        srcSize = androidx.compose.ui.unit.IntSize(srcW, srcH),
        dstOffset = androidx.compose.ui.unit.IntOffset(dstX.toInt(), dstY.toInt()),
        dstSize = androidx.compose.ui.unit.IntSize(dstW.toInt(), dstH.toInt()),
    )
}

@Composable
private fun SkinCard(
    skin: SkinEntryUi,
    selected: Boolean,
    imageUrl: String,
    onClick: () -> Unit,
) {
    val pressSource = remember { MutableInteractionSource() }
    Card(
        onClick = onClick,
        interactionSource = pressSource,
        modifier = Modifier.pressScale(pressSource),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            // 缩略图：选中时用已解码的，否则用 Coil 异步加载
            if (imageUrl.isNotBlank()) {
                val bmp = remember(imageUrl) { decodeDataUrl(imageUrl) }
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 90.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
            } else {
                com.zhayi.qookix.compose.ui.QkImageWithSkeleton(
                    model = null,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(90.dp),
                    cornerRadius = 8.dp,
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = skin.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 把 data URL 解成 Bitmap。 */
private fun decodeDataUrl(dataUrl: String): Bitmap? = try {
    val comma = dataUrl.indexOf(',')
    val payload = if (comma >= 0) dataUrl.substring(comma + 1) else dataUrl
    val bytes = Base64.decode(payload, Base64.DEFAULT)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
} catch (e: Exception) {
    android.util.Log.w("SkinPage", "解码皮肤 data URL 失败", e)
    null
}

/**
 * 披风（对应 Rust `CapeInfo`）。
 *
 * @param id       Mojang 侧的披风 id（应用时传它）
 * @param name     显示名
 * @param dataUrl  披风贴图（用于 3D 预览里 loadCape）
 * @param active   账号当前是否正穿着它
 */
data class CapeUi(
    val id: String,
    val name: String = "",
    val dataUrl: String = "",
    val active: Boolean = false,
)









