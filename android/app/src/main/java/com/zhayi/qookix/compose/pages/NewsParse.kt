package com.zhayi.qookix.compose.pages

import android.content.Context
import android.util.Log
import com.zhayi.qookix.R
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 解析 Rust `fetch_news` 返回的 JSON 数组。
 *
 * 字段与 `browse.rs::fetch_news` 的 `json!({...})` 一一对应：
 *   title / description / author / time(**秒**时间戳) / image / image_alt / url
 *
 * ⚠️ `time` 是 **秒**，不是毫秒 —— 实测踩到（2026-10-13）：
 * 上游 minecraft.net 返回的是秒（如 1791763200），
 * 而我按毫秒写 `Date(time)` → 显示成 **1970年1月22日**。
 *
 * 本项目所有外部时间戳统一按「秒」处理（与 fsutil::modified_secs 一致）。
 *
 * ⚠️ i18n：日期 pattern 现在来自 string 资源（`news_date_month_day` /
 * `news_date_year_month_day`），所以需要 [Context]。无 context 的重载保留兼容，
 * 回退到系统 locale 的最佳日期 pattern。
 */
fun parseNews(json: String): List<NewsItemUi> = parseNews(null, json)

/** 带 [context] 的重载：日期 pattern 走 string 资源（可翻译）。 */
fun parseNews(context: Context?, json: String): List<NewsItemUi> {
    return try {
        val arr = JSONArray(json)
        // ⚠️ url 是列表的 key，必须唯一且非空（2026-10-26 审查 P1-19）：
        // 两条缺 url 的新闻 key 都是 ""，LazyColumn 直接抛
        // `IllegalArgumentException: Key ... was already present` 崩掉整页。
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val title = o.optString("title").trim()
            if (title.isEmpty()) return@mapNotNull null
            NewsItemUi(
                title = title,
                description = o.optString("description"),
                author = o.optString("author"),
                date = fmtDateShort(context, o.optLong("time", 0L)),
                url = o.optString("url"),
                image = o.optString("image"),
            )
        }.filter { it.url.isNotBlank() }.distinctBy { it.url }
    } catch (e: Exception) {
        Log.w("NewsPage", "解析新闻失败", e)
        emptyList()
    }
}

/**
 * 短日期格式，对齐旧前端 `utils/format.ts` 的 `fmtDateShort`。
 *
 * 行为：今天显示「HH:mm」，今年显示「M月d日」，更早显示「yyyy年M月d日」。
 * 与旧实现保持一致的目的是避免同一个用户在两个界面上看到不同格式。
 *
 * ⚠️ i18n（2026-10-17）：原来把「M月d日」「yyyy年M月d日」写死在
 * `SimpleDateFormat` 的 pattern 里 —— 等于把中文日期格式焊死，
 * 换任何语言都还是「3月5日」。现在 pattern 走 string 资源
 * （译文里可以写 `MMM d` / `d. MMM` 等），无 context 时回退到
 * 系统 locale 的 `MMMd` / `yMMMd` 最佳 pattern。
 */
private fun fmtDateShort(context: Context?, seconds: Long): String {
    if (seconds <= 0L) return ""
    // ⚠️ 入参是「秒」，而 Date 要「毫秒」—— 必须 ×1000。
    //
    // 实测踩到（2026-10-13）：上游 minecraft.net 返回 1791763200（秒），
    // 我按毫秒直接 Date(1791763200) → 显示成 1970年1月22日。
    //
    // 本项目所有外部时间戳统一按「秒」处理
    // （与 fsutil::modified_secs 一致）。
    val d = Date(seconds * 1000)
    val now = Date()

    val locale = Locale.getDefault()
    val sameDay = SimpleDateFormat("yyyyMMdd", locale)
        .format(d) == SimpleDateFormat("yyyyMMdd", locale).format(now)
    if (sameDay) return SimpleDateFormat("HH:mm", locale).format(d)

    val sameYear = SimpleDateFormat("yyyy", locale)
        .format(d) == SimpleDateFormat("yyyy", locale).format(now)
    val pattern = if (sameYear) {
        context?.getString(R.string.news_date_month_day)
            ?: android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMd")
    } else {
        context?.getString(R.string.news_date_year_month_day)
            ?: android.text.format.DateFormat.getBestDateTimePattern(locale, "yMMMd")
    }
    return SimpleDateFormat(pattern, locale).format(d)
}

