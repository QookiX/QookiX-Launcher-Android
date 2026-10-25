#!/usr/bin/env node
/**
 * 安卓打包（把「顺序」这件容易踩的事固化成一条命令）。
 *
 * 用法：
 *   node scripts/build-android.mjs                     # arm64 debug
 *   node scripts/build-android.mjs x86_64              # 模拟器
 *   node scripts/build-android.mjs arm64 --release     # release 包
 *   node scripts/build-android.mjs arm64 --install     # 打完装到设备（多设备要 --device）
 *   node scripts/build-android.mjs arm64 --install --device 685b26c4
 *
 * ## 为什么需要它
 *
 * 顺序是硬要求：**先编译 Rust → 再拷 .so 进 jniLibs → 最后 gradle 打包**。
 * 顺序颠倒的话 APK 里装的是上一个版本的 .so，表现为「改了没生效」，
 * 而且 `adb install -r` 看不出任何异常。
 *
 * ## 与旧版的区别（2026-10-25）
 *
 * 旧脚本是 Tauri 时代的：要先跑 `npm run build:web-assets` 把 index.html 塞进
 * assets、还要 `touch lib.rs` 逼 `generate_context!` 重跑；NDK 与 adb 路径写死
 * 在某个人的 `C:\Users\...` 里。这些现在**全部不需要**（纯 JNI，没有 WebView、
 * 没有 generate_context!）。现在路径从 `ANDROID_HOME` / `NDK` 推导。
 */
import { execFileSync } from "node:child_process";
import { copyFileSync, existsSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";

const ROOT = process.cwd();
const RUST_DIR = join(ROOT, "src-tauri");
const ANDROID_DIR = join(ROOT, "android");

const ABIS = {
  arm64: { rust: "aarch64-linux-android", cc: "aarch64-linux-android", jni: "arm64-v8a", gradle: "Arm64" },
  x86_64: { rust: "x86_64-linux-android", cc: "x86_64-linux-android", jni: "x86_64", gradle: "X86_64" },
  arm: { rust: "armv7-linux-androideabi", cc: "armv7a-linux-androideabi", jni: "armeabi-v7a", gradle: "Arm" },
};

const argv = process.argv.slice(2);
const abiName = argv.find((a) => !a.startsWith("--")) ?? "arm64";
const abi = ABIS[abiName];
if (!abi) {
  console.error(`未知 ABI: ${abiName}（可选：${Object.keys(ABIS).join(" / ")}）`);
  process.exit(1);
}
const isRelease = argv.includes("--release");
const shouldInstall = argv.includes("--install");
const deviceIdx = argv.indexOf("--device");
const device = deviceIdx >= 0 ? argv[deviceIdx + 1] : null;

const SDK =
  process.env.ANDROID_HOME ||
  process.env.ANDROID_SDK_ROOT ||
  (process.env.LOCALAPPDATA ? join(process.env.LOCALAPPDATA, "Android", "Sdk") : "");
const HOST = { win32: "windows-x86_64", darwin: "darwin-x86_64" }[process.platform] ?? "linux-x86_64";

/** 找 NDK 里版本号最大的那个（不写死 r27c）。 */
function ndkBin() {
  if (process.env.NDK) return join(process.env.NDK, "toolchains", "llvm", "prebuilt", HOST, "bin");
  const ndkRoot = join(SDK, "ndk");
  if (!existsSync(ndkRoot)) {
    throw new Error("找不到 NDK：设置 ANDROID_HOME 或 NDK 环境变量");
  }
  const newest = readdirSync(ndkRoot).sort((a, b) => a.localeCompare(b, undefined, { numeric: true })).pop();
  return join(ndkRoot, newest, "toolchains", "llvm", "prebuilt", HOST, "bin");
}

function step(title) {
  console.log(`\n=== ${title} ===`);
}

function run(cmd, args, opts = {}) {
  execFileSync(cmd, args, { stdio: "inherit", shell: process.platform === "win32", ...opts });
}

const bin = ndkBin();

/**
 * NDK 里该 ABI 的 clang 包装脚本。
 *
 * Windows 上必须取 `.cmd`（另一个同名文件是给 MSYS 用的 shell 脚本，
 * execFileSync 跑不起来）；按名字升序取第一个 = 最低 API level（24），
 * 与 minSdk 一致。
 */
function clangFor(prefix) {
  const suffix = process.platform === "win32" ? ".cmd" : "";
  const candidates = readdirSync(bin)
    .filter((f) => f.startsWith(prefix) && f.endsWith(`-clang${suffix}`) && !f.includes("++"))
    .sort((a, b) => a.localeCompare(b, undefined, { numeric: true }));
  if (!candidates.length) throw new Error(`NDK 里找不到 ${prefix}*-clang${suffix}`);
  return join(bin, candidates[0]);
}

step(`1/3 编译 Rust（${abi.rust}，${isRelease ? "release" : "debug"}）`);
const cc = clangFor(abi.cc);
const env = {
  ...process.env,
  [`CC_${abi.rust.replace(/-/g, "_")}`]: cc,
  [`CXX_${abi.rust.replace(/-/g, "_")}`]: cc.replace(/(-clang)$/, "-clang++"),
  [`AR_${abi.rust.replace(/-/g, "_")}`]: join(bin, process.platform === "win32" ? "llvm-ar.exe" : "llvm-ar"),
  // 16KB 页对齐：本地也需要（CI 由 release.yml 的 CARGO_TARGET_*_RUSTFLAGS 提供）
  [`CARGO_TARGET_${abi.rust.toUpperCase().replace(/-/g, "_")}_RUSTFLAGS`]:
    "-C link-arg=-Wl,-z,max-page-size=16384",
};
run("cargo", ["build", "--target", abi.rust, ...(isRelease ? ["--release"] : [])], { cwd: RUST_DIR, env });

step("2/3 同步 .so 到 jniLibs");
const profile = isRelease ? "release" : "debug";
const so = join(RUST_DIR, "target", abi.rust, profile, "libqookix_lib.so");
if (!existsSync(so)) throw new Error(`没有产出 ${so}`);
const dest = join(ANDROID_DIR, "app", "src", "main", "jniLibs", abi.jni, "libqookix_lib.so");
copyFileSync(so, dest);
console.log(`  ${(statSync(dest).size / 1024 / 1024).toFixed(1)} MB → ${abi.jni}/libqookix_lib.so`);

step(`3/3 gradle assemble${abi.gradle}${isRelease ? "Release" : "Debug"}`);
run(
  process.platform === "win32" ? ".\\gradlew.bat" : "./gradlew",
  [`:app:assemble${abi.gradle}${isRelease ? "Release" : "Debug"}`, "--no-daemon"],
  { cwd: ANDROID_DIR, shell: process.platform === "win32" },
);

const apkDir = join(ANDROID_DIR, "app", "build", "outputs", "apk", abiName, profile);
const apk = existsSync(apkDir) ? readdirSync(apkDir).find((f) => f.endsWith(".apk")) : null;
if (apk) console.log(`  ${join(apkDir, apk)}`);

if (shouldInstall) {
  step("安装到设备");
  const adb = join(SDK, "platform-tools", process.platform === "win32" ? "adb.exe" : "adb");
  if (!apk) throw new Error("没找到 APK，无法安装");
  const target = device ? ["-s", device] : [];
  run(adb, [...target, "install", "-r", join(apkDir, apk)]);
} else {
  step("跳过安装（加 --install 可自动装）");
}

console.log("\n完成。");
