param(
    [string]$PythonPath = "$env:LOCALAPPDATA\DungeonMice\api-venv\Scripts\python.exe",
    [string]$PythonProject = 'F:\JavaApps\DungeonMice',
    [string]$VideoDirectory = 'C:\Users\Usuario\Downloads\CrossMaze',
    [string]$JavaPath = 'C:\Program Files\Java\jdk-17\bin\java.exe'
)
$ErrorActionPreference = 'Stop'
if (-not $PSBoundParameters.ContainsKey('PythonPath') -and -not (Test-Path -LiteralPath $PythonPath)) {
    $redirected = Join-Path $env:LOCALAPPDATA 'Packages\OpenAI.Codex_2p2nqsd0c76g0\LocalCache\Local\DungeonMice\api-venv\Scripts\python.exe'
    if (Test-Path -LiteralPath $redirected) { $PythonPath = $redirected }
}
Push-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
$temporary = Join-Path ([IO.Path]::GetTempPath()) ('dungeonmice-bridge-check-' + [guid]::NewGuid())
$server = $null
try {
    & $PythonPath -B scripts/test_dungeonmice_bridge.py
    if ($LASTEXITCODE -ne 0) { throw 'Adapter unit tests failed' }
    New-Item -ItemType Directory -Path $temporary | Out-Null
    # Python reads original Unicode paths itself; nothing is copied to either project.
    & $PythonPath -B -c 'import sys,subprocess,hashlib; from pathlib import Path; folder=Path(sys.argv[1]); target=Path(sys.argv[2]); source=next(folder.glob("*.mov")); target.joinpath("original.sha256").write_text(hashlib.file_digest(source.open("rb"),"sha256").hexdigest()); target.joinpath("original.path").write_text(str(source),encoding="utf-8"); subprocess.run(["ffmpeg","-hide_banner","-loglevel","error","-nostdin","-i",str(source),"-t","35","-c","copy","-an",str(target/"crossmaze-35s.mp4")],check=True)' $VideoDirectory $temporary
    if ($LASTEXITCODE -ne 0) { throw 'CrossMaze clip creation failed' }
    & mvn -q test-compile
    if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }
    & mvn -q dependency:copy-dependencies '-DincludeScope=runtime' '-DoutputDirectory=target/runtime-libs'
    if ($LASTEXITCODE -ne 0) { throw 'Runtime dependencies failed' }
    $bridge = (Resolve-Path -LiteralPath 'scripts/dungeonmice_api_bridge.py').Path
    $server = Start-Process -FilePath $PythonPath -ArgumentList @('-B', "`"$bridge`"", '--python-project', "`"$PythonProject`"", '--port', '0') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $temporary 'server.stdout.log') -RedirectStandardError (Join-Path $temporary 'server.stderr.log')
    $url = $null
    for ($i = 0; $i -lt 100; $i++) {
        if ($server.HasExited) { throw 'Real Python bridge exited at startup' }
        $line = Get-Content -LiteralPath (Join-Path $temporary 'server.stdout.log') -ErrorAction SilentlyContinue | Where-Object { $_ -match '^DungeonMice API:' } | Select-Object -First 1
        if ($line -match '(http://127\.0\.0\.1:\d+)') { $url = $Matches[1]; break }
        Start-Sleep -Milliseconds 100
    }
    if (-not $url) { throw 'Bridge startup timed out' }
    $fx = (Get-ChildItem -LiteralPath "$env:USERPROFILE\.m2\repository\org\openjfx" -Recurse -Filter '*17.0.10-win.jar').FullName -join ';'
    & $JavaPath --module-path $fx --add-modules javafx.controls,javafx.fxml,javafx.media -cp 'target/classes;target/test-classes;target/runtime-libs/*' com.phys.dungeonmiceapp.DungeonMiceBridgeChecks $url (Join-Path $temporary 'crossmaze-35s.mp4') (Join-Path $temporary 'processed.mp4')
    if ($LASTEXITCODE -ne 0) { throw 'Real processing/Java playback check failed' }
    $summaryLine = Get-Content -LiteralPath (Join-Path $temporary 'server.stdout.log') | Where-Object { $_ -like 'DungeonMice result:*' } | Select-Object -Last 1
    if (-not $summaryLine) { throw 'No real processing summary' }
    $summary = $summaryLine.Substring('DungeonMice result: '.Length) | ConvertFrom-Json
    if ($summary.frames -lt 500 -or $summary.trackingPoints -le 0 -or $summary.reports.Count -eq 0) { throw 'Tracking/report generation incomplete' }
    Write-Output $summaryLine
    & $PythonPath -B -c 'import sys,cv2; from pathlib import Path; folder=Path(sys.argv[1]); source=cv2.VideoCapture(str(folder/"crossmaze-35s.mp4")); result=cv2.VideoCapture(str(folder/"processed.mp4")); ok,a=source.read(); ret,b=result.read(); assert ok and ret and a.shape==b.shape==(720,1280,3); assert int(source.get(cv2.CAP_PROP_FRAME_COUNT))==int(result.get(cv2.CAP_PROP_FRAME_COUNT)); source.release(); result.release(); print("PASS encoded dimensions and frame count preserved")' $temporary
    if ($LASTEXITCODE -ne 0) { throw 'Encoded video dimensions/frame count changed' }
    & $PythonPath -B -c 'import sys,hashlib; from pathlib import Path; folder=Path(sys.argv[1]); source=Path(folder.joinpath("original.path").read_text(encoding="utf-8")); assert hashlib.file_digest(source.open("rb"),"sha256").hexdigest()==folder.joinpath("original.sha256").read_text(); print("PASS original CrossMaze video unchanged")' $temporary
    if ($LASTEXITCODE -ne 0) { throw 'Original changed' }
} catch {
    foreach ($name in @('server.stdout.log', 'server.stderr.log')) {
        $log = Join-Path $temporary $name
        if (Test-Path -LiteralPath $log) { Get-Content -LiteralPath $log | Write-Output }
    }
    throw
} finally {
    if ($server -and -not $server.HasExited) { Stop-Process -Id $server.Id }
    $resolved = [IO.Path]::GetFullPath($temporary)
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
    if ($resolved.StartsWith($tempRoot + '\', [StringComparison]::OrdinalIgnoreCase) -and (Split-Path -Leaf $resolved).StartsWith('dungeonmice-bridge-check-') -and (Test-Path -LiteralPath $resolved)) {
        if ((Get-ChildItem -LiteralPath $resolved -Recurse -Force) | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }) { throw 'Unexpected reparse point in disposable test directory' }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
    Pop-Location
}
