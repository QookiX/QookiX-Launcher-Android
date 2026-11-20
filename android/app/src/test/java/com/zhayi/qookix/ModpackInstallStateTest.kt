package com.zhayi.qookix

import com.zhayi.qookix.compose.pages.BrowseRepo
import com.zhayi.qookix.compose.pages.ContentVersionUi
import com.zhayi.qookix.compose.pages.InstallTargetUi
import com.zhayi.qookix.compose.pages.InstallUiState
import com.zhayi.qookix.compose.pages.ProjectUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 整合包安装状态机与内容类型归一化测试：
 * 验证整合包在零实例时仍可直接选版本安装（新建实例），且不被当作模组处理。
 */
class ModpackInstallStateTest {

    private fun sampleProject(type: String = "modpack") = ProjectUi(
        id = "pack-1",
        slug = "better-mc",
        provider = "modrinth",
        title = "Better MC",
        description = "Modpack",
        author = "Author",
        iconUrl = "",
        downloads = 100L,
        projectType = type,
    )

    private fun sampleVersion() = ContentVersionUi(
        id = "ver-1",
        versionNumber = "1.0.0",
        type = "release",
        typeLabel = "正式版",
        gameVersions = "1.20.1",
        datePublished = "",
    )

    @Test
    fun normalizeProjectType_handles_modrinth_and_curseforge_class_ids() {
        assertEquals("modpack", BrowseRepo.normalizeProjectType("modpack"))
        assertEquals("modpack", BrowseRepo.normalizeProjectType(" MODPACK "))
        assertEquals("modpack", BrowseRepo.normalizeProjectType("4471"))
        assertEquals("mod", BrowseRepo.normalizeProjectType("6"))
        assertEquals("resourcepack", BrowseRepo.normalizeProjectType("12"))
        assertEquals("shader", BrowseRepo.normalizeProjectType("6552"))
        assertEquals("", BrowseRepo.normalizeProjectType(""))
    }

    @Test
    fun modpack_canInstall_without_existing_instances_or_servers() {
        val ready = InstallUiState(
            project = sampleProject("modpack"),
            kind = "modpack",
            isPlugin = false,
            isModpack = true,
            versions = listOf(sampleVersion()),
            loadingVersions = false,
            selectedVersion = "ver-1",
            instances = emptyList(),
            servers = emptyList(),
            selectedInstanceId = null,
            selectedServerId = null,
            typeFilter = "all",
        )
        assertFalse(ready.serverTargets)
        assertTrue(ready.canInstall)

        val missingVersion = ready.copy(selectedVersion = null)
        assertFalse(missingVersion.canInstall)
    }

    @Test
    fun mod_still_requires_target_and_no_compat_warning() {
        val base = InstallUiState(
            project = sampleProject("mod"),
            kind = "mod",
            isPlugin = false,
            isModpack = false,
            versions = listOf(sampleVersion()),
            loadingVersions = false,
            selectedVersion = "ver-1",
            instances = listOf(InstallTargetUi("inst-1", "Fabric 1.20.1", "fabric", "1.20.1")),
            servers = emptyList(),
            selectedInstanceId = "inst-1",
            selectedServerId = null,
            typeFilter = "all",
        )
        assertTrue(base.serverTargets)
        assertTrue(base.canInstall)
        assertFalse(base.copy(selectedInstanceId = null).canInstall)
        assertFalse(base.copy(compatWarning = "incompatible").canInstall)
    }
}
