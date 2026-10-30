#!/usr/bin/env node
/**
 * 统一版本号脚本。
 *
 * 用法：
 *   node scripts/update-version.mjs 0.4.6            # 正式版
 *   node scripts/update-version.mjs 0.4.6-rc1        # 发布候选
 *   node scripts/update-version.mjs 0.4.6-rc1 --dry  # 只预览，不写文件
 *
 * 会同步以下文件（**只有这几个是真的在用的**）：
 *  - package.json                       (前端/脚本版本)
 *  - src-tauri/Cargo.toml               (Rust 包版本)
 *  - android/app/build.gradle.kts       (安卓 versionName / versionCode)
 *
 * ## versionCode 编码（2026-10-25 改）
 *
 *   AA BB CC DD
 *   │  │  │  └── 通道号段：01–19 alpha / 20–49 beta / 50–98 rc / **99 正式版**
 *   │  │  └───── patch
 *   │  └──────── minor
 *   └─────────── major
 *
 * 为什么正式版用 99 而不是 00：
 *   如果正式版是 `…00`、rc 是 `…01`，那 **rc 的 versionCode 比正式版大** ——
 *   用户装完 rc1 之后，正式版会被系统判定为降级，直接装不上。
 *   反过来（正式 99、rc 01–98）才能保证 `rc1 < rc2 < 正式版 < 下个 rc`。
 *
 * 单调性断言：新 versionCode 必须 > 当前 build.gradle.kts 里的值，否则直接失败。
 *   这条是防「发了个低版本号，包装不上」——Android 的 versionCode 只能升不能降。
 *
 * 已知边界：`0.1.0`（10099）比旧的 `1.8.0`（10800）小，会被断言拦下。
 *   换编码方案后的第一个版本，minor 至少要 ≥ 2（或 major ≥ 1）。
 *
 * ⚠️ 2026-10-25 修：原来还写 `src-tauri/tauri.conf.json`、`app/tauri.properties`
 * 和 `src/views/SettingsView.vue` —— 那三个文件在纯 JNI 改造时都删了。脚本当时是
 * 「文件不存在就跳过」，所以静默没写 versionCode，安卓包会一直沿用旧值 →
 * 覆盖安装被系统拒绝。现在直接改 build.gradle.kts 里的字面量，缺一个就报错。
 */
