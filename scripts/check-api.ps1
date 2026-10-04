param([string]$JavaPath = 'C:\Program Files\Java\jdk-17\bin\java.exe', [string]$PythonUrl = '', [string]$VideoPath = '')
$ErrorActionPreference = 'Stop'
Push-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
try {
    & mvn test-compile
    if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }
    & mvn -q dependency:copy-dependencies '-DincludeScope=runtime' '-DoutputDirectory=target/runtime-libs'
    if ($LASTEXITCODE -ne 0) { throw 'Runtime dependencies failed' }
    & $JavaPath --add-modules jdk.httpserver -cp 'target/classes;target/test-classes;target/runtime-libs/*' com.phys.dungeonmiceapp.PythonApiChecks
    if ($LASTEXITCODE -ne 0) { throw 'API checks failed' }
    if ($PythonUrl) {
        if (-not $VideoPath) { throw 'Provide -VideoPath for the Python integration check' }
        & $JavaPath --add-modules jdk.httpserver -cp 'target/classes;target/test-classes;target/runtime-libs/*' com.phys.dungeonmiceapp.PythonApiChecks $PythonUrl $VideoPath
        if ($LASTEXITCODE -ne 0) { throw 'Python integration check failed' }
    }
} finally { Pop-Location }
