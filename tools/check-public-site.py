"""Check published pages and their local targets without third-party dependencies."""
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urlsplit, unquote

root = Path(__file__).resolve().parents[1] / 'docs'
class Page(HTMLParser):
    def __init__(self, path):
        super().__init__(convert_charrefs=True)
        self.ids, self.links, self.headings = set(), [], 0
        self.feed(path.read_text(encoding='utf-8'))
    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if 'id' in a:
            assert a['id'] not in self.ids, f'Duplicate ID: {a["id"]}'
            self.ids.add(a['id'])
        if tag == 'h1': self.headings += 1
        assert tag not in ('script', 'iframe'), 'Unexpected script or embedded third party'
        if tag == 'img': assert 'alt' in a, 'Image requires alternate text'
        for key in ('href', 'src'):
            if key in a: self.links.append(a[key])

pages = {p: Page(p) for p in root.glob('*.html')}
count = 0
for path, page in pages.items():
    assert page.headings == 1, f'{path}: expected one primary heading'
    for link in page.links:
        url = urlsplit(link)
        if url.scheme or url.netloc: continue
        dest = (path.parent / unquote(url.path)).resolve() if url.path else path
        if dest.is_dir(): dest /= 'index.html'
        assert dest.is_relative_to(root.resolve()), f'Outside public site: {link}'
        assert dest.is_file(), f'{path.name}: missing {link}'
        if url.fragment: assert unquote(url.fragment) in pages[dest].ids, f'Missing fragment: {link}'
        count += 1
    for target in ('privacy.html', 'terms.html'):
        assert target in page.links, f'Missing legal navigation in {path.name}'
print(f'PASS: {len(pages)} pages; {count} internal links/assets; fragments, headings, image alt text, legal navigation and no scripts/iframes.')
