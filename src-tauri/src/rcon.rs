//! Minecraft RCON 客户端 —— 用来**优雅停服**和执行控制台命令。
//!
//! # 为什么必须是 RCON，不能靠杀进程
//!
//! `System.exit(0)`（`JvmLauncher::shutdown` 那条路）能让 JVM 退出，但实测
//! 服务端日志里**没有** `Stopping the server / Saving worlds` —— 世界数据
//! 可能没存盘，玩家建筑就会回档。
//!
//! RCON 是官方控制台通道：`stop` 命令会让服务端自己走正常的关闭流程
//! （存盘 → 关世界 → 关监听 → 退出）。这是唯一能保证数据安全的停服方式。
//!
//! # 协议（很简短，共 3 种包）
//!
//! ```text
//! 包 = [int32 LE 长度][int32 LE requestId][int32 LE type][body 以 \0\0 结尾]
//! type: 3 = 鉴权, 2 = 命令, 0 = 响应值, 2 = 鉴权结果
//! ```
//!
//! 注意「长度」字段算的是 **id+type+body** 的字节数，不含长度字段本身。

use std::io::{Read, Write};
use std::net::TcpStream;
use std::path::Path;
use std::time::Duration;

/// RCON 凭据（密码存在 `files/servers/{id}/rcon.json`，不进版本库）
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct RconCreds {
    pub port: u16,
    pub password: String,
}

const TYPE_AUTH: i32 = 3;
const TYPE_CMD: i32 = 2;
const TYPE_AUTH_RESPONSE: i32 = 2;

/// 读凭据；没有就生成一个并落盘
pub fn load_or_create_creds(dir: &Path, game_port: u16) -> Result<RconCreds, String> {
    let path = dir.join("rcon.json");
    if let Ok(text) = std::fs::read_to_string(&path) {
        if let Ok(c) = serde_json::from_str::<RconCreds>(&text) {
            return Ok(c);
        }
    }
    // RCON 端口不能和游戏端口相同（否则两个监听撞在一起）
    let port = if game_port == 25575 { 25576 } else { 25575 };
    let creds = RconCreds {
        port,
        password: random_password(),
    };
    let _ = std::fs::write(&path, serde_json::to_string_pretty(&creds).map_err(|e| e.to_string())?);
    Ok(creds)
}

/// 12 位随机密码（只用便于手输的字符集，便于用户查看/修改）
fn random_password() -> String {
    const CHARS: &[u8] = b"abcdefghijkmnpqrstuvwxyz23456789";
    let mut s = String::new();
    for i in 0..12 {
        if i == 6 {
            s.push('-');
        }
        // 简单混合，避免首字节为 0
        let n = (std::process::id() as usize)
            .wrapping_mul(31)
            .wrapping_add(i * 7)
            .wrapping_add(std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.subsec_nanos() as usize)
                .unwrap_or(0));
        s.push(CHARS[n % CHARS.len()] as char);
    }
    s
}

fn write_packet(s: &mut TcpStream, id: i32, ty: i32, body: &str) -> std::io::Result<()> {
    let mut payload: Vec<u8> = Vec::with_capacity(body.len() + 12);
    payload.extend_from_slice(&id.to_le_bytes());
    payload.extend_from_slice(&ty.to_le_bytes());
    payload.extend_from_slice(body.as_bytes());
    payload.push(0);
    payload.push(0);
    let mut head = (payload.len() as i32).to_le_bytes().to_vec();
    head.extend_from_slice(&payload);
    s.write_all(&head)?;
    s.flush()
}

/// 读一个包，返回 (id, type, body)
fn read_packet(s: &mut TcpStream) -> Result<(i32, i32, String), String> {
    let mut len_buf = [0u8; 4];
    s.read_exact(&mut len_buf).map_err(|e| format!("读长度失败: {e}"))?;
    let len = i32::from_le_bytes(len_buf);
    if len < 10 || len > 4 * 1024 * 1024 {
        return Err(format!("RCON 包长度异常: {len}"));
    }
    let mut buf = vec![0u8; len as usize];
    s.read_exact(&mut buf).map_err(|e| format!("读包体失败: {e}"))?;
    let id = i32::from_le_bytes([buf[0], buf[1], buf[2], buf[3]]);
    let ty = i32::from_le_bytes([buf[4], buf[5], buf[6], buf[7]]);
    let mut body = String::from_utf8_lossy(&buf[8..]).to_string();
    while body.ends_with('\0') {
        body.pop();
    }
    Ok((id, ty, body))
}

/// 鉴权 + 发送一条命令，返回服务端输出
pub fn exec(port: u16, password: &str, command: &str) -> Result<String, String> {
    let mut s = send_command(port, password, command)?;
    let (_id, _ty, out) = read_packet(&mut s)?;
    Ok(out)
}

// A server may close the RCON socket during shutdown without sending a reply.
// Successful dispatch is not a successful stop; the caller must confirm exit.
pub fn request_stop(creds: &RconCreds) -> Result<(), String> {
    send_command(creds.port, &creds.password, "stop").map(|_| ())
}

fn send_command(port: u16, password: &str, command: &str) -> Result<TcpStream, String> {
    let addr = format!("127.0.0.1:{port}");
    // 服务端刚起时 RCON 监听可能慢，超时给足 5 秒
    let mut s = TcpStream::connect(&addr)
        .map_err(|e| format!("连不上 RCON（{addr}）：{e}"))?;
    s.set_read_timeout(Some(Duration::from_secs(5)))
        .map_err(|e| e.to_string())?;
    s.set_write_timeout(Some(Duration::from_secs(5)))
        .map_err(|e| e.to_string())?;

    // 1) 鉴权
    write_packet(&mut s, 1, TYPE_AUTH, password).map_err(|e| format!("鉴权发送失败: {e}"))?;
    let (id, ty, _body) = read_packet(&mut s)?;
    if ty != TYPE_AUTH_RESPONSE {
        return Err(format!("鉴权响应类型异常: type={ty}"));
    }
    if id == -1 {
        return Err("RCON 密码不正确（服务端拒绝）".to_string());
    }

    // 2) 执行命令
    write_packet(&mut s, 2, TYPE_CMD, command).map_err(|e| format!("命令发送失败: {e}"))?;
    Ok(s)
}
