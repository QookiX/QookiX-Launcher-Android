package com.zhayi.qookix

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

/**
 * 把**服务器文件目录**（`filesDir/servers/`）作为「文档提供者」暴露给系统。
 *
 * ## 为什么必须自己写 DocumentsProvider
 *
 * 服务器文件在应用私有目录里（`/data/data/<包名>/files/servers/`），第三方文件管理器
 * 根本进不去。先试过用 FileProvider 把目录包成 `content://` 目录 URI 交给
 * `ACTION_VIEW` —— 实测系统文件应用（DocumentsUI）只会打开它自己的「下载/最近」首页，
 * **列不出我们的目录**：FileProvider 不是「能列子目录」的文档提供者，它只能给单个文件
 * 造 URI。DocumentsProvider 才是系统为这种需求准备的正规接口：文件管理器把它当成一个
 * 存储位置（侧栏/根列表里能看到「QookiX 服务器」），浏览、复制、粘贴、重命名、删除
 * 全都由这些回调实现，所以用户能真的把插件 jar 拷进去、把世界存档拷出来。
 *
 * ## 边界与安全
 *
 * - 只暴露 `servers/` 这一棵子树。所有 docId 都做「规范化后必须仍在 baseDir 内」的校验
 *   （防 `../` 穿越），不只是做字符串前缀判断。
 * - provider 由 `android:permission="android.permission.MANAGE_DOCUMENTS"` 保护：
 *   只有系统（SAF / 文件管理器）能访问，其它应用必须经用户授权拿到 URI 才行。
 * - docId 就是「相对 servers/ 的路径」，统一带 `srv:` 前缀 —— 这样根目录的 docId 与
 *   空串区分得开，也避免和别家 provider 的 id 撞。
 */
class ServerDocsProvider : DocumentsProvider() {

  companion object {
    /**
     * 根 id。**必须**等于下面 docId 里「第一个冒号前」的那一段 ——
     * `DocumentsContract.getRootId(documentId)` 就是这么解析的，系统靠它把文档归到某个根上；
     * 对不上（例如根 id 写成带冒号的 `srv:`）时系统认不出这个根，会静默回退到它自己的首页
     * （真机症状：打开后只看到「下载/图片/音频」，看不到我们的目录）。
     */
    const val ROOT_ID = "srv"

    /** 根下各级文档的 docId 前缀（`srv:<相对 servers/ 的路径>`）。 */
    const val DOC_PREFIX = "srv:"

    /** 暴露给系统的目录名（`filesDir/servers`）。 */
    const val DIR_NAME = DataDirs.SERVER  // ⚠️ 走常量（2026-10-17 目录改名）

    private val DOC_COLS = arrayOf(
      Document.COLUMN_DOCUMENT_ID,
      Document.COLUMN_DISPLAY_NAME,
      Document.COLUMN_MIME_TYPE,
      Document.COLUMN_SIZE,
      Document.COLUMN_LAST_MODIFIED,
      Document.COLUMN_FLAGS,
    )

    private val ROOT_COLS = arrayOf(
      Root.COLUMN_ROOT_ID,
      Root.COLUMN_DOCUMENT_ID,
      Root.COLUMN_TITLE,
      Root.COLUMN_SUMMARY,
      Root.COLUMN_FLAGS,
      Root.COLUMN_MIME_TYPES,
      Root.COLUMN_AVAILABLE_BYTES,
    )

    /** 文档 id 里带的扩展名 → MIME：认不出来的给 octet-stream，系统按「未知文件」处理即可。 */
    private fun mimeOf(file: File): String {
      if (file.isDirectory) return Document.MIME_TYPE_DIR
      return when (file.extension.lowercase()) {
        "json", "mcmeta" -> "application/json"
        "txt", "log", "properties", "yml", "yaml", "toml", "cfg", "conf", "md" -> "text/plain"
        "zip" -> "application/zip"
        "jar" -> "application/java-archive"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gz" -> "application/gzip"
        else -> "application/octet-stream"
      }
    }
  }

  override fun onCreate(): Boolean = true

  private fun baseDir(): File = File(context!!.filesDir, DIR_NAME)

  /** docId → 相对 servers/ 的路径（根 = ""）。 */
  private fun relOf(docId: String): String {
    if (docId == ROOT_ID) return ""
    return docId.removePrefix(DOC_PREFIX).trim('/').trimStart(File.separatorChar)
  }

