param(
    [string]$VideoDirectory = 'C:\Users\Usuario\Downloads\MorrisPool',
    [ValidateSet('CrossMaze','MorrisPool')][string]$Experiment = 'MorrisPool',
    [string]$JavaPath = 'C:\Program Files\Java\jdk-17\bin\java.exe'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$temporary = Join-Path ([IO.Path]::GetTempPath()) ('dungeonmice-bundle-check-' + [guid]::NewGuid())
$savedEnvironment = @{}
foreach ($name in @('PATH','LOCALAPPDATA','PYTHONHOME','PYTHONPATH','DUNGEONMICE_PYTHON')) { $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name) }
Push-Location -LiteralPath $root
try {
    & mvn -q test-compile
    if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }
    New-Item -ItemType Directory -Path $temporary | Out-Null
    $copy = Join-Path $temporary 'Installed App'
    New-Item -ItemType Directory -Path $copy | Out-Null
    foreach ($name in @('DungeonMice.exe','DungeonMice.ico','app','runtime','backend')) {
        Copy-Item -LiteralPath (Join-Path $root "dist\DungeonMice\$name") -Destination $copy -Recurse
    }
    $video = Get-ChildItem -LiteralPath $VideoDirectory -File -Recurse | Where-Object { $_.Extension -in @('.avi','.mov','.mp4','.mkv','.m4v') } | Sort-Object FullName | Select-Object -First 1
    if (-not $video) { throw 'No source video available' }
    $probe = (& ffprobe -v error -select_streams v:0 -show_entries stream=width,height -of json $video.FullName) -join "`n" | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0) { throw 'Video inspection failed' }
    $preset = if ($Experiment -eq 'MorrisPool') { 'MorrisPool_escopolamina_s1.json' } else { 'CrossMaze_control_s1.json' }
    $config = Get-Content -LiteralPath (Join-Path $copy "backend\engine\src\$preset") -Raw | ConvertFrom-Json
    foreach ($region in $config.regions) {
        $region | Add-Member -NotePropertyName frameWidth -NotePropertyValue $probe.streams[0].width -Force
        $region | Add-Member -NotePropertyName frameHeight -NotePropertyValue $probe.streams[0].height -Force
        foreach ($point in @($region.points)) {
            if ($null -eq $point) { continue }
            $point[0] = [Math]::Min($point[0], $probe.streams[0].width - 1)
            $point[1] = [Math]::Min($point[1], $probe.streams[0].height - 1)
        }
    }
    $regions = Join-Path $temporary 'regions.json'
    ConvertTo-Json -InputObject @($config.regions) -Depth 30 | Set-Content -LiteralPath $regions -Encoding utf8
    $before = @(Get-ChildItem -LiteralPath $copy -File -Recurse | Sort-Object FullName | Get-FileHash | Select-Object Path,Hash | ConvertTo-Json -Compress)
    $classpath = (Join-Path $copy 'app\DungeonMiceApp-1.0-SNAPSHOT.jar') + ';' + (Join-Path $root 'target\test-classes')
    $env:PATH = "$env:SystemRoot\System32"
    $env:LOCALAPPDATA = Join-Path $temporary 'User Data'
    $env:PYTHONHOME = Join-Path $temporary 'Missing Host Python'
    $env:PYTHONPATH = $env:PYTHONHOME
    Remove-Item Env:DUNGEONMICE_PYTHON -ErrorAction SilentlyContinue
    Set-Location -LiteralPath $temporary
    & (Join-Path $copy 'backend\runtime\python\python.exe') -B -I -X utf8 (Join-Path $root 'scripts\check_python_bundle.py') (Join-Path $copy 'backend')
    if ($LASTEXITCODE -ne 0) { throw 'Relocated interpreter/import/source checks failed' }
    & $JavaPath -cp $classpath com.phys.dungeonmiceapp.PackagedBridgeChecks $copy $VideoDirectory $regions
    if ($LASTEXITCODE -ne 0) { throw 'Relocated package integration checks failed' }
    $stdout = Join-Path $temporary 'launcher-out.log'
    $stderr = Join-Path $temporary 'launcher-error.log'
    $launcher = Start-Process -FilePath (Join-Path $copy 'DungeonMice.exe') -ArgumentList '--python-startup-check' -WorkingDirectory $temporary -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
    if (-not $launcher.WaitForExit(45000)) {
        $launcher.Kill()
        $launcher.WaitForExit()
        throw 'Packaged EXE startup check timed out'
    }
    $output = Get-Content -LiteralPath $stdout -Raw
    if ($launcher.ExitCode -ne 0 -or $output -notmatch 'PASS App automatic Python startup PID=' -or $output -notmatch 'PASS App automatic Python shutdown PID=') {
        Get-Content -LiteralPath $stderr | Write-Output
        throw 'Packaged EXE automatic Python lifecycle check failed'
    }
    Write-Output 'PASS Actual EXE starts its JavaFX UI and bundled Python, then closes both'
    $after = @(Get-ChildItem -LiteralPath $copy -File -Recurse | Sort-Object FullName | Get-FileHash | Select-Object Path,Hash | ConvertTo-Json -Compress)
    if (Compare-Object $before $after) { throw 'Installed application files changed during processing' }
    Write-Output 'PASS Relocated package processes real video without changing its installed files or using host Python'
} finally {
    foreach ($name in $savedEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name]) }
    Pop-Location
    $path = [IO.Path]::GetFullPath($temporary)
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
    if (-not $path.StartsWith($tempRoot + '\dungeonmice-bundle-check-', [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe temporary test directory' }
    if (Test-Path -LiteralPath $path) {
        if ((Get-Item -LiteralPath $path).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Unexpected test reparse point' }
        if (Get-ChildItem -LiteralPath $path -Recurse -Force | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }) { throw 'Unexpected test reparse point' }
        Remove-Item -LiteralPath $path -Recurse -Force
    }
}
