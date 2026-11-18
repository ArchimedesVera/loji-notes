# Loji Notes - offline build script
# Requires: local IntelliJ IDEA install (ships its own JBR + kotlinc). No network, no Gradle.
#
#   powershell -ExecutionPolicy Bypass -File build.ps1
#   powershell -ExecutionPolicy Bypass -File build.ps1 -Test
#   powershell -ExecutionPolicy Bypass -File build.ps1 -IdeaHome "E:\IntelliJ IDEA 2026.2.1"
#
param(
    [string]$IdeaHome = "",
    [string]$Version = "0.1.0",
    [switch]$Test
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Definition
Set-Location $root

function Resolve-IdeaHome {
    if ($IdeaHome -ne "") {
        if (-not (Test-Path $IdeaHome)) { throw "IDEA home not found: $IdeaHome" }
        return (Resolve-Path $IdeaHome).Path
    }
    if ($env:LOJI_IDEA_HOME -and (Test-Path $env:LOJI_IDEA_HOME)) {
        return (Resolve-Path $env:LOJI_IDEA_HOME).Path
    }
    $base = 'HKLM:\SOFTWARE\JetBrains\IntelliJ IDEA'
    if (Test-Path $base) {
        $keys = Get-ChildItem $base | Sort-Object PSChildName -Descending
        foreach ($k in $keys) {
            $p = (Get-ItemProperty $k.PSPath -ErrorAction SilentlyContinue).'(default)'
            if ($p -and (Test-Path $p)) { return (Resolve-Path $p).Path }
        }
    }
    throw "IntelliJ IDEA not found. Pass -IdeaHome or set LOJI_IDEA_HOME."
}

function Write-ArgFile([string]$path, [string[]]$lines) {
    $text = ($lines -join "`n")
    [System.IO.File]::WriteAllText($path, $text, (New-Object System.Text.UTF8Encoding($false)))
}

function Compile([string]$outDir, [string[]]$sources, [string[]]$classpathEntries, [string]$label) {
    $cp = [string]([string]::Join(';', $classpathEntries)).Replace('\', '/')
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.Append('-classpath' + "`n")
    [void]$sb.Append('"' + $cp + '"' + "`n")
    [void]$sb.Append('-jvm-target' + "`n" + '21' + "`n")
    [void]$sb.Append('-nowarn' + "`n")
    [void]$sb.Append('-d' + "`n" + '"' + $outDir.Replace('\', '/') + '"' + "`n")
    foreach ($s in $sources) {
        [void]$sb.Append('"' + $s.Replace('\', '/') + '"' + "`n")
    }
    $argFile = Join-Path $root "build\$label.args"
    New-Item -ItemType Directory -Force -Path (Split-Path $argFile) | Out-Null
    [System.IO.File]::WriteAllText($argFile, $sb.ToString(), (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "==> compile [$label] $($sources.Count) files"
    & $kotlinc "@$argFile"
    if ($LASTEXITCODE -ne 0) { throw "compilation failed ($label), exit=$LASTEXITCODE" }
}

$idea = Resolve-IdeaHome
$kotlinc = Join-Path $idea 'plugins\Kotlin\kotlinc\bin\kotlinc.bat'
$stdlib = Join-Path $idea 'plugins\Kotlin\kotlinc\lib\kotlin-stdlib.jar'
$java = Join-Path $idea 'jbr\bin\java.exe'

Write-Host "IDEA: $idea"
if (-not (Test-Path $kotlinc)) { throw "kotlinc not bundled in this IDEA: $kotlinc" }

$env:JAVA_HOME = Join-Path $idea 'jbr'

foreach ($d in 'build\classes', 'build\test-classes', 'build\dist') {
    if (Test-Path $d) { Remove-Item $d -Recurse -Force }
}

$platformJars = Get-ChildItem (Join-Path $idea 'lib') -Filter *.jar | ForEach-Object { $_.FullName }
$mainSources = Get-ChildItem 'src\main\kotlin' -Recurse -Filter *.kt | ForEach-Object { $_.FullName }
Compile 'build\classes' $mainSources $platformJars 'main'

Write-Host "==> copy resources"
Copy-Item 'src\main\resources\*' 'build\classes' -Recurse -Force

# 版本号单一来源：以 -Version 参数为准，写回打进包里的 plugin.xml
$pluginXml = 'build\classes\META-INF\plugin.xml'
$xml = [System.IO.File]::ReadAllText($pluginXml)
$xml = [regex]::Replace($xml, '<version>[^<]*</version>', "<version>$Version</version>")
[System.IO.File]::WriteAllText($pluginXml, $xml, (New-Object System.Text.UTF8Encoding($false)))
Write-Host "==> version pinned to $Version"

if ($Test) {
    $testSources = Get-ChildItem 'src\test\kotlin' -Recurse -Filter *.kt | ForEach-Object { $_.FullName }
    $testCp = @((Resolve-Path 'build\classes').Path, $stdlib)
    Compile 'build\test-classes' $testSources $testCp 'test'
    Write-Host "==> run self-test"
    & $java -cp ("build\classes;build\test-classes;$stdlib") loji.notes.SelfTest
    if ($LASTEXITCODE -ne 0) { throw "self-test failed" }
}

$pluginDir = 'build\dist\loji-notes'
New-Item -ItemType Directory -Force -Path (Join-Path $pluginDir 'lib') | Out-Null

$py = $null
foreach ($cand in @('python', 'py')) {
    $cmd = Get-Command $cand -ErrorAction SilentlyContinue
    if ($cmd) { $py = $cmd.Source; break }
}
if (-not $py) {
    $bundled = Join-Path $env:LOCALAPPDATA '..\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe'
    if (Test-Path $bundled) { $py = $bundled }
}
if (-not $py) { throw "python not found, cannot package" }

& $py 'tools\package.py' `
    --classes 'build\classes' `
    --out-jar (Join-Path $pluginDir 'lib\loji-notes.jar') `
    --plugin-dir $pluginDir `
    --zip ("build\dist\loji-notes-$Version.zip")
if ($LASTEXITCODE -ne 0) { throw "packaging failed" }

Write-Host ""
Write-Host "done." -ForegroundColor Green
Write-Host "install: IDEA -> Settings -> Plugins -> gear -> Install Plugin from Disk... -> build\dist\loji-notes-$Version.zip"
Write-Host "or unzip into: $env:APPDATA\JetBrains\IntelliJIdea2026.2\plugins\"
