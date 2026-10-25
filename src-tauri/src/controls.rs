//! 手机端的「按键」= 屏幕上的触控控制层（虚拟按键 / 摇杆 / 组合键抽屉）。
//!
//! 布局文件放在 `<files>/controlmap/<名字>.json`，**全局共享、不区分实例**；
//! 细调（大小、颜色、键位映射、组合键）在游戏内右侧抽屉的「自定义控制布局」里做，
//! 这里只负责启动器侧能做的事：
//!
//! 1. 「按键透传」的读取与修改 —— 透传 = `ControlData.passThruEnabled`：按住这个键时，
//!    手指滑动会转发给游戏画面，也就是「按住跳跃也能拖视角」。默认布局里只有跳跃开着。
//! 2. 布局管理 —— 列出 / 切换当前布局 / 复制 / 重命名 / 删除。
//!
//! 当前生效的布局由 pojav 偏好 `defaultCtrl` 指向（默认 `<files>/controlmap/default.json`），
//! 改动游戏下次启动读取时生效。

use std::path::{Path, PathBuf};

use serde::Serialize;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ControlButtonInfo {
    /// 在 `mControlDataList` 里的下标（写回时用它定位）
    pub index: usize,
    pub name: String,
    pub pass_thru: bool,
}

/// 当前生效的控制布局文件；找不到就报错（前端显示为「还没生成布局」）。
fn layout_path() -> Result<PathBuf, String> {
    // pojav 偏好里记着用户选的布局（「选择默认控制布局」会写它）
    if let Ok(json) = crate::android_bridge::read_pojav_prefs() {
        if let Ok(value) = serde_json::from_str::<serde_json::Value>(&json) {
            if let Some(path) = value.get("defaultCtrl").and_then(|v| v.as_str()) {
                let path = PathBuf::from(path);
                if path.is_file() {
                    return Ok(path);
                }
            }
        }
    }

    let data_dir = crate::settings::data_dir_sync().ok_or_else(|| "拿不到数据目录".to_string())?;
    let fallback = Path::new(&data_dir).join("controlmap/default.json");
    if fallback.is_file() {
        Ok(fallback)
    } else {
        Err("还没生成控制布局文件，先进一次游戏".to_string())
    }
}

fn read_layout() -> Result<(PathBuf, serde_json::Value), String> {
    let path = layout_path()?;
    let text = std::fs::read_to_string(&path).map_err(|e| format!("读取布局失败：{e}"))?;
    let value: serde_json::Value =
        serde_json::from_str(&text).map_err(|e| format!("布局不是合法 JSON：{e}"))?;
    Ok((path, value))
}

/// 列出布局里的按钮（顺序与游戏内一致）。
pub async fn get_control_buttons() -> Result<Vec<ControlButtonInfo>, String> {
    let (_, value) = read_layout()?;
    Ok(collect_buttons(&value))
}

/// 改一个按钮的透传开关，返回改完后的完整列表。
pub async fn set_control_button_passthru(
    index: usize,
    enabled: bool,
) -> Result<Vec<ControlButtonInfo>, String> {
    let (path, mut value) = read_layout()?;
    let list = value
        .get_mut("mControlDataList")
        .and_then(|v| v.as_array_mut())
        .ok_or_else(|| "布局里没有按钮列表".to_string())?;
    let entry = list
        .get_mut(index)
        .ok_or_else(|| format!("按钮下标 {index} 不存在"))?;
    let obj = entry
        .as_object_mut()
        .ok_or_else(|| "按钮数据格式不对".to_string())?;
    obj.insert(
        "passThruEnabled".to_string(),
        serde_json::Value::Bool(enabled),
    );

    let text = serde_json::to_string(&value).map_err(|e| format!("写回失败：{e}"))?;
    // 原子写：布局是用户手工调出来的，撕裂=布局丢（同 settings.json 的教训）。
    crate::fsutil::write_text_atomic(&path, &text).map_err(|e| format!("保存布局失败：{e}"))?;
    Ok(collect_buttons(&value))
}

