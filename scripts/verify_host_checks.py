"""Run Kotlin host regressions using dependencies downloaded by the Android build (macOS/Linux)."""
from pathlib import Path
import os
import subprocess

root = Path(__file__).resolve().parents[1]
cache = Path(os.environ.get('GRADLE_USER_HOME', root / 'toolchain/gradle-home')) / 'caches/modules-2/files-2.1'
java_home = os.environ.get('JAVA_HOME')
java = str(Path(java_home) / 'bin/java') if java_home else 'java'

def jar(group, artifact, version=None):
    base = cache / group / artifact
    matches = sorted((base / version if version else base).rglob('*.jar'))
    if not matches:
        raise SystemExit(f'Missing cached dependency: {base}; run the Android build first')
    return str(matches[-1])

stdlib = jar('org.jetbrains.kotlin', 'kotlin-stdlib', '2.0.20')
compiler = [jar('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.0.20'),
    jar('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.0.20'),
    jar('org.jetbrains.kotlin', 'kotlin-reflect'), jar('org.jetbrains.intellij.deps', 'trove4j'),
    jar('org.jetbrains', 'annotations'), stdlib]
coroutines = jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.1')
compiler.append(coroutines)
classpath = os.pathsep.join([stdlib, coroutines])
src = root / 'app/src/main/java/com/dcimgallery/raw'
output = root / '.gradle/host-checks'
output.mkdir(parents=True, exist_ok=True)
sources = [src / p for p in ['core/geometry/PhotoGeometry.kt', 'core/tone/ToneParams.kt',
    'core/tone/MaskLayer.kt', 'core/tone/PhotoEditClipboard.kt', 'core/tone/HistogramData.kt',
    'core/tone/TonePreviewScheduler.kt', 'data/scan/ScanBatcher.kt', 'data/repo/IncrementalIndex.kt',
    'core/media/DeletionSession.kt']]
checks = ['PhotoGeometryCheck', 'MaskGeometryCheck', 'PhotoEditClipboardCheck', 'ToneEffectsCheck',
    'HistogramCheck', 'TonePreviewSchedulerCheck', 'GalleryLoadingCheck', 'PhotoDeletionCheck']
sources += [root / 'scripts' / f'{name}.kt' for name in checks]
target = output / 'checks.jar'
subprocess.run([java, '-cp', os.pathsep.join(compiler), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', classpath, '-d', str(target),
    *map(str, sources)], check=True)
for name in checks:
    package = next(line.split()[1] for line in (root / 'scripts' / f'{name}.kt').read_text().splitlines()
                   if line.startswith('package '))
    subprocess.run([java, '-cp', os.pathsep.join([str(target), classpath]), f'{package}.{name}Kt'], check=True)
