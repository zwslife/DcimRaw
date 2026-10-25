$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'verify_tone_preview.ps1')
$galleryJar = Join-Path $outputPath 'gallery-loading-check.jar'
$sources = @(
    (Join-Path $projectPath 'app/src/main/java/com/dcimgallery/raw/data/scan/ScanBatcher.kt'),
    (Join-Path $projectPath 'app/src/main/java/com/dcimgallery/raw/data/repo/IncrementalIndex.kt'),
    (Join-Path $PSScriptRoot 'GalleryLoadingCheck.kt')
)
& $javaPath -cp ($compilerDependencies -join ';') org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath "$stdlibPath;$coroutinesPath" -d $galleryJar @sources
if ($LASTEXITCODE -ne 0) { throw 'Gallery check compilation failed' }
& $javaPath -cp "$galleryJar;$stdlibPath;$coroutinesPath" com.dcimgallery.raw.data.repo.GalleryLoadingCheckKt
if ($LASTEXITCODE -ne 0) { throw 'Gallery check failed' }
$formatJar = Join-Path $outputPath 'gallery-format-check.jar'
$formatSources = @(
    (Join-Path $projectPath 'app/src/main/java/com/dcimgallery/raw/core/raw/PhotoFormatProbe.kt'),
    (Join-Path $projectPath 'app/src/main/java/com/dcimgallery/raw/core/raw/PhotoFileType.kt'),
    (Join-Path $PSScriptRoot 'gallery-stubs/Uri.kt'),
    (Join-Path $PSScriptRoot 'gallery-stubs/Context.kt'),
    (Join-Path $PSScriptRoot 'gallery-stubs/Database.kt'),
    (Join-Path $PSScriptRoot 'GalleryFormatCheck.kt')
)
& $javaPath -cp ($compilerDependencies -join ';') org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath "$stdlibPath;$coroutinesPath" -d $formatJar @formatSources
if ($LASTEXITCODE -ne 0) { throw 'Format cache check compilation failed' }
& $javaPath -cp "$formatJar;$stdlibPath;$coroutinesPath" com.dcimgallery.raw.core.raw.GalleryFormatCheckKt
if ($LASTEXITCODE -ne 0) { throw 'Format cache check failed' }
