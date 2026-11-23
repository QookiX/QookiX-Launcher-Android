use std::fmt;
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

use anyhow::{anyhow, bail, Context, Result};
use base64::{engine::general_purpose::STANDARD, Engine};
use reqwest::{Client, Response, StatusCode, Url};
use serde::{de::DeserializeOwned, Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use tokio::io::AsyncReadExt;
use tokio::sync::Mutex;
use uuid::Uuid;

use crate::accounts::{add_account_to_disk, get_account_by_uuid};
use crate::models::{Account, AccountType};

const LITTLE_SKIN: &str = "https://littleskin.cn/api/yggdrasil";
const INJECTOR_FILE: &str = "authlib-injector-1.2.8.jar";
const INJECTOR_SHA256: &str = "9c7f4343e6c82034958ffb48c14a2cb0c85928be7283103ce17da00c6d5a7b10";
const INJECTOR_LIMIT: usize = 4 * 1024 * 1024;
const API_LIMIT: usize = 256 * 1024;
const LOGIN_WINDOW: Duration = Duration::from_secs(300);

pub(crate) static ACCOUNT_WRITE: Mutex<()> = Mutex::const_new(());
static INJECTOR_INSTALL: Mutex<()> = Mutex::const_new(());
static PENDING: Mutex<Option<PendingLogin>> = Mutex::const_new(None);

#[derive(Clone, Deserialize, Serialize)]
pub(crate) struct Session {
    root: String,
    pub server_name: String,
    profile_id: Uuid,
    access_token: String,
    client_token: String,
}

impl Session {
    pub(crate) fn homepage(&self) -> Option<String> {
        api_root(&self.root)
            .ok()
            .map(|url| url.origin().ascii_serialization())
    }
}

impl fmt::Debug for Session {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Session")
            .field("profile_id", &self.profile_id)
            .finish_non_exhaustive()
    }
}

#[derive(Deserialize, Serialize)]
struct Profile {
    id: Uuid,
    name: String,
}

