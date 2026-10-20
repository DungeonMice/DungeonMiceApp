param(
    [string]$VideoDirectory = 'C:\Users\Usuario\Downloads\MorrisPool',
    [ValidateSet('MorrisPool','CrossMaze')][string]$Experiment = 'MorrisPool',
    [string]$JavaPath = 'C:\Program Files\Java\jdk-17\bin\java.exe'
)
$ErrorActionPreference = 'Stop'
Push-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
try {
    $output = Join-Path (Get-Location) ('target\report-check-' + $Experiment + '-' + [guid]::NewGuid())
    New-Item -ItemType Directory -Path $output | Out-Null
    $source = Get-ChildItem -LiteralPath $VideoDirectory -Recurse -File | Where-Object { $_.Extension -in @('.avi','.mov','.mp4','.mkv','.m4v') } | Sort-Object FullName | Select-Object -First 1
    if (-not $source) { throw 'No source video found' }
    $probe = (& ffprobe -v error -select_streams v:0 -show_entries stream=width,height -of json $source.FullName) -join "`n" | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect source' }
    $preset = if ($Experiment -eq 'MorrisPool') { 'MorrisPool_escopolamina_s1.json' } else { 'CrossMaze_control_s1.json' }
    $config = Get-Content -Raw -LiteralPath (Join-Path 'F:\JavaApps\DungeonMice\src' $preset) | ConvertFrom-Json
    foreach ($region in $config.regions) {
        $region | Add-Member -NotePropertyName frameWidth -NotePropertyValue $probe.streams[0].width -Force
        $region | Add-Member -NotePropertyName frameHeight -NotePropertyValue $probe.streams[0].height -Force
        foreach ($point in @($region.points)) {
            if ($null -ne $point) {
                $point[0] = [Math]::Min($point[0], $probe.streams[0].width - 1)
                $point[1] = [Math]::Min($point[1], $probe.streams[0].height - 1)
            }
        }
    }
    ConvertTo-Json -InputObject @($config.regions) -Depth 30 | Set-Content -Encoding utf8 (Join-Path $output 'regions.json')
    & mvn -q test-compile
    if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }
    & mvn -q dependency:copy-dependencies '-DincludeScope=runtime' '-DoutputDirectory=target/runtime-libs'
    if ($LASTEXITCODE -ne 0) { throw 'Dependencies failed' }
    & $JavaPath -cp 'target/classes;target/test-classes;target/runtime-libs/*' com.phys.dungeonmiceapp.ReportArchiveChecks
    if ($LASTEXITCODE -ne 0) { throw 'Report archive checks failed' }
    & $JavaPath -cp 'target/classes;target/test-classes;target/runtime-libs/*' com.phys.dungeonmiceapp.NativeReportChecks $VideoDirectory (Join-Path $output 'regions.json') $output
    if ($LASTEXITCODE -ne 0) { throw 'Native report checks failed' }
    $fx = (Get-ChildItem "$env:USERPROFILE\.m2\repository\org\openjfx" -Recurse -Filter '*17.0.10-win.jar').FullName -join ';'
    & $JavaPath --module-path $fx --add-modules javafx.controls,javafx.fxml,javafx.media -cp 'target/classes;target/test-classes;target/runtime-libs/*' com.phys.dungeonmiceapp.ReportUiChecks $output
    if ($LASTEXITCODE -ne 0) { throw 'Report preview checks failed' }
    Write-Output "Report fixtures and visual checks: $output"
} finally { Pop-Location }
