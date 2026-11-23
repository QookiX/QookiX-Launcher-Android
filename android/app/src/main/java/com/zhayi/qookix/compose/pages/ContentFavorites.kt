package com.zhayi.qookix.compose.pages

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zhayi.qookix.R
import org.json.JSONArray
import org.json.JSONObject

/** 收藏保留卡片数据及安装类型，列表可以直接从本地打开。 */
internal class ContentFavorites(context: Context) {
    companion object {
        const val RECENT_SORT = "recent"
        val SORTS = listOf(
            BrowseRepo.SortOption(RECENT_SORT, R.string.browse_sort_recent_favorites),
            BrowseRepo.SortOption("downloads", R.string.browse_sort_downloads),
        )
    }

    private val prefs = context.getSharedPreferences("qookix_content", Context.MODE_PRIVATE)
    private var translations by mutableStateOf(emptyMap<String, String>())

    private data class Favorite(val project: ProjectUi, val snapshot: JSONObject)

    private var favorites by mutableStateOf(load())
    val items get() = favorites.map { it.project }

    fun contains(project: ProjectUi): Boolean = favorites.any { it.project.contentKey == project.contentKey }

    fun visibleItems(query: String, type: String, sort: String): List<ProjectUi> {
        val keyword = query.trim()
        val filtered = items.filter { type.isEmpty() || it.projectType == type }
            .filter { project ->
                keyword.isEmpty() || sequenceOf(
                    project.title, project.author, project.description, translations[project.contentKey].orEmpty(),
                ).any { it.contains(keyword, ignoreCase = true) }
            }
            .map { it.withDescriptionTranslation(translations) }
        return if (sort == "downloads") filtered.sortedByDescending { it.downloads } else filtered
    }

    fun applyTranslations(values: Map<String, String>) {
        translations = translations + values
    }

    fun toggle(selection: ContentSelection) {
        val project = selection.project.copy(projectType = selection.kind)
        val next = if (contains(project)) {
            favorites.filterNot { it.project.contentKey == project.contentKey }
        } else {
            listOf(Favorite(project, snapshot(project))) + favorites
        }
        save(next)
    }

    fun cloudSnapshot(): JSONArray = JSONArray(encode(favorites).toString())

    fun mergeFromCloud(cloud: JSONArray): Int {
        val existing = favorites.mapTo(hashSetOf()) { it.project.contentKey }
        val additions = decode(cloud).filter { existing.add(it.project.contentKey) }
        if (additions.isNotEmpty()) {
            save((favorites + additions).sortedByDescending { it.snapshot.optLong("addedAt") })
        }
        return additions.size
    }

    private fun save(next: List<Favorite>) {
        prefs.edit().putString("favorites", encode(next).toString()).apply()
        favorites = next
    }

    private fun encode(values: List<Favorite>): JSONArray =
        JSONArray(values.map { it.snapshot })

    private fun load(): List<Favorite> {
        val raw = runCatching { JSONArray(prefs.getString("favorites", "[]") ?: "[]") }
            .getOrNull() ?: return emptyList()
        return decode(raw)
    }

    private fun decode(raw: JSONArray): List<Favorite> {
        return (0 until raw.length()).mapNotNull { index ->
            val entry = raw.optJSONObject(index) ?: return@mapNotNull null
            val id = entry.optString("id")
            val provider = entry.optString("provider")
            val kind = BrowseRepo.normalizeProjectType(entry.optString("project_type"))
                .ifBlank { if (provider == "hangar") "plugin" else "" }
            if (id.isBlank() || provider !in setOf("modrinth", "curseforge", "hangar") ||
                BrowseType.entries.none { it.id == kind }
            ) {
                return@mapNotNull null
            }
            val project = ProjectUi(
                id = id,
                slug = entry.optString("slug"),
                provider = provider,
                title = entry.optString("title", id),
                description = entry.optString("description"),
                author = entry.optString("author"),
                iconUrl = entry.optString("icon_url"),
                downloads = entry.optLong("downloads"),
                projectType = kind,
            )
            val normalized = snapshot(project, entry.optLong("addedAt"))
            entry.keys().forEach { key -> if (!entry.isNull(key)) normalized.put(key, entry.get(key)) }
            normalized.put("key", project.contentKey)
            Favorite(project, normalized)
        }.distinctBy { it.project.contentKey }
    }

    private fun snapshot(item: ProjectUi, addedAt: Long = System.currentTimeMillis()) = JSONObject()
        .put("key", item.contentKey)
        .put("provider", item.provider)
        .put("id", item.id)
        .put("slug", item.slug)
        .put("title", item.title)
        .put("description", item.description)
        .put("author", item.author)
        .put("icon_url", item.iconUrl)
        .put("downloads", item.downloads)
        .put("project_type", item.projectType)
        .put("follows", 0)
        .put("categories", JSONArray())
        .put("updated", "")
        .put("addedAt", addedAt)
}

/** 保留 Android 尚不认识的桌面条目。相同项目以当前设备的记录为准。 */
internal fun mergeFavoriteSnapshots(local: JSONArray, cloud: JSONArray): JSONArray {
    val byKey = linkedMapOf<String, JSONObject>()
    for (snapshot in listOf(local, cloud)) {
        for (index in 0 until snapshot.length()) {
            val entry = snapshot.optJSONObject(index) ?: continue
            val provider = entry.optString("provider")
            val id = entry.optString("id")
            if (provider.isBlank() || id.isBlank() || entry.optString("title").isBlank()) continue
            val key = "$provider:$id"
            val current = byKey[key]
            if (current == null) {
                byKey[key] = JSONObject(entry.toString()).put("key", key)
            } else {
                if (current.optLong("follows") == 0L) current.put("follows", entry.optLong("follows"))
                if (current.optString("updated").isBlank()) current.put("updated", entry.optString("updated"))
                if (current.optLong("addedAt") == 0L) current.put("addedAt", entry.optLong("addedAt"))
                if ((current.optJSONArray("categories")?.length() ?: 0) == 0) {
                    entry.optJSONArray("categories")?.let { current.put("categories", it) }
                }
            }
        }
    }
    return JSONArray(byKey.values.sortedByDescending { it.optLong("addedAt") })
}

internal val ProjectUi.contentKey: String get() = "$provider:$id"

internal fun ProjectUi.withDescriptionTranslation(translations: Map<String, String>): ProjectUi =
    translations[contentKey]?.let { copy(description = it) } ?: this