impl Profile {
    fn selection(&self) -> Value {
        json!({ "id": self.id.simple().to_string(), "name": self.name })
    }
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct AuthResponse {
    access_token: String,
    client_token: String,
    selected_profile: Option<Profile>,
    available_profiles: Option<Vec<Profile>>,
}

impl AuthResponse {
    fn check_tokens(&self, client_token: &str) -> Result<()> {
        if self.access_token.is_empty()
            || self.access_token.contains('\0')
            || self.client_token != client_token
        {
            bail!("皮肤站返回了无效的登录令牌");
        }
        Ok(())
    }
}

struct PendingLogin {
    id: Uuid,
    started: Instant,
    root: Url,
    server_name: String,
    login: String,
    auth: AuthResponse,
}

fn api_root(input: &str) -> Result<Url> {
    let root = match input.trim().trim_end_matches('/') {
        "littleskin.cn" | "https://littleskin.cn" => LITTLE_SKIN,
        other => other,
    };
    let url = Url::parse(root).context("请输入完整的皮肤站 Yggdrasil API 地址")?;
    if url.scheme() != "https"
        || url.host().is_none()
        || !url.username().is_empty()
        || url.password().is_some()
        || url.query().is_some()
        || url.fragment().is_some()
    {
        bail!("皮肤站 API 地址必须使用 HTTPS，且不能包含凭据、查询参数或片段");
    }
    Ok(url)
}

async fn auth_client() -> Result<Client> {
    // 密码和令牌只发送给用户选定的 API，遇到重定向时要求填写目标 API 地址。
    let mut builder = Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .timeout(Duration::from_secs(15))
        .user_agent("QookiX-Launcher-Android");
    let proxy = crate::util::resolved_proxy().await;
    if !proxy.is_empty() {
        builder = builder.proxy(reqwest::Proxy::all(proxy).map_err(|_| anyhow!("代理地址无效"))?);
    }
    builder.build().context("无法创建皮肤站网络连接")
}

fn endpoint(root: &Url, path: &str) -> String {
    format!("{}/{}", root.as_str().trim_end_matches('/'), path)
}

async fn response_bytes(mut response: Response, limit: usize) -> Result<Vec<u8>> {
    let mut bytes = Vec::new();
    while let Some(chunk) = response.chunk().await.context("无法读取响应")? {
        if chunk.len() > limit - bytes.len() {
            bail!("响应超过大小限制");
        }
        bytes.extend_from_slice(&chunk);
    }
    Ok(bytes)
}

async fn read_json<T: DeserializeOwned>(response: Response) -> Result<T> {
    let bytes = response_bytes(response, API_LIMIT).await?;
    serde_json::from_slice(&bytes).map_err(|_| anyhow!("皮肤站返回了无效的 JSON 数据"))
}

async fn metadata(client: &Client, root: &Url) -> Result<Value> {
    let response = client
        .get(root.clone())
        .send()
        .await
        .context("无法连接到皮肤站")?;
    let status = response.status();
    if !status.is_success() {
        bail!("无法读取皮肤站 API 信息（HTTP {status}），请检查 API 地址");
    }
    let value: Value = read_json(response).await?;
    if !value.get("meta").is_some_and(Value::is_object) {
        bail!("此地址未返回 Yggdrasil API 信息");
    }
    Ok(value)
}

async fn auth_post(client: &Client, root: &Url, operation: &str, body: &Value) -> Result<Response> {
    client
        .post(endpoint(root, &format!("authserver/{operation}")))
        .json(body)
        .send()
        .await
        .context("无法连接到皮肤站")
}

fn credentials_rejected(status: StatusCode) -> bool {
    matches!(status, StatusCode::UNAUTHORIZED | StatusCode::FORBIDDEN)
}

fn account_id(root: &Url, login: &str, profile: Uuid) -> Uuid {
    let key = json!([root.as_str().trim_end_matches('/'), login, profile]);
    Uuid::new_v3(&Uuid::NAMESPACE_URL, key.to_string().as_bytes())
}

async fn save_account(
    root: &Url,
    server_name: String,
    login: &str,
    auth: AuthResponse,
    profile: Profile,
) -> Result<Value> {
    let account = Account {
        uuid: account_id(root, login, profile.id).to_string(),
        username: profile.name,
        created: chrono::Utc::now().timestamp(),
        account_type: AccountType::Yggdrasil,
        msa_refresh_token: None,
        xuid: None,
        expires_at: None,
        skin_face_base64: None,
        yggdrasil: Some(Session {
            root: root.to_string(),
            server_name,
            profile_id: profile.id,
            access_token: auth.access_token,
            client_token: auth.client_token,
        }),
    };
    let _guard = ACCOUNT_WRITE.lock().await;
    add_account_to_disk(&account).await?;
    Ok(json!({ "accountId": account.uuid }))
}

pub(crate) async fn login(root: &str, login: &str, password: &str) -> Result<Value> {
    let login = login.trim();
    if login.is_empty() || password.is_empty() {
        bail!("请输入皮肤站账号和密码");
    }
    let root = api_root(root)?;
    let client = auth_client().await?;
    let info = metadata(&client, &root).await?;
    let server_name = info
        .pointer("/meta/serverName")
        .and_then(Value::as_str)
        .filter(|name| !name.trim().is_empty())
        .unwrap_or_else(|| root.host_str().unwrap_or("Yggdrasil"))
        .chars()
        .take(80)
        .collect::<String>();
    let client_token = Uuid::new_v4().simple().to_string();
    let response = auth_post(
        &client,
        &root,
        "authenticate",
        &json!({
            "agent": { "name": "Minecraft", "version": 1 },
            "username": login, "password": password, "clientToken": client_token,
        }),
    )
    .await?;
    let status = response.status();
    if credentials_rejected(status) {
        bail!("皮肤站拒绝登录，请检查账号、密码和账号状态");
    }
    if !status.is_success() {
        bail!("皮肤站登录失败（HTTP {status}）");
    }
    let mut auth: AuthResponse = read_json(response).await?;
    auth.check_tokens(&client_token)?;
    if let Some(profile) = auth.selected_profile.take() {
        return save_account(&root, server_name, login, auth, profile).await;
    }
    let profiles = auth.available_profiles.as_deref().unwrap_or_default();
    if profiles.is_empty() {
        bail!("这个皮肤站账号还没有游戏角色，请在皮肤站创建一个角色");
    }
    if let [profile] = profiles {
        let (bound, profile) = bind_profile(&client, &root, &auth, profile).await?;
        return save_account(&root, server_name, login, bound, profile).await;
    }
    let id = Uuid::new_v4();
    let result = json!({ "loginId": id, "profiles": profiles });
    *PENDING.lock().await = Some(PendingLogin {
        id,
        started: Instant::now(),
        root,
        server_name,
        login: login.to_owned(),
        auth,
    });
    Ok(result)
}

pub(crate) async fn cancel_login(login_id: Uuid) {
    let mut pending = PENDING.lock().await;
    if pending.as_ref().is_some_and(|flow| flow.id == login_id) {
        *pending = None;
    }
}

pub(crate) async fn select_profile(login_id: Uuid, profile_id: Uuid) -> Result<Value> {
    let flow = {
        let mut pending = PENDING.lock().await;
        let flow = pending
            .as_ref()
            .filter(|flow| flow.id == login_id && flow.started.elapsed() < LOGIN_WINDOW)
            .context("角色选择已过期，请重新登录皮肤站")?;
        if !flow
            .auth
            .available_profiles
            .as_deref()
            .unwrap_or_default()
            .iter()
            .any(|profile| profile.id == profile_id)
        {
            bail!("此角色不属于当前登录流程");
        }
        pending.take().expect("pending flow checked above")
    };
    let profile = flow
        .auth
        .available_profiles
        .as_deref()
        .unwrap_or_default()
        .iter()
        .find(|profile| profile.id == profile_id)
        .context("此角色不属于当前登录流程")?;
    let client = auth_client().await?;
    let (auth, profile) = bind_profile(&client, &flow.root, &flow.auth, profile).await?;
    save_account(&flow.root, flow.server_name, &flow.login, auth, profile).await
}

async fn bind_profile(
    client: &Client,
    root: &Url,
    auth: &AuthResponse,
    profile: &Profile,
) -> Result<(AuthResponse, Profile)> {
    let response = auth_post(
        client,
        root,
        "refresh",
        &json!({
            "accessToken": auth.access_token, "clientToken": auth.client_token,
            "selectedProfile": profile.selection(),
        }),
    )
    .await?;
    let (bound, selected) = refreshed(response, &auth.client_token, profile.id).await?;
    Ok((bound, selected.context("皮肤站没有绑定所选角色")?))
}

async fn refreshed(
    response: Response,
    client_token: &str,
    profile_id: Uuid,
) -> Result<(AuthResponse, Option<Profile>)> {
    let status = response.status();
    if credentials_rejected(status) {
        bail!("皮肤站会话已失效，请在「我的」重新登录该账号");
    }
    if !status.is_success() {
        bail!("皮肤站会话刷新失败（HTTP {status}）");
    }
    let mut auth: AuthResponse = read_json(response).await?;
    auth.check_tokens(client_token)?;
    let selected = auth.selected_profile.take();
    if selected
        .as_ref()
        .is_some_and(|profile| profile.id != profile_id)
    {
        bail!("皮肤站返回了不同的游戏角色");
    }
    Ok((auth, selected))
}

pub(crate) struct LaunchSession {
    pub profile_id: Uuid,
    pub username: String,
    pub access_token: String,
    pub jvm_args: [String; 2],
}

pub(crate) async fn prepare_launch(account_id: &str, data_dir: &Path) -> Result<LaunchSession> {
    let (client, root, session, username) = {
        let _guard = ACCOUNT_WRITE.lock().await;
        let mut account = get_account_by_uuid(account_id).await?;
        let session = account
            .yggdrasil
            .as_mut()
            .context("皮肤站会话缺失，请重新登录")?;
        let root = api_root(&session.root)?;
        let client = auth_client().await?;
        let body =
            json!({ "accessToken": session.access_token, "clientToken": session.client_token });
        let status = auth_post(&client, &root, "validate", &body).await?.status();
        if credentials_rejected(status) {
            let response = auth_post(&client, &root, "refresh", &body).await?;
            let (auth, profile) =
                refreshed(response, &session.client_token, session.profile_id).await?;
            session.access_token = auth.access_token;
            if let Some(profile) = profile {
                account.username = profile.name;
            }
            add_account_to_disk(&account).await?;
        } else if status != StatusCode::NO_CONTENT && status != StatusCode::OK {
            bail!("皮肤站会话验证失败（HTTP {status}）");
        }
        (
            client,
            root,
            account.yggdrasil.context("皮肤站会话缺失")?,
            account.username,
        )
    };
    let (jar, info) = tokio::try_join!(ensure_injector(data_dir), metadata(&client, &root))?;
    let prefetched = STANDARD.encode(serde_json::to_vec(&info)?);
    Ok(LaunchSession {
        profile_id: session.profile_id,
        username,
        access_token: session.access_token,
        jvm_args: [
            format!("-javaagent:{}={root}", jar.display()),
            format!("-Dauthlibinjector.yggdrasil.prefetched={prefetched}"),
        ],
    })
}

fn verified_injector(bytes: &[u8]) -> bool {
    bytes.len() <= INJECTOR_LIMIT && format!("{:x}", Sha256::digest(bytes)) == INJECTOR_SHA256
}

async fn ensure_injector(data_dir: &Path) -> Result<PathBuf> {
    let _guard = INJECTOR_INSTALL.lock().await;
    let dir = data_dir.join("authlib-injector");
    let path = dir.join(INJECTOR_FILE);
    if let Ok(file) = tokio::fs::File::open(&path).await {
        let mut bytes = Vec::new();
        if file
            .take(INJECTOR_LIMIT as u64 + 1)
            .read_to_end(&mut bytes)
            .await
            .is_ok()
            && verified_injector(&bytes)
        {
            return Ok(path);
        }
    }
    // 构建号和摘要来自上游的 artifact/56.json；镜像也必须通过同一摘要校验。
    let client = crate::util::http_client().await;
    for base in [
        "https://authlib-injector.yushi.moe/artifact/56",
        "https://bmclapi2.bangbang93.com/mirrors/authlib-injector/artifact/56",
        "https://github.com/yushijinhun/authlib-injector/releases/download/v1.2.8",
    ] {
        let result = tokio::time::timeout(Duration::from_secs(12), async {
            let response = client
                .get(format!("{base}/{INJECTOR_FILE}"))
                .send()
                .await?
                .error_for_status()?;
            response_bytes(response, INJECTOR_LIMIT).await
        })
        .await;
        let Ok(Ok(bytes)) = result else { continue };
        if !verified_injector(&bytes) {
            continue;
        }
        tokio::fs::create_dir_all(dir)
            .await
            .context("无法创建皮肤站组件目录")?;
        crate::fsutil::write_bytes_atomic(&path, &bytes).context("无法保存皮肤站组件")?;
        return Ok(path);
    }
    bail!("authlib-injector 下载或 SHA-256 校验失败，请检查网络后重试");
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn https_api_roots_only_and_littleskin_alias() {
        assert_eq!(api_root(" littleskin.cn ").unwrap().as_str(), LITTLE_SKIN);
        for input in [
            "http://skin.example",
            "https://me@skin.example",
            "https://skin.example/?q=1",
        ] {
            assert!(api_root(input).is_err(), "{input}");
        }
    }

    #[test]
    fn shared_profile_uuid_has_distinct_local_identities() {
        let profile = Uuid::new_v4();
        let a = api_root("https://a.example/yggdrasil").unwrap();
        let b = api_root("https://b.example/yggdrasil").unwrap();
        assert_ne!(account_id(&a, "nick", profile), profile);
        assert_ne!(
            account_id(&a, "nick", profile),
            account_id(&b, "nick", profile)
        );
        assert_ne!(
            account_id(&a, "nick", profile),
            account_id(&a, "another", profile)
        );
    }

    #[test]
    fn unbound_login_preserves_profiles_and_checks_client_token() {
        let id = Uuid::new_v4();
        let auth: AuthResponse = serde_json::from_value(json!({
            "accessToken": "session-token",
            "clientToken": "client-token",
            "selectedProfile": null,
            "availableProfiles": [{ "id": id.simple().to_string(), "name": "Player" }],
        }))
        .unwrap();
        assert!(auth.selected_profile.is_none());
        let profile = &auth.available_profiles.as_ref().unwrap()[0];
        assert_eq!(profile.id, id);
        assert_eq!(profile.selection()["id"], id.simple().to_string());
        assert!(auth.check_tokens("client-token").is_ok());
        assert!(auth.check_tokens("another-client-token").is_err());
    }
}
