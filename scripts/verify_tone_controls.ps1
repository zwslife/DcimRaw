$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'verify_tone_preview.ps1')
$controlsJarPath = Join-Path $outputPath 'controls-check.jar'
$controlsSourcePath = Join-Path $projectPath 'app/src/main/java/com/dcimgallery/raw/ui/ToneControls.kt'
$toneSourcePath = Join-Path $projectPath 'app/src/main/java/com/dcimgallery/raw/core/tone/ToneParams.kt'
$controlsCheckPath = Join-Path $PSScriptRoot 'ToneControlsCheck.kt'
& $javaPath -cp ($compilerDependencies -join ';') org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath $stdlibPath -d $controlsJarPath $toneSourcePath $controlsSourcePath $controlsCheckPath
if ($LASTEXITCODE -ne 0) { throw 'Tone controls check compilation failed' }
& $javaPath -cp "$controlsJarPath;$stdlibPath" com.dcimgallery.raw.ui.ToneControlsCheckKt
if ($LASTEXITCODE -ne 0) { throw 'Tone controls check failed' }
