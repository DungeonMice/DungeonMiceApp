param(
    [string]$PythonPath = "$env:LOCALAPPDATA\DungeonMice\api-venv\Scripts\python.exe",
    [string]$PythonProject = 'F:\JavaApps\DungeonMice',
    [string]$ConfigPath = '',
    [int]$Port = 8765
)
$ErrorActionPreference = 'Stop'
if (-not $PSBoundParameters.ContainsKey('PythonPath') -and -not (Test-Path -LiteralPath $PythonPath)) {
    $redirected = Join-Path $env:LOCALAPPDATA 'Packages\OpenAI.Codex_2p2nqsd0c76g0\LocalCache\Local\DungeonMice\api-venv\Scripts\python.exe'
    if (Test-Path -LiteralPath $redirected) { $PythonPath = $redirected }
}
if (-not (Test-Path -LiteralPath $PythonPath)) { throw 'Provide -PythonPath pointing to a Python environment with DungeonMice requirements installed.' }
$arguments = @('-B', (Join-Path $PSScriptRoot 'dungeonmice_api_bridge.py'), '--python-project', $PythonProject, '--port', $Port)
if ($ConfigPath) { $arguments += @('--config', $ConfigPath) }
& $PythonPath @arguments
if ($LASTEXITCODE -ne 0) { throw 'DungeonMice API exited with an error.' }
