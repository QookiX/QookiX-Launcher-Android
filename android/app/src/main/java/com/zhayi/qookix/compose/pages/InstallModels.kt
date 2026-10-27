package com.zhayi.qookix.compose.pages

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 「装这个内容」的展示状态。
 *
 * ⚠️ 为什么单独抽一份：同一套 UI 有两个宿主 ——
 * 内容中心卡片上的安装**对话框**（[InstallDialog]），
 * 和内容详情页左栏的**常驻面板**（[InstallPanel]）。
 * 两边要显示的字段完全一样，各自维护一份必然走偏。
 */
data class InstallUiState(
    val project: ProjectUi,
    val kind: String,
    val isPlugin: Boolean,
    val versions: List<ContentVersionUi>,
    val loadingVersions: Boolean,
    val selectedVersion: String?,
    val instances: List<InstallTargetUi>,
    /** 已经按「能否装模组」过滤过（插件则不过滤）。 */
    val servers: List<ServerUi>,
    val selectedInstanceId: String?,
    val selectedServerId: String?,
    val typeFilter: String,
    /** 版本列表里出现过的正式游戏版本（倒序，最多十个），空 = 不显示这一行筛选。 */
    val gameVersions: List<String> = emptyList(),
    val gameFilter: String = "all",
    val deps: List<DependencyUi> = emptyList(),
    val descZh: String? = null,
    val descLoading: Boolean = false,
    val installing: Boolean = false,
    val error: String? = null,
) {
    /** 模组能装服务器或实例；插件只能服务器；资源包/光影只能实例。 */
    val serverTargets: Boolean get() = isPlugin || kind == "mod"

    val canInstall: Boolean
        get() = if (isPlugin) {
            !selectedServerId.isNullOrBlank()
        } else {
            !selectedVersion.isNullOrBlank() &&
                (!selectedServerId.isNullOrBlank() || !selectedInstanceId.isNullOrBlank())
        }
}

/**
 * 详情页顶栏那个「安装」按钮要能触发面板里的安装流程。
 *
 * 状态在 [ContentInstallHost] 内部，按钮却画在外层顶栏（用户要求把安装按钮
 * 放进标题栏），所以用这个句柄把「能不能装 / 正在装 / 点了做什么」递出去。
 */
class ContentInstallHandle {
    internal var installAction: (() -> Unit)? = null
    var canInstall by mutableStateOf(false)
    var installing by mutableStateOf(false)
    fun install() {
        installAction?.invoke()
    }
}

/**
 * 安装目标：一个具备加载器信息的实例。
 *
 * ⚠️ 不能复用 InstancesPage 的 InstanceUi —— 它只有 id/name/subtitle，
 * **没有 loader 与 mc_version**，而版本筛选两者都要用。
 */
data class InstallTargetUi(
    val id: String,
    val name: String,
    /** 加载器：vanilla / fabric / forge / neoforge / quilt */
    val loader: String,
    /** MC 版本（Rust 字段是 version_id）。 */
    val mcVersion: String,
)

/** 一个可选版本。 */
data class ContentVersionUi(
    val id: String,
    val versionNumber: String,
    val type: String,
    val typeLabel: String,
    /** 展示用（最新的两个）。 */
    val gameVersions: String,
    val datePublished: String,
    /**
     * 完整支持的游戏版本。
     *
     * ⚠️ 筛选必须用这个而不是 [gameVersions]：后者为了不撑爆列表只保留了最后两个，
     * 拿它筛「1.20.1」会漏掉同时支持 1.20.1/1.21/1.21.1 的版本。
     */
    val allGameVersions: List<String> = emptyList(),
)

/** 一个依赖。 */
data class DependencyUi(
    val projectId: String,
    val title: String,
    val type: String,
    val typeLabel: String,
    val provider: String,
)
