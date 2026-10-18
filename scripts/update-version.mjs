#!/usr/bin/env node
/**
 * 统一版本号脚本（Android 版，与桌面端用法一致）。
 * 用法：node scripts/update-version.mjs 1.1.0
 * 或：  npm run version -- 1.1.0
 *
 * 会将新版本号同步到以下文件：
 *  - package.json                                (前端版本)
 *  - src-tauri/Cargo.toml                        (Rust 包版本)
 *  - src-tauri/tauri.conf.json                   (应用版本)
 *  - src-tauri/gen/android/app/tauri.properties  (安卓 versionName / versionCode)
 *  - src/views/SettingsView.vue                  (关于页 "vX.Y.Z")
 *
 * 发布流程：先跑这个脚本 → 提交 → 打 tag（vX.Y.Z）→ Release 工作流自动打包。
 * 注意 versionCode 必须单调递增，这里按 MAJOR*1e4 + MINOR*1e2 + PATCH 生成。
 */
import { readFileSync, writeFileSync, existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const __dirname = dirname(fileURLToPath(import.meta.url));
const root = join(__dirname, "..");

// ---- 解析新版本号 ----
const arg = process.argv[2];
if (!arg || !/^\d+\.\d+\.\d+/.test(arg)) {
  console.error("用法: node scripts/update-version.mjs <版本号，如 1.1.0>");
  process.exit(1);
}
const newVersion = arg.replace(/^v/, "");

// ---- 当前版本来源（tauri.conf.json，与发布产物一致）----
const confPath = join(root, "src-tauri/tauri.conf.json");
let oldVersion = "?";
try {
  oldVersion = JSON.parse(readFileSync(confPath, "utf8")).version ?? "?";
} catch {
  /* 读不到就只显示新版本 */
}
console.log(`版本号: ${oldVersion} -> ${newVersion}`);

// ---- 逐个更新 ----
function patch(file, replaceFn) {
  const path = join(root, file);
  if (!existsSync(path)) {
    console.log(`  (跳过，文件不存在) ${file}`);
    return;
  }
  const content = readFileSync(path, "utf8");
  const next = replaceFn(content);
  if (next !== content) {
    writeFileSync(path, next);
    console.log(`  已更新 ${file}`);
  } else {
    console.log(`  (未匹配) ${file}`);
  }
}

// 1. package.json（前端版本）
patch("package.json", (c) => {
  const pkg = JSON.parse(c);
  pkg.version = newVersion;
  return JSON.stringify(pkg, null, 2) + "\n";
});

// 2. Cargo.toml 顶层 [package] version
patch("src-tauri/Cargo.toml", (c) =>
  c.replace(/^(version\s*=\s*")[^"]+(")/m, `$1${newVersion}$2`),
);

// 3. tauri.conf.json 顶层 version
patch("src-tauri/tauri.conf.json", (c) =>
  c.replace(/^(\s*"version"\s*:\s*")[^"]+(")/m, `$1${newVersion}$2`),
);

// 4. 安卓 versionName / versionCode
const [major, minor, patchNo] = newVersion.split(".").map((n) => parseInt(n, 10) || 0);
const versionCode = major * 10_000 + minor * 100 + patchNo;
patch("src-tauri/gen/android/app/tauri.properties", (c) => {
  const next = c.replace(
    /^(tauri\.android\.versionName\s*=\s*).*$/m,
    `$1${newVersion}`,
  );
  return next.replace(
    /^(tauri\.android\.versionCode\s*=\s*).*$/m,
    `$1${versionCode}`,
  );
});


console.log(`完成（安卓 versionCode=${versionCode}）。`);
