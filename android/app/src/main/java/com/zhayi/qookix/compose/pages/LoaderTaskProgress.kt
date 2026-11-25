package com.zhayi.qookix.compose.pages

internal fun isLoaderInstallStage(stage: String): Boolean = stage == "loader" || stage.startsWith("loader-")

internal data class LoaderTaskProgress(val completed: Int, val total: Int) {
    val fraction: Float get() = completed.toFloat() / total
}

// The older native library sent overall 2/3 steps for `loader`; those are not loader progress.
internal fun loaderDependencyProgress(stage: String, done: Int, total: Int): LoaderTaskProgress? =
    if (stage == "loader-libraries" && total > 0) LoaderTaskProgress(done.coerceIn(0, total), total) else null

internal val TaskUi.displayFraction: Float?
    get() = if (!finished && isLoaderInstallStage(stage)) loaderDependencyProgress(stage, stepDone, stepTotal)?.fraction
    else fraction
