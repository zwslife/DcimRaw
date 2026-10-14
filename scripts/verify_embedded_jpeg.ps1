$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'verify_tone_preview.ps1')
$parserSource = Join-Path $projectPath 'app/src/main/java/com/dcimgallery/raw/core/cr3/Cr3Parser.kt'
$embeddedCheck = Join-Path $PSScriptRoot 'EmbeddedJpegCheck.kt'
$typeSource = Join-Path $projectPath 'app/src/main/java/com/dcimgallery/raw/core/raw/PhotoFileType.kt'
$embeddedJar = Join-Path $outputPath 'embedded-jpeg-check.jar'
$androidJar = Join-Path $projectPath 'toolchain/android-sdk/platforms/android-34/android.jar'
& $javaPath -cp ($compilerDependencies -join ';') org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath "$stdlibPath;$androidJar" -d $embeddedJar $parserSource $typeSource $embeddedCheck
if ($LASTEXITCODE -ne 0) { throw 'Embedded JPEG check compilation failed' }
& $javaPath '-Djava.awt.headless=true' -cp "$embeddedJar;$stdlibPath;$androidJar" com.dcimgallery.raw.core.cr3.EmbeddedJpegCheckKt (Join-Path $projectPath 'IMG_9807.CR3') (Join-Path $projectPath 'IMG_9811.CR3') (Join-Path $projectPath 'IMG_9812.CR3') (Join-Path $projectPath 'IMG_4258_6E51FC0F.CR3') (Join-Path $projectPath 'IMG_4318_4788B9A6.CR3')
if ($LASTEXITCODE -ne 0) { throw 'Embedded JPEG extraction check failed' }
