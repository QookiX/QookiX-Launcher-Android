#!/usr/bin/env node
/**
 * 文件体量闸门（「只许变少」棘轮）。
 *
 * ## 为什么要有它
 *
 * 评审里 Q2 点名的几个巨文件（PageHost 280KB / browse.rs 137KB / servers.rs 104KB…）
 * 都是「一次加一点、没人拦」长出来的。一次性拆分风险太大，但至少要有一条线：
 * **现有文件不许再变大，新文件不许一出生就是巨物。**
 *
 * 做法是把当前的行数记进 `scripts/file-size-baseline.json` 当预算，
 * CI 里跑这个脚本，超了就让构建失败。要加预算必须单独改那个 JSON ——
 * 改动会出现在 diff 里，review 时一眼能看到「这又是往巨文件里塞东西」。
 *
 * ## 用法
 *
 *   node scripts/check-file-size.mjs          # 检查（CI 用）
 *   node scripts/check-file-size.mjs --write  # 把当前行数写回预算（只在拆分后变小、或明确要加预算时用）
 *
 * ## 阈值
 *
 * - 已在预算里的文件：超过记录值即失败（**不涉及拆分，只是不许涨**）
 * - 不在预算里的新文件：超过 [NEW_FILE_LIMIT] 行即失败
 */
import { readFileSync, writeFileSync, existsSync } from "node:fs";
import { readdirSync, statSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join, relative, sep } from "node:path";

const __dirname = dirname(fileURLToPath(import.meta.url));
const root = join(__dirname, "..");
const BASELINE = join(__dirname, "file-size-baseline.json");

/** 新文件的硬上限（评审的健康线是 800，这里留出余量，只拦「一出生就是巨物」）。 */
const NEW_FILE_LIMIT = 1500;

/** 要检查的源码根目录（相对仓库根）。 */
const SOURCE_DIRS = [
  "src-tauri/src",
  "src-tauri/gen/android/app/src/main/java",
];

/** 只检查这些扩展名。 */
const EXTS = [".rs", ".kt", ".java"];

function walk(dir, out) {
  if (!existsSync(dir)) return;
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    const st = statSync(full);
    if (st.isDirectory()) {
      // 构建产物 / 生成目录不参与统计
      if (name === "build" || name === "target" || name === "node_modules") continue;
      walk(full, out);
    } else if (EXTS.some((e) => name.endsWith(e))) {
      out.push(full);
    }
  }
}

function lineCount(file) {
  const text = readFileSync(file, "utf8");
  if (text.length === 0) return 0;
  // 末尾换行不算多一行
  return text.split("\n").length - (text.endsWith("\n") ? 1 : 0);
}

const files = [];
for (const d of SOURCE_DIRS) walk(join(root, d), files);

const current = {};
for (const f of files) {
  const rel = relative(root, f).split(sep).join("/");
  current[rel] = lineCount(f);
}

const write = process.argv.includes("--write");
if (write) {
  const sorted = Object.fromEntries(Object.entries(current).sort(([a], [b]) => a.localeCompare(b)));
  writeFileSync(BASELINE, JSON.stringify(sorted, null, 2) + "\n");
  const top = Object.entries(sorted).sort((a, b) => b[1] - a[1]).slice(0, 10);
  console.log(`已写入预算：${relative(root, BASELINE)}（${Object.keys(sorted).length} 个文件）`);
  console.log("当前最大的 10 个：");
  for (const [f, n] of top) console.log(`  ${String(n).padStart(6)}  ${f}`);
  process.exit(0);
}

const baseline = existsSync(BASELINE) ? JSON.parse(readFileSync(BASELINE, "utf8")) : {};
const violations = [];

for (const [file, lines] of Object.entries(current)) {
  const budget = baseline[file];
  if (budget == null) {
    if (lines > NEW_FILE_LIMIT) {
      violations.push(`${file}: 新文件 ${lines} 行，超过新文件上限 ${NEW_FILE_LIMIT}`);
    }
    continue;
  }
  if (lines > budget) {
    violations.push(`${file}: ${budget} → ${lines} 行（+${lines - budget}），超出预算`);
  }
}

// 预算里已经不存在 / 变小的文件只提示，不失败（变小是好事）
const improved = Object.entries(baseline)
  .filter(([f, n]) => current[f] != null && current[f] < n)
  .map(([f, n]) => `${f}: ${n} → ${current[f]} 行（-${n - current[f]}，可跑 --write 收紧预算）`);

if (improved.length) {
  console.log("体量下降（建议更新预算）：");
  for (const line of improved) console.log(`  ${line}`);
}

if (violations.length) {
  console.error("\n文件体量闸门未通过：");
  for (const line of violations) console.error(`  ✗ ${line}`);
  console.error(
    `\n这些文件本来就在评审的 Q2 清单里。请拆到新文件，` +
      `或在明确接受「继续变大」时改 ${relative(root, BASELINE)}（会出现在 diff 里）。`,
  );
  process.exit(1);
}

console.log(`文件体量闸门通过（${Object.keys(current).length} 个文件，0 处超预算）。`);
