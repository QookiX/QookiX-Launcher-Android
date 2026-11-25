package com.zhayi.qookix.compose.pages

import java.util.Locale

/** Only open an exact project reference; never guess from a local filename or version ID. */
internal fun ContentUi.resourceCenterSelection(kind: String): ContentSelection? {
    val source = provider.trim().lowercase(Locale.ROOT)
    val id = projectId.trim()
    val supported = when (source) {
        "modrinth" -> id.matches(Regex("[A-Za-z0-9_-]+")) && !id.equals("null", ignoreCase = true)
        "curseforge" -> id.toLongOrNull()?.let { it > 0 && id.all { ch -> ch in '0'..'9' } } == true
        else -> false
    }
    if (!supported || kind !in setOf("mod", "resourcepack", "shader")) return null
    return ContentSelection(
        ProjectUi(id = id, provider = source, title = name, description = "", author = "",
            iconUrl = "", downloads = 0, projectType = kind),
        kind,
    )
}