import { readFileSync, writeFileSync, existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const __dirname = dirname(fileURLToPath(import.meta.url));
const root = join(__dirname, "..");

/**
 * 末两位（`DD`）的号段划分。
 *
 *   01–19  alpha（19 个）
 *   20–49  beta （30 个）
 *   50–98  rc   （49 个）
 *     99   正式版
 *
 * 按通道分号段而不是都从 01 起：这样 `alpha < beta < rc < 正式` 的先后关系
 * 天然成立，同时发 alpha 和 rc 也不会撞号。次数用完（比如 rc 超过 49 个）
 * 就该提升 patch 了 —— 那说明这个版本本身该重新规划。
 */
const CHANNEL_BASE = { alpha: 1, beta: 20, rc: 50 };
const CHANNEL_MAX = { alpha: 19, beta: 30, rc: 49 };
const RELEASE_PRE = 99;

const args = process.argv.slice(2);
const dryRun = args.includes("--dry");
/**
 * 显式重置版本号（跳过单调性断言）。
 *
 * 只在「有意识地重开版本序列」时用（例如 1.8.0 → 0.2.0）。
 * 代价：versionCode 变小 → **已装该应用的用户无法覆盖安装**，必须卸载重装
 *（卸载会清掉应用私有目录：设置、账号、插件状态；/sdcard 下的实例数据不受影响）。
 *
 * 默认不给这个开关，就是怕手滑发出一个「谁都装不上」的包。
 */
const allowReset = args.includes("--reset");
const arg = args.find((a) => !a.startsWith("--"));

if (!arg) {
  console.error("用法: node scripts/update-version.mjs <X.Y.Z | X.Y.Z-rcN | X.Y.Z-betaN> [--dry]");
  process.exit(1);
}

const newVersion = arg.replace(/^v/, "");
const m = /^(\d+)\.(\d+)\.(\d+)(?:-(alpha|beta|rc)\.?(\d+))?$/.exec(newVersion);
if (!m) {
  console.error(`版本号格式不对：${newVersion}（要 X.Y.Z 或 X.Y.Z-rc1 / -beta1 / -alpha1）`);
  process.exit(1);
}

const [, majorS, minorS, patchS, channel, preS] = m;
const major = Number(majorS);
const minor = Number(minorS);
const patchNo = Number(patchS);
const pre = channel ? Number(preS) : 0;

if (channel && (pre < 1 || pre > CHANNEL_MAX[channel])) {
  console.error(
    `${channel} 的序号必须在 1–${CHANNEL_MAX[channel]} 之间（收到 ${pre}）。` +
      `该通道号段用完了，提升 patch 重新开始。`,
  );
  process.exit(1);
}

const preCode = channel ? CHANNEL_BASE[channel] + (pre - 1) : RELEASE_PRE;
const versionCode = major * 1_000_000 + minor * 10_000 + patchNo * 100 + preCode;

// ---- 单调性断言：读当前 versionCode ----
const gradlePath = join(root, "android/app/build.gradle.kts");
if (!existsSync(gradlePath)) {
  console.error("找不到 android/app/build.gradle.kts，无法校验 versionCode 单调性");
  process.exit(1);
}
const currentCode = Number(/^\s*versionCode\s*=\s*(\d+)\s*$/m.exec(readFileSync(gradlePath, "utf8"))?.[1] ?? 0);
if (!currentCode) {
  console.error("读不到当前 versionCode，拒绝继续（怕写出装不上的包）");
  process.exit(1);
}
if (versionCode <= currentCode) {
  if (!allowReset) {
    console.error(
      `拒绝写入：新 versionCode=${versionCode} 不大于当前值 ${currentCode}。\n` +
        `  Android 只允许 versionCode 递增，否则用户装不上（INSTALL_FAILED_VERSION_DOWNGRADE）。\n` +
        `  换编码方案后的首个版本，minor 需要 ≥ 2（或 major ≥ 1）。\n` +
        `  —— 确实要重开版本序列（接受老用户卸载重装）就加 --reset。`,
    );
    process.exit(1);
  }
  console.warn(
    `⚠️ --reset：versionCode ${currentCode} → ${versionCode}（变小了）。\n` +
      `   已装旧版的用户**无法覆盖安装**，必须卸载重装（会丢私有目录里的设置/账号）。\n` +
      `   发布说明里必须写清这一步。`,
  );
}

console.log(
  `${dryRun ? "[dry] " : ""}${newVersion}  →  versionCode ${currentCode} → ${versionCode}` +
    `（${String(versionCode).padStart(8, "0")}）`,
);

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
    if (!dryRun) writeFileSync(path, next);
    console.log(`  ✓ ${dryRun ? "将更新" : "已更新"} ${file}`);
  } else if (alreadyOk && alreadyOk(content)) {
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
  "android/app/build.gradle.kts",
  (c) => {
    const next = c.replace(/^(\s*versionCode\s*=\s*)\d+$/m, `$1${versionCode}`);
    return next.replace(/^(\s*versionName\s*=\s*)".*"$/m, `$1"${newVersion}"`);
  },
  (c) =>
    c.includes(`versionName = "${newVersion}"`) &&
    new RegExp(`versionCode\\s*=\\s*${versionCode}\\b`).test(c),
);

// 4. package-lock.json（审计 B43：锁文件里的 version 一直停在旧版，
//    npm ci 会提示清单与锁文件不一致）
patch(
  "package-lock.json",
  (c) => {
    const lock = JSON.parse(c);
    lock.version = newVersion;
    if (lock.packages && lock.packages[""]) lock.packages[""].version = newVersion;
    return JSON.stringify(lock, null, 2) + "\n";
  },
  (c) => {
    const lock = JSON.parse(c);
    return (
      lock.version === newVersion &&
      (!lock.packages || !lock.packages[""] || lock.packages[""].version === newVersion)
    );
  },
);

if (process.exitCode) {
  console.error(`版本号同步未全部成功（安卓 versionCode 目标值=${versionCode}）。`);
} else {
  console.log(`${dryRun ? "[dry] 未写入任何文件。" : "完成。"}（安卓 versionCode=${versionCode}）`);
}