  /** docId → 真实文件；越界一律 FileNotFoundException。 */
  private fun fileOf(docId: String): File {
    val base = baseDir().canonicalFile
    val rel = relOf(docId)
    val f = (if (rel.isEmpty()) base else File(base, rel)).canonicalFile
    if (f != base && !f.path.startsWith(base.path + File.separator)) {
      throw FileNotFoundException("越界路径：$docId")
    }
    return f
  }

  private fun docIdOf(file: File): String {
    val base = baseDir().canonicalFile
    val rel = file.canonicalFile.toRelativeString(base).trim('/')
    return if (rel.isEmpty() || rel == ".") ROOT_ID else DOC_PREFIX + rel
  }

  private fun addDocRow(cursor: MatrixCursor, cols: Array<out String>, file: File, docId: String) {
    val isDir = file.isDirectory
    // 目录要给出 SUPPORTS_CREATE，文件管理器才会显示「新建/粘贴」；
    // 文件名/删除/重命名这几个由 renameDocument / deleteDocument 实现。
    val flags = if (isDir) {
      Document.FLAG_DIR_SUPPORTS_CREATE or
        Document.FLAG_SUPPORTS_DELETE or
        Document.FLAG_SUPPORTS_RENAME or
        Document.FLAG_DIR_PREFERS_GRID
    } else {
      Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or Document.FLAG_SUPPORTS_WRITE
    }
    val row = cursor.newRow()
    for (col in cols) {
      when (col) {
        Document.COLUMN_DOCUMENT_ID -> row.add(docId)
        Document.COLUMN_DISPLAY_NAME -> row.add(file.name.ifEmpty { DIR_NAME })
        Document.COLUMN_MIME_TYPE -> row.add(mimeOf(file))
        Document.COLUMN_SIZE -> row.add(if (isDir) 0L else file.length())
        Document.COLUMN_LAST_MODIFIED -> row.add(file.lastModified() / 1000)
        Document.COLUMN_FLAGS -> row.add(flags)
        else -> row.add(null)
      }
    }
  }

  override fun queryRoots(projection: Array<out String>?): Cursor {
    val cols = projection ?: ROOT_COLS
    val cursor = MatrixCursor(cols, 1)
    // 一份根：整个 servers 目录（每台服务器是它的一个子目录）。
    // 不做「每台服务器一个根」是因为根列表在系统文件管理器里是常驻的，
    // 服务器增删频繁会把它刷得很乱。
    val row = cursor.newRow()
    for (col in cols) {
      when (col) {
        Root.COLUMN_ROOT_ID -> row.add(ROOT_ID)
        // 根的「文档 id」按约定与根 id 相同（不能带冒号，否则 getRootId 解析出的不是本根）
        Root.COLUMN_DOCUMENT_ID -> row.add(ROOT_ID)
        Root.COLUMN_TITLE -> row.add("QookiX 服务器")
        Root.COLUMN_SUMMARY -> row.add("服务器文件：世界、配置、插件与模组")
        Root.COLUMN_FLAGS -> row.add(
          Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD,
        )
        Root.COLUMN_MIME_TYPES -> row.add("*/*")
        Root.COLUMN_AVAILABLE_BYTES -> row.add(baseDir().usableSpace)
        else -> row.add(null)
      }
    }
    return cursor
  }

