package com.zhayi.qookix.compose.bridge

import com.zhayi.qookix.R

/**
 * 结构化错误 → 用户能看懂的中文（A2 错误契约，见 `docs/adr/0002`）。
 *
 * 与 [friendlyError]（按文本关键词猜）的分工：
 *   · 能拿到 `Throwable` 时用这里 —— `code` 是 Rust 给的**稳定分类**
 *     （`src-tauri/src/error_code.rs`），不依赖中英文文案；
 *     文案改动、翻译调整都不影响判断；
 *   · 拿不到 Throwable（纯数据层 / 老代码路径）时，继续用文本匹配的那版。
 *
 * 为什么单独一个文件：`QookixApi.kt` 已经 2300+ 行，是评审点名的巨文件；
 * 新逻辑不再往里塞（文件体量闸门 `scripts/check-file-size.mjs` 会拦）。
 */

/**
 * `code` → 字符串资源（纯函数，JVM 单测可覆盖，不需要 Context）。
 *
 * ⚠️ `auth` 故意**不映射**：它的原文通常已经说清了该去哪儿配 Key / 重新登录
 *（例如「未配置 CurseForge API Key，请到设置 → 内容服务填写」），
 * 换成一句笼统的「需要登录」反而把可操作信息丢了。
 */
internal fun errorResFor(code: String): Int? = when (code) {
    ApiError.CODE_NETWORK -> R.string.error_network
    ApiError.CODE_NOT_FOUND -> R.string.error_not_found
    ApiError.CODE_IO -> R.string.error_disk_io
    ApiError.CODE_PARSE -> R.string.error_bad_response
    else -> null
}

/**
 * 结构化错误 → 用户文案。
 *
 * 非 [ApiError]（老路径 / 本地异常）直接回退到文本匹配版，行为与改造前一致。
 */
fun friendlyError(context: android.content.Context, err: Throwable): String {
    val api = err as? ApiError ?: return friendlyError(context, err.message)
    errorResFor(api.code)?.let { return context.getString(it) }
    // auth / unknown：保留原文（通常已带「去设置填 Key」这类可操作信息）
    return friendlyError(context, api.message)
}
