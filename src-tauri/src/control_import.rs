//! 导入控制布局的**最后一步**：把中转文件落成正式布局。
//!
//! ## 为什么需要这一步
//!
//! 原生侧（`ComposeMainActivity.importControlLayoutFromUri`）只负责把用户挑的
//! 文件读出来、校验（`version` + `mControlDataList`）、写进
//! `controlmap/TMP_IMPORT_FILE.json`，然后回一份 `{"ok":true,…}`。
//!
//! 这一步以前**到此为止**了 —— 没有任何代码把 `TMP_IMPORT_FILE` 变成
//! 玩家能看到的布局，而 `list_control_layouts` 又刻意跳过 `TMP_IMPORT*`
//! （那是中转文件，不该出现在列表里）。于是「导入成功」只剩一行 Toast，
//! 列表、当前布局、游戏内全都不变 —— 用户看到的就是「导入没反应」。
//!
//! Pojav 原版是靠 `ImportControlActivity` → `CustomControlsActivity(preview)`
//! → `confirmImportedLayout()` 那套界面完成落名的；启动器走的是 Compose
//! 导入按钮这条路，没有那一环，所以在这里补一个不依赖界面的版本：
//! 用源文件名当布局名（重名自动加序号）。
//!
//! 落名之后 `list_control_layouts` 自然就能列出它，`keysReload` 一刷就可见。

use serde_json::Value;

/// 中转文件的布局名（与 `ComposeMainActivity` 写的文件名一致，不含 `.json`）。
const TEMP_NAME: &str = "TMP_IMPORT_FILE";

/// 名字缺失 / 非法时的兜底。
const FALLBACK_NAME: &str = "导入的布局";

/// 把 `result`（原生侧返回的那份 JSON）里的导入结果落成正式布局。
///
/// 成功时补上 `saved_as`（前端拿它做提示），失败时把 `ok` 改成 false
/// 并写 `error` —— 调用方只认这两个字段，不需要额外分支。
pub fn finalize(result: &mut Value) {
    if result.get("ok").and_then(|b| b.as_bool()) != Some(true) {
        return;
    }
    let src = match crate::controls::layout_file(TEMP_NAME) {
        Ok(p) => p,
        Err(e) => return fail(result, e),
    };
    if !src.is_file() {
        return fail(result, "导入的中转文件不见了，请重新选择一次".to_string());
    }

    let wanted = result
        .get("name")
        .and_then(|n| n.as_str())
        .unwrap_or("")
        .trim()
        .trim_end_matches(".json")
        .to_string();
    let Some(name) = unique_name(&wanted) else {
        return fail(result, "无法生成布局文件名".to_string());
    };

    let dst = match crate::controls::layout_file(&name) {
        Ok(p) => p,
        Err(e) => return fail(result, e),
    };
    if let Err(e) = std::fs::copy(&src, &dst) {
        return fail(result, format!("写入布局失败：{e}"));
    }
    if let Some(obj) = result.as_object_mut() {
        obj.insert("saved_as".to_string(), Value::String(name));
    }
    crate::util::log_line(&format!(
        "[controls] 导入的布局已落盘：{}",
        dst.display()
    ));
}

fn fail(result: &mut Value, error: String) {
    crate::util::log_line(&format!("[controls] 导入落盘失败：{error}"));
    if let Some(obj) = result.as_object_mut() {
        obj.insert("ok".to_string(), Value::Bool(false));
        obj.insert("error".to_string(), Value::String(error));
    }
}

/// 把源文件名洗成合法布局名，重名时加「 (2)」「 (3)」……
///
/// 返回 `None` 表示连兜底名都写不进去（目录不可写之类），由调用方报错。
fn unique_name(wanted: &str) -> Option<String> {
    let base = sanitize(wanted);
    if let Ok(name) = crate::controls::validate_name(&base) {
        if let Some(free) = with_free_suffix(&name) {
            return Some(free);
        }
    }
    let fallback = sanitize(FALLBACK_NAME);
    with_free_suffix(&crate::controls::validate_name(&fallback).ok()?)
}

/// 去掉非法字符、限制长度（`validate_name` 上限 32 字符）。
fn sanitize(raw: &str) -> String {
    let cleaned: String = raw
        .trim()
        .chars()
        .map(|c| if "/\\:*?\"<>|".contains(c) { '_' } else { c })
        .collect();
    let trimmed = cleaned.trim_matches(['.', ' ']).to_string();
    if trimmed.chars().count() > 28 {
        trimmed.chars().take(28).collect()
    } else if trimmed.is_empty() {
        String::new()
    } else {
        trimmed
    }
}

/// 找一个还没被占用的名字；全都占用时返回 `None`。
fn with_free_suffix(name: &str) -> Option<String> {
    let first = crate::controls::layout_file(name).ok()?;
    if !first.exists() {
        return Some(name.to_string());
    }
    // 只试到 99：再多说明冲突异常，交给调用方报错，别无限循环
    (2..=99).find_map(|i| {
        let candidate = format!("{name} ({i})");
        let path = crate::controls::layout_file(&candidate).ok()?;
        (!path.exists()).then_some(candidate)
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sanitize_strips_path_separators_and_bounds_length() {
        assert_eq!(sanitize("  SweetLayout.json "), "SweetLayout.json");
        assert_eq!(sanitize("a/b:c"), "a_b_c");
        assert_eq!(sanitize("..."), "");
        let long = "布".repeat(40);
        assert_eq!(sanitize(&long).chars().count(), 28);
    }

    #[test]
    fn non_ok_results_are_left_alone() {
        let mut v = serde_json::json!({"ok": false, "error": "已取消"});
        finalize(&mut v);
        assert_eq!(v["error"], "已取消");
        assert!(v.get("saved_as").is_none());
    }
}