fn collect_buttons(value: &serde_json::Value) -> Vec<ControlButtonInfo> {
    value
        .get("mControlDataList")
        .and_then(|v| v.as_array())
        .map(|list| {
            list.iter()
                .enumerate()
                .map(|(index, item)| ControlButtonInfo {
                    index,
                    name: item
                        .get("name")
                        .and_then(|v| v.as_str())
                        .unwrap_or("未命名")
                        .to_string(),
                    pass_thru: item
                        .get("passThruEnabled")
                        .and_then(|v| v.as_bool())
                        .unwrap_or(false),
                })
                .collect()
        })
        .unwrap_or_default()
}

// ==================== 布局管理 ====================

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ControlLayoutInfo {
    /// 文件名（不含 .json），也是「设为当前」时传的值
    pub name: String,
    pub buttons: usize,
    pub joysticks: usize,
    pub drawers: usize,
    /// 是否是当前生效的那份
    pub current: bool,
    pub size: u64,
    /// 最后修改时间（unix 秒，0 = 取不到）
    pub modified: u64,
}

/// 所有控制布局所在的目录。
fn controlmap_dir() -> Result<PathBuf, String> {
    let data_dir = crate::settings::data_dir_sync().ok_or_else(|| "拿不到数据目录".to_string())?;
    let dir = Path::new(&data_dir).join("controlmap");
    if !dir.is_dir() {
        std::fs::create_dir_all(&dir).map_err(|e| format!("创建控制布局目录失败：{e}"))?;
    }
    Ok(dir)
}

/// 当前生效布局的文件名；偏好里没有就回落 `default`。
fn current_layout_name() -> String {
    if let Ok(json) = crate::android_bridge::read_pojav_prefs() {
        if let Ok(value) = serde_json::from_str::<serde_json::Value>(&json) {
            if let Some(path) = value.get("defaultCtrl").and_then(|v| v.as_str()) {
                if let Some(name) = Path::new(path).file_stem().and_then(|s| s.to_str()) {
                    return name.to_string();
                }
            }
        }
    }
    "default".to_string()
}

/// 布局名要当文件名用，所以挡掉路径分隔符与 Windows 保留字符；
/// 中文、空格、`_ - .` 都允许（玩家自己起名时常见）。
fn validate_name(name: &str) -> Result<String, String> {
    let name = name.trim();
    if name.is_empty() {
        return Err("名字不能为空".to_string());
    }
    if name.chars().count() > 32 {
        return Err("名字最长 32 个字符".to_string());
    }
    if name.starts_with('.') || name.contains("..") {
        return Err("名字不能以点开头，也不能包含 ..".to_string());
    }
    if name.chars().any(|c| "/\\:*?\"<>|".contains(c)) {
        return Err("名字不能包含 / \\ : * ? \" < > |".to_string());
    }
    Ok(name.to_string())
}

fn layout_file(name: &str) -> Result<PathBuf, String> {
    Ok(controlmap_dir()?.join(format!("{name}.json")))
}

/// 数一下布局里有多少按键 / 摇杆 / 组合键（读不了就当 0，不因此让整个列表失败）。
fn count_layout(path: &Path) -> (usize, usize, usize) {
    let Ok(text) = std::fs::read_to_string(path) else {
        return (0, 0, 0);
    };
    let Ok(value) = serde_json::from_str::<serde_json::Value>(&text) else {
        return (0, 0, 0);
    };
    let len = |key: &str| {
        value
            .get(key)
            .and_then(|v| v.as_array())
            .map(|a| a.len())
            .unwrap_or(0)
    };
    (
        len("mControlDataList"),
        len("mJoystickDataList"),
        len("mDrawerDataList"),
    )
}

