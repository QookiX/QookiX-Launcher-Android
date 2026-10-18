// ============================================================================
//  generate-release-notes.mjs - 生成 Android 版 GitHub Release 正文
//
//  Usage:
//    GITHUB_REPOSITORY=owner/repo node scripts/generate-release-notes.mjs \
//      <tag> <output.md> [changelog.md] [template.md]
//
//  更新日志来自 CHANGELOG.md 里版本号对得上的那一节（`## [1.1.0] - 2026-09-27`），
//  小节里如果写了 `### 已知问题`，会单独渲染成一段。
//  对不上时依次退化：用 [Unreleased] 那一节 → 保留模板里的占位提示（只在日志里警告，
//  不让发版失败）。
//
//  安装 / 说明 / 反馈这些固定内容放在 .github/release-notes/DEFAULT.md（模板），
//  脚本只替换其中「## 本次更新」那一节。
// ============================================================================
import fs from 'node:fs'

const [tag, output, changelogPath = 'CHANGELOG.md', templatePath = '.github/release-notes/DEFAULT.md'] =
  process.argv.slice(2)
if (!tag || !output) {
  throw new Error(
    'Usage: node scripts/generate-release-notes.mjs <tag> <output.md> [changelog.md] [template.md] (with GITHUB_REPOSITORY set)',
  )
}

const repo = process.env.GITHUB_REPOSITORY || 'QookiX/QookiX-Launcher-Android'
const ver = tag.replace(/^v/, '')
const date = new Date().toISOString().slice(0, 10)
const commitsUrl = `https://github.com/${repo}/commits/${tag}`

// ---------------------------------------------------------------- CHANGELOG ----

/** 按 `## ` 标题切成若干节（标题在文件里也常写成 `## [1.1.0] - 2026-09-27`） */
function splitSections(text) {
  const sections = []
  let current = null
  for (const line of text.replace(/\r\n/g, '\n').split('\n')) {
    const heading = /^##\s+(.*)$/.exec(line)
    if (heading) {
      current = { title: heading[1].trim(), body: [] }
      sections.push(current)
      continue
    }
    if (current) current.body.push(line)
  }
  return sections
}

/** 只留标题里的版本号：`[1.1.0] - 2026-09-27` / `v1.1.0` → `1.1.0` */
function sectionVersion(title) {
  const inner = /^\[(.*?)\]\s*(.*)$/.exec(title.trim())
  const rest = inner ? `${inner[1]}${inner[2] ? ` - ${inner[2]}` : ''}` : title
  return rest.replace(/^v/, '').split(/\s+-\s+/)[0].trim()
}

function pickChangelog(file, version) {
  let text
  try {
    text = fs.readFileSync(file, 'utf8')
  } catch {
    console.warn(`! 读不到 ${file}，本次 Release 用模板里的占位说明`)
    return null
  }

  const sections = splitSections(text)
  let hit = sections.find((s) => sectionVersion(s.title) === version)
  let fallback = false
  if (!hit) {
    hit = sections.find((s) => /unreleased/i.test(s.title))
    fallback = !!hit
  }
  if (!hit) {
    console.warn(`! ${file} 里没有 ${version} 的小节，也没找到 [Unreleased]，本次不放更新日志`)
    return null
  }
  if (fallback) {
    console.warn(`! ${file} 里没有 ${version} 的小节，暂用 [Unreleased] 那一节（发版前记得把标题改成该版本）`)
  }
  if (!hit.body.join('\n').trim()) {
    const unreleased = sections.find((s) => /unreleased/i.test(s.title))
    if (fallback === false && unreleased?.body.join('\n').trim()) {
      console.warn(`! ${file} 里「${hit.title}」一节是空的，改用 [Unreleased] 的内容`)
      return { title: unreleased.title, body: unreleased.body.join('\n').trim() }
    }
    console.warn(`! ${file} 里「${hit.title}」一节是空的，本次不放更新日志`)
    return null
  }
  return { title: hit.title, body: hit.body.join('\n').trim() }
}

/**
 * 把一节切成「更新条目」和「已知问题」：遇到 `### 已知问题` 标题后归到后者。
 */
function splitKnownIssues(body) {
  const lines = body.replace(/\r\n/g, '\n').split('\n')
  const entries = []
  const known = []
  let inKnown = false
  for (const line of lines) {
    const heading = /^###\s+(.+?)\s*$/.exec(line.trim())
    if (heading) {
      const title = heading[1].trim()
      if (/已知问题|known issue/i.test(title)) {
        inKnown = true
        continue
      }
      // 其它 ### 小标题（修复 / 新增 / 优化 …）**保留**，并转成正文加粗行。
      // 2026-10-18：原来所有 ### 都被丢弃，导致分好组的更新日志在 Release 里
      // 变成一大坨没有分类的条目。这里改成保留并降级成 `**标题**`。
      inKnown = false
      entries.push('', `**${title}**`, '')
      continue
    }
    ;(inKnown ? known : entries).push(line)
  }
  return { entries: entries.join('\n').trim(), known: known.join('\n').trim() }
}

const changelog = pickChangelog(changelogPath, ver)

// ----------------------------------------------------------------- TEMPLATE ----

let template
try {
  template = fs.readFileSync(templatePath, 'utf8')
} catch {
  template = [
    '# QookiX Launcher Android {{VERSION}}',
    '',
    '发布于 {{DATE}} · [完整提交记录]({{CHANGELOG}})',
    '',
    '## 本次更新',
    '',
  ].join('\n')
}

let body = template
  .replace(/\{\{VERSION\}\}/g, tag)
  .replace(/\{\{DATE\}\}/g, date)
  .replace(/\{\{CHANGELOG\}\}/g, commitsUrl)

// 模板里的「## 本次更新」这一节整块换成更新日志（模板尾部固定内容原样保留）
const SECTION = /^##\s+本次更新\s*$/m
if (SECTION.test(body)) {
  const start = body.search(SECTION)
  const afterHead = body.indexOf('\n', start) + 1
  const rest = body.slice(afterHead)
  const next = rest.search(/^##\s+/m)
  const tail = next === -1 ? '' : rest.slice(next)

  let block
  if (changelog) {
    const { entries, known } = splitKnownIssues(changelog.body)
    console.log(`Changelog: ${changelogPath} → 「${changelog.title}」`)
    block = `## 更新日志 v${ver}\n\n${entries}\n\n`
    if (known) block += `## 已知问题\n\n${known}\n\n`
  } else {
    console.warn('! 没有可用的更新日志小节，保留模板里的占位提示')
    // 保留原样（含 `## 本次更新` 标题），只是把这一节原封不动留在正文里
    block = `## 本次更新\n\n${body.slice(afterHead, body.length - tail.length).trimStart()}`
  }
  body = body.slice(0, start) + block + tail
}

// 去掉多余的空行，末尾补一个换行
body = `${body.replace(/\n{3,}/g, '\n\n').trim()}\n`
fs.writeFileSync(output, body)
console.log(`Wrote ${output}`)
console.log(`  Changelog: ${changelog ? `yes (${changelog.title})` : 'no'}`)

