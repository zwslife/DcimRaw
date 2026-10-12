$ErrorActionPreference = 'Stop'
$projectPath = Split-Path -Parent $PSScriptRoot
$cachePath = Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1'
function Find-CachedJar([string]$artifactPath) {
    $jar = Get-ChildItem -LiteralPath (Join-Path $cachePath $artifactPath) -Recurse -Filter '*.jar' | Select-Object -First 1
    if (-not $jar) { throw "Missing cached dependency: $artifactPath" }
    return $jar.FullName
}
$stdlibPath = Find-CachedJar 'org.jetbrains.kotlin/kotlin-stdlib/2.0.20'
$coroutinesPath = Find-CachedJar 'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.1'
$compilerDependencies = @(
    (Find-CachedJar 'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.0.20'),
    (Find-CachedJar 'org.jetbrains.kotlin/kotlin-script-runtime/2.0.20'),
    (Find-CachedJar 'org.jetbrains.kotlin/kotlin-reflect/1.6.10'),
    (Find-CachedJar 'org.jetbrains.intellij.deps/trove4j'),
    (Find-CachedJar 'org.jetbrains/annotations/13.0'),
    $stdlibPath,
    $coroutinesPath
)
$outputPath = Join-Path $projectPath '.gradle/tone-preview-check'
New-Item -ItemType Directory -Path $outputPath -Force | Out-Null
$jarPath = Join-Path $outputPath 'check.jar'
$javaPath = Join-Path $projectPath 'toolchain/jdk/home/bin/java.exe'
$schedulerPath = Join-Path $projectPath 'app/src/main/java/com/dcimgallery/raw/core/tone/TonePreviewScheduler.kt'
$checkPath = Join-Path $PSScriptRoot 'TonePreviewSchedulerCheck.kt'
& $javaPath -cp ($compilerDependencies -join ';') org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath "$stdlibPath;$coroutinesPath" -d $jarPath $schedulerPath $checkPath
if ($LASTEXITCODE -ne 0) { throw 'Preview regression check compilation failed' }
& $javaPath -cp "$jarPath;$stdlibPath;$coroutinesPath" com.dcimgallery.raw.core.tone.TonePreviewSchedulerCheckKt
if ($LASTEXITCODE -ne 0) { throw 'Preview regression check failed' }
