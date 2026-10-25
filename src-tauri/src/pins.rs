//! 固定项（首页 / 侧边栏）：读写数据根目录下的 `pins.json`。

use crate::models::PinItem;
use crate::settings::get_data_dir;
use std::path::Path;
use tokio::fs;

/// 读取固定项列表（首页 / 侧边栏）。文件不存在或损坏时返回空列表。
pub async fn load_pins() -> Vec<PinItem> {
    let Ok(data_dir) = get_data_dir().await else {
        return Vec::new();
    };
    let path = Path::new(&data_dir).join("pins.json");
    fs::read_to_string(&path)
        .await
        .ok()
        .and_then(|s| serde_json::from_str::<Vec<PinItem>>(&s).ok())
        .unwrap_or_default()
}

/// 将固定项列表写入 `pins.json`（美化格式，便于人工查看/编辑）。
pub async fn save_pins(items: &[PinItem]) -> Result<(), String> {
    let data_dir = get_data_dir().await.map_err(|e| e.to_string())?;
    let path = Path::new(&data_dir).join("pins.json");
    let json = serde_json::to_string_pretty(items).map_err(|e| e.to_string())?;
    crate::fsutil::write_text_atomic(&path, &json).map_err(|e| e.to_string())
}
