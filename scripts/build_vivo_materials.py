"""Build preview/store assets and a local materials ZIP; never include signing keys."""
from pathlib import Path
from html import escape
from zipfile import ZipFile, ZIP_DEFLATED
from PIL import Image, ImageDraw, ImageFont

project = Path(__file__).resolve().parents[1]
folder = project / 'publishing/vivo'
assets = folder / 'assets'
assets.mkdir(exist_ok=True)
font = ImageFont.truetype('C:/Windows/Fonts/arialbd.ttf', 322)
icon = Image.new('RGB', (1280, 1280), 'black')
draw = ImageDraw.Draw(icon)
b = draw.textbbox((0, 0), 'RAW', font=font)
draw.text(((1280-b[2]+b[0])/2-b[0], (1280-b[3]+b[1])/2-b[1]), 'RAW', font=font, fill='white')
for size in (256, 512):
    path = assets / f'icon-{size}.png'
    icon.resize((size, size), Image.Resampling.LANCZOS).save(path, optimize=True)
    assert path.stat().st_size < 50_000
    with Image.open(path) as result:
        assert result.size == (size, size) and result.mode == 'RGB'

policy = (folder / '02-隐私政策.md').read_text(encoding='utf-8')
publisher_path = project / 'publishing/private/publisher.properties'
publisher = {}
if publisher_path.is_file():
    publisher = dict(line.split('=', 1) for line in publisher_path.read_text(encoding='utf-8-sig').splitlines() if '=' in line and not line.startswith('#'))
approved = publisher.get('policyApproved') == 'true' and bool(publisher.get('developerName')) and bool(publisher.get('effectiveDate'))
if approved:
    import re
    date = publisher['effectiveDate']
    if date == 'FIRST_RELEASE':
        date = '首次正式发布之日（以 vivo 商店首次上架记录为准）'
    policy = policy.split('\n---', 1)[0].replace('（待定稿）', '').replace('【上线前确认】', date)
    policy = re.sub(r'(?m)^开发者：.*$', lambda _: '开发者：' + publisher['developerName'], policy)
    (folder / 'privacy-policy-final.md').write_text(policy, encoding='utf-8', newline='\n')
html = '''<!doctype html><html lang="zh-CN"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>若图隐私政策</title>
<style>body{font-family:system-ui,sans-serif;max-width:760px;margin:32px auto;padding:0 20px;color:#202522;line-height:1.8}pre{white-space:pre-wrap;font:inherit}.draft{background:#fff4d8;padding:12px;border-radius:8px}</style>
''' + ('' if approved else '<p class="draft">未生效草稿：最终主体、生效日期及行为待确认。</p>') + '<pre>' + escape(policy) + '</pre></html>'
(folder / ('privacy-policy-final.html' if approved else 'privacy-policy-draft.html')).write_text(html, encoding='utf-8', newline='\n')

licenses = folder / 'licenses'
licenses.mkdir(exist_ok=True)
for name in ('COPYRIGHT', 'LICENSE.CDDL', 'LICENSE.LGPL'):
    (licenses / f'LibRaw-{name}.txt').write_bytes((project / 'app/src/main/cpp/libraw' / name).read_bytes())

out = folder / '若图-vivo上架准备材料.zip'
with ZipFile(out, 'w', ZIP_DEFLATED) as archive:
    for path in sorted(folder.rglob('*')):
        if path.is_file() and path.suffix in ('.md', '.html', '.png', '.txt', '.json'):
            if approved and path.name == 'privacy-policy-draft.html':
                continue
            archive.write(path, path.relative_to(folder))
        elif path.name == 'ruotu-signing-certificate.pem':
            assert 'BEGIN CERTIFICATE' in path.read_text() and 'PRIVATE KEY' not in path.read_text()
            archive.write(path, path.relative_to(folder))
print(f'{out}\n{out.stat().st_size} bytes; policy {"approved" if approved else "draft"}; no APK or private credentials')
