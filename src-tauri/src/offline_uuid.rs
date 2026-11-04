use md5::{Digest, Md5};
use uuid::Uuid;

/// Java `UUID.nameUUIDFromBytes("OfflinePlayer:<name>")` 的等价实现。
///
/// MC 的离线账号 UUID 是 **MD5(name)**，再把 byte[6] 改成 version 3、
/// byte[8] 改成 RFC4122 variant。官方启动器与服务端都按这个算。
///
/// ⚠️ 不能用 `Uuid::new_v3(&Uuid::nil(), name)`：v3 的摘要输入是
/// 16 字节 namespace 再拼 name，结果与 Java 完全不同
///（Notch：Java 是 b50ad385…，v3-nil 是 0a6bfc7c…）。算错的后果是
/// 联机时服务端认不出玩家，白名单 / 背包存档 / 皮肤全部对不上。
pub fn offline_uuid(name: &str) -> Uuid {
    let mut hasher = Md5::new();
    hasher.update(format!("OfflinePlayer:{name}").as_bytes());
    let mut bytes: [u8; 16] = hasher.finalize().into();
    bytes[6] = (bytes[6] & 0x0f) | 0x30; // version 3
    bytes[8] = (bytes[8] & 0x3f) | 0x80; // RFC4122 variant
    Uuid::from_bytes(bytes)
}

#[cfg(test)]
mod tests {
    use super::offline_uuid;

    /// 期望值来自 Java `UUID.nameUUIDFromBytes("OfflinePlayer:<name>".getBytes(UTF_8))`。
    /// 这两个值是官方启动器与服务端认的，改错会让白名单 / 存档 / 皮肤全部对不上。
    #[test]
    fn matches_java_name_uuid_from_bytes() {
        assert_eq!(
            offline_uuid("Notch").to_string(),
            "b50ad385-829d-3141-a216-7e7d7539ba7f"
        );
        assert_eq!(
            offline_uuid("Steve").to_string(),
            "5627dd98-e6be-3c21-b8a8-e92344183641"
        );
    }

    /// 防回归：别再图省事写回 `Uuid::new_v3(&Uuid::nil(), name)`。
    #[test]
    fn is_not_namespaced_v3() {
        let wrong = uuid::Uuid::new_v3(&uuid::Uuid::nil(), b"OfflinePlayer:Notch");
        assert_ne!(offline_uuid("Notch"), wrong);
    }

    #[tokio::test]
    async fn migrates_legacy_file_and_is_idempotent() {
        let dir = std::env::temp_dir()
            .join(format!("qookix-uuid-{}", uuid::Uuid::new_v4()));
        tokio::fs::create_dir_all(&dir).await.unwrap();
        let wrong = uuid::Uuid::new_v3(&uuid::Uuid::nil(), b"OfflinePlayer:Notch").to_string();
        let old = dir.join(format!("{wrong}.json"));
        let json = serde_json::json!({
            "type": "offline", "uuid": wrong, "username": "Notch", "created": 0
        })
        .to_string();
        crate::fsutil::write_text_atomic(&old, &json).unwrap();

        assert_eq!(super::migrate_legacy_account_files(&dir).await.unwrap(), 1);
        let right = offline_uuid("Notch").to_string();
        assert!(!old.exists(), "旧文件应被删掉，否则 remove_account 会找不到");
        assert!(dir.join(format!("{right}.json")).exists());
        assert_eq!(
            super::migrate_legacy_account_files(&dir).await.unwrap(),
            0,
            "第二次跑应无迁移（幂等）"
        );
        tokio::fs::remove_dir_all(&dir).await.ok();
    }
}

/// 迁移旧版算错的离线账号文件（幂等）。
///
/// 旧版把 UUID 写成 v3(nil) 的结果，升级后如果只修「新建账号」，老账号仍然
/// 与官方启动器不互通；而且账号文件名就是 UUID，只改内存值不改文件名的话，
/// `remove_account(account_id)` 会按新 UUID 拼路径、找不到旧文件 → 删不掉账号。
///
/// 这里按 Java 规则重算、写成新文件、删掉旧文件。跑完后文件里的 uuid
/// 与文件名一致，后续读取直接跳过（幂等）。
pub async fn migrate_legacy_account_files(dir: &std::path::Path) -> std::io::Result<usize> {
    let mut fixed = 0usize;
    let mut entries = tokio::fs::read_dir(dir).await?;
    while let Some(entry) = entries.next_entry().await? {
        let path = entry.path();
        if path.extension().and_then(|e| e.to_str()) != Some("json") {
            continue;
        }
        let text = match tokio::fs::read_to_string(&path).await {
            Ok(t) => t,
            Err(_) => continue,
        };
        let mut value: serde_json::Value = match serde_json::from_str(&text) {
            Ok(v) => v,
            Err(_) => continue,
        };
        if value.get("type").and_then(|t| t.as_str()) != Some("offline") {
            continue;
        }
        let name = value.get("username").and_then(|u| u.as_str()).unwrap_or("");
        if name.is_empty() {
            continue;
        }
        let right = offline_uuid(name).to_string();
        if value.get("uuid").and_then(|u| u.as_str()) == Some(right.as_str()) {
            continue;
        }
        value["uuid"] = serde_json::Value::String(right.clone());
        let new_path = dir.join(format!("{right}.json"));
        if let Ok(json) = serde_json::to_string_pretty(&value) {
            if crate::fsutil::write_text_atomic(&new_path, &json).is_ok() {
                let _ = tokio::fs::remove_file(&path).await;
                fixed += 1;
            }
        }
    }
    Ok(fixed)
}
