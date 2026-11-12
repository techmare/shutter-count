"""Run after any change:  python3 midox-shutter-count-website/build.py

1. Copies the shared photo reader and USB code (web/reader.js, web/ptp-usb.js) next to the page.
2. Stamps the version number in index.html.
3. Rebuilds for-testing-on-phone-only/index.html: the same page with the scripts and the logo inside
   one file, so it opens straight from a phone's Files app (file://), where separate script files can't load.
"""
import base64, datetime, pathlib, re, shutil, subprocess
here = pathlib.Path(__file__).parent
web = here.parent / 'web'

for name in ('reader.js', 'ptp-usb.js'):
    shutil.copyfile(web / name, here / name)

ver = datetime.date.today().strftime('%Y.%m.%d') + '-' + subprocess.run(
    ['git', 'rev-list', '--count', 'HEAD'], capture_output=True, text=True, cwd=here).stdout.strip()
page = (here / 'index.html').read_text()
page, a = re.subn(r'(<span id="version">)[^<]*', r'\g<1>' + ver, page)
page, b = re.subn(r"(const VERSION = ')[^']*", r'\g<1>' + ver, page)
assert a == 1 and b == 1, 'version spots'
(here / 'index.html').write_text(page)

def strip_exports(js):
    return re.sub(r'^export (?=(async )?function|const|class|let)', '', js, flags=re.M)

reader = strip_exports((here / 'reader.js').read_text())
usb = re.sub(r"^import .*?from './reader.js';\n", '', (here / 'ptp-usb.js').read_text(), flags=re.M)
usb = ('const usb = (() => {\n' + strip_exports(usb) +
       '\nreturn { supported, connect, known, open, readCamera, disconnect, platform, UsbError };\n})();\n')

start = page.index('<script type="module">') + len('<script type="module">')
end = page.index('</script>', start)
script = re.sub(r"^import .*?;\n", '', page[start:end], flags=re.M)
one = page[:start] + '\n// ---- reader.js\n' + reader + '\n// ---- ptp-usb.js\n' + usb + '\n// ---- page\n' + script + page[end:]

logo = 'data:image/webp;base64,' + base64.b64encode((here / 'logo.webp').read_bytes()).decode()
assert one.count('logo.webp') == 2, 'logo spots'
one = one.replace('logo.webp', logo)

out = here / 'for-testing-on-phone-only'
out.mkdir(exist_ok=True)
(out / 'index.html').write_text(one)
print('index.html', ver, len(page) // 1024, 'KB  |  for-testing-on-phone-only/index.html', len(one) // 1024, 'KB')
