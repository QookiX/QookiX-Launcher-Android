#!/usr/bin/env node
/**
 * 统一版本号脚本。
 *
 * 用法：node scripts/update-version.mjs 1.9.0
 * 或：  npm run version -- 1.9.0
 *
 * 会同步以下文件（**只有这几个是真的在用的**）：
 *  - package.json                                    (前端/脚本版本)
 *  - src-tauri/Cargo.toml                            (Rust 包版本)
 *  - src-tauri/gen/android/app/build.gradle.kts       (安卓 versionName / versionCode)
 *
 * ⚠️ 2026-10-25 修：原来还写 `src-tauri/tauri.conf.json`、`app/tauri.properties`
 * 和 `src/views/SettingsView.vue` —— 那三个文件在纯 JNI 改造时都删了（tauri 配置、
 * tauri 生成的属性文件、Vue 前端）。脚本当时是「文件不存在就跳过」，所以静默
 * 没写 versionCode，结果安卓包会一直沿用一个旧值 → 覆盖安装被系统拒绝。
 * 现在直接改 build.gradle.kts 里的字面量，缺一个就报错。
 *
 * 发布流程：先跑这个脚本 → 提交 → 打 tag（vX.Y.Z）→ Release 工作流自动打包。
 * versionCode 必须单调递增，这里按 MAJOR*1e4 + MINOR*1e2 + PATCH 生成。
 */
import { readFileSync, writeFileSync, existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const __dirname = dirname(fileURLToPath(import.meta.url));
const root = join(__dirname, "..");

// ---- 解析新版本号 ----
const arg = process.argv[2];
if (!arg || !/^\d+\.\d+\.\d+/.test(arg)) {
  console.error("用法: node scripts/update-version.mjs <版本号，如 1.9.0>");
  process.exit(1);
}
const newVersion = arg.replace(/^v/, "");

const [major, minor, patchNo] = newVersion.split(".").map((n) => parseInt(n, 10) || 0);
const versionCode = major * 10_000 + minor * 100 + patchNo;

// ---- 逐个更新 ----
function patch(file, replaceFn, alreadyOk) {
  const path = join(root, file);
  if (!existsSync(path)) {
    console.error(`  ✗ 文件不存在：${file}（版本号没写进去，别当成功）`);
    process.exitCode = 1;
    return;
  }
  const content = readFileSync(path, "utf8");
  const next = replaceFn(content);
  if (next !== content) {
    writeFileSync(path, next);
    console.log(`  ✓ 已更新 ${file}`);
  } else if (alreadyOk && alreadyOk(content)) {
    // 值本来就等于目标值：没变化是正确结果，不是「没匹配上」
    console.log(`  = 已是最新 ${file}`);
  } else {
    console.error(`  ✗ 未匹配到版本号：${file}`);
    process.exitCode = 1;
  }
}

// 1. package.json
patch(
  "package.json",
  (c) => {
    const pkg = JSON.parse(c);
    pkg.version = newVersion;
    return JSON.stringify(pkg, null, 2) + "\n";
  },
  (c) => JSON.parse(c).version === newVersion,
);

// 2. Cargo.toml 顶层 [package] version
patch(
  "src-tauri/Cargo.toml",
  (c) => c.replace(/^(version\s*=\s*")[^"]+(")/m, `$1${newVersion}$2`),
  (c) => new RegExp(`^version\\s*=\\s*"${newVersion}"`, "m").test(c),
);

// 3. 安卓 versionName / versionCode（两者都在 defaultConfig 里，各出现一次）
patch(
  "src-tauri/gen/android/app/build.gradle.kts",
  (c) => {
    const next = c.replace(/^(\s*versionCode\s*=\s*)\d+$/m, `$1${versionCode}`);
    return next.replace(/^(\s*versionName\s*=\s*)".*"$/m, `$1"${newVersion}"`);
  },
  (c) =>
    c.includes(`versionName = "${newVersion}"`) &&
    new RegExp(`versionCode\\s*=\\s*${versionCode}\\b`).test(c),
);

if (process.exitCode) {
  console.error(`版本号同步未全部成功（安卓 versionCode 目标值=${versionCode}）。`);
} else {
  console.log(`完成（安卓 versionCode=${versionCode}）。`);
}