/// 列出所有控制布局（当前生效的排最前）。所有改动类命令都返回这份新列表，
/// 前端拿到直接替换，少一次往返。
pub async fn list_control_layouts() -> Result<Vec<ControlLayoutInfo>, String> {
    let dir = controlmap_dir()?;
    let current = current_layout_name();
    let mut out: Vec<ControlLayoutInfo> = Vec::new();

    let entries = std::fs::read_dir(&dir).map_err(|e| format!("读取布局目录失败：{e}"))?;
    for entry in entries.flatten() {
        let path = entry.path();
        if path.extension().and_then(|e| e.to_str()) != Some("json") {
            continue;
        }
        let Some(name) = path.file_stem().and_then(|s| s.to_str()) else {
            continue;
        };
        // 原生侧的导入中转文件（ImportControlActivity 用的），不算玩家的布局
        if name.starts_with("TMP_IMPORT") {
            continue;
        }
        let (buttons, joysticks, drawers) = count_layout(&path);
        let meta = entry.metadata().ok();
        out.push(ControlLayoutInfo {
            name: name.to_string(),
            buttons,
            joysticks,
            drawers,
            current: name == current,
            size: meta.as_ref().map(|m| m.len()).unwrap_or(0),
            modified: meta
                .as_ref()
                .and_then(|m| m.modified().ok())
                .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                .map(|d| d.as_secs())
                .unwrap_or(0),
        });
    }

    out.sort_by(|a, b| b.current.cmp(&a.current).then_with(|| a.name.cmp(&b.name)));
    Ok(out)
}

/// 打开原生控制布局编辑器（就是游戏里那个横屏界面）。
///
/// 为什么不自己画一个：那个编辑器本来就是「所见即所得」的实现（同一套 `ControlLayout` 视图，
/// 直接读游戏那份布局文件），重写一套只会得到「预览和游戏里不完全一样」的结果。
/// `layout` 传空 = 编辑当前默认那份；`preview` = 只读预览（导入时先给用户看一眼）。
pub async fn open_control_layout_editor(
    layout: Option<String>,
    preview: bool,
    save_as: Option<String>,
) -> Result<(), String> {
    #[cfg(target_os = "android")]
    {
        let name = match layout {
            Some(n) if !n.trim().is_empty() => Some(validate_name(&n)?),
            _ => None,
        };
        let save = match save_as {
            Some(n) if !n.trim().is_empty() => Some(validate_name(&n)?),
            _ => None,
        };
        crate::android_bridge::open_control_editor(name.as_deref(), preview, save.as_deref());
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = (layout, preview, save_as);
        return Err("控制布局编辑器只能在手机上使用".to_string());
    }
    Ok(())
}

/// 导出（系统分享）一份布局。
pub async fn export_control_layout(name: String) -> Result<(), String> {
    let name = validate_name(&name)?;
    let path = layout_file(&name)?;
    if !path.is_file() {
        return Err(format!("布局「{name}」不存在"));
    }
    #[cfg(target_os = "android")]
    crate::android_bridge::export_control_layout(&name);
    #[cfg(not(target_os = "android"))]
    return Err("导出功能只能在手机上使用".to_string());
    Ok(())
}

/// 弹系统文件选择器，让用户挑一个布局文件导入（真正的读取在原生侧异步完成）。
pub async fn pick_control_layout() -> Result<(), String> {
    #[cfg(target_os = "android")]
    crate::android_bridge::pick_control_layout();
    #[cfg(not(target_os = "android"))]
    return Err("导入功能只能在手机上使用".to_string());
    Ok(())
}

/// 取回导入结果（原生侧校验完写进临时文件，这里取走即删）。
pub async fn take_control_import() -> Result<Option<serde_json::Value>, String> {
    #[cfg(target_os = "android")]
    {
        let Some(s) = crate::android_bridge::take_control_import() else {
            return Ok(None);
        };
        return Ok(serde_json::from_str(&s).ok());
    }
    #[cfg(not(target_os = "android"))]
    Ok(None)
}

