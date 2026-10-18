/**
 * 安卓打包（并把「顺序」这件容易踩的事固化成一条命令）。
 *
 * 用法：
 *   node scripts/build-android.mjs                    # arm64 debug 包（默认）
 *   node scripts/build-android.mjs x86_64             # 模拟器用
 *   node scripts/build-android.mjs arm64 --install    # 打完装到设备（有多个设备要 --device）
 *   node scripts/build-android.mjs arm64 --install --device 685b26c4
 *
 * **为什么必须有这个脚本**：Tauri 的 `generate_context!` 会在**编译 Rust 时**把
 * `frontendDist`（这里就是 gen/android/app/src/main/assets）**嵌进 .so**。
 * 所以只要改了 `bootstrap-public/` 里的静态资源，就必须「先跑 build:web-assets，再 cargo build」，
 * 否则 APK 里虽然是新资源，运行时 WebView 拿到的仍是 .so 里那份旧资源 —— 
 * 表现为「改了没生效」，而且 `adb install -r` 看不出任何异常（今天在这上面栽了两次）。
 *
 * 顺序：build:web-assets → touch lib.rs（保证 generate_context! 重跑）→ cargo build →
 *      拷 .so 进 jniLibs → gradle assemble<Abi>Debug →（可选）install -r
 */
import { execFileSync, execSync } from "node:child_process";
import { copyFileSync, existsSync, utimesSync, statSync } from "node:fs";
import { join } from "node:path";

const ROOT = process.cwd();
const APP = join(ROOT, "src-tauri");
const GEN = join(APP, "gen", "android");

const ABIS = {
  arm64: { rust: "aarch64-linux-android", ccPrefix: "aarch64-linux-android", jni: "arm64-v8a", gradle: "Arm64" },
  x86_64: { rust: "x86_64-linux-android", ccPrefix: "x86_64-linux-android", jni: "x86_64", gradle: "X86_64" },
  arm: { rust: "armv7-linux-androideabi", ccPrefix: "armv7a-linux-androideabi", jni: "armeabi-v7a", gradle: "Arm" },
};

const argv = process.argv.slice(2);
const abiName = argv.find((a) => !a.startsWith("--")) ?? "arm64";
const abi = ABIS[abiName];
if (!abi) {
  console.error(`未知 ABI: ${abiName}（可选：${Object.keys(ABIS).join(" / ")}）`);
  process.exit(1);
}
const shouldInstall = argv.includes("--install");
const deviceIdx = argv.indexOf("--device");
const device = deviceIdx >= 0 ? argv[deviceIdx + 1] : null;

const NDK_BIN = "C:\\Users\\ZhaYi\\AppData\\Local\\Android\\Sdk\\ndk\\android-ndk-r27c\\toolchains\\llvm\\prebuilt\\windows-x86_64\\bin";
const ADB = "C:\\Users\\ZhaYi\\AppData\\Local\\Android\\Sdk\\platform-tools\\adb.exe";

function step(title) {
  console.log(`\n=== ${title} ===`);
}

function run(cmd, args, opts = {}) {
  execFileSync(cmd, args, { stdio: "inherit", shell: process.platform === "win32", ...opts });
}

/** 取 NDK 里版本号最大的 clang（避免写死 API level） */
function clangFor(prefix) {
  const list = execSync(`dir /b "${NDK_BIN}\\${prefix}*-clang.cmd"`, { shell: "cmd.exe" })
    .toString()
    .trim()
    .split(/\r?\n/)
    .sort();
  if (!list.length) throw new Error(`NDK 里找不到 ${prefix}*-clang.cmd`);
  return join(NDK_BIN, list[list.length - 1]);
}

step("1/5 前端资源（bootstrap 静态文件 → gen/android/app/src/main/assets）");
// Vue 前端已删除（界面全部是 Compose）。这里只拷贝 index.html + skin3d/*.js —— 
// 那个 index.html 是 Tauri IPC 的引导页，没有它所有 Rust 命令都会静默失效。
run("npm", ["run", "build:web-assets"]);

step("2/5 重编 Rust（generate_context! 会在这里把前端嵌进 .so）");
const cc = clangFor(abi.ccPrefix);
const env = {
  ...process.env,
  [`CC_${abi.rust.replace(/-/g, "_")}`]: cc,
  [`CXX_${abi.rust.replace(/-/g, "_")}`]: cc.replace(/\.cmd$/, "++.cmd"),
  [`AR_${abi.rust.replace(/-/g, "_")}`]: join(NDK_BIN, "llvm-ar.exe"),
};
// 触摸 lib.rs：cargo 的增量判断看 mtime，改前端不会让它重编，只能靠这个
const stamp = new Date();
utimesSync(join(APP, "src", "lib.rs"), stamp, stamp);
run("cargo", ["build", "--target", abi.rust], { cwd: APP, env });

step("3/5 同步 .so 到 jniLibs");
const so = join(APP, "target", abi.rust, "debug", "libqookix_lib.so");
if (!existsSync(so)) throw new Error(`没有产出 ${so}`);
const dest = join(GEN, "app", "src", "main", "jniLibs", abi.jni, "libqookix_lib.so");
copyFileSync(so, dest);
console.log(`  ${(statSync(dest).size / 1024 / 1024).toFixed(1)} MB → ${abi.jni}/libqookix_lib.so`);

step(`4/5 gradle assemble${abi.gradle}Debug`);
run(process.platform === "win32" ? ".\\gradlew.bat" : "./gradlew", [`:app:assemble${abi.gradle}Debug`, "--no-daemon"], { cwd: GEN });

const apkDir = join(GEN, "app", "build", "outputs", "apk", abiName, "debug");
const apk = execSync(`dir /b "${apkDir}\\*.apk"`, { shell: "cmd.exe" }).toString().trim().split(/\r?\n/)[0];
console.log(`  ${join(apkDir, apk)}`);

if (shouldInstall) {
  step("5/5 安装到设备");
  const target = device ? ["-s", device] : [];
  run(ADB, [...target, "install", "-r", join(apkDir, apk)]);
  console.log("\n提示：装完若界面还是旧的，先清 WebView 缓存再硬重载 ——");
  console.log("  node cdp_reload.cjs <ws-url>   （拿 ws-url 见 cdp_drive.js 的用法）");
} else {
  step("5/5 跳过安装（加 --install 可自动装）");
}
console.log("\n完成。");

