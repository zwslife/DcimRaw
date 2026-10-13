$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'verify_tone_preview.ps1')
$storageJarPath = Join-Path $outputPath 'storage-check.jar'
$storageSourcePath = Join-Path $projectPath 'app/src/main/java/com/dcimgallery/raw/ui/StorageOriginPolicy.kt'
$storageCheckPath = Join-Path $PSScriptRoot 'StorageOriginCheck.kt'
& $javaPath -cp ($compilerDependencies -join ';') org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath $stdlibPath -d $storageJarPath $storageSourcePath $storageCheckPath
if ($LASTEXITCODE -ne 0) { throw 'Storage origin check compilation failed' }
& $javaPath -cp "$storageJarPath;$stdlibPath" com.dcimgallery.raw.ui.StorageOriginCheckKt
if ($LASTEXITCODE -ne 0) { throw 'Storage origin check failed' }