  override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
    val cols = projection ?: DOC_COLS
    val cursor = MatrixCursor(cols, 1)
    addDocRow(cursor, cols, fileOf(documentId), documentId)
    return cursor
  }

  override fun queryChildDocuments(
    parentDocumentId: String,
    projection: Array<out String>?,
    sortOrder: String?,
  ): Cursor {
    val cols = projection ?: DOC_COLS
    val parent = fileOf(parentDocumentId)
    val cursor = MatrixCursor(cols)
    val children = parent.listFiles()
    if (children != null) {
      // 目录在前、同类按名字排：文件管理器默认按自己的规则再排一次，这里给个稳定顺序
      for (child in children.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))) {
        addDocRow(cursor, cols, child, docIdOf(child))
      }
    }
    return cursor
  }

  override fun openDocument(
    documentId: String,
    mode: String,
    signal: CancellationSignal?,
  ): ParcelFileDescriptor {
    val file = fileOf(documentId)
    if (file.isDirectory) throw FileNotFoundException("目录不能当文件打开：$documentId")
    val flags = when (mode) {
      "r" -> ParcelFileDescriptor.MODE_READ_ONLY
      "w", "wt" -> ParcelFileDescriptor.MODE_WRITE_ONLY or
        ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
      "wa" -> ParcelFileDescriptor.MODE_WRITE_ONLY or
        ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_APPEND
      else -> ParcelFileDescriptor.MODE_READ_WRITE
    }
    return ParcelFileDescriptor.open(file, flags)
  }

  override fun createDocument(
    parentDocumentId: String,
    mimeType: String,
    displayName: String,
  ): String {
    val parent = fileOf(parentDocumentId)
    if (!parent.isDirectory) throw FileNotFoundException("父级不是目录：$parentDocumentId")
    var target = File(parent, displayName)
    // 同名不覆盖：按系统惯例改成「name (1).ext」——文件管理器「粘贴」到已有同名文件时
    // 直接覆盖会把用户的世界/配置悄悄冲掉。
    if (target.exists()) target = uniqueName(parent, displayName)
    if (mimeType == Document.MIME_TYPE_DIR) {
      if (!target.mkdirs()) throw FileNotFoundException("新建目录失败：${target.name}")
    } else {
      if (!target.createNewFile()) throw FileNotFoundException("新建文件失败：${target.name}")
    }
    return docIdOf(target)
  }

  override fun deleteDocument(documentId: String) {
    val f = fileOf(documentId)
    if (f == baseDir().canonicalFile) throw FileNotFoundException("不能删除根目录")
    if (!f.deleteRecursively()) throw FileNotFoundException("删除失败：${f.name}")
  }

  override fun renameDocument(documentId: String, displayName: String): String {
    val f = fileOf(documentId)
    val dest = File(f.parentFile, displayName)
    if (dest.exists()) throw FileNotFoundException("已存在同名项：$displayName")
    if (!f.renameTo(dest)) throw FileNotFoundException("重命名失败：${f.name}")
    return docIdOf(dest)
  }

  override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
    runCatching {
      val parent = fileOf(parentDocumentId)
      val child = fileOf(documentId)
      child != parent && child.path.startsWith(parent.path + File.separator)
    }.getOrDefault(false)

  override fun getDocumentType(documentId: String): String = mimeOf(fileOf(documentId))

  /**
   * 从根到某个文档的路径（系统文件应用打开**文件夹**时用它拼面包屑/返回栈）。
   *
   * **必须实现**：`DocumentsProvider` 的默认实现直接抛
   * `UnsupportedOperationException("findDocumentPath not supported.")`，而 Android 16 的
   * 文件应用遇到这个异常会**静默退回它自己的首页**（实测症状：点了「打开目录」，打开的是
   * 「下载/图片/音频」，看不到我们的目录；logcat 里是
   * `LoadDocStackTask: Failed to build document stack ... findDocumentPath not supported`）。
   */
  override fun findDocumentPath(
    parentDocumentId: String?,
    childDocumentId: String,
  ): DocumentsContract.Path {
    val target = childDocumentId.ifEmpty { ROOT_ID }
    val rel = relOf(target)
    val ids = mutableListOf(ROOT_ID)
    if (rel.isNotEmpty()) {
      var acc = ""
      for (part in rel.split('/')) {
        acc = if (acc.isEmpty()) part else "$acc/$part"
        ids.add(DOC_PREFIX + acc)
      }
    }
    // 传了 parent 就必须是路径上的祖先，否则按「找不到」处理（系统据此判断越界）
    if (parentDocumentId != null && parentDocumentId !in ids) {
      throw FileNotFoundException("$parentDocumentId 不是 $childDocumentId 的祖先")
    }
    return DocumentsContract.Path(ROOT_ID, ids)
  }

  private fun uniqueName(parent: File, displayName: String): File {
    val dot = displayName.lastIndexOf('.')
    val stem = if (dot > 0) displayName.substring(0, dot) else displayName
    val ext = if (dot > 0) displayName.substring(dot) else ""
    for (i in 1..999) {
      val f = File(parent, "$stem ($i)$ext")
      if (!f.exists()) return f
    }
    throw FileNotFoundException("同名文件太多：$displayName")
  }
}

