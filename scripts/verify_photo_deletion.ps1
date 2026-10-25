$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'verify_tone_preview.ps1')
$deleteJar = Join-Path $outputPath 'photo-deletion-check.jar'
$deleteSources = @(
    (Join-Path $projectPath 'app/src/main/java/com/dcimgallery/raw/core/media/DeletionSession.kt'),
    (Join-Path $PSScriptRoot 'PhotoDeletionCheck.kt')
)
& $javaPath -cp ($compilerDependencies -join ';') org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath "$stdlibPath;$coroutinesPath" -d $deleteJar @deleteSources
if ($LASTEXITCODE -ne 0) { throw 'Photo deletion check compilation failed' }
& $javaPath -cp "$deleteJar;$stdlibPath;$coroutinesPath" com.dcimgallery.raw.core.media.PhotoDeletionCheckKt
if ($LASTEXITCODE -ne 0) { throw 'Photo deletion check failed' }
