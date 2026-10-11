# ============================================================================
# 打包「插件」zip 并生成清单 manifest.json
#
# 三类插件都从这里出：
#   components  游戏组件（LWJGL 3.4.1 + SDL3 + spirv-cross）→ jars/ natives/ libs/
#   renderer    渲染器（GL4ES / MobileGlues / Zink-OSMesa）  → libs/
#   driver      驱动（Turnip / Vulkan 层）                   → libs/
#
# 用法：
#   pwsh -File scripts/make-plugin-zip.ps1
#   pwsh -File scripts/make-plugin-zip.ps1 -BaseUrl https://github.com/USER/REPO/releases/download/plugins
#   pwsh -File scripts/make-plugin-zip.ps1 -Abis arm64-v8a -Only renderer
#
# 产物在 dist-plugins/（已 gitignore）：每个插件一个 zip + 一份 manifest.json。
# 本地测试不用上传：dist-plugins/serve.mjs 起个静态服务 + `adb reverse`，
# 在「设置 → 插件 → 插件源」里填 http://127.0.0.1:18181/manifest.json 即可。
#
# 包内布局（与 src-tauri/src/plugin.rs 的默认 layout 一致）：
#   plugin.json          自描述（本地安装时靠它识别 id/版本/渲染器键）
#   jars/*.jar           → classpath
#   natives/*.so         → org.lwjgl.librarypath
#   libs/*.so            → java.library.path（渲染器实现、SDL3 等）
# ============================================================================
param(
    [string]$BaseUrl = "",
    [string[]]$Abis = @("arm64-v8a", "x86_64"),
    [string]$ComponentsVersion = "3.4.1-3",
    [string]$RendererVersion = "1.0.1",
    [string]$DriverVersion = "1.0.1",
    # frpc 的版本号就跟着上游走（方便一眼看出装的是哪个 frpc）
    [string]$FrpcVersion = "0.71.0",
    # 只打某一类/某个 id（comma 分隔的 kind 或 id，例如 "renderer" / "qookix-renderer-mobileglues"）
    [string[]]$Only = @()
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$root = Split-Path -Parent $PSScriptRoot
$main = Join-Path $root "src-tauri\gen\android\app\src\main"
$outDir = Join-Path $root "dist-plugins"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$jarsDir = Join-Path $main "assets-components\components\lwjgl3\3.4.1"
$nativesRoot = Join-Path $main "assets-components\components\lwjgl-3.4.1-natives"
$jniLibs = Join-Path $main "jniLibs"
# 渲染器/驱动插件的库源：这些库**不再打进 APK**（jniLibs 里只留随包兜底的
# gl4es / openal / pojav 版 lwjgl 等），统一从这份独立目录取，与 APK 解耦。
# $main = ...\gen\android\app\src\main，往上三层才是 gen\android。
$pluginLibs = Join-Path (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $main))) "plugin-libs"

