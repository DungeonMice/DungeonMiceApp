param([string]$JavaPath = 'C:\Program Files\Java\jdk-17\bin\java.exe', [string]$PythonProject = 'F:\JavaApps\DungeonMice', [string]$TestVideo)
$ErrorActionPreference = 'Stop'
Push-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
$temporary = Join-Path ([IO.Path]::GetTempPath()) ('dungeonmice-startup-check-' + [guid]::NewGuid())
try {
    & mvn -q test-compile
    if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }
    & mvn -q dependency:copy-dependencies '-DincludeScope=runtime' '-DoutputDirectory=target/runtime-libs'
    if ($LASTEXITCODE -ne 0) { throw 'Runtime dependencies failed' }
    $testArguments = @()
    if ($TestVideo) { $testArguments += (Resolve-Path -LiteralPath $TestVideo).Path }
    & $JavaPath "-Ddungeonmice.python.project=$PythonProject" --add-modules jdk.httpserver -cp 'target/classes;target/test-classes;target/runtime-libs/*' com.phys.dungeonmiceapp.ManagedPythonBridgeChecks @testArguments
    if ($LASTEXITCODE -ne 0) { throw 'Managed lifecycle checks failed' }
    New-Item -ItemType Directory -Path $temporary | Out-Null
    $fx = (Get-ChildItem -LiteralPath "$env:USERPROFILE\.m2\repository\org\openjfx" -Recurse -Filter '*17.0.10-win.jar').FullName -join ';'
    $output = & $JavaPath "-Ddungeonmice.python.project=$PythonProject" "-Ddungeonmice.database=$temporary/data/database.enc" "-Ddungeonmice.storage=$temporary" -cp "target/classes;target/test-classes;target/runtime-libs/*;$fx" com.phys.dungeonmiceapp.App --python-startup-check 2>&1
    $code = $LASTEXITCODE
    $output | Write-Output
    if ($code -ne 0 -or -not ($output -match 'PASS App automatic Python startup PID=') -or -not ($output -match 'PASS App automatic Python shutdown PID=')) { throw 'App startup/shutdown check failed' }
} finally {
    $resolved = [IO.Path]::GetFullPath($temporary)
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
    if ($resolved.StartsWith($tempRoot + '\', [StringComparison]::OrdinalIgnoreCase) -and (Split-Path -Leaf $resolved).StartsWith('dungeonmice-startup-check-') -and (Test-Path -LiteralPath $resolved)) {
        if ((Get-ChildItem -LiteralPath $resolved -Recurse -Force) | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }) { throw 'Unexpected reparse point in temporary directory' }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
    Pop-Location
}
