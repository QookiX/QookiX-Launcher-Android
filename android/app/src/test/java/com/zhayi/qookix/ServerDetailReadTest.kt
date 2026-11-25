package com.zhayi.qookix

import com.zhayi.qookix.compose.pages.DetailRead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerDetailReadTest {
    @Test
    fun manualRefreshesJoinThePendingPollAndReceiveTheSameResult() {
        val loads = mutableListOf<(Result<List<String>>) -> Unit>()
        val read = DetailRead<List<String>> { loads.add(it) }
        val received = mutableListOf<List<String>>()

        assertTrue(read.request())
        repeat(8) { assertFalse(read.request()) }
        repeat(2) { assertFalse(read.request { received.add(it.getOrThrow()) }) }
        assertEquals(1, loads.size)

        val lines = listOf("Starting server")
        loads.single()(Result.success(lines))
        assertEquals(listOf(lines, lines), received)
        assertTrue(read.request())
        assertEquals(2, loads.size)
    }

    @Test
    fun failureIsDeliveredAndDoesNotPreventLaterReads() {
        lateinit var finish: (Result<String>) -> Unit
        val read = DetailRead<String> { finish = it }
        val failure = IllegalStateException("log read failed")
        var delivered: Throwable? = null

        assertTrue(read.request { delivered = it.exceptionOrNull() })
        finish(Result.failure(failure))
        assertEquals(failure, delivered)
        assertTrue(read.request())
    }
}
