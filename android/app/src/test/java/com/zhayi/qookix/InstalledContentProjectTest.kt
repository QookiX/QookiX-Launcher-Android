package com.zhayi.qookix

import com.zhayi.qookix.compose.pages.ContentUi
import com.zhayi.qookix.compose.pages.resourceCenterSelection
import org.junit.Assert.*
import org.junit.Test

class InstalledContentProjectTest {
    private val content = ContentUi("example.jar", "Example", provider = "modrinth", projectId = "A1b2C3d4", versionId = "other")

    @Test fun usesProjectNotInstalledVersionAndPreservesKind() {
        val selection = content.resourceCenterSelection("shader")!!
        assertEquals("A1b2C3d4", selection.project.id)
        assertEquals("modrinth", selection.project.provider)
        assertEquals("shader", selection.kind)
        assertEquals("12345", content.copy(provider = "CurseForge", projectId = "12345").resourceCenterSelection("mod")!!.project.id)
    }

    @Test fun doesNotGuessMissingOrInvalidReferences() {
        for (item in listOf(content.copy(provider = "manual"), content.copy(provider = ""),
            content.copy(projectId = "null"), content.copy(projectId = ""),
            content.copy(provider = "curseforge", projectId = "../123"))) {
            assertNull(item.resourceCenterSelection("mod"))
        }
    }
}
