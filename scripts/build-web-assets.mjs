/**
 * 构建前端静态资源（Compose 版）。
 *
 * ## 这个脚本做什么
 *
 * 把 `bootstrap-public/` 里的静态文件，加上手写的 `index.html`，
 * 一起放到 `src-tauri/gen/android/app/src/main/assets/`。
 *
 * ## 为什么还需要前端资源
 *
 * 界面已经全部改成 Jetpack Compose，**没有任何 Vue 代码**。
 * 但 Tauri/wry 仍然需要一个 WebView 来承载 IPC：
 *
 *   `Rust.ipc()` 的实现是「按 webview id 查表」
 *   （wry-0.57.0/src/android/binding.rs:412）：
 *     if let Some(ipc) = IPC.lock().unwrap().get(&webview_id) { ... }
 *
 *   那张表只在 **Rust 创建 WebView 时**写入。没有 WebView 就没有 id，
 *   `Rust.ipc()` 会**静默什么都不做** —— 所有 Rust 命令都会失效。
 *
 * 所以 `index.html` 必须存在，而且必须让 Tauri 的注入脚本跑起来
 *（建立 `window.__TAURI_INTERNALS__`）。这个页面**没有界面**，
 * 用户看到的一切都是 Compose 画的；承载它的 WebView 是 1×1、alpha=0 的。
 *
 * 另外 `skin3d/` 下的两个 JS 是皮肤 3D 预览用的（skinview3d）。
 *
 * ## 为什么不用 vite 了
 *
 * 原来的 `vite.config.ts` 把 `outDir` 指向 `assets/`，靠 `npm run build`
 * 生成 Vue 产物。Vue 代码删掉之后 vite 无事可做，所以改成这个纯拷贝脚本。
 * 少了一个打包器，构建也更快。
 *
 * ## 注意
 *
 * - `assets/` 在 `.gitignore` 里（不被 git 跟踪），所以**每次构建前必须跑一次**，
 *   否则新 clone 的仓库没有 `index.html`，App 启动后所有 Rust 命令都失效。
 * - `index.html` 的源文件在 `bootstrap-public/index.html`。
 * - Tauri 的 `generate_context!()` 在 **Rust 编译期**扫描 `frontendDist`
 *   并把页面**内嵌进 .so**，所以改完这些文件必须**重新编译 Rust**才生效。
 */
import { cpSync, mkdirSync, existsSync, rmSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const root = join(__dirname, "..");

const SRC = join(root, "bootstrap-public");
const OUT = join(root, "src-tauri/gen/android/app/src/main/assets");

// 1) 清掉上一次的产物（保留目录本身）
//    ⚠️ 原来 `clean-web-assets.mjs` 只清 `assets/assets/` 子目录；
//    现在没有 vite 的 `assets/` 子目录了，改为清掉除 index.html 与 skin3d 之外的东西。
if (existsSync(OUT)) {
  for (const name of ["assets", "skin3d", "index.html"]) {
    const p = join(OUT, name);
    if (existsSync(p)) {
      rmSync(p, { recursive: true, force: true });
      console.log(`[build-web-assets] 清理 ${name}`);
    }
  }
} else {
  mkdirSync(OUT, { recursive: true });
}

// 2) 拷 bootstrap 静态文件（skin3d/*.js）
if (!existsSync(SRC)) {
  console.error(`[build-web-assets] 找不到 ${SRC}，无法继续`);
  process.exit(1);
}
cpSync(SRC, OUT, { recursive: true });
console.log(`[build-web-assets] 已拷贝 ${SRC} -> ${OUT}`);

// 3) 确认 index.html 到位
const indexHtml = join(OUT, "index.html");
if (!existsSync(indexHtml)) {
  console.error(
    `[build-web-assets] ⚠️ 缺少 index.html！\n` +
      `  Tauri 的 WebView 需要它来建立 window.__TAURI_INTERNALS__，\n` +
      `  没有它所有 Rust 命令都会静默失效。\n` +
      `  请确认 bootstrap-public/index.html 存在。`,
  );
  process.exit(1);
}
console.log(`[build-web-assets] ✅ index.html 就位`);
