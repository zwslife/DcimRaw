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
html = '''<!doctype html><html lang="zh-CN"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>若图隐私政策草稿</title>
<style>body{font-family:system-ui,sans-serif;max-width:760px;margin:32px auto;padding:0 20px;color:#202522;line-height:1.8}pre{white-space:pre-wrap;font:inherit}.draft{background:#fff4d8;padding:12px;border-radius:8px}</style>
<p class="draft">未生效草稿：开发者实名名称、生效日期和最终包行为待确认。不要直接作为最终政策上传。</p><pre>''' + escape(policy) + '</pre></html>'
(folder / 'privacy-policy-draft.html').write_text(html, encoding='utf-8')

licenses = folder / 'licenses'
licenses.mkdir(exist_ok=True)
for name in ('COPYRIGHT', 'LICENSE.CDDL', 'LICENSE.LGPL'):
    (licenses / f'LibRaw-{name}.txt').write_bytes((project / 'app/src/main/cpp/libraw' / name).read_bytes())

out = folder / '若图-vivo上架准备材料.zip'
with ZipFile(out, 'w', ZIP_DEFLATED) as archive:
    for path in sorted(folder.rglob('*')):
        if path.is_file() and path.suffix in ('.md', '.html', '.png', '.txt', '.json'):
            archive.write(path, path.relative_to(folder))
print(f'{out}\n{out.stat().st_size} bytes; drafts only, no APK or private credentials')
