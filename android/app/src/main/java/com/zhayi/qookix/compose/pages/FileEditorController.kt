package com.zhayi.qookix.compose.pages

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.zhayi.qookix.R
import com.zhayi.qookix.compose.bridge.friendlyError

/**
 * 纯文本文件的「打开 → 改 → 存」状态机。
 *
 * ── 为什么抽出来（2026-10-26）─────────────────────────────────────────
 * 这份逻辑原来只写在 `PageHost.FileBrowserRoute` 里，实例详情「文件」tab 的
 * `onOpenFile` 是个空 lambda —— 点 `options.txt` / 模组 jar **毫无反应**，
 * 既没有提示也没有预览（真机走查发现）。要修就得让两处共用同一份实现，
 * 而不是再抄一遍（抄出来的第二份必然和第一份走偏）。
 *
 * 大文件不进编辑器（会把界面卡死），[open] 返回 false 由调用方提示。
 */
@Stable
class FileEditorController(
    private val ops: FileOps,
    private val context: Context,
) {
    /** 正在编辑的文件；`null` = 处于列表层。 */
    var state by mutableStateOf<FileEditorUiState?>(null)
        private set

    /**
     * 打开请求的序号。
     *
     * ⚠️ 用来丢弃**迟到的读盘回调**：用户连着点了 A、B 两个文件时，A 的回调可能
     * 晚于 B 到达，若不加判断就会把 A 的内容填进 B 的编辑器 —— 此时点保存，
     * A 的内容就被写进 B 的文件（真机上是「把 options.txt 写成了 server.properties」）。
     * 审查报告里那条「快速连开两个文件会串内容」说的就是这个。
     */
    private var generation = 0

    /**
     * 打开一个文件。
     *
     * @return false = 文件太大，没有打开（调用方负责提示）
     */
    fun open(entry: FileEntryUi): Boolean {
        if (tooLargeToEdit(entry.size)) return false
        val gen = ++generation
        state = FileEditorUiState(
            rel = entry.rel,
            fileName = entry.name,
            size = entry.size,
            loading = true,
        )
        ops.read(entry.rel) { r ->
            // 已经切到别的文件 / 已关闭：这份结果作废
            if (gen != generation) return@read
            val cur = state ?: return@read
            state = r.fold(
                onSuccess = { text ->
                    // originalContent 用于 dirty 比较：改了又改回来应该显示「未修改」
                    cur.copy(content = text, originalContent = text, loading = false)
                },
                onFailure = { e ->
                    cur.copy(
                        loading = false,
                        error = context.getString(R.string.file_read_failed, friendlyError(context, e)),
                    )
                },
            )
        }
        return true
    }

    fun edit(newText: String) {
        val cur = state ?: return
        state = cur.copy(content = newText, dirty = newText != cur.originalContent)
    }

    fun save() {
        val cur = state ?: return
        // 保存的是「点下去那一刻」的内容快照
        val snapshot = cur.content
        state = cur.copy(saving = true)
        ops.write(cur.rel, snapshot) { r ->
            val now = state ?: return@write
            // 保存期间可能已经切到别的文件，这份结果与它无关
            if (now.rel != cur.rel) return@write
            state = r.fold(
                onSuccess = {
                    // ⚠️ 2026-10-26 审查 P1-16：原来无条件 `dirty = false` 且把
                    // originalContent 设成**快照**，于是保存期间继续敲的字会被标成
                    // 「已保存」，关页即丢。现在以真正写下去的那份为基准重新比 dirty。
                    now.copy(
                        saving = false,
                        dirty = now.content != snapshot,
                        originalContent = snapshot,
                        size = snapshot.toByteArray().size.toLong(),
                    )
                },
                onFailure = { e ->
                    now.copy(
                        saving = false,
                        error = context.getString(R.string.settings_save_failed, e.message),
                    )
                },
            )
        }
    }

    fun close() {
        // 序号 +1：关掉之后才回来的读盘结果不许再把编辑器重新打开
        generation++
        state = null
    }
}

/** 记住一个 [FileEditorController]（按 [ops] 区分实例/服务器）。 */
@Composable
fun rememberFileEditor(ops: FileOps, context: Context): FileEditorController =
    remember(ops) { FileEditorController(ops, context) }

/**
 * 「正在编辑就渲染编辑器」的开关。
 *
 * 用法：`if (FileEditorOverlay(ctl)) return` —— 编辑器整页覆盖列表，
 * 并把系统返回键接管成「退出编辑器」（否则会顺着全局 handler 直接跳回首页）。
 */
@Composable
fun FileEditorOverlay(ctl: FileEditorController): Boolean {
    val ed = ctl.state ?: return false
    BackHandler { ctl.close() }
    FileEditorPage(
        state = ed,
        onContentChange = ctl::edit,
        onSave = ctl::save,
        onBack = { ctl.close() },
    )
    return true
}
