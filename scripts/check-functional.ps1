param([string]$JavaPath = '', [string]$CrossMazePath = '')
$ErrorActionPreference = 'Stop'
$testProject = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $testProject
try {
    if (-not $JavaPath) {
        $testJdk = 'C:\Program Files\Java\jdk-17\bin\java.exe'
        if (Test-Path -LiteralPath $testJdk) { $JavaPath = $testJdk }
        elseif ($env:JAVA_HOME) { $JavaPath = Join-Path $env:JAVA_HOME 'bin\java.exe' }
        else { $JavaPath = (Get-Command java).Source }
    }
    & mvn test-compile
    if ($LASTEXITCODE -ne 0) { throw 'Compilation failed.' }
    & mvn -q dependency:copy-dependencies '-DincludeScope=runtime' '-DoutputDirectory=target/runtime-libs'
    if ($LASTEXITCODE -ne 0) { throw 'Runtime dependencies failed.' }
    New-Item -ItemType Directory -Path target/fixtures -Force | Out-Null
    & ffmpeg -hide_banner -loglevel error -y -f lavfi -i 'testsrc2=size=640x360:rate=30' -f lavfi -i 'sine=frequency=440:sample_rate=44100' -t 4 -c:v libx264 -pix_fmt yuv420p -c:a aac target/fixtures/test.mp4
    if ($LASTEXITCODE -ne 0) { throw 'MP4 fixture generation failed.' }
    & ffmpeg -hide_banner -loglevel error -y -i target/fixtures/test.mp4 -c:v mpeg4 -c:a pcm_s16le target/fixtures/test.avi
    if ($LASTEXITCODE -ne 0) { throw 'AVI fixture generation failed.' }
    $testRepository = Join-Path $env:USERPROFILE '.m2\repository'
    $testFx = (Get-ChildItem -LiteralPath (Join-Path $testRepository 'org\openjfx') -Recurse -Filter '*17.0.10-win.jar').FullName -join ';'
    $testClasspath = "target/classes;target/test-classes;target/runtime-libs/*"
    & $JavaPath --module-path $testFx --add-modules javafx.controls,javafx.fxml,javafx.media -cp $testClasspath com.phys.dungeonmiceapp.LoadingAnimationChecks
    if ($LASTEXITCODE -ne 0) { throw 'Animated loading checks failed.' }
    & $JavaPath --module-path $testFx --add-modules javafx.controls,javafx.fxml,javafx.media -cp $testClasspath com.phys.dungeonmiceapp.FunctionalChecks target/fixtures/test.mp4 target/fixtures/test.avi
    if ($LASTEXITCODE -ne 0) { throw 'Functional checks failed.' }
    & $JavaPath --module-path $testFx --add-modules javafx.controls,javafx.fxml,javafx.media -cp $testClasspath com.phys.dungeonmiceapp.RegionEditorChecks target/fixtures/test.mp4
    if ($LASTEXITCODE -ne 0) { throw 'Region editor checks failed.' }
    & $JavaPath --module-path $testFx --add-modules javafx.controls,javafx.fxml,javafx.media -cp $testClasspath com.phys.dungeonmiceapp.ProcessedVideoChecks target/fixtures/test.mp4
    if ($LASTEXITCODE -ne 0) { throw 'Processed library checks failed.' }
    & $JavaPath --module-path $testFx --add-modules javafx.controls,javafx.fxml,javafx.media -cp $testClasspath com.phys.dungeonmiceapp.WindowControlsChecks
    if ($LASTEXITCODE -ne 0) { throw 'Window controls checks failed.' }
    & $JavaPath --module-path $testFx --add-modules javafx.controls,javafx.fxml,javafx.media -cp $testClasspath com.phys.dungeonmiceapp.WindowControlsChecks --native-close
    if ($LASTEXITCODE -ne 0) { throw 'Native close checks failed.' }
    & $JavaPath --module-path $testFx --add-modules javafx.controls,javafx.fxml,javafx.media -cp $testClasspath com.phys.dungeonmiceapp.LoginEnterChecks
    if ($LASTEXITCODE -ne 0) { throw 'Password Enter login checks failed.' }
    & $JavaPath --module-path $testFx --add-modules javafx.controls,javafx.fxml,javafx.media -cp $testClasspath com.phys.dungeonmiceapp.LoginEnterChecks --username
    if ($LASTEXITCODE -ne 0) { throw 'Username Enter login checks failed.' }
    # Native pointer events exercise scene picking, including translated login forms.
    foreach ($screen in @('login', 'register')) {
        foreach ($size in @('compact', 'large')) {
            $pointerArguments = @('--pointer')
            if ($screen -eq 'register') { $pointerArguments += '--register' }
            if ($size -eq 'large') { $pointerArguments += '--large' }
            & $JavaPath --module-path $testFx --add-modules javafx.controls,javafx.fxml,javafx.media -cp $testClasspath com.phys.dungeonmiceapp.WindowControlsChecks @pointerArguments
            if ($LASTEXITCODE -ne 0) { throw "Native pointer controls failed: $screen/$size" }
        }
    }
    $startupDirectory = Join-Path ([System.IO.Path]::GetTempPath()) ('dungeonmice-startup-' + [guid]::NewGuid())
    & $JavaPath "-Ddungeonmice.database=$startupDirectory/data/database.enc" "-Ddungeonmice.storage=$startupDirectory" -cp "$testClasspath;$testFx" com.phys.dungeonmiceapp.App --ui-snapshots
    if ($LASTEXITCODE -ne 0) { throw 'Direct Java application startup failed.' }
    if ($CrossMazePath) {
        & $JavaPath --module-path $testFx --add-modules javafx.controls,javafx.fxml,javafx.media -cp $testClasspath com.phys.dungeonmiceapp.CrossMazeChecks $CrossMazePath
        if ($LASTEXITCODE -ne 0) { throw 'CrossMaze real-video checks failed.' }
    }
} finally { Pop-Location }
