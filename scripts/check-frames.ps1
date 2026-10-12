param([string]$VideoDirectory = 'C:\Users\Usuario\Downloads\CrossMaze', [string]$JavaPath = 'C:\Program Files\Java\jdk-17\bin\java.exe')
$ErrorActionPreference = 'Stop'
Push-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
$temporary = Join-Path ([IO.Path]::GetTempPath()) ('dungeonmice-frame-check-' + [guid]::NewGuid())
try {
    New-Item -ItemType Directory -Path $temporary | Out-Null
    $source = Get-ChildItem -LiteralPath $VideoDirectory -File -Filter '*.mov' | Sort-Object Name | Select-Object -First 1
    if (-not $source) { throw 'No CrossMaze MOV video available' }
    $probe = (& ffprobe -v error -select_streams v:0 -show_entries stream=width,height -of json $source.FullName) -join "`n" | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect video dimensions' }
    $preset = Get-Content -LiteralPath 'F:\JavaApps\DungeonMice\src\CrossMaze_control_s1.json' -Raw | ConvertFrom-Json
    foreach ($region in $preset.regions) {
        $region | Add-Member -NotePropertyName frameWidth -NotePropertyValue $probe.streams[0].width -Force
        $region | Add-Member -NotePropertyName frameHeight -NotePropertyValue $probe.streams[0].height -Force
        foreach ($point in $region.points) {
            $point[0] = [Math]::Min($point[0], $probe.streams[0].width - 1)
            $point[1] = [Math]::Min($point[1], $probe.streams[0].height - 1)
        }
    }
    $regions = Join-Path $temporary 'regions.json'
    ConvertTo-Json -InputObject @($preset.regions) -Depth 30 | Set-Content -LiteralPath $regions -Encoding utf8
    & mvn -q test-compile
    if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }
    & mvn -q dependency:copy-dependencies '-DincludeScope=runtime' '-DoutputDirectory=target/runtime-libs'
    if ($LASTEXITCODE -ne 0) { throw 'Runtime dependencies failed' }
    & $JavaPath -cp 'target/classes;target/test-classes;target/runtime-libs/*' com.phys.dungeonmiceapp.FrameApiChecks $VideoDirectory $regions
    if ($LASTEXITCODE -ne 0) { throw 'Real Python frame API checks failed' }
    $fx = (Get-ChildItem -LiteralPath "$env:USERPROFILE\.m2\repository\org\openjfx" -Recurse -Filter '*17.0.10-win.jar').FullName -join ';'
    & $JavaPath --module-path $fx --add-modules javafx.controls,javafx.fxml,javafx.media -cp 'target/classes;target/test-classes;target/runtime-libs/*' com.phys.dungeonmiceapp.FrameUiChecks $VideoDirectory $regions
    if ($LASTEXITCODE -ne 0) { throw 'Frame UI checks failed' }
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