# ── 插件定义表 ───────────────────────────────────────────────────────────────
# pack 决定「从哪凑文件」；renderers 是 v2 的匹配键（设置里选的渲染器名）。
$defs = @(
    # frpc（内网穿透客户端）。放进插件通道的意义：**不出启动器新版本也能更新 frpc**
    # （上游发版比我发版快得多）。包里就是那个静态可执行文件，但从 `libs/` 走 ——
    # 安装器的解压白名单只收 jar/so/json，没有扩展名的可执行文件会被直接丢掉。
    # 路径查找见 src-tauri/src/frp.rs：插件优先、APK 里那份兜底。
    [ordered]@{
        id = "qookix-frpc"
        name = "frpc 隧道客户端"
        summary = "内网穿透用的 frpc。装了它才能在「FRP 隧道」页启停隧道；不装则用随包自带的那份。"
        version = $FrpcVersion
        kind = "tool"
        renderers = @()
        layout = [ordered]@{ librarypath = "libs" }
        pack = "libs"
        libs = @("libfrpc.so")
        notice = @(
            "frp（frpc 客户端）—— Apache-2.0，未作修改",
            "  上游：https://github.com/fatedier/frp",
            "  说明：本包把它作为独立可执行文件分发，未修改其代码。"
        )
    }
    [ordered]@{
        id = "qookix-components-lwjgl341"
        name = "LWJGL 3.4.1 + SDL3 组件包"
        summary = "26.3 以上要用的新 LWJGL（带 SDL3 窗口层）。不装就用随包自带的。"
        version = $ComponentsVersion
        kind = "components"
        renderers = @()
        layout = [ordered]@{ classpath = "jars"; lightgl = "natives"; librarypath = "libs" }
        pack = "components"
        libs = @()
        notice = @(
            "包含的第三方组件：",
            "  - LWJGL（AngelAuraMC fork）—— BSD-3-Clause，未作修改",
            "  - SPIRV-Cross —— Apache-2.0，未作修改"
        )
    }
    [ordered]@{
        id = "qookix-renderer-mobileglues"
        name = "MobileGlues 渲染器"
        summary = "性能更好的渲染器，自带 EGL。26.3 以上要用它。"
        version = $RendererVersion
        kind = "renderer"
        renderers = @("mobileglues")
        layout = [ordered]@{ librarypath = "libs" }
        pack = "libs"
        libs = @("libmobileglues.so", "libmobileglues_info_getter.so")
        notice = @(
            "MobileGlues —— GNU LGPL-2.1",
            "  上游：https://github.com/MobileGL-Dev/MobileGlues（基线提交 97558a6）",
            "",
            "★ 本包内的 libmobileglues.so 是**修改版**：",
            "  glsl/glsl_for_es.cpp 的 uniform 关键字改为整词匹配（否则 26.x 的地形着色器",
            "  会被改写成非法语句，进世界后一片虚无）。",
            "  修改后的源码（补丁 + 复现构建步骤）：",
            "  https://github.com/ZhaYi-Miao/QookiX-Launcher-Android/tree/master/patches/mobileglues",
            "  许可原文见上游仓库的 LICENSE。"
        )
    }
    [ordered]@{
        id = "qookix-renderer-gl4es"
        name = "GL4ES 渲染器"
        summary = "默认渲染器，兼容性最好。不装就用随包自带的。"
        version = $RendererVersion
        kind = "renderer"
        renderers = @("opengles2")
        layout = [ordered]@{ librarypath = "libs" }
        pack = "libs"
        libs = @("libgl4es_114.so")
        notice = @("GL4ES（ptitSeb）—— MIT，未作修改")
    }
    [ordered]@{
        id = "qookix-renderer-zink-osmesa"
        name = "Zink (OSMesa) 渲染器"
        summary = "走 Vulkan 的渲染器，要配合 Turnip 驱动用。"
        version = $RendererVersion
        kind = "renderer"
        renderers = @("vulkan_zink")
        layout = [ordered]@{ librarypath = "libs" }
        pack = "libs"
        libs = @("libOSMesa.so")
        notice = @("Mesa 3D（OSMesa / zink）—— MIT，未作修改")
    }
    [ordered]@{
        id = "qookix-driver-turnip"
        name = "Turnip 驱动（Vulkan）"
        summary = "Adreno 上的开源 Vulkan 驱动，给 Zink 用。"
        version = $DriverVersion
        kind = "driver"
        renderers = @()
        layout = [ordered]@{ librarypath = "libs" }
        pack = "libs"
        libs = @("libvulkan_freedreno.so", "libVkLayer_khronos_timeline_semaphore.so")
        notice = @(
            "  - Mesa 3D（Turnip）—— MIT，未作修改",
            "  - Khronos Vulkan-ExtensionLayer —— Apache-2.0，未作修改"
        )
    }
)

function Select-Defs {
    param($all, $only)
    if (-not $only -or $only.Count -eq 0) { return $all }
    return $all | Where-Object { $d = $_; $only | Where-Object { $_ -eq $d.kind -or $_ -eq $d.id } }
}

function Get-Sha1([string]$path) {
    (Get-FileHash -Path $path -Algorithm SHA1).Hash.ToLower()
}

