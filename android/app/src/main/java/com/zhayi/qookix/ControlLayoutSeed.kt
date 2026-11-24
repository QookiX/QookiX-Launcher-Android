package com.zhayi.qookix

import android.content.Context
import android.util.Log
import net.kdt.pojavlaunch.Tools
import java.io.File

/**
 * 内置默认控制布局的落盘（`<files>/controlmap/default.json`）。
 *
 * ## 为什么单独抽出来
 *
 * 这份布局原先只在 **GameActivity.onCreate**（= 游戏启动）时写入。于是「从没进过游戏」的
 * 用户（新装机 / 清过数据 / 刚换机型）在启动器里点「编辑布局」时，原生编辑器拿到的
 * `PREF_DEFAULTCTRL_PATH` 指向一个**不存在的文件**，`ControlLayout.loadLayout` 直接抛：
 *
 * ```text
 * 控制层异常
 * java.nio.file.NoSuchFileException: /data/user/0/com.zhayi.qookix/files/controlmap/default.json
 *   at net.kdt.pojavlaunch.Tools.read
 *   at net.kdt.pojavlaunch.customcontrols.LayoutConverter.loadAndConvertIfNecessary
 *   at net.kdt.pojavlaunch.customcontrols.ControlLayout.loadLayout
 *   at net.kdt.pojavlaunch.CustomControlsActivity.onCreate
 * ```
 *
 * 用户看到的是「点了编辑布局 → 弹报错 + 编辑器一片黑」（2026-10-09 反馈）。
 * 现在**三个入口都先调一次 [ensure]**：游戏启动（GameActivity）、启动器启动（MainActivity）、
 * 编辑器自己（CustomControlsActivity，兼任「指定那份不在了」的兜底）。
 *
 * ## 升级语义
 *
 * 只在「文件不存在」或「皮肤版本号变了」时写入，版本标记放在同目录的 [STYLE_MARKER] 里。
 * 版本变化一律换成新版内置布局，旧文件先备份为 `default.user-backup.json`。
 * 曾经靠单个 SHA 判断「是不是原样内置布局」来决定跳不跳过，内置布局一改这个判断就失效，
 * 老用户永远拿不到新按钮和新场景标记 —— 改为按版本覆盖，靠备份兜底。
 */
object ControlLayoutSeed {

    private const val TAG = "QookiXControl"

    /** 内置布局的皮肤版本，见类注释。 */
    // 7：内置布局的功能键补上 binding，玩家在游戏里改键后控件会跟着走。
    private const val CONTROL_STYLE_VERSION = 7
    private const val STYLE_MARKER = ".qk-control-style"
    private const val USER_BACKUP = "default.user-backup.json"

    /**
     * 确保 `<files>/controlmap/default.json` 存在、且是当前皮肤版本。
     *
     * @return true = 文件可用（本来就在，或这次写成功）；false = 真的写不出来。
     *         调用方拿 false 时应该退回自己的兜底（编辑器会再试一次内置路径）。
     */
    @JvmStatic
    fun ensure(ctx: Context): Boolean {
        // 存储常量（`Tools.CTRLDEF_FILE`）由 `Tools.initStorageConstants` 初始化；
        // 调用方可能还没做过（启动器 onCreate 就属于这种），这里补一次。
        val defPath = Tools.CTRLDEF_FILE ?: run {
            Tools.initStorageConstants(ctx)
            Tools.CTRLDEF_FILE
        } ?: return false

        val target = File(defPath)
        target.parentFile?.mkdirs()

        val marker = File(target.parentFile, STYLE_MARKER)
        val installed = try {
            marker.readText().trim()
        } catch (_: Throwable) {
            ""
        }
        val wanted = CONTROL_STYLE_VERSION.toString()
        if (target.isFile && target.length() > 0L && installed == wanted) return true

        return try {
            // 版本变化就覆盖，旧布局备份一次（保留用户第一次手调的版本，不被后续升级冲掉）。
            if (target.isFile && target.length() > 0L) {
                val backup = File(target.parentFile, USER_BACKUP)
                runCatching { if (!backup.exists()) target.copyTo(backup) }
                    .onFailure { Log.w(TAG, "备份用户默认布局失败", it) }
            }
            ctx.resources.openRawResource(R.raw.pojav_default_control).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            marker.writeText(wanted)
            Log.i(TAG, "内置控制布局已更新到皮肤版本 $wanted（原版本 '${installed.ifEmpty { "无" }}'，用户旧版已备份为 default.user-backup.json）")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "默认控制布局写入失败", e)
            // 写不出来但文件还在（比如只读文件系统）：当可用，别让调用方再走一遍兜底
            target.isFile
        }
    }

}
