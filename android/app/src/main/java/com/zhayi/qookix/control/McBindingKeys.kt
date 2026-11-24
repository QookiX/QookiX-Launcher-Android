package com.zhayi.qookix.control

import net.kdt.pojavlaunch.utils.MCOptionUtils

/**
 * 把 MC 的功能绑定名解析成玩家当前设置的 GLFW 键码。
 *
 * `options.txt` 里同一项有两种历史格式：
 *  - 1.8.9 及更早：`key_key.inventory:18`，数字是 **Linux input code**
 *    （与 FCL 的键码体系一致），需要先经 [ImportedControlKeys.fcl] 换成 GLFW 名；
 *  - 1.13+：`key_key.inventory:key.keyboard.b`，直接查
 *    [ImportedControlKeys.zalithAliases]。
 *
 * 只给内置布局里标了 `binding` 的按钮用；导入的 FCL / ZL2 布局绑的是具体键，
 * 不该被玩家改键影响。
 */
object McBindingKeys {
    /** 返回 0 表示没有配置 / 解析失败，调用方应回落到硬编码 keycode。 */
    @JvmStatic
    fun resolve(binding: String?): Int {
        if (binding.isNullOrEmpty()) return 0
        val raw = MCOptionUtils.get("key_$binding")?.trim().orEmpty()
        if (raw.isEmpty()) return 0

        // 1.8.9 及更早：纯数字（Linux input code）
        raw.toIntOrNull()?.let { code ->
            val glfwName = ImportedControlKeys.fcl[code] ?: return 0
            return ImportedControlKeys.glfw[glfwName] ?: 0
        }
        // 1.13+：key.keyboard.b
        val glfwName = ImportedControlKeys.zalithAliases[raw] ?: return 0
        return ImportedControlKeys.glfw[glfwName] ?: 0
    }
}
