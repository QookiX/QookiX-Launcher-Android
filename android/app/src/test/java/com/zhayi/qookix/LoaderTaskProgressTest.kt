package com.zhayi.qookix

import com.zhayi.qookix.compose.pages.TaskUi
import com.zhayi.qookix.compose.pages.displayFraction
import com.zhayi.qookix.compose.pages.loaderDependencyProgress
import org.junit.Assert.*
import org.junit.Test

class LoaderTaskProgressTest {
    @Test fun previousGameBytesAndOldPipelineStepsDoNotCountAsLoaderProgress() {
        val task = TaskUi(1, "Fabric", "正在安装加载器", 1f, "", "", finished = false,
            stage = "loader", bytesDone = 700, bytesTotal = 700, stepDone = 2, stepTotal = 3)
        assertNull(task.displayFraction)
        assertEquals(1f, task.copy(stage = "download").displayFraction!!, 0f)
        assertEquals(0.5f, task.copy(stage = "loader-libraries", stepDone = 4, stepTotal = 8).displayFraction!!, 0f)
    }

    @Test fun onlyKnownDependencyTotalsProduceProgress() {
        assertNull(loaderDependencyProgress("loader-libraries", 0, 0))
        assertEquals(0f, loaderDependencyProgress("loader-libraries", -1, 8)!!.fraction, 0f)
        assertEquals(1f, loaderDependencyProgress("loader-libraries", 9, 8)!!.fraction, 0f)
    }
}
