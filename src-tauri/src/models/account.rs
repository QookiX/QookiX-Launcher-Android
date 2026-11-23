use serde::{Deserialize, Serialize};

#[derive(Clone, Debug)]
pub struct Account {
    pub uuid: String,
    pub username: String,
    pub created: i64,
    pub account_type: AccountType,
    pub msa_refresh_token: Option<String>,
    pub xuid: Option<String>,
    pub expires_at: Option<i64>,
    pub skin_face_base64: Option<String>,
    pub yggdrasil: Option<crate::yggdrasil::Session>,
}

#[derive(Clone, Debug, PartialEq)]
pub enum AccountType {
    Offline,
    Microsoft,
    Yggdrasil,
}

impl AccountType {
    pub fn as_str(&self) -> &'static str {
        match self {
            AccountType::Offline => "offline",
            AccountType::Microsoft => "microsoft",
            AccountType::Yggdrasil => "yggdrasil",
        }
    }

    fn parse(raw: &str) -> AccountType {
        if raw.eq_ignore_ascii_case("microsoft") {
            AccountType::Microsoft
        } else if raw.eq_ignore_ascii_case("yggdrasil") {
            AccountType::Yggdrasil
        } else {
            AccountType::Offline
        }
    }
}

/// 账号文件兼容旧版的 `account_type` 字段。
impl Serialize for Account {
    fn serialize<S: serde::Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        use serde::ser::SerializeMap;
        let mut map = serializer.serialize_map(None)?;
        map.serialize_entry("type", self.account_type.as_str())?;
        map.serialize_entry("uuid", &self.uuid)?;
        map.serialize_entry("username", &self.username)?;
        map.serialize_entry("created", &self.created)?;
        map.serialize_entry("msa_refresh_token", &self.msa_refresh_token)?;
        map.serialize_entry("xuid", &self.xuid)?;
        map.serialize_entry("expires_at", &self.expires_at)?;
        map.serialize_entry("skin_face_base64", &self.skin_face_base64)?;
        if let Some(session) = &self.yggdrasil {
            map.serialize_entry("yggdrasil", session)?;
        }
        if let Some(exp) = self.expires_at {
            map.serialize_entry("msa_expires_at", &exp)?;
        }
        map.end()
    }
}

impl<'de> Deserialize<'de> for Account {
    fn deserialize<D: serde::Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        #[derive(Deserialize)]
        struct Raw {
            #[serde(default)]
            uuid: String,
            #[serde(default)]
            username: String,
            #[serde(default)]
            created: i64,
            #[serde(default, rename = "type")]
            kind: Option<String>,
            #[serde(default)]
            account_type: Option<String>,
            #[serde(default)]
            msa_refresh_token: Option<String>,
            #[serde(default)]
            xuid: Option<String>,
            #[serde(default)]
            expires_at: Option<i64>,
            #[serde(default)]
            msa_expires_at: Option<i64>,
            #[serde(default)]
            skin_face_base64: Option<String>,
            yggdrasil: Option<crate::yggdrasil::Session>,
        }
        let raw = Raw::deserialize(deserializer)?;
        let account_type = AccountType::parse(
            raw.kind
                .as_deref()
                .or(raw.account_type.as_deref())
                .unwrap_or("offline"),
        );
        Ok(Account {
            uuid: raw.uuid,
            username: raw.username,
            created: raw.created,
            account_type,
            msa_refresh_token: raw.msa_refresh_token,
            xuid: raw.xuid,
            expires_at: raw.expires_at.or(raw.msa_expires_at),
            skin_face_base64: raw.skin_face_base64,
            yggdrasil: raw.yggdrasil,
        })
    }
}