/// 按**文件路径**导入控制布局（不经 SAF）。
///
/// 存在的理由：SAF 打不开 `Android/data` 等目录，而 MT 管理器能拿到那些文件的绝对路径 ——
/// 用户复制路径粘过来，我们凭「所有文件访问」直接读。
/// （技术上**不能**让 MT 代替我们弹选择器：MT 没有这个接口，系统只认 SAF/DocumentsUI。）
pub async fn import_control_layout_by_path(path: String) -> Result<serde_json::Value, String> {
    #[cfg(target_os = "android")]
    {
        let raw = crate::android_bridge::import_control_layout_from_path(path.trim())
            .ok_or_else(|| "导入失败".to_string())?;
        return serde_json::from_str(&raw).map_err(|e| format!("导入结果解析失败：{e}"));
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = path;
        Err("按路径导入只能在手机上使用".to_string())
    }
}

/// 切换当前使用的布局（写 pojav 偏好 `defaultCtrl`）。
pub async fn set_current_control_layout(name: String) -> Result<Vec<ControlLayoutInfo>, String> {
    let name = validate_name(&name)?;
    let path = layout_file(&name)?;
    if !path.is_file() {
        return Err(format!("布局「{name}」不存在"));
    }
    let patch = serde_json::json!({ "defaultCtrl": path.to_string_lossy() });
    crate::android_bridge::write_pojav_prefs(&patch.to_string())?;
    list_control_layouts().await
}

/// 复制一份布局（手机打字麻烦，名字由前端传，通常就是「原名 + 副本」）。
pub async fn duplicate_control_layout(
    name: String,
    new_name: String,
) -> Result<Vec<ControlLayoutInfo>, String> {
    let from = layout_file(&validate_name(&name)?)?;
    let new_name = validate_name(&new_name)?;
    let to = layout_file(&new_name)?;
    if !from.is_file() {
        return Err(format!("布局「{name}」不存在"));
    }
    if to.exists() {
        return Err(format!("布局「{new_name}」已存在"));
    }
    std::fs::copy(&from, &to).map_err(|e| format!("复制失败：{e}"))?;
    list_control_layouts().await
}

/// 重命名布局；如果改的正是当前使用的那份，偏好里的路径也要跟着改，
/// 否则游戏下次启动会回落默认布局。
pub async fn rename_control_layout(
    name: String,
    new_name: String,
) -> Result<Vec<ControlLayoutInfo>, String> {
    let name = validate_name(&name)?;
    let from = layout_file(&name)?;
    let new_name = validate_name(&new_name)?;
    let to = layout_file(&new_name)?;
    if !from.is_file() {
        return Err(format!("布局「{name}」不存在"));
    }
    if to.exists() {
        return Err(format!("布局「{new_name}」已存在"));
    }
    let was_current = current_layout_name() == name;
    std::fs::rename(&from, &to).map_err(|e| format!("重命名失败：{e}"))?;
    if was_current {
        let patch = serde_json::json!({ "defaultCtrl": to.to_string_lossy() });
        crate::android_bridge::write_pojav_prefs(&patch.to_string())?;
    }
    list_control_layouts().await
}

/// 删除布局。删掉正在使用的那份时切回随包默认布局，避免进游戏时找不到文件。
/// `default` 本身是随包兜底的那份，不允许删（要改就在它基础上复制一份再改）。
pub async fn delete_control_layout(name: String) -> Result<Vec<ControlLayoutInfo>, String> {
    let name = validate_name(&name)?;
    if name == "default" {
        return Err("default 是随包兜底的布局，不能删除；想改它请先复制一份".to_string());
    }
    let path = layout_file(&name)?;
    if !path.is_file() {
        return Err(format!("布局「{name}」不存在"));
    }
    std::fs::remove_file(&path).map_err(|e| format!("删除失败：{e}"))?;
    if current_layout_name() == name {
        let fallback = layout_file("default")?;
        let patch = serde_json::json!({ "defaultCtrl": fallback.to_string_lossy() });
        crate::android_bridge::write_pojav_prefs(&patch.to_string())?;
    }
    list_control_layouts().await
}
