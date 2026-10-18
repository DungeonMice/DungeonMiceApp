param([string]$JavaPath = 'C:\Program Files\Java\jdk-17\bin\java.exe')
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$temporary = Join-Path ([IO.Path]::GetTempPath()) ('dungeonmice-user-data-' + [guid]::NewGuid())
$oldLocal = $env:LOCALAPPDATA
Push-Location -LiteralPath $root
try {
    & mvn -q test-compile
    if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }
    & mvn -q dependency:copy-dependencies '-DincludeScope=runtime' '-DoutputDirectory=target/runtime-libs'
    if ($LASTEXITCODE -ne 0) { throw 'Runtime dependencies failed' }
    $classpath = "$root/target/classes;$root/target/test-classes;$root/target/runtime-libs/*"
    $first = Join-Path $temporary 'installed-program'
    $second = Join-Path $temporary 'another-working-directory'
    New-Item -ItemType Directory -Path (Join-Path $first 'data'),$second -Force | Out-Null
    [IO.File]::WriteAllText((Join-Path $first 'data/database.enc'), 'Do not import or modify this bundled database')
    $env:LOCALAPPDATA = Join-Path $temporary 'local'
    $database = Join-Path $env:LOCALAPPDATA 'DungeonMice/data/database.enc'
    $profiles = Join-Path $env:LOCALAPPDATA 'DungeonMice/data/profiles'
    Set-Location -LiteralPath $first
    & $JavaPath -cp $classpath com.phys.dungeonmiceapp.AppDataChecks $database $profiles
    if ($LASTEXITCODE -ne 0) { throw 'Default per-user storage check failed' }
    $before = (Get-FileHash -LiteralPath $database).Hash
    Set-Location -LiteralPath $second
    & $JavaPath -cp $classpath com.phys.dungeonmiceapp.AppDataChecks $database $profiles
    if ($LASTEXITCODE -ne 0 -or (Get-FileHash -LiteralPath $database).Hash -ne $before) { throw 'Working directory changed existing user data' }
    & $JavaPath '-Ddungeonmice.database=isolated.enc' -cp $classpath com.phys.dungeonmiceapp.AppDataChecks (Join-Path $second 'isolated.enc') $profiles
    if ($LASTEXITCODE -ne 0) { throw 'Explicit relative database override failed' }
    $storage = Join-Path $temporary 'explicit-storage'
    $explicitDatabase = Join-Path $temporary 'explicit-database/data/database.enc'
    & $JavaPath "-Ddungeonmice.database=$explicitDatabase" "-Ddungeonmice.storage=$storage" -cp $classpath com.phys.dungeonmiceapp.AppDataChecks $explicitDatabase (Join-Path $storage 'data/profiles')
    if ($LASTEXITCODE -ne 0) { throw 'Explicit development/test overrides failed' }
    Remove-Item Env:LOCALAPPDATA
    $testHome = Join-Path $temporary 'home'
    & $JavaPath "-Duser.home=$testHome" -cp $classpath com.phys.dungeonmiceapp.AppDataChecks (Join-Path $testHome 'AppData/Local/DungeonMice/data/database.enc') (Join-Path $testHome 'AppData/Local/DungeonMice/data/profiles')
    if ($LASTEXITCODE -ne 0) { throw 'Missing LocalAppData fallback failed' }
} finally {
    $env:LOCALAPPDATA = $oldLocal
    Set-Location -LiteralPath $root
    $path = [IO.Path]::GetFullPath($temporary)
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
    if (-not $path.StartsWith($tempRoot + '\dungeonmice-user-data-', [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe temporary path' }
    if (Test-Path -LiteralPath $path) {
        if ((Get-Item -LiteralPath $path).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Unexpected reparse point' }
        if (Get-ChildItem -LiteralPath $path -Recurse -Force | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }) { throw 'Unexpected reparse point' }
        Remove-Item -LiteralPath $path -Recurse -Force
    }
    Pop-Location
}
