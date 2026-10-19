param([string]$Version = '1.0.7', [string]$BuildPython = 'C:\Python314\python.exe', [string]$PythonProject = '')
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $BuildPython)) { $BuildPython = (Get-Command python.exe -ErrorAction Stop).Source }
$arguments = @('-B', (Join-Path $PSScriptRoot 'build-linux.py'), '--version', $Version)
if ($PythonProject) { $arguments += @('--python-project', $PythonProject) }
& $BuildPython @arguments
if ($LASTEXITCODE -ne 0) { throw 'Linux archive build failed' }
