"""Render approved policy only, with no external requests or telemetry."""
from pathlib import Path
from html import escape
import re

root = Path(__file__).resolve().parents[1]
source = root / 'publishing/vivo/privacy-policy-final.md'
output = root / 'publishing/ruotu-privacy-site/dist'
output.mkdir(parents=True, exist_ok=True)
lines = source.read_text(encoding='utf-8').splitlines()
blocks = []
navigation = []
paragraph = []

def flush():
    if paragraph:
        blocks.append('<p>' + '<br>'.join(escape(line) for line in paragraph) + '</p>')
        paragraph.clear()

for line in lines:
    if line.startswith('# '):
        flush()
        blocks.append('<h1>' + escape(line[2:]) + '</h1>')
    elif line.startswith('## '):
        flush()
        anchor = 'section-' + str(len(navigation) + 1)
        navigation.append((anchor, line[3:]))
        blocks.append(f'<h2 id="{anchor}">' + escape(line[3:]) + '</h2>')
    elif not line.strip():
        flush()
    elif re.match(r'^\d+\. ', line) or line.startswith('- '):
        flush()
        blocks.append('<p class="item">' + escape(line) + '</p>')
    else:
        paragraph.append(line)
flush()
nav = ''.join(f'<a href="#{anchor}">{escape(label)}</a>' for anchor, label in navigation)
page = '''<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="description" content="若图 Android 应用隐私政策，说明照片、本机编辑记录、授权与导出的处理方式。">
<meta name="color-scheme" content="light"><title>若图隐私政策</title>
<style>
*{box-sizing:border-box}html{scroll-behavior:smooth;scroll-padding-top:24px}body{margin:0;background:#fff;color:#202923;font:16px/1.9 system-ui,-apple-system,"Segoe UI",sans-serif}
header{max-width:1120px;margin:auto;padding:28px 28px 20px;border-bottom:1px solid #e4e9e3;display:flex;align-items:center;gap:12px}.mark{background:#000;color:#fff;border-radius:12px;padding:10px;font-weight:750;letter-spacing:-1px;line-height:1.2}
.layout{max-width:1120px;margin:auto;display:grid;grid-template-columns:220px minmax(0,1fr);gap:64px;padding:40px 28px 64px}nav{align-self:start;position:sticky;top:28px;font-size:14px}nav a{display:block;color:#526158;text-decoration:none;padding:8px 0}nav a:hover,nav a:focus-visible{color:#28745c;text-decoration:underline}
main{min-width:0}h1{font-size:30px;line-height:1.3;margin:0 0 22px;letter-spacing:-.5px}h2{font-size:20px;line-height:1.5;margin:42px 0 14px}p{margin:0 0 18px;overflow-wrap:anywhere}.item{padding-left:8px;border-left:2px solid #e8f1eb}footer{max-width:1120px;margin:auto;padding:20px 28px 32px;border-top:1px solid #e4e9e3;color:#68736b;font-size:14px}footer a{color:#28745c}
@media(max-width:760px){header{padding:20px}.layout{display:block;padding:28px 20px 44px}nav{position:static;margin-bottom:32px;display:flex;gap:8px 18px;flex-wrap:wrap}nav a{padding:2px 0}h1{font-size:26px}footer{padding:20px}}
@media(prefers-reduced-motion:reduce){html{scroll-behavior:auto}}
</style></head><body><header><span class="mark" aria-hidden="true">RAW</span><strong>若图</strong></header>
<div class="layout"><nav aria-label="政策目录">''' + nav + '</nav><main>' + ''.join(blocks) + '''</main></div>
<footer>联系开发者：<a href="mailto:zwi_1207@163.com">zwi_1207@163.com</a></footer></body></html>'''
(output / 'index.html').write_text(page, encoding='utf-8', newline='\n')
(root / 'publishing/vivo/privacy-policy-web.html').write_text(page, encoding='utf-8', newline='\n')
# Preserve the exact approved Markdown alongside the readable page.
(output / 'privacy-policy.md').write_bytes(source.read_bytes())
assert '<script' not in page and '待定稿' not in page and 'PRIVATE KEY' not in page
print(f'{output / "index.html"}: {len(navigation)} policy sections; no external assets or scripts')
