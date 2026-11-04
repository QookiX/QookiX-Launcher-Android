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
}
