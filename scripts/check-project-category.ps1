param([string]$JavaPath = 'C:\Program Files\Java\jdk-17\bin\java.exe')
$ErrorActionPreference = 'Stop'
Push-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
$temporary = Join-Path ([IO.Path]::GetTempPath()) ('dungeonmice-category-check-' + [guid]::NewGuid())
try {
    New-Item -ItemType Directory -Path $temporary -Force | Out-Null
    $fixture = Join-Path $temporary 'category.mp4'
    & ffmpeg -v error -f lavfi -i 'testsrc2=size=320x240:rate=30' -t 2 -c:v libx264 -pix_fmt yuv420p $fixture
    if ($LASTEXITCODE -ne 0) { throw 'Fixture generation failed' }
    & mvn -q test-compile
    if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }
    & mvn -q dependency:copy-dependencies '-DincludeScope=runtime' '-DoutputDirectory=target/runtime-libs'
    if ($LASTEXITCODE -ne 0) { throw 'Runtime dependencies failed' }
    $fx = (Get-ChildItem -LiteralPath "$env:USERPROFILE\.m2\repository\org\openjfx" -Recurse -Filter '*17.0.10-win.jar').FullName -join ';'
    & $JavaPath --module-path $fx --add-modules javafx.controls,javafx.fxml,javafx.media -cp 'target/classes;target/test-classes;target/runtime-libs/*' com.phys.dungeonmiceapp.ProjectCategoryChecks $fixture
    if ($LASTEXITCODE -ne 0) { throw 'Project category checks failed' }
} finally {
    $path = [IO.Path]::GetFullPath($temporary)
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
    if (-not $path.StartsWith($tempRoot + '\dungeonmice-category-check-', [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe temporary path' }
    if (Test-Path -LiteralPath $path) {
        if ((Get-Item -LiteralPath $path).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Unexpected reparse point' }
        if (Get-ChildItem -LiteralPath $path -Recurse -Force | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }) { throw 'Unexpected reparse point' }
        Remove-Item -LiteralPath $path -Recurse -Force
    }
    Pop-Location
}
