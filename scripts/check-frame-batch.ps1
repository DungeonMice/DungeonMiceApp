param([string]$JavaPath = 'C:\Program Files\Java\jdk-17\bin\java.exe')
$ErrorActionPreference = 'Stop'
Push-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
$temporary = Join-Path ([IO.Path]::GetTempPath()) ('dungeonmice-batch-check-' + [guid]::NewGuid())
try {
    New-Item -ItemType Directory -Path $temporary | Out-Null
    foreach ($name in @('first','second')) {
        & ffmpeg -v error -f lavfi -i 'testsrc2=size=320x240:rate=30' -t 2 -c:v mjpeg (Join-Path $temporary "$name.avi")
        if ($LASTEXITCODE -ne 0) { throw 'Cannot generate short test fixture' }
    }
    [IO.File]::WriteAllText((Join-Path $temporary 'broken.avi'), 'Deliberately invalid test video')
    $preset = Get-Content -LiteralPath 'F:\JavaApps\DungeonMice\src\MorrisPool_escopolamina_s1.json' -Raw | ConvertFrom-Json
    foreach ($region in $preset.regions) {
        $region | Add-Member -NotePropertyName frameWidth -NotePropertyValue 320 -Force
        $region | Add-Member -NotePropertyName frameHeight -NotePropertyValue 240 -Force
    }
    $regions = Join-Path $temporary 'regions.json'
    ConvertTo-Json -InputObject @($preset.regions) -Depth 30 | Set-Content -LiteralPath $regions -Encoding utf8
    & mvn -q test-compile
    if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }
    & mvn -q dependency:copy-dependencies '-DincludeScope=runtime' '-DoutputDirectory=target/runtime-libs'
    if ($LASTEXITCODE -ne 0) { throw 'Runtime dependencies failed' }
    $fx = (Get-ChildItem -LiteralPath "$env:USERPROFILE\.m2\repository\org\openjfx" -Recurse -Filter '*17.0.10-win.jar').FullName -join ';'
    & $JavaPath --module-path $fx --add-modules javafx.controls,javafx.fxml,javafx.media -cp 'target/classes;target/test-classes;target/runtime-libs/*' com.phys.dungeonmiceapp.FrameBatchUiChecks $temporary $regions
    if ($LASTEXITCODE -ne 0) { throw 'Frame batch UI checks failed' }
} finally {
    $path = [IO.Path]::GetFullPath($temporary)
    $root = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
    if (-not $path.StartsWith($root + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe temporary path' }
    if (Test-Path -LiteralPath $path) {
        if (Get-ChildItem -LiteralPath $path -Recurse -Force | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }) { throw 'Unexpected reparse point' }
        Remove-Item -LiteralPath $path -Recurse -Force
    }
    Pop-Location
}
