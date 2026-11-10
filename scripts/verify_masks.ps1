. (Join-Path $PSScriptRoot 'verify_tone_preview.ps1')
$maskJarPath = Join-Path $outputPath 'masks-check.jar'
$toneSourcePath = Join-Path $projectPath 'app/src/main/java/com/dcimgallery/raw/core/tone/ToneParams.kt'
$geometrySourcePath = Join-Path $projectPath 'app/src/main/java/com/dcimgallery/raw/core/geometry/PhotoGeometry.kt'
$maskSourcePath = Join-Path $projectPath 'app/src/main/java/com/dcimgallery/raw/core/tone/MaskLayer.kt'
$maskCheckPath = Join-Path $PSScriptRoot 'MaskGeometryCheck.kt'
$clipboardPath = Join-Path $projectPath 'app/src/main/java/com/dcimgallery/raw/core/tone/PhotoEditClipboard.kt'
$clipboardCheckPath = Join-Path $PSScriptRoot 'PhotoEditClipboardCheck.kt'
& $javaPath -cp ($compilerDependencies -join ';') org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath $stdlibPath -d $maskJarPath $toneSourcePath $geometrySourcePath $maskSourcePath $maskCheckPath $clipboardPath $clipboardCheckPath
if ($LASTEXITCODE -ne 0) { throw 'Mask geometry check compilation failed' }
& $javaPath -cp "$maskJarPath;$stdlibPath" com.dcimgallery.raw.core.tone.MaskGeometryCheckKt
if ($LASTEXITCODE -ne 0) { throw 'Mask geometry check failed' }
& $javaPath -cp "$maskJarPath;$stdlibPath" com.dcimgallery.raw.core.tone.PhotoEditClipboardCheckKt
if ($LASTEXITCODE -ne 0) { throw 'Photo edit clipboard check failed' }
