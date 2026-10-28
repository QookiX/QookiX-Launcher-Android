package com.zhayi.qookix.compose

import com.zhayi.qookix.compose.theme.QkSpacing
import androidx.compose.ui.res.stringResource
import com.zhayi.qookix.R
import android.graphics.Bitmap
import org.json.JSONObject
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.withFrameNanos
import com.zhayi.qookix.compose.motion.QookiX
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.zhayi.qookix.compose.bridge.*
import com.zhayi.qookix.compose.motion.qkFadeIn
import com.zhayi.qookix.compose.motion.qkFadeOut
import com.zhayi.qookix.compose.motion.qkSlideInLeft
import com.zhayi.qookix.compose.motion.qkSlideInRight
import com.zhayi.qookix.compose.pages.AccountPanel
import com.zhayi.qookix.compose.pages.AccountUi
import com.zhayi.qookix.compose.pages.BrowsePage
import com.zhayi.qookix.compose.pages.BrowseType
import com.zhayi.qookix.compose.pages.BrowseRepo
import com.zhayi.qookix.compose.pages.BrowseUiState
import com.zhayi.qookix.compose.pages.CreateInstancePage
import com.zhayi.qookix.compose.pages.CreateRepo
import com.zhayi.qookix.compose.pages.ServerCreatePage
import com.zhayi.qookix.compose.pages.ServerCreateUiState
import com.zhayi.qookix.compose.pages.loadServerVersions
import com.zhayi.qookix.compose.pages.CreateUiState
import com.zhayi.qookix.compose.pages.settings.AppUpdateUiState
import com.zhayi.qookix.compose.pages.settings.loadContributors
import com.zhayi.qookix.compose.pages.DownloadsPage
import com.zhayi.qookix.compose.pages.DownloadsTab
import com.zhayi.qookix.compose.pages.DownloadsUiState
import com.zhayi.qookix.compose.pages.FileEditorOverlay
import com.zhayi.qookix.compose.pages.rememberFileEditor
import com.zhayi.qookix.compose.pages.FileManagerPage
import com.zhayi.qookix.compose.pages.FileManagerUiState
import com.zhayi.qookix.compose.pages.FileOps
import com.zhayi.qookix.compose.pages.fmtBytes
import com.zhayi.qookix.compose.pages.tooLargeToEdit
import com.zhayi.qookix.compose.pages.FrpNodeUi
import com.zhayi.qookix.compose.pages.FrpRoomUi
import com.zhayi.qookix.compose.pages.HomePage
import com.zhayi.qookix.compose.pages.HomeUiState
import com.zhayi.qookix.compose.pages.InstanceFileOps
import com.zhayi.qookix.compose.pages.InstanceDetailPage
import com.zhayi.qookix.compose.pages.InstanceDetailRepo
import com.zhayi.qookix.compose.pages.InstanceDetailToolbar
import com.zhayi.qookix.compose.pages.FileReportUi
import com.zhayi.qookix.compose.pages.MissingItemUi
import com.zhayi.qookix.compose.pages.InstanceDetailUiState
import com.zhayi.qookix.compose.pages.ControlButtonUi
import com.zhayi.qookix.compose.pages.SharedUsageUi
import com.zhayi.qookix.compose.pages.rememberInstanceSettings
import com.zhayi.qookix.compose.pages.InstanceTab
import com.zhayi.qookix.compose.pages.InstanceSection
import com.zhayi.qookix.compose.pages.ProjectUi
import com.zhayi.qookix.compose.pages.ContentVersionUi
import com.zhayi.qookix.compose.pages.DependencyUi
import com.zhayi.qookix.compose.pages.ExportPlanUi
import com.zhayi.qookix.compose.pages.InstallTargetUi
import com.zhayi.qookix.compose.pages.InstallDialog
import com.zhayi.qookix.compose.pages.ServerUi
import com.zhayi.qookix.compose.pages.InstanceUi
import com.zhayi.qookix.compose.pages.InstancesRepo
import com.zhayi.qookix.compose.pages.FrpPage
import com.zhayi.qookix.compose.pages.FrpUiState
import com.zhayi.qookix.compose.pages.FrpTunnelUi
import com.zhayi.qookix.compose.pages.FrpLoginDialog
import com.zhayi.qookix.compose.pages.FrpLoginUiState
import com.zhayi.qookix.compose.pages.FrpLoginPhase
import com.zhayi.qookix.compose.pages.FrpTunnelLogDialog
import com.zhayi.qookix.compose.pages.FrpRoomsPage
import com.zhayi.qookix.compose.pages.InstancesPage
import com.zhayi.qookix.compose.pages.InstancesUiState
import com.zhayi.qookix.compose.pages.FrpRoomsUiState
import com.zhayi.qookix.compose.pages.MorePage
import com.zhayi.qookix.compose.pages.MsLoginFlow
import com.zhayi.qookix.compose.pages.PlaytimePage
import com.zhayi.qookix.compose.pages.PlaytimeRoute
import com.zhayi.qookix.compose.ui.QkFeedback
import com.zhayi.qookix.compose.pages.PlaytimeUiState
import com.zhayi.qookix.compose.pages.InstancePlaytimeUi
import com.zhayi.qookix.compose.pages.DailyPlaytimeUi
import com.zhayi.qookix.compose.pages.MultiplayerPage
import com.zhayi.qookix.compose.pages.MultiplayerRepo
import com.zhayi.qookix.compose.pages.MultiplayerUiState
import com.zhayi.qookix.compose.pages.ServerDetailPage
import com.zhayi.qookix.compose.pages.ServerDetailRepo
import com.zhayi.qookix.compose.pages.ServerDetailRoute
import com.zhayi.qookix.compose.pages.ServerDetailUiState
import com.zhayi.qookix.compose.pages.ServerFileOps
import com.zhayi.qookix.compose.pages.CapeUi
import com.zhayi.qookix.compose.pages.Skin3dHost
import com.zhayi.qookix.compose.pages.SkinPage
import com.zhayi.qookix.compose.pages.SkinRepo
import com.zhayi.qookix.compose.pages.SkinUiState
import com.zhayi.qookix.compose.pages.NewsItemUi
import com.zhayi.qookix.compose.pages.NewsPage
import com.zhayi.qookix.compose.pages.NewsUiState
import com.zhayi.qookix.compose.pages.TaskUi
import com.zhayi.qookix.compose.pages.TasksRepo
import com.zhayi.qookix.compose.pages.loadNews
import com.zhayi.qookix.compose.pages.parseNews
import com.zhayi.qookix.compose.pages.settings.AboutPane
import com.zhayi.qookix.compose.pages.settings.AppearancePane
import com.zhayi.qookix.compose.pages.settings.ContentServicesPane
import com.zhayi.qookix.compose.pages.settings.DownloadPane
import com.zhayi.qookix.compose.pages.settings.GamePane
import com.zhayi.qookix.compose.pages.settings.GeneralPane
import com.zhayi.qookix.compose.pages.settings.InfoDialog
import com.zhayi.qookix.compose.pages.settings.MemoryInfoUi
import com.zhayi.qookix.compose.pages.settings.PluginUi
import com.zhayi.qookix.compose.pages.settings.PluginsPane
import com.zhayi.qookix.compose.pages.settings.PluginsUiState
import com.zhayi.qookix.compose.pages.settings.SettingsPage
import com.zhayi.qookix.compose.pages.settings.SettingsPatch
import com.zhayi.qookix.compose.pages.settings.SettingsPlaceholder
import com.zhayi.qookix.compose.pages.settings.SettingsRepo
import com.zhayi.qookix.compose.pages.settings.SettingsTab
import com.zhayi.qookix.compose.pages.settings.SettingsUiState
import com.zhayi.qookix.compose.pages.settings.StoragePane
import com.zhayi.qookix.compose.shell.NavDestination

