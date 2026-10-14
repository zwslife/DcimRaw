from pathlib import Path
from html.parser import HTMLParser
import re

root = Path(__file__).resolve().parents[1]
page = root / 'publishing/ruotu-privacy-site/dist/index.html'
policy = root / 'publishing/vivo/privacy-policy-final.md'

class PolicyPage(HTMLParser):
    def __init__(self):
        super().__init__()
        self.in_main = False
        self.text = []
        self.ids = set()
        self.links = []
        self.scripts = []
    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == 'main':
            self.in_main = True
        if 'id' in attrs:
            assert attrs['id'] not in self.ids, 'Duplicate navigation target'
            self.ids.add(attrs['id'])
        if tag == 'a':
            self.links.append(attrs.get('href', ''))
        if tag == 'script' or (tag in ('img', 'iframe', 'link') and any(k in attrs for k in ('src', 'href'))):
            self.scripts.append(tag)
    def handle_endtag(self, tag):
        if tag == 'main':
            self.in_main = False
    def handle_data(self, data):
        if self.in_main:
            self.text.append(data)

parser = PolicyPage()
parser.feed(page.read_text(encoding='utf-8'))
normalized = lambda text: re.sub(r'\s+', '', text)
expected = re.sub(r'(?m)^#+ ', '', policy.read_text(encoding='utf-8'))
assert normalized(''.join(parser.text)) == normalized(expected), 'Public page must preserve every policy clause'
assert len(parser.ids) == 7 and not parser.scripts
assert all(link[1:] in parser.ids for link in parser.links if link.startswith('#'))
assert all(link.startswith('#') or link == 'mailto:zwi_1207@163.com' for link in parser.links)
print('Policy content matches exactly; all 7 anchors resolve; no external assets, scripts or collection endpoints.')
