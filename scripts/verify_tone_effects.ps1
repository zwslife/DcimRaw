param([switch]$Sanitize)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'verify_masks.ps1')
$effectsJarPath = Join-Path $outputPath 'effects-check.jar'
$effectsCheckPath = Join-Path $PSScriptRoot 'ToneEffectsCheck.kt'
& $javaPath -cp ($compilerDependencies -join ';') org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath $stdlibPath -d $effectsJarPath $toneSourcePath $effectsCheckPath
if ($LASTEXITCODE -ne 0) { throw 'Tone effects check compilation failed' }
& $javaPath -cp "$effectsJarPath;$stdlibPath" com.dcimgallery.raw.core.tone.ToneEffectsCheckKt
if ($LASTEXITCODE -ne 0) { throw 'Tone effects parameter check failed' }
$vswherePath = "${env:ProgramFiles(x86)}/Microsoft Visual Studio/Installer/vswhere.exe"
$vsPath = & $vswherePath -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if (-not $vsPath) { throw 'MSVC C++ toolchain required for native host checks' }
$vcvarsPath = Join-Path $vsPath 'VC/Auxiliary/Build/vcvars64.bat'
$nativeSourcePath = Join-Path $PSScriptRoot 'ToneEngineCheck.cpp'
$nativeExePath = Join-Path $outputPath 'tone-engine-check.exe'
$nativeObjPath = Join-Path $outputPath 'tone-engine-check.obj'
$nativePdbPath = Join-Path $outputPath 'tone-engine-check-compile.pdb'
$commandFilePath = Join-Path $outputPath 'compile-native.cmd'
$sanitizerFlags = if ($Sanitize) { '/fsanitize=address /Zi' } else { '' }
@"
@echo off
set "PATH=%SystemRoot%\System32;%PATH%"
call "$vcvarsPath" >nul
cl /nologo /utf-8 /std:c++17 /EHsc /O2 /W3 $sanitizerFlags "$nativeSourcePath" /Fe:"$nativeExePath" /Fo:"$nativeObjPath" /Fd:"$nativePdbPath"
"@ | Set-Content -LiteralPath $commandFilePath
& $env:ComSpec /d /c $commandFilePath
if ($LASTEXITCODE -ne 0) { throw 'Native tone check compilation failed' }
$msvcPath = Get-ChildItem -LiteralPath (Join-Path $vsPath 'VC/Tools/MSVC') -Directory | Sort-Object Name -Descending | Select-Object -First 1
$env:PATH = (Join-Path $msvcPath.FullName 'bin/Hostx64/x64') + ';' + $env:PATH
& $nativeExePath
if ($LASTEXITCODE -ne 0) { throw 'Native tone check failed' }
