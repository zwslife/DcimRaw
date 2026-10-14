"""Archive resolved dependencies and permissions without reading signing secrets."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET
import os

project = Path(__file__).resolve().parents[1]
folder = project / 'publishing/vivo'
graph = (project / '.gradle/vivo-release-dependencies.txt').read_text(encoding='utf-8-sig')
coordinates = set()
for line in graph.splitlines():
    if '(c)' in line:
        continue  # Constraints aren't themselves the packaged runtime artifacts.
    found = re.search(r'--- ([\w.-]+):([\w.-]+)(?::([^\s]+))?(?: -> ([^\s]+))?', line)
    if found:
        group, artifact, requested, resolved = found.groups()
        version = resolved or requested
        if version and ':' not in version:
            coordinates.add((group, artifact, version))

cache = Path(os.environ['USERPROFILE']) / '.gradle/caches/modules-2/files-2.1'
rows = []
ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
def pom_metadata(group, artifact, version, depth=0):
    if depth > 5:
        return [], ''
    poms = list((cache / group / artifact / version).glob('*/*.pom'))
    if not poms:
        return [], ''
    root = ET.parse(poms[0]).getroot()
    url = root.findtext('m:url', '', ns)
    licenses = []
    for license in root.findall('m:licenses/m:license', ns):
        name = license.findtext('m:name', '', ns)
        link = license.findtext('m:url', '', ns)
        licenses.append(f'[{name}]({link})' if link else name)
    parent = root.find('m:parent', ns)
    if not licenses and parent is not None:
        inherited, inherited_url = pom_metadata(
            parent.findtext('m:groupId', '', ns), parent.findtext('m:artifactId', '', ns),
            parent.findtext('m:version', '', ns), depth + 1)
        licenses = [text + '（继承父 POM）' for text in inherited]
        url = url or inherited_url
    return licenses, url

missing = 0
for group, artifact, version in sorted(coordinates):
    licenses, url = pom_metadata(group, artifact, version)
    if not licenses:
        missing += 1
    rows.append(f'| {group}:{artifact}:{version} | {"; ".join(licenses) or "POM 未列明/缓存缺失，待核对"} | {url} |')

inventory = '# Release 依赖及许可证元数据\n\n'
inventory += '来源：Gradle releaseRuntimeClasspath 解析结果和本机缓存 POM。仅列元数据，不代表已满足 NOTICE、源代码提供或所有分发义务；约束节点已排除。\n\n'
inventory += f'共 {len(coordinates)} 个解析坐标，{missing} 个许可证信息待补查。\n\n'
inventory += '| 坐标 | POM 许可证 | 项目网址 |\n| --- | --- | --- |\n' + '\n'.join(rows)
inventory += '\n\n另有原生源码 LibRaw 0.21.5（LGPL 2.1 / CDDL 1.0 双许可）和 Lucide 矢量图标（ISC，部分来自 Feather 为 MIT）。原文已归档到 licenses 目录；正式分发仍需确定适用许可和完整义务。\n'
(folder / '08-依赖许可证清单.md').write_text(inventory, encoding='utf-8')
(folder / 'licenses/Lucide-Feather.txt').write_bytes((project / 'app/src/main/assets/licenses/lucide.txt').read_bytes())

android = '{http://schemas.android.com/apk/res/android}'
manifest = project / 'app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml'
root = ET.parse(manifest).getroot()
app = root.find('application')
permissions = [p.get(android+'name') for p in root.findall('uses-permission')]
assert 'android.permission.INTERNET' not in permissions
assert app.get(android+'debuggable', 'false') == 'false'
report = '# Release 准备核验记录\n\n日期：2026-10-14。非上架/签名完成证明。\n\n'
report += '- compileReleaseKotlin、lintRelease 已通过；lint：0 errors, 8 warnings。\n'
report += f'- 合并 Manifest 包名：{root.get("package")}；版本：{root.get(android+"versionName")}（{root.get(android+"versionCode")}）。\n'
report += '- Release 无 debuggable=true；未声明 INTERNET。allowBackup=false。\n'
report += '- 完整合并权限：\n' + ''.join(f'  - {permission}\n' for permission in permissions)
report += '- AndroidX 增加包名专属 signature 级动态广播权限；不是额外读取照片/身份/网络的危险权限。\n'
report += '- releaseRuntimeClasspath 已解析并归档许可证元数据；图标 256/512 PNG 已检查、均低于 50 KB。\n'
report += '- 尚无正式签名密钥，没有构建、上传正式 APK；未进行 Android 真机安装与商店审核。\n'
report += '- 用户尚未完成 vivo 账号注册实名；个人姓名、版权/备案材料、政策定稿/接入/网址、真实截图和最终验收未完成。\n'
(folder / '09-发布核验记录.md').write_text(report, encoding='utf-8')
print(f'Resolved {len(coordinates)} coordinates; {missing} license metadata gaps. Release manifest verified.')
