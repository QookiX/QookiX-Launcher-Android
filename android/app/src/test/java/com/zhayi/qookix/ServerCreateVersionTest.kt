package com.zhayi.qookix

import com.zhayi.qookix.compose.pages.ServerCore
import com.zhayi.qookix.compose.pages.ServerCreateUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerCreateVersionTest {
    private val paper = ServerCore.Paper.id
    private val fabric = ServerCore.Fabric.id
    private val formatError: (Throwable) -> String = { "Failed: ${it.message}" }

    @Test
    fun switchingCoreImmediatelyClearsVersionsAndSelection() {
        val ready = ServerCreateUiState(
            name = "Server",
            version = "1.21.4",
            versions = listOf("1.21.4"),
            loadingVersions = false,
        )
        assertTrue(ready.canCreate)
        val switched = ready.reloadVersions(fabric)
        assertEquals(fabric, switched.core)
        assertEquals("Server", switched.name)
        assertTrue(switched.loadingVersions)
        assertTrue(switched.versions.isEmpty())
        assertEquals("", switched.version)
        assertFalse(switched.canCreate)
    }

    @Test
    fun lateResponsesCannotOverrideNewCoreOrNewRequestForSameCore() {
        val firstPaper = ServerCreateUiState()
        val pendingFabric = firstPaper.reloadVersions(fabric)
        val secondPaper = pendingFabric.reloadVersions(paper)
        val staleSuccess = Result.success(listOf("1.20.1"))
        val staleFailure = Result.failure<List<String>>(IllegalStateException("stale"))
        assertSame(pendingFabric, pendingFabric.finishVersions(firstPaper.versionsRequest, staleSuccess, formatError))
        assertSame(secondPaper, secondPaper.finishVersions(firstPaper.versionsRequest, staleSuccess, formatError))
        assertSame(secondPaper, secondPaper.finishVersions(pendingFabric.versionsRequest, staleFailure, formatError))

        val loaded = secondPaper.finishVersions(secondPaper.versionsRequest, Result.success(listOf("1.21.4")), formatError)
        assertFalse(loaded.loadingVersions)
        assertEquals(listOf("1.21.4"), loaded.versions)
        assertEquals("", loaded.version)
        assertSame(loaded, loaded.finishVersions(firstPaper.versionsRequest, staleFailure, formatError))
    }

    @Test
    fun failureCanRetryInPlaceAndOnlyCurrentVersionIsSubmittable() {
        val initial = ServerCreateUiState(core = fabric, name = "Server")
        val failed = initial.finishVersions(
            initial.versionsRequest,
            Result.failure(IllegalStateException("offline")),
            formatError,
        )
        assertEquals("Failed: offline", failed.versionsError)
        assertFalse(failed.loadingVersions)
        val retry = failed.reloadVersions()
        assertEquals(fabric, retry.core)
        assertEquals("Server", retry.name)
        assertTrue(retry.loadingVersions)
        assertNull(retry.versionsError)
        assertSame(retry, retry.finishVersions(initial.versionsRequest, Result.success(listOf("1.20.1")), formatError))

        val loaded = retry.finishVersions(retry.versionsRequest, Result.success(listOf("1.21.4")), formatError)
        assertFalse(loaded.copy(version = "1.20.1").canCreate)
        assertTrue(loaded.copy(version = "1.21.4").canCreate)
    }
}
