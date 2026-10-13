$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'verify_tone_preview.ps1')
$histogramJarPath = Join-Path $outputPath 'histogram-check.jar'
$histogramSourcePath = Join-Path $projectPath 'app/src/main/java/com/dcimgallery/raw/core/tone/HistogramData.kt'
$histogramCheckPath = Join-Path $PSScriptRoot 'HistogramCheck.kt'
& $javaPath -cp ($compilerDependencies -join ';') org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath $stdlibPath -d $histogramJarPath $histogramSourcePath $histogramCheckPath
if ($LASTEXITCODE -ne 0) { throw 'Histogram check compilation failed' }
& $javaPath -cp "$histogramJarPath;$stdlibPath" com.dcimgallery.raw.core.tone.HistogramCheckKt
if ($LASTEXITCODE -ne 0) { throw 'Histogram check failed' }
