param(
    [string]$AppImage,
    [string]$JPackage = 'C:\Program Files\Java\jdk-17\bin\jpackage.exe',
    [string]$Version = '1.0.0'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (-not $AppImage) { $AppImage = Join-Path $root 'dist\DungeonMice' }
$AppImage = (Resolve-Path -LiteralPath $AppImage).Path
$jarName = 'DungeonMiceApp-1.0-SNAPSHOT.jar'
$jar = Join-Path $AppImage "app\$jarName"
$runtime = Join-Path $AppImage 'runtime'
$logo = Join-Path $root 'src\main\resources\images\dungeon-mouse-yellow.png'
$icon = Join-Path $root 'src\main\resources\images\dungeon-mouse.ico'
foreach ($required in @($JPackage, $jar, $logo, (Join-Path $AppImage 'DungeonMice.exe'))) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Missing file: $required" }
}
if (-not (Test-Path -LiteralPath $runtime -PathType Container)) { throw "Missing Java runtime: $runtime" }

Add-Type -AssemblyName System.Drawing
$source = [Drawing.Image]::FromFile($logo)
$images = [Collections.Generic.List[byte[]]]::new()
$sizes = @(16, 24, 32, 48, 64, 128, 256)
try {
    foreach ($size in $sizes) {
        $bitmap = [Drawing.Bitmap]::new($size, $size)
        $graphics = [Drawing.Graphics]::FromImage($bitmap)
        $memory = [IO.MemoryStream]::new()
        try {
            $graphics.Clear([Drawing.Color]::Transparent)
            $graphics.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
            $scale = [Math]::Min($size / $source.Width, $size / $source.Height)
            $width = [int][Math]::Round($source.Width * $scale)
            $height = [int][Math]::Round($source.Height * $scale)
            $graphics.DrawImage($source, [int](($size - $width) / 2), [int](($size - $height) / 2), $width, $height)
            $bitmap.Save($memory, [Drawing.Imaging.ImageFormat]::Png)
            $images.Add($memory.ToArray())
        } finally { $memory.Dispose(); $graphics.Dispose(); $bitmap.Dispose() }
    }
} finally { $source.Dispose() }

# ICO directory followed by PNG payloads, including a full-resolution 256px icon.
$writer = [IO.BinaryWriter]::new([IO.File]::Create($icon))
try {
    $writer.Write([uint16]0); $writer.Write([uint16]1); $writer.Write([uint16]$sizes.Count)
    $offset = 6 + 16 * $sizes.Count
    for ($i = 0; $i -lt $sizes.Count; $i++) {
        $dimension = if ($sizes[$i] -eq 256) { 0 } else { $sizes[$i] }
        $writer.Write([byte]$dimension); $writer.Write([byte]$dimension)
        $writer.Write([byte]0); $writer.Write([byte]0)
        $writer.Write([uint16]1); $writer.Write([uint16]32)
        $writer.Write([uint32]$images[$i].Length); $writer.Write([uint32]$offset)
        $offset += $images[$i].Length
    }
    foreach ($bytes in $images) { $writer.Write([byte[]]$bytes) }
} finally { $writer.Dispose() }

# Build separately; never delete or regenerate the user's existing distribution.
$build = Join-Path $root ('target\icon-launcher-' + [guid]::NewGuid())
$inputDirectory = Join-Path $build 'input'
New-Item -ItemType Directory -Path $inputDirectory -Force | Out-Null
Copy-Item -LiteralPath $jar -Destination $inputDirectory
try {
    & $JPackage --type app-image --name DungeonMice --app-version $Version --input $inputDirectory --main-jar $jarName --main-class com.phys.dungeonmiceapp.App --runtime-image $runtime --icon $icon --dest (Join-Path $build 'output')
    if ($LASTEXITCODE -ne 0) { throw 'jpackage failed; existing launcher was not modified' }
    $generated = Join-Path $build 'output\DungeonMice'
    Copy-Item -LiteralPath (Join-Path $generated 'DungeonMice.exe') -Destination (Join-Path $AppImage 'DungeonMice.exe') -Force
    Copy-Item -LiteralPath (Join-Path $generated 'DungeonMice.ico') -Destination (Join-Path $AppImage 'DungeonMice.ico') -Force
} finally {
    $path = [IO.Path]::GetFullPath($build)
    $target = [IO.Path]::GetFullPath((Join-Path $root 'target')).TrimEnd('\')
    if (-not $path.StartsWith($target + '\icon-launcher-', [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe temporary build path' }
    if ((Get-Item -LiteralPath $path).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Unexpected build reparse point' }
    if (Get-ChildItem -LiteralPath $path -Recurse -Force | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }) { throw 'Unexpected build reparse point' }
    Remove-Item -LiteralPath $path -Recurse -Force
}
Write-Output "Mouse icon applied: $AppImage\DungeonMice.exe"
Write-Output "Reusable icon: $icon"