function New-PluginZip {
    param($def, $abi, $baseUrl)
    $stage = Join-Path $env:TEMP ("qookix-plugin-" + [guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Force -Path $stage | Out-Null

    $descriptor = [ordered]@{
        id        = $def.id
        name      = $def.name
        version   = $def.version
        kind      = $def.kind
        layout    = $def.layout
        renderers = $def.renderers
    }
    $descriptor | ConvertTo-Json -Depth 5 | Set-Content -Path (Join-Path $stage "plugin.json") -Encoding UTF8

    # 随包 NOTICE：许可 + （LGPL 组件的）修改声明与源码位置。
    # 只把 .so 发给用户、不带许可与修改声明，LGPL 的「显著声明修改 + 提供源码」就没落到
    # 用户手上（仓库里有不等于分发包里有）。
    $notice = @(
        "$($def.name)（$($def.id)）",
        "版本 $($def.version)　目标 ABI $abi",
        "",
        "分发者：QookiX Launcher Android",
        "第三方声明全文：https://github.com/ZhaYi-Miao/QookiX-Launcher-Android/blob/master/THIRD_PARTY_NOTICES.md",
        ""
    ) + $def.notice + @(
        "",
        "本包内二进制均以独立 .so / .jar 形式提供，用户可自行替换后重新打包。"
    )
    $notice | Set-Content -Path (Join-Path $stage "NOTICE.txt") -Encoding UTF8

    $counts = [ordered]@{ jar = 0; native = 0; lib = 0 }

    if ($def.pack -eq "components") {
        if (-not (Test-Path $jarsDir)) { throw "找不到 LWJGL 3.4.1 jar 目录：$jarsDir" }
        $nativesDir = Join-Path $nativesRoot $abi
        if (-not (Test-Path $nativesDir)) { throw "找不到 $abi 的 natives：$nativesDir" }

        New-Item -ItemType Directory -Force -Path (Join-Path $stage "jars") | Out-Null
        New-Item -ItemType Directory -Force -Path (Join-Path $stage "natives") | Out-Null
        New-Item -ItemType Directory -Force -Path (Join-Path $stage "libs") | Out-Null
        Copy-Item (Join-Path $jarsDir "*.jar") (Join-Path $stage "jars") -Force
        Copy-Item (Join-Path $nativesDir "*.so") (Join-Path $stage "natives") -Force
        # **故意不打包 libSDL3.so**（2026-09-27 验证）：
        #   MC 26.3 的窗口层是 SDL3，而 SDL3 的安卓后端要靠 `org.libsdl.app.*` 那套 Java 胶水 ——
        #   那些类只在 APK（ART 侧）里，游戏跑在独立 HotSpot VM 里看不到（跨 VM），
        #   于是 SDL 初始化必然失败、建窗口时空指针崩。
        #   反过来，**不提供 SDL3 时 MC 会自己回退到 GLFW**（它日志里那句
        #   "SDL3 (isXander's libsdl4j) isn't supported in this system. GLFW will be used instead."），
        #   而 GLFW 那条路正是我们打磨了很久、且能配合 MobileGlues 跑起来的
        #   （26.3 + MG 实测进主界面、渲染正常）。
        #   spirv-cross 是 MC 启动时强加载的 spvc 依赖（官方名是 c-shared），保留。
        foreach ($lib in @("libspirv-cross-c-shared.so")) {
            $src = Join-Path $jniLibs "$abi\$lib"
            if (-not (Test-Path $src)) { throw "缺少 $abi 的 $lib（$src）" }
            Copy-Item $src (Join-Path $stage "libs") -Force
        }
        $counts.jar = (Get-ChildItem (Join-Path $stage "jars") -Filter *.jar).Count
        $counts.native = (Get-ChildItem (Join-Path $stage "natives") -Filter *.so).Count
        $counts.lib = (Get-ChildItem (Join-Path $stage "libs") -Filter *.so).Count
    }
    else {
        New-Item -ItemType Directory -Force -Path (Join-Path $stage "libs") | Out-Null
        $missing = @()
        foreach ($lib in $def.libs) {
            $src = Join-Path $pluginLibs "$abi\$lib"
            if (-not (Test-Path $src)) {
                # 库还在 jniLibs（随包分发的那份，如 gl4es）也能打插件包
                $src = Join-Path $jniLibs "$abi\$lib"
            }
            if (Test-Path $src) { Copy-Item $src (Join-Path $stage "libs") -Force }
            else { $missing += $lib }
        }
        if ($missing.Count -eq $def.libs.Count) {
            Remove-Item -Recurse -Force $stage
            throw "「$($def.name)」在 $abi 下一个库都没找到（缺：$($missing -join ', ')）"
        }
        if ($missing.Count -gt 0) {
            Write-Host ("    [警告] $abi 缺少可选库：" + ($missing -join ", "))
        }
        $counts.lib = (Get-ChildItem (Join-Path $stage "libs") -Filter *.so).Count
    }

    $zipName = "$($def.id)-$($def.version)-$abi.zip"
    $zipPath = Join-Path $outDir $zipName
    if (Test-Path $zipPath) { Remove-Item $zipPath -Force }
    [System.IO.Compression.ZipFile]::CreateFromDirectory(
        $stage, $zipPath, [System.IO.Compression.CompressionLevel]::Optimal, $false)
    Remove-Item -Recurse -Force $stage

    $size = (Get-Item $zipPath).Length
    $sha1 = Get-Sha1 $zipPath
    $url = if ($baseUrl) { "$baseUrl/$zipName" } else { "!! 把 $zipName 上传后填这里 !!" }

    $detail = "libs=$($counts.lib)"
    if ($def.pack -eq "components") { $detail = "jar=$($counts.jar) natives=$($counts.native) libs=$($counts.lib)" }
    Write-Host ("  [{0,-8}] {1,-42} {2,7:N1} MB  sha1={3}" -f $abi, $zipName, ($size / 1MB), $sha1)
    Write-Host ("             {0}" -f $detail)

    return [ordered]@{ abi = $abi; url = $url; sha1 = $sha1; size = $size }
}

$selected = Select-Defs -all $defs -only $Only
if (-not $selected -or $selected.Count -eq 0) { throw "没有匹配的插件定义（-Only=$($Only -join ',')）" }

$manifestPlugins = @()
foreach ($def in $selected) {
    Write-Host ("=== " + $def.name + "（" + $def.id + "） ===")
    $files = [ordered]@{}
    foreach ($abi in $Abis) {
        try {
            $r = New-PluginZip -def $def -abi $abi -baseUrl $BaseUrl
            $files[$abi] = [ordered]@{ url = $r.url; sha1 = $r.sha1; size = $r.size }
        }
        catch {
            Write-Host ("    [跳过] $abi：" + $_.Exception.Message)
        }
    }
    if ($files.Count -eq 0) { Write-Host "    [跳过] 没有任何 ABI 打包成功"; continue }

    $manifestPlugins += [ordered]@{
        id        = $def.id
        name      = $def.name
        summary   = $def.summary
        version   = $def.version
        kind      = $def.kind
        layout    = $def.layout
        renderers = $def.renderers
        files     = $files
    }
}

$manifest = [ordered]@{
    schema     = 1
    updated_at = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
    plugins    = $manifestPlugins
}
$manifestPath = Join-Path $outDir "manifest.json"
$manifest | ConvertTo-Json -Depth 8 | Set-Content -Path $manifestPath -Encoding UTF8

Write-Host ""
Write-Host "产物目录：$outDir（共 $($manifestPlugins.Count) 个插件）"
Write-Host "  manifest.json —— 上传到插件源（默认是仓库 Release 的 plugins 标签），并确保 files.*.url 指向 zip"
Write-Host "  本地测试：node dist-plugins/serve.mjs + adb reverse tcp:18181 tcp:18181，"
Write-Host "            然后在「设置 → 插件 → 插件源」填 http://127.0.0.1:18181/manifest.json（或直接「本地安装」选 zip）"
