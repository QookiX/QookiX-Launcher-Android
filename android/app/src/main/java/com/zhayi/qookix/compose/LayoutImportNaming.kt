package com.zhayi.qookix.compose

/**
 * 导入控制布局的默认名字：取源文件名（去掉 `.json`），非法字符换成 `_`。
 *
 * ## 为什么需要
 *
 * 原生侧把玩家挑的文件落到 `controlmap/TMP_IMPORT_FILE.json` 之后，
 * 以前固定用 `TMP_IMPORT_FILE` 当名字 —— 而布局列表刻意跳过 `TMP_IMPORT*`
 * （那是中转文件）。于是导入成功后列表永远不长出新布局，用户看到的就是
 * 「导入了没反应」。
 *
 * 现在这个名字会一路带到 Rust 侧的 `control_import::finalize`，由它把中转文件
 * 复制成 `<这个名字>.json`，布局才真正出现在列表里。
 *
 * 放在单独文件而不是 `ComposeMainActivity` 里：那个文件已经顶到体量预算，
 * 这类无状态的小函数塞进去只会让它更难维护。
 */
internal fun importLayoutName(raw: String?): String =
    (raw ?: "").substringAfterLast('/').removeSuffix(".json")
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .trim()
        .ifBlank { "导入的布局" }