/**
 * 皮肤页 / 多人页 / 新建实例页。
 *
 * 从 PageHost.kt 拆出（同包，符号不用 import）。三页都是「进入即自己拉数据」的
 * 形式，只依赖 bridge 与回调。纯搬运，private 改 internal。
 */

/** 皮肤页。
 *
 * 3D 预览降级成 2D 双视图（见 SkinRepo / SkinPage 的注释说明取舍）。
 */
@Composable
internal fun SkinRoute(
    bridge: RustBridge,
    bridgeReady: Boolean,
    activity: ComposeMainActivity? = null,
    /** 3D 预览渲染方式（compose=省电 / webview=流畅）。 */
    previewMode: String = "compose",
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val api = remember(bridge) { QookixApi(bridge) }
    val repo = remember(api) { SkinRepo(api, context) }

    var state by remember { mutableStateOf(SkinUiState(loading = true)) }

    // 载入皮肤库
    LaunchedEffect(bridgeReady) {
        if (!bridgeReady) return@LaunchedEffect
        repo.listSkins { r ->
            state = r.fold(
                onSuccess = { list ->
                    val first = list.firstOrNull()
                    state.copy(skins = list, loading = false, selected = first)
                },
                onFailure = { state.copy(loading = false, error = context.getString(R.string.skin_lib_read_failed, friendlyError(context, it))) },
            )
        }
        // 顺带读一下当前账号（用于「应用到账号」按钮）
        api.listAccounts { r ->
            r.onSuccess { arr ->
                val acc = arr.optJSONObject(0) ?: return@onSuccess
                val isOnline = acc.optString("type").contains("microsoft", ignoreCase = true)
                val accName = acc.optString("username").ifBlank { acc.optString("name") }
                state = state.copy(
                    accountUuid = acc.optString("uuid"),
                    accountName = accName,
                    accountOnline = isOnline,
                )
                // ⚠️ 2026-10-18 加（修「正版账号皮肤页永远显示未选择皮肤」）：
                // 正版账号的皮肤在 Mojang 服务器上，本地皮肤库是空的 ——
                // 界面只能显示「未选择皮肤」，用户以为皮肤功能坏了。
                // 现在进皮肤页时**自动按账号名拉一次皮肤**存进皮肤库，
                // 与「按正版玩家名获取」按钮同一条路，只是不用用户手填名字。
                // 静默失败（网络不通等），不打扰用户。
                if (isOnline && accName.isNotBlank() && state.skins.isEmpty()) {
                    repo.fetchPlayerSkin(accName) { fr ->
                        fr.onSuccess { ps ->
                            if (ps.dataUrl.isNotBlank()) {
                                repo.saveFromData("player-$accName-${System.currentTimeMillis()}", ps.dataUrl) { sr ->
                                    sr.onSuccess { savedJson ->
                                        // savedJson 是 Rust SkinEntry 的 JSON 串，取 filename 用于选中
                                        val savedFile = runCatching {
                                            org.json.JSONObject(savedJson).optString("filename")
                                        }.getOrNull().orEmpty()
                                        repo.listSkins { lr ->
                                            lr.onSuccess { list ->
                                                state = state.copy(
                                                    skins = list,
                                                    selected = list.firstOrNull { it.filename == savedFile }
                                                        ?: list.firstOrNull(),
                                                    imageDataUrl = ps.dataUrl,
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
    }

    // 选中变化时加载图片
    LaunchedEffect(state.selected?.filename) {
        val f = state.selected?.filename ?: return@LaunchedEffect
        if (state.imageDataUrl.isNotBlank() && state.selected?.filename == f) return@LaunchedEffect
        repo.loadImage(f) { r ->
            r.onSuccess { state = state.copy(imageDataUrl = it) }
            r.onFailure { state = state.copy(error = context.getString(R.string.skin_image_read_failed, friendlyError(context, it))) }
        }
    }

    // ── 3D 预览（复用隐形 WebView 跑 skinview3d）────────────────────────
    //
    // 旧 UI 用 skinview3d 做 3D 预览（旋转 / 动画 / 纤细模型）。
    // Compose 没有 3D 引擎，所以复用那个承载 IPC 的隐形 WebView
    // 离屏渲染，再用 gl.readPixels 读回像素。
    //
    // 细节与"为什么不用 toDataURL"见 Skin3dHost / skin3d.js 的注释。
    val host3d = remember(bridge, activity) {
        activity?.let { Skin3dHost(bridge, it) }
    }
    var preview3d by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var preview3dRendering by remember { mutableStateOf(false) }
    var preview3dError by remember { mutableStateOf<String?>(null) }
    var preview3dAnim by remember { mutableStateOf("idle") }
    var preview3dSlim by remember { mutableStateOf(false) }
    // 皮肤删除的二次确认（删除不可恢复）
    var skinDeleteTarget by remember { mutableStateOf<String?>(null) }

    /** 触发一次 3D 渲染（皮肤 / 动画 / 模型 / 视角变化后调）。 */
    fun render3d(dataUrl: String) {
        val h = host3d ?: return
        if (dataUrl.isBlank()) {
            preview3d = null
            return
        }
        preview3dRendering = true
        preview3dError = null
        h.loadSkin(dataUrl, preview3dSlim) { r ->
            preview3dRendering = false
            r.fold(
                onSuccess = { bmp ->
                    preview3d = bmp.asImageBitmap()
                },
                onFailure = { e ->
                    preview3dError = context.getString(R.string.skin_3d_render_failed, e.message)
                    android.util.Log.w("SkinRoute", "3D 渲染失败", e)
                },
            )
        }
    }

    // 皮肤图片就绪后就渲染一次 3D
    LaunchedEffect(state.imageDataUrl, preview3dSlim) {
        if (state.imageDataUrl.isNotBlank()) render3d(state.imageDataUrl)
    }

    // ── 自动旋转：模型自转需要持续取帧 ────────────────────────────────
    //
    // ⚠️⚠️ 2026-10-16 修 —— **不能只依赖 skinview3d 的 autoRotate**。
    //
    // 它内部靠 `requestAnimationFrame` 循环累积角度：
    //   draw() { playerWrapper.rotation.y += clock.getDelta() * speed; ... }
    // 但我们跑在**不可见的 WebView** 里，实测 rAF 不推进 ——
    // 真机自检 `rotY=0.000`（开了也纹丝不动）。
    //
    // 所以改成自己驱动：每轮先 `stepAutoRotate(6°)` 推进角度，再取帧。
    //
    // ⚠️ 频率必须克制：每帧的 PNG base64（几十 KB）要穿过 `evaluateJavascript`，
    // 也就是走 binder。之前用 22KB 高频回传时被系统判为
    // "excessive binder traffic" 直接杀进程。
    // 150ms ≈ 6.7fps，6°/步 → 约 9 秒转一圈，流量只有 16ms 的 1/9。
    // ⚠️ 频率：从固定 150ms 改成**上一帧完成后立刻取下一帧**。
    //
    // 原来写死 `delay(150)`，但单帧实际只要 30–40ms —— 等于人为把
    // 上限压到 6.7fps。改成"等上一帧落地再发下一帧"后，实际帧率
    // 由设备能力决定（实测能到 ~20fps），而且不会积压请求。
    //
    // 保留一个**下限节流**（MIN_FRAME_MS）：没有它的话循环会空转，
    // 白白占 CPU 还制造不必要的 binder 流量。
    // 自动旋转的最小帧间隔（下限节流）。
    // 取值依据：单帧实测 30–40ms，留一点余量 → 45ms ≈ 22fps 上限。
    // 不设下限的话循环会空转（刚发完就立刻发下一个，白占 CPU 和 binder）。
    val MIN_FRAME_MS = 45L
    var preview3dAutoRotate by remember { mutableStateOf(false) }
    var perfTick by remember { mutableStateOf(0) }
    LaunchedEffect(preview3dAutoRotate, state.imageDataUrl) {
        if (!preview3dAutoRotate || state.imageDataUrl.isBlank()) return@LaunchedEffect
        while (true) {
            val t0 = android.os.SystemClock.uptimeMillis()
            // 等这一帧真正落地（用挂起而不是轮询，省 CPU）
            val done = kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
                host3d?.snapshot { r ->
                    r.onSuccess { preview3d = it.asImageBitmap() }
                    cont.resumeWith(Result.success(r.isSuccess))
                } ?: cont.resumeWith(Result.success(false))
            }
            val cost = android.os.SystemClock.uptimeMillis() - t0
            perfTick++
            if (perfTick % 20 == 0) {
                android.util.Log.i("SkinRoute", "自转帧耗时 ${cost}ms（约 ${if (cost > 0) 1000 / cost else 0}fps）")
                host3d?.logPerf()
            }
            // 只在**快于下限**时补一个 delay，避免空转
            if (cost < MIN_FRAME_MS) kotlinx.coroutines.delay(MIN_FRAME_MS - cost)
            // 先推进角度，下一轮取帧时就是新姿态
            host3d?.stepAutoRotate(3f)
            if (!done) kotlinx.coroutines.delay(50)   // 失败时缓一下，别死循环
        }
    }

    // ── 披风列表（仅正版账号）────────────────────────────────────────
    var capes by remember { mutableStateOf<List<CapeUi>>(emptyList()) }
    var capesLoading by remember { mutableStateOf(false) }
    var selectedCapeId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.accountUuid, state.accountOnline) {
        val uuid = state.accountUuid
        if (!state.accountOnline || uuid.isBlank()) {
            capes = emptyList()
            return@LaunchedEffect
        }
        capesLoading = true
        api.fetchPlayerCapes(uuid) { r ->
            capesLoading = false
            r.fold(
                onSuccess = { arr ->
                    val list = (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        CapeUi(
                            id = o.optString("id"),
                            name = o.optString("name"),
                            dataUrl = o.optString("dataUrl").ifBlank { o.optString("data_url") },
                            active = o.optBoolean("active", false),
                        )
                    }
                    capes = list
                    // 默认选中账号当前正穿着的那件，并让 3D 预览穿上
                    val cur = list.firstOrNull { it.active }
                    selectedCapeId = cur?.id
                    if (cur != null && cur.dataUrl.isNotBlank()) host3d?.loadCape(cur.dataUrl)
                },
                onFailure = { e ->
                    capes = emptyList()
                    android.util.Log.w("SkinRoute", "读取披风失败: ${e.message}")
                },
            )
        }
    }

    SkinPage(
        state = state,
        preview3d = preview3d,
        preview3dRendering = preview3dRendering,
        preview3dError = preview3dError,
        preview3dAnimation = preview3dAnim,
        preview3dSlim = preview3dSlim,
        preview3dAutoRotate = preview3dAutoRotate,
        // 渲染方式来自全局设置（外观页可切）
        previewRenderMode = previewMode,
        // webview 模式：把预览框的位置交给 Activity，让它调整 wry WebView
        // 的位置/尺寸 —— 那是「直接渲染」模式的实际画面来源。
        // compose 模式回调 null（收回隐形）。
        onPreviewBoxChanged = { visible, x, y, w, h ->
            if (visible == true) {
                activity?.setSkinPreviewVisible(true, x, y, w, h)
            } else {
                activity?.setSkinPreviewVisible(false, 0, 0, 0, 0)
            }
        },
        onPreview3dAutoRotate = { on ->
            preview3dAutoRotate = on
            host3d?.setAutoRotate(on)
        },
        onPreview3dDrag = { dx, dy ->
            // 拖动：**一次调用**完成「旋转 + 取帧」（orbitSnap），
            // 并且用拖动专用的小尺寸（128×160）。
            //
            // 之前是 orbit() + snapshot() 两次 evaluateJavascript 往返，
            // 每帧多付一次 binder + JS 调度，拖动起来就是「卡」。
            host3d?.orbitSnap(dx, dy) { r ->
                r.onSuccess { preview3d = it.asImageBitmap() }
            }
        },
        capes = capes,
        capesLoading = capesLoading,
        selectedCapeId = selectedCapeId,
        accountOnline = state.accountOnline,
        onSelectCape = { cape ->
            selectedCapeId = cape?.id
            // 本地预览立刻换装
            host3d?.loadCape(cape?.dataUrl?.takeIf { it.isNotBlank() })
            // 正版账号还要同步到 Mojang
            if (state.accountUuid.isNotBlank()) {
                api.applyCapeToAccount(state.accountUuid, cape?.id) { r ->
                    r.onFailure { e ->
                        state = state.copy(error = context.getString(R.string.skin_cape_apply_failed, e.message))
                    }
                }
            }
            if (state.imageDataUrl.isNotBlank()) {
                host3d?.snapshot { r ->
                    r.onSuccess { preview3d = it.asImageBitmap() }
                }
            }
        },
        onPreview3dAnimation = { id ->
            preview3dAnim = id
            host3d?.setAnimation(id)
            // 动画切换后要重取一帧，否则画面还是旧姿态
            if (state.imageDataUrl.isNotBlank()) {
                host3d?.snapshot { r ->
                    r.onSuccess { preview3d = it.asImageBitmap() }
                }
            }
        },
        onPreview3dModel = { slim ->
            preview3dSlim = slim
            // 模型切换走 loadSkin（需要重建几何体），LaunchedEffect 会触发
        },
        onPreview3dReset = {
            host3d?.reset {
                if (state.imageDataUrl.isNotBlank()) {
                    host3d.snapshot { r ->
                        r.onSuccess { preview3d = it.asImageBitmap() }
                    }
                }
            }
        },
        onSelect = { s -> state = state.copy(selected = s, imageDataUrl = "") },
        onApply = { s ->
            state = state.copy(applying = s.filename)
            // ⚠️ 把当前选中的模型（经典/纤细）一起传下去 ——
            // Rust 的 apply_skin_offline 需要 variant 才能把皮肤记成对应模型，
            // 否则永远按 classic 存，纤细模型下的皮肤会显示错位。
            repo.applyToAccount(
                uuid = state.accountUuid,
                filename = s.filename,
                online = state.accountOnline,
                slim = preview3dSlim,
            ) { r ->
                state = r.fold(
                    onSuccess = { state.copy(applying = "") },
                    onFailure = { state.copy(applying = "", error = context.getString(R.string.skin_apply_failed, friendlyError(context, it))) },
                )
            }
        },
        onDelete = { s ->
            // ⚠️⚠️ 2026-10-16 修 —— 这里原来是错的。
            //
            // 我此前写的是「暂不支持在应用内删除皮肤」，注释还声称
            // 「api.ts 里确实没有对应命令（核对过）」。**那个结论是错的**：
            // `delete_skin` 一直都在（skins.rs:194 / commands.rs:944 /
            // lib.rs:289 注册），我只是只看了前端 api.ts 就下结论。
            //
            // **"前端没包"不等于"后端没有"** —— 这类判断必须去 Rust 侧确认。
            //
            // ⚠️ 2026-10-17：删除前必须确认 —— 原来点一下垃圾桶图标就**直接删了**，
            // 皮肤文件不可恢复（用户自己导入的 PNG 会彻底丢失）。
            // 这里只记下待删目标，真正的删除在下面的确认框里做。
            skinDeleteTarget = s.filename
        },
        onImport = {
            // 导入皮肤：弹图片选择器 → 读成 data URL → save_skin_from_data。
            // 皮肤是 64×64 的 PNG，用 base64 传（Rust 侧接口就是收 data URL）。
            val act = activity
            if (act == null) {
                state = state.copy(error = context.getString(R.string.file_picker_unsupported))
                return@SkinPage
            }
            act.pickImage { path ->
                if (path == null) return@pickImage
                // 读文件并转成 data URL
                val dataUrl = runCatching {
                    val f = java.io.File(path)
                    if (!f.isFile) return@runCatching null
                    // 皮肤都很小（64×64 ≈ 几 KB），一次读完没问题；
                    // 加个上限兜底，避免误选大图把内存打爆
                    if (f.length() > 2 * 1024 * 1024) return@runCatching null
                    val bytes = f.readBytes()
                    val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    "data:image/png;base64,$b64"
                }.getOrNull()

                if (dataUrl == null) {
                    state = state.copy(error = context.getString(R.string.skin_invalid_png))
                    return@pickImage
                }
                repo.saveFromData(java.io.File(path).name, dataUrl) { r ->
                    r.fold(
                        onSuccess = {
                            // 重新载入列表，让新皮肤出现
                            repo.listSkins { lr ->
                                lr.onSuccess { state = state.copy(skins = it) }
                            }
                        },
                        onFailure = { e -> state = state.copy(error = context.getString(R.string.skin_import_failed, e.message)) },
                    )
                }
            }
        },
        onFetchPlayer = { name ->
            // 按正版玩家名从 Mojang 拉皮肤 → 存进皮肤库 → 自动选中预览。
            //
            // 对应旧 UI `SkinView.vue` 的 fetchPlayer()。
            state = state.copy(fetchingPlayer = name, fetchMessage = null)
            repo.fetchPlayerSkin(name) { r ->
                r.fold(
                    onSuccess = { ps ->
                        if (ps.dataUrl.isBlank()) {
                            state = state.copy(
                                fetchingPlayer = "",
                                fetchMessage = context.getString(R.string.skin_fetch_no_image),
                            )
                            return@fold
                        }
                        // 存进皮肤库（名字带时间戳避免覆盖同名的）
                        repo.saveFromData("player-$name-${System.currentTimeMillis()}", ps.dataUrl) { sr ->
                            sr.fold(
                                onSuccess = {
                                    state = state.copy(
                                        fetchingPlayer = "",
                                        fetchMessage = context.getString(R.string.skin_fetched, name, ps.model),
                                    )
                                    // 重新拉列表，再选中刚存的那张
                                    repo.listSkins { lr ->
                                        lr.onSuccess { list ->
                                            state = state.copy(skins = list)
                                        }
                                    }
                                    state = state.copy(imageDataUrl = ps.dataUrl)
                                },
                                onFailure = { e ->
                                    state = state.copy(
                                        fetchingPlayer = "",
                                        fetchMessage = context.getString(R.string.skin_save_failed, e.message),
                                    )
                                },
                            )
                        }
                    },
                    onFailure = { e ->
                        state = state.copy(
                            fetchingPlayer = "",
                            fetchMessage = context.getString(R.string.skin_fetch_failed, e.message),
                        )
                    },
                )
            }
        },
    )

    // 皮肤删除确认（不可恢复：用户自己导入的 PNG 会彻底丢失）
    skinDeleteTarget?.let { target ->
        com.zhayi.qookix.compose.pages.settings.ConfirmDialog(
            title = context.getString(R.string.skin_delete_title),
                message = context.getString(R.string.skin_delete_msg, target),
            confirmText = context.getString(R.string.common_delete),
            danger = true,
            onDismiss = { skinDeleteTarget = null },
            onConfirm = {
                skinDeleteTarget = null
                api.deleteSkin(target) { r ->
                    r.fold(
                        onSuccess = {
                            repo.listSkins { rr ->
                                rr.onSuccess { list ->
                                    state = state.copy(
                                        skins = list,
                                        selected = null,
                                        imageDataUrl = "",
                                        error = null,
                                    )
                                    preview3d = null
                                }
                            }
                        },
                        onFailure = { e ->
                            state = state.copy(error = context.getString(R.string.skin_delete_failed, friendlyError(context, e)))
                        },
                    )
                }
            },
        )
    }
}


/** 多人游戏（本地服务器 + 陶瓦联机）。 */
@Composable
internal fun MultiplayerRoute(
    bridge: RustBridge,
    bridgeReady: Boolean,
    onOpenServer: (String) -> Unit,
    onNavigate: (NavDestination) -> Unit,
        // 复制房间码要用（房间码是发给朋友的短串）
        activity: ComposeMainActivity? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val api = remember(bridge) { QookixApi(bridge) }
    val repo = remember(api) { MultiplayerRepo(api) }

    var state by remember { mutableStateOf(MultiplayerUiState(loading = true)) }

    // 新建服务器对话框
    var showCreate by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var createError by remember { mutableStateOf<String?>(null) }

    // 联机房间（陶瓦）状态
    var tunnelBusy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var tunnelReady by remember { mutableStateOf<Boolean?>(null) }

    // ── 生命周期取消闸 ─────────────────────────────────────────────────
    // 陶瓦开房的房间码轮询（postDelayed({ poll(tries - 1) }, 1500) × 8 轮）
    // 会在用户离开页面后继续跑：迟到回调会写 `state` / `notice` /
    // `tunnelBusy`，把状态写进一个已经不存在的页面，还可能卡住下一次
    // 进来时的 `tunnelBusy`。
    //
    // 为什么用 alive 布尔闸而不是 removeCallbacks：
    //   poll 是局部嵌套函数，靠自我 postDelayed 重排，Runnable 引用在
    //   lambda 深处，外部要存句柄再逐一取消既繁琐又容易漏；页面里的
    //   多个循环还共用 Main Looper。布尔闸最简单可靠 —— onDispose 一定
    //   执行，回调醒来后读 alive 就知道自己该不该继续/写状态。
    var alive by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { alive = false } }

    fun reload() {
        // error 在**开始时**清，不在成功回调里清 —— 否则「启动失败」的红字
        // 会被紧随其后的列表刷新顺手抹掉。
        state = state.copy(loading = true, error = null)
        repo.loadServers { r ->
            state = r.fold(
                onSuccess = { state.copy(servers = it, loading = false) },
                onFailure = { state.copy(loading = false, error = context.getString(R.string.server_list_read_failed, friendlyError(context, it))) },
            )
        }
    }

    /** 启停失败必须给反馈（以前 `{ reload() }` 把 Result 丢了，失败零提示）。 */
    fun serverActionFailed(failRes: Int, e: Throwable?) {
        if (e == null) return
        state = state.copy(error = context.getString(failRes, friendlyError(context, e)))
    }

    // 服务器列表：bridge 就绪时拉一次；Rust 推 servers 脏事件时重拉（A2）。
    // 以前只在进页面时拉一次 —— 建/删服务器后要退出重进才看到变化。
    val serversRevision by CoreState.serversRevision.collectAsState()
    LaunchedEffect(bridgeReady, serversRevision) {
        if (bridgeReady) reload()
    }

    LaunchedEffect(bridgeReady) {
        if (bridgeReady) {
            // 顺带探一次陶瓦隧道是否就绪（决定按钮文案是「下载」还是「开启」）。
            //
            // ⚠️ 这里以前调 `terracotta_detect` —— Android 上没有这个命令，
            // 运行时报「Command terracotta_detect not found」。
            // Android 的正确探法是 `terracotta_ping`（见 [terracottaTunnelReady]）。
            api.terracottaTunnelReady { ready -> tunnelReady = ready }
        }
    }

    MultiplayerPage(
        state = state,
        onTabChange = { state = state.copy(tab = it) },
        onStartServer = { id -> repo.start(id) { r -> reload(); serverActionFailed(R.string.server_detail_start_failed, r.exceptionOrNull()) } },
        onStopServer = { id -> repo.stop(id) { r -> reload(); serverActionFailed(R.string.server_detail_stop_failed, r.exceptionOrNull()) } },
        onOpenServer = onOpenServer,
        // 2026-10-18：从弹窗改成独立页面（用户反馈「对话框不合适，
        // 像新建实例那样做成页面会好一点」）。见 ServerCreatePage.kt。
        onCreateServer = { onNavigate(NavDestination.ServerCreate) },
        onPingServer = { address, onResult ->
            QookixApi(bridge).pingMcServer(address) { r ->
                r.fold(
                    onSuccess = { o ->
                        val isOnline = o.optBoolean("online", false)
                        if (isOnline) {
                            val latency = o.optInt("latency_ms", 0)
                            val online = o.optInt("players_online", 0)
                            val max = o.optInt("players_max", 0)
                            onResult(Result.success(context.getString(R.string.multiplayer_ping_ok, latency, online, max)))
                        } else {
                            onResult(Result.failure(Exception(o.optString("error", "无法连接"))))
                        }
                    },
                    onFailure = { onResult(Result.failure(it)) },
                )
            }
        },
        onOpenFrp = { onNavigate(NavDestination.Frp) },
        onOpenFrpRooms = { onNavigate(NavDestination.FrpRooms) },
        tunnelBusy = tunnelBusy, onRetry = { reload() },
        // 复制房间码 —— 房间码本来就是要发给朋友的，
        // 不能复制就得手抄（旧 UI 的 copyRoom 就是这个作用）
        onCopyRoom = {
            if (state.roomCode.isNotBlank()) {
                activity?.copyToClipboard(context.getString(R.string.frp_room_code), state.roomCode)
                notice = context.getString(R.string.frp_room_code_copied)
            }
        },
        // ── 加入别人的房间（旧 UI 的 joinRoom，我先前完全漏做）──────────
        //
        // 对多数用户来说「进朋友的房间」比「自己开房」更常用，
        // 漏掉它等于这个功能只能单向使用。
        onJoinRoom = { code ->
            if (tunnelBusy) return@MultiplayerPage
            tunnelBusy = true
            notice = null

            // 加入前同样要确保隧道在跑 —— 隧道没起来的话 /guest 会直接连接被拒，
            // 报错看不懂。探活走 terracotta_ping（Android 上没有 detect 命令）。
            api.terracottaTunnelReady { ready ->
                if (!ready) {
                    tunnelBusy = false
                    notice = context.getString(R.string.frp_tunnel_not_ready_join)
                    return@terracottaTunnelReady
                }
                // 顺序：先确保 VPN 授权（数据面要走 TUN），否则客人的包根本进不来
                api.terracottaRequestVpn { _ ->
                    api.terracottaGuest(code, "player") { jr ->
                        tunnelBusy = false
                        val body = jr.getOrNull().orEmpty()
                        if (body.contains("\"ok\":true")) {
                            state = state.copy(tunnelRunning = true, roomCode = code)
                            notice = context.getString(R.string.frp_joined_room, code)
                        } else {
                            val err = Regex("\"error\":\"([^\"]*)\"")
                                .find(body)?.groupValues?.getOrNull(1)
                notice = if (err != null) context.getString(R.string.frp_join_failed, err)
                else context.getString(R.string.frp_join_invalid)
                        }
                    }
                }
            }
        },
        onToggleTunnel = {
            if (tunnelBusy) return@MultiplayerPage
            tunnelBusy = true
            notice = null

            // 陶瓦联机在 **Android** 上的正确顺序（对照旧 Vue 的 startHosting）：
            //   1. 探活   —— 隧道进程可能刚被系统回收，直接发请求只会撞上
            //               「连接被拒」这种看不懂的报错
            //   2. VPN    —— TUN 模式必须授权，否则房间码出得来、朋友却连不上
            //   3. /host  —— 真正开房
            //   4. 轮询 /state —— 等房间码（房主要先在游戏里「对局域网开放」）
            //
            // ⚠️ 这里以前调 terracotta_detect / terracotta_download / terracotta_launch
            // —— 三个**在 Android 上不存在**的命令（桌面版接口），整个功能是死的。
            fun startHosting() {
                api.terracottaRequestVpn { _ ->
                    val playerName = "player"
                    api.terracottaHost(playerName) { hr ->
                        val body = hr.getOrNull().orEmpty()
                        if (!body.contains("\"ok\":true")) {
                            tunnelBusy = false
                            val err = Regex("\"error\":\"([^\"]*)\"")
                                .find(body)?.groupValues?.getOrNull(1)
                notice = if (err != null) context.getString(R.string.frp_status_prefix, err)
                else context.getString(R.string.frp_tunnel_not_ready)
                            return@terracottaHost
                        }
                        // 房间码要等房主在游戏里「对局域网开放」才出现 —— 轮询 8 次
                        fun poll(tries: Int) {
                            // 页面已销毁：停止重排，也别再碰 tunnelBusy/notice
                            if (!alive) return
                            if (tries <= 0) {
                                tunnelBusy = false
                notice = context.getString(R.string.frp_room_creating)
                                return
                            }
                            api.terracottaTunnelState { st ->
                                // 迟到回调：页面可能已经不在了，别写它的状态
                                if (!alive) return@terracottaTunnelState
                                if (st.room.isNotBlank()) {
                                    tunnelBusy = false
                                    state = state.copy(tunnelRunning = true, roomCode = st.room)
                                    notice = context.getString(R.string.frp_room_opened2, st.room)
                                } else {
                                    android.os.Handler(android.os.Looper.getMainLooper())
                                        .postDelayed({ poll(tries - 1) }, 1500)
                                }
                            }
                        }
                        poll(8)
                    }
                }
            }

            // 先探活：ping 会在端口文件过期时顺手自愈（见 Rust terracotta_ping）
            api.terracottaTunnelReady { ready ->
                if (ready) {
                    startHosting()
                } else {
                    // 隧道没在跑。Android 上**没有**「启动隧道」的命令 ——
                    // 它由 :tunnel 进程自己管。给用户一个能自己动手的提示，
                    // 而不是报一个不存在的命令名。
                    tunnelBusy = false
                    notice = context.getString(R.string.frp_tunnel_not_ready_host)
                }
            }
        },
    )

    notice?.let { msg ->
        InfoDialog(
            title = context.getString(R.string.multiplayer_short),
            body = msg,
            onDismiss = { notice = null },
        )
    }
}


/** 新建实例 + 表单状态。 */
@Composable
internal fun CreateRoute(
    bridge: RustBridge,
    bridgeReady: Boolean,
    onBack: () -> Unit,
    onCreated: () -> Unit,
    activity: ComposeMainActivity? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val api = remember(bridge) { QookixApi(bridge) }
    val repo = remember(api) { CreateRepo(api) }

    var state by remember { mutableStateOf(CreateUiState(loadingVersions = true)) }

    // 预估下载体积（2026-10-21 补：estimateDownload 一直没 UI）
    LaunchedEffect(bridgeReady, state.version) {
        if (!bridgeReady || state.version.isBlank()) {
            state = state.copy(estimatedDownloadMb = null)
        } else {
            api.estimateDownload(state.version) { r ->
                r.onSuccess { o ->
                    val bytes = o.optLong("download_bytes", 0)
                    if (bytes > 0) state = state.copy(estimatedDownloadMb = bytes / 1_000_000)
                }
            }
        }
    }

    // ── 加载器版本（非 vanilla 必填）────────────────────────────────────
    //
    // 旧注释原话：选了加载器就必须带上版本号 —— 空着创建出来的实例
    // 加载器装不上、依赖也解析不出来，连「补全游戏文件」都没得下手。
    var loaderVersions by remember { mutableStateOf<List<String>>(emptyList()) }
    var loaderVersionsLoading by remember { mutableStateOf(false) }
    var loaderVersionsError by remember { mutableStateOf<String?>(null) }

    /** 换加载器或换游戏版本都重取，默认选最新（列表第一项）。 */
    fun loadLoaderVersions() {
        state = state.copy(loaderVersion = "")
        if (state.loader == "vanilla" || state.version.isBlank()) {
            loaderVersions = emptyList()
            loaderVersionsError = null
            return
        }
        loaderVersionsLoading = true
        loaderVersionsError = null
        api.getLoaderVersions(state.loader, state.version) { r ->
            loaderVersionsLoading = false
            r.fold(
                onSuccess = { arr ->
                    val list = (0 until arr.length()).mapNotNull { i ->
                        arr.optString(i).takeIf { it.isNotBlank() }
                    }
                    loaderVersions = list
                    if (list.isEmpty()) {
                        loaderVersionsError = context.getString(R.string.loader_no_versions, state.loader)
                    } else {
                        state = state.copy(loaderVersion = list.first())
                    }
                },
                onFailure = { e -> loaderVersionsError = context.getString(R.string.loader_versions_failed, e.message) },
            )
        }
    }

    // 选了非 vanilla 加载器 / 换了游戏版本 → 重新拉版本列表
    LaunchedEffect(state.loader, state.version) { loadLoaderVersions() }

    // ── 导入整合包模式 ──────────────────────────────────────────────
    //
    // 对应旧 UI `CreateInstanceView.vue` 的第二种模式。整合包自带 MC 版本与
    // 加载器，用户只要选文件 —— 再手填一遍只会填错（旧注释原话）。
    // 解析 / 建实例 / 解 overrides / 下载内容全在后端一条流水线里跑。
    var importMode by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    // ── 导入进度（2026-10-23）───────────────────────────────────────────
    // 直接听 install://progress：`TasksRepo.onChange` 是**单槽位**（见本文件上面的注释），
    // 抢它的订阅会让下载页没有进度；而 `bridge.listen` 支持多订阅者。
    // 只更新「阶段文案 + done/total」，导入页据此画确定进度条。
    var importStage by remember { mutableStateOf("") }
    var importDone by remember { mutableStateOf(0) }
    var importTotal by remember { mutableStateOf(0) }
    DisposableEffect(bridge) {
        val sub = bridge.listen("install://progress") { payload ->
            runCatching {
                val o = org.json.JSONObject(payload)
                Triple(o.optString("message"), o.optInt("done", 0), o.optInt("total", 0))
            }.getOrNull()?.let { (msg, done, total) ->
                // 只在导入期间采纳：别的安装（补全/装插件）也会发同样的阶段，别串到导入页
                if (importing) {
                    importStage = msg
                    importDone = done
                    importTotal = total
                }
            }
        }
        onDispose { bridge.unlisten(sub) }
    }
    fun pickAndImportModpack() {
        if (importing) return
        // 用系统文件选择器拿路径（走 HostCapabilities 的 pickFile）
        val act = activity ?: return
        act.pickFileByMime("*/*") { path ->
            if (path.isNullOrBlank()) return@pickFileByMime
            importing = true
            api.importModpack(path) { r ->
                importing = false
                r.fold(
                    onSuccess = { json ->
                        val o = runCatching { org.json.JSONObject(json) }.getOrNull()
                        val inst = o?.optJSONObject("instance")
                        val name = inst?.optString("name").orEmpty()
                        // ⚠️ 「少下了一部分」必须当场告诉用户：只写日志的后果是
                        // 他进游戏才崩，而那时错误信息指不到真凶（旧注释原话）。
                        val bad = (o?.optInt("failed", 0) ?: 0) +
                            (o?.optInt("skippedNoUrl", 0) ?: 0)
                        // 原来这里写进一个**没有任何读取点**的 importMsg（审查 P1-4），
                        // 提示等于没弹；改走全局一行轻提示。
                        QkFeedback.show(
                            if (bad > 0) context.getString(R.string.import_partial, bad)
                            else context.getString(R.string.import_done, name),
                        )
                        // 导入只解包**内容**（mods/config/…），游戏本体（客户端 jar、
                        // libraries、assets）不在包里 —— 以前要用户进实例手动点「补全」，
                        // 而大多数人只会觉得「导入完了却启动不了」。
                        // 这里导入成功即自动补全缺失的游戏文件（同一套 repair_instance_files，
                        // 进度仍由 install://progress 推到任务卡/详情页）。
                        val newId = inst?.optString("id").orEmpty()
                        if (newId.isNotBlank()) {
                            QkFeedback.show(context.getString(R.string.import_auto_repair))
                            api.repairInstanceFiles(newId) { }
                        }
                        onCreated()
                    },
                    onFailure = { e ->
                        QkFeedback.show(context.getString(R.string.import_failed, e.message))
                    },
                )
            }
        }
    }


    // 进页面就拉版本清单
    LaunchedEffect(bridgeReady) {
        if (!bridgeReady) return@LaunchedEffect
        repo.loadVersions { r ->
            state = r.fold(
                onSuccess = { vs ->
                    // 默认选中最新正式版（与旧界面一致）
                    val latest = vs.firstOrNull { it.type == "release" }?.id ?: vs.firstOrNull()?.id ?: ""
                    state.copy(versions = vs, version = latest, loadingVersions = false)
                },
                onFailure = { e ->
                    state.copy(loadingVersions = false, error = context.getString(R.string.version_manifest_failed, e.message))
                },
            )
        }
    }

    CreateInstancePage(
        state = state,
        onNameChange = { state = state.copy(name = it) },
        onVersionChange = { state = state.copy(version = it) },
        onLoaderChange = { state = state.copy(loader = it) },
        onMemoryChange = { state = state.copy(memoryMb = it) },
        loaderVersions = loaderVersions,
        loaderVersionsLoading = loaderVersionsLoading,
        loaderVersionsError = loaderVersionsError,
        onLoaderVersionChange = { state = state.copy(loaderVersion = it) },
        onBack = onBack,
        importMode = importMode,
        onImportModeChange = { importMode = it },
        onPickModpack = { pickAndImportModpack() },
        importing = importing,
    importStage = importStage,
    importDone = importDone,
    importTotal = importTotal,
        onCreate = {
            if (state.creating) return@CreateInstancePage
            state = state.copy(creating = true, error = null)
            repo.create(
                name = state.name.trim(),
                mcVersion = state.version,
                loader = state.loader,
                loaderVersion = state.loaderVersion,
                // ⚠️ 必须显式传渲染器（2026-10-16 修，阻断性缺陷）。
                //
                // 不传的话实例 `renderer` 为 null → Rust 走「按版本自动」→
                // 1.21.x 被判给 **GL4ES**，而 GL4ES 不支持 `glMapBufferRange`，
                // 游戏一点启动就崩：
                //     IllegalStateException: Can't map buffer, opengl error 0
                // 真机实测：GL4ES 崩、MobileGlues 正常（见 CreateRepo.autoRendererFor）。
                renderer = CreateRepo.autoRendererFor(state.version),
                memoryMb = state.memoryMb,
            ) { r ->
                r.fold(
                    onSuccess = { json ->
                        // ⚠️ 创建实例 ≠ 装好游戏 —— 这是旧界面有、我漏掉的**关键一步**。
                        //
                        // 实测（用户反馈「实例下载没有」）：只调 create_instance 的话
                        // 生成的是一个**空壳**（只有 instance.json，没有游戏文件），
                        // 启动必然失败。旧 UI 创建后立刻调 install_game
                        // （CreateInstanceView.vue L123）逐文件下载游戏本体。
                        //
                        // install_game 返回 taskId 后进度走 install://progress
                        // 事件 → 下载页能看到（TasksRepo 已监听）。所以这里
                        // 不等安装完成 —— 回实例页即可，进度去下载页看。
                        val instId = runCatching { JSONObject(json).optString("id") }
                            .getOrDefault("")
                        if (instId.isNotBlank()) {
                            // ⚠️ 2026-10-20 真机修：**必须接结果**。
                            //
                            // 原来这里 `api.installGame(instId)` 不带回调 ——
                            // 安装失败时（弱网、meta 拉不到、磁盘满）**一点痕迹都没有**：
                            // 下载页显示「暂无进行中的任务」，实例详情显示「依赖 0/7」，
                            // 用户完全不知道为什么，只能看到游戏一启动就闪退。
                            // 真机实测的「Fabric 26.3 加载界面闪退」就是这么来的。
                            api.installGame(instId) { r ->
                                r.onFailure { e ->
                                    android.util.Log.w(
                                        "PageHost",
                                        "自动安装实例 $instId 失败: ${e.message}",
                                    )
                                    state = state.copy(
                                        error = context.getString(
                                            R.string.create_install_failed,
                                            e.message ?: "",
                                        ),
                                    )
                                }
                            }
                        }
                        state = state.copy(creating = false)
                        onCreated()
                    },
                    onFailure = { e ->
                        state = state.copy(creating = false, error = context.getString(R.string.create_failed, e.message))
                    },
                )
            }
        },
    )
}
