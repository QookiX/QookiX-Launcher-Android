# ============================================================================
# 把插件发布到仓库的固定 Release（tag = plugins）
#
# 用法：
#   pwsh -File scripts/publish-plugins.ps1                        # 全部插件、双 ABI
#   pwsh -File scripts/publish-plugins.ps1 -Abis arm64-v8a        # 只出 arm64
#   pwsh -File scripts/publish-plugins.ps1 -Only renderer         # 只打包某类
#
# 它做四件事：
#   1. 调 make-plugin-zip.ps1 重新打 zip，BaseUrl 指向 plugins tag（清单里的下载地址）
#   2. 确保仓库存在 tag 为 plugins 的 Release（没有就创建）
#   3. 上传 zip —— **不带 --clobber**：同名文件已存在会失败，这正是闸门，
#      逼着改内容必须升版本号（老客户端按 sha1 校验，覆盖同名 = 拒装）
#   4. 上传 manifest.json —— 带 --clobber：它是索引，必须指向最新一批 zip
#
# 前置：gh 已登录（gh auth login），且对本仓库有发布权限。
# ============================================================================
param(
    [string]$Repo = "ZhaYi-Miao/QookiX-Launcher-Android",
    [string]$Tag = "plugins",
    [string[]]$Abis = @("arm64-v8a", "x86_64"),
    [string[]]$Only = @()
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$BaseUrl = "https://github.com/$Repo/releases/download/$Tag"

# 0) gh 必须已登录
gh auth status 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) { throw "gh 未登录：先运行 gh auth login" }

# 1) 打包
& (Join-Path $PSScriptRoot "make-plugin-zip.ps1") -BaseUrl $BaseUrl -Abis $Abis -Only $Only
if ($LASTEXITCODE -ne 0) { throw "插件打包失败" }

$dist = Join-Path $root "dist-plugins"
$zips = Get-ChildItem $dist -Filter *.zip
if (-not $zips) { throw "dist-plugins 里没有 zip" }

# 2) 确保 Release 存在
gh release view $Tag --repo $Repo 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) {
    gh release create $Tag --repo $Repo `
        --title "插件源（plugins）" `
        --notes "启动器插件的固定发布地址。manifest.json 是索引；zip 按 <id>-<版本>-<abi>.zip 命名，同名文件永不覆盖 —— 改内容请升版本号。"
    if ($LASTEXITCODE -ne 0) { throw "创建 Release「$Tag」失败" }
}

# 3) 上传 zip（不许覆盖）
#
# ⚠️ 2026-10-29（外部审计 B37 核实成立）：原来上传失败只 Write-Warning 就继续，
# 后面第 4 步再用 --clobber 覆盖 manifest —— 结果 manifest 指向一批**不存在的 zip**，
# 手机端刷新清单后看到新版本、点下载 404。
# 现在任何一个 zip 上传失败都直接中止，不碰 manifest。
foreach ($z in $zips) {
    gh release upload $Tag $z.FullName --repo $Repo
    if ($LASTEXITCODE -ne 0) {
        throw "「$($z.Name)」上传失败 —— 多半是同名文件已存在。请把版本号升一位后重新打包（不覆盖旧 zip）；本次已中止，manifest 未被修改。"
    }
}

# 4) manifest.json 覆盖上传
gh release upload $Tag (Join-Path $dist "manifest.json") --repo $Repo --clobber
if ($LASTEXITCODE -ne 0) { throw "manifest.json 上传失败" }

Write-Host ""
Write-Host "完成：https://github.com/$Repo/releases/tag/$Tag"
Write-Host "手机端：「设置 → 插件 → 刷新清单」即可看到新版本。"
