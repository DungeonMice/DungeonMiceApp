[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$Destination,
    [string]$PythonProject,
    [string]$BuildPython,
    [string]$CacheDirectory
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$root = Split-Path -Parent $PSScriptRoot
if (-not $PythonProject) { $PythonProject = Join-Path (Split-Path -Parent $root) 'DungeonMice' }
$PythonProject = (Resolve-Path -LiteralPath $PythonProject).Path
if (-not $BuildPython) {
    if (Test-Path -LiteralPath 'C:\Python314\python.exe') { $BuildPython = 'C:\Python314\python.exe' }
    else { $BuildPython = (Get-Command python.exe -ErrorAction Stop).Source }
}
if (-not $CacheDirectory) { $CacheDirectory = Join-Path $env:LOCALAPPDATA 'DungeonMice\build-cache\python-win64' }
$lock = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'python-bundle-lock.json') -Raw | ConvertFrom-Json
$Destination = [IO.Path]::GetFullPath($Destination)
if (Test-Path -LiteralPath $Destination) { throw 'Stage the Python backend into a new directory, not an existing installation.' }
foreach ($required in @('src\RunExperiment.py','src\RunExperiment_per_frame.py','src\input_loader.py','LICENSE.txt')) {
    if (-not (Test-Path -LiteralPath (Join-Path $PythonProject $required) -PathType Leaf)) { throw "Missing Python source: $required" }
}
$protectedSource = Join-Path $PythonProject 'src\RunExperiment_per_frame.py'
$protectedHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $protectedSource).Hash
New-Item -ItemType Directory -Path $CacheDirectory -Force | Out-Null
function Get-VerifiedDownload($artifact) {
    if ([IO.Path]::GetFileName($artifact.file) -ne $artifact.file) { throw 'Invalid artifact filename' }
    $file = Join-Path $CacheDirectory $artifact.file
    if (-not (Test-Path -LiteralPath $file)) {
        $partial = $file + '.partial-' + [guid]::NewGuid()
        try {
            Write-Host "Downloading $($artifact.file)..."
            Invoke-WebRequest -UseBasicParsing -Uri $artifact.url -OutFile $partial
            if ((Get-FileHash -Algorithm SHA256 -LiteralPath $partial).Hash -ne $artifact.sha256) { throw "Download hash mismatch: $($artifact.file)" }
            Move-Item -LiteralPath $partial -Destination $file
        } finally { if (Test-Path -LiteralPath $partial) { Remove-Item -LiteralPath $partial -Force } }
    }
    if ((Get-FileHash -Algorithm SHA256 -LiteralPath $file).Hash -ne $artifact.sha256) { throw "Cached file hash mismatch: $file" }
    return $file
}
$archive = Get-VerifiedDownload $lock.python
$wheels = @($lock.packages | ForEach-Object { Get-VerifiedDownload $_ })
$visualCpp = Get-VerifiedDownload $lock.visualCpp
$signature = Get-AuthenticodeSignature -LiteralPath $visualCpp
if ($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notmatch 'O=Microsoft Corporation') { throw 'Invalid Microsoft prerequisite signature' }
$runtime = Join-Path $Destination 'runtime\python'
New-Item -ItemType Directory -Path $runtime -Force | Out-Null
Expand-Archive -LiteralPath $archive -DestinationPath $runtime
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'python314._pth') -Destination $runtime -Force
& $BuildPython -m pip --isolated install --no-index --no-deps --no-compile --only-binary=:all: --platform win_amd64 --python-version 3.14 --implementation cp --abi cp314 --target (Join-Path $runtime 'Lib\site-packages') @wheels
if ($LASTEXITCODE -ne 0) { throw 'Could not stage Python dependency wheels' }
$adapters = Join-Path $Destination 'scripts'
$engine = Join-Path $Destination 'engine'
$prerequisites = Join-Path $Destination 'prerequisites'
New-Item -ItemType Directory -Path $adapters,$engine,$prerequisites -Force | Out-Null
foreach ($file in @('dungeonmice_api_bridge.py','python_api_example.py','frame_sessions.py','experiment_config.py')) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $file) -Destination $adapters
}
$sourceRoot = Join-Path $PythonProject 'src'
$engineFiles = @()
foreach ($source in Get-ChildItem -LiteralPath $sourceRoot -Recurse -File) {
    $relative = $source.FullName.Substring($sourceRoot.Length).TrimStart('\')
    if ($relative -match '(^|\\)(\.[^\\]+|__pycache__)(\\|$)' -or $source.Extension -notin @('.py','.json')) { continue }
    $target = Join-Path $engine ('src\' + $relative)
    New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
    Copy-Item -LiteralPath $source.FullName -Destination $target
    $engineFiles += [ordered]@{path=('engine/src/' + $relative.Replace('\','/')); sha256=(Get-FileHash -Algorithm SHA256 -LiteralPath $source.FullName).Hash.ToLowerInvariant()}
}
Copy-Item -LiteralPath (Join-Path $PythonProject 'LICENSE.txt') -Destination $engine
Copy-Item -LiteralPath $visualCpp -Destination $prerequisites
[ordered]@{schemaVersion=1; pythonVersion=$lock.pythonVersion; downloads=$lock; engineFiles=$engineFiles} | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $Destination 'bundle-manifest.json') -Encoding UTF8
if ((Get-FileHash -Algorithm SHA256 -LiteralPath $protectedSource).Hash -ne $protectedHash) { throw 'Original frame engine changed during packaging' }
& (Join-Path $runtime 'python.exe') -I -B -X utf8 (Join-Path $PSScriptRoot 'check_python_bundle.py') $Destination
if ($LASTEXITCODE -ne 0) { throw 'Bundled Python import verification failed' }
Write-Host "Portable Python backend prepared: $Destination"
