[CmdletBinding()]
param(
    [string]$JavaHome = $env:JAVA_HOME,
    [ValidatePattern('^\d+\.\d+\.\d+$')][string]$Version = '1.0.3',
    [string]$PythonProject,
    [string]$BuildPython,
    [string]$CacheDirectory,
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$appImage = Join-Path $root 'dist\DungeonMice'
$icon = Join-Path $root 'src\main\resources\images\dungeon-mouse.ico'
$jarName = 'DungeonMiceApp-1.0-SNAPSHOT.jar'
$oldJavaHome = $env:JAVA_HOME
$build = $null

if (-not $JavaHome) {
    $bundledJdk = 'C:\Program Files\Java\jdk-17'
    if (Test-Path -LiteralPath (Join-Path $bundledJdk 'bin\jpackage.exe')) { $JavaHome = $bundledJdk }
    else { $JavaHome = Split-Path -Parent (Split-Path -Parent (Get-Command jpackage.exe -ErrorAction Stop).Source) }
}
$jpackage = Join-Path $JavaHome 'bin\jpackage.exe'
if (-not (Test-Path -LiteralPath $jpackage -PathType Leaf)) { throw 'Use -JavaHome with a Windows JDK containing jpackage (17 or newer).' }
$jdkVersion = & $jpackage --version
if ($LASTEXITCODE -ne 0 -or "$jdkVersion" -notmatch '^(\d+)' -or [int]$Matches[1] -lt 17) { throw 'A Windows JDK 17 or newer is required.' }
if (-not (Test-Path -LiteralPath $icon -PathType Leaf)) { throw "Missing application icon: $icon" }
$installedExe = Join-Path $appImage 'DungeonMice.exe'
if (Test-Path -LiteralPath $installedExe) {
    try { $lock = [IO.File]::Open($installedExe, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::None); $lock.Dispose() }
    catch { throw "Cannot access the launcher. Close Dungeon Mice and check its permissions: $($_.Exception.Message)" }
}

Push-Location -LiteralPath $root
try {
    $env:JAVA_HOME = $JavaHome
    if (-not $SkipBuild) {
        # A fresh thin JAR avoids re-shading a previous fat JAR.
        & mvn -q clean package
        if ($LASTEXITCODE -ne 0) { throw 'Maven build failed; existing distribution was not modified.' }
    }
    $jar = Join-Path $root "target\$jarName"
    if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) { throw 'Build the JAR first or omit -SkipBuild.' }
    $build = Join-Path $root ('target\windows-package-' + [guid]::NewGuid())
    $inputDirectory = Join-Path $build 'input'
    New-Item -ItemType Directory -Path $inputDirectory -Force | Out-Null
    Copy-Item -LiteralPath $jar -Destination $inputDirectory
    & $jpackage --type app-image --name DungeonMice --app-version $Version --input $inputDirectory --main-jar $jarName --main-class com.phys.dungeonmiceapp.App --add-modules ALL-MODULE-PATH --icon $icon --dest (Join-Path $build 'output')
    if ($LASTEXITCODE -ne 0) { throw 'jpackage failed; existing distribution was not modified.' }
    $generated = Join-Path $build 'output\DungeonMice'
    foreach ($required in @('DungeonMice.exe', 'DungeonMice.ico', "app\$jarName", 'app\DungeonMice.cfg', 'runtime\release')) {
        if (-not (Test-Path -LiteralPath (Join-Path $generated $required))) { throw "Incomplete generated package: $required" }
    }
    & (Join-Path $PSScriptRoot 'package-python.ps1') -Destination (Join-Path $generated 'backend') -PythonProject $PythonProject -BuildPython $BuildPython -CacheDirectory $CacheDirectory
    if (-not (Test-Path -LiteralPath (Join-Path $generated 'backend\bundle-manifest.json'))) { throw 'Python backend packaging failed.' }
    # Update only generated application files. Existing user data/VLC installers remain untouched.
    New-Item -ItemType Directory -Path $appImage -Force | Out-Null
    $backend = [IO.Path]::GetFullPath((Join-Path $appImage 'backend'))
    $expected = [IO.Path]::GetFullPath((Join-Path $root 'dist\DungeonMice\backend'))
    if ($backend -ne $expected) { throw 'Unsafe backend destination' }
    $backup = Join-Path $build 'previous-backend'
    $hadBackend = Test-Path -LiteralPath $backend
    if ($hadBackend) {
        if (-not (Test-Path -LiteralPath (Join-Path $backend 'bundle-manifest.json'))) { throw 'Refusing to replace an unrecognized backend directory.' }
        if ((Get-Item -LiteralPath $backend).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Unexpected backend reparse point' }
        if (Get-ChildItem -LiteralPath $backend -Recurse -Force | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }) { throw 'Unexpected backend reparse point' }
        if (-not ([IO.Path]::GetFullPath($backup)).StartsWith([IO.Path]::GetFullPath($build) + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe backend backup' }
        Move-Item -LiteralPath $backend -Destination $backup
    }
    try { Copy-Item -LiteralPath (Join-Path $generated 'backend') -Destination $appImage -Recurse -Force }
    catch {
        if (Test-Path -LiteralPath $backend) {
            if ((Get-Item -LiteralPath $backend).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Unexpected partial backend reparse point' }
            if (Get-ChildItem -LiteralPath $backend -Recurse -Force | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }) { throw 'Unexpected partial backend reparse point' }
            Remove-Item -LiteralPath $backend -Recurse -Force
        }
        if ($hadBackend) { Move-Item -LiteralPath $backup -Destination $backend }
        throw
    }
    foreach ($name in @('app', 'runtime', 'DungeonMice.exe', 'DungeonMice.ico')) {
        Copy-Item -LiteralPath (Join-Path $generated $name) -Destination $appImage -Recurse -Force
    }
    Write-Output "Executable: $installedExe"
    Write-Output 'Keep the complete DungeonMice directory, including app and runtime.'
    Write-Output 'Includes portable Python, locked dependencies, unchanged analysis engine and API adapters.'
    Write-Warning 'Optional MP4 export still requires FFmpeg; live frame analysis does not.'
} finally {
    $env:JAVA_HOME = $oldJavaHome
    if ($build -and (Test-Path -LiteralPath $build)) {
        $path = [IO.Path]::GetFullPath($build)
        $target = [IO.Path]::GetFullPath((Join-Path $root 'target')).TrimEnd('\')
        if (-not $path.StartsWith($target + '\windows-package-', [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe temporary build path' }
        if ((Get-Item -LiteralPath $path).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Unexpected build reparse point' }
        if (Get-ChildItem -LiteralPath $path -Recurse -Force | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }) { throw 'Unexpected build reparse point' }
        Remove-Item -LiteralPath $path -Recurse -Force
    }
    Pop-Location
}
