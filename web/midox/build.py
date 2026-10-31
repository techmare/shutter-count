"""Builds one self-contained page (dist/index.html): the reader, the USB code and the logo inside it,
so it works opened straight from a phone's Files app (file://) as well as on midoxshop.ma."""
import base64, pathlib, re
here = pathlib.Path(__file__).parent
page = (here / 'index.html').read_text()
reader = (here.parent / 'reader.js').read_text()
usb = (here.parent / 'ptp-usb.js').read_text()

def strip_exports(js):
    return re.sub(r'^export (?=(async )?function|const|class|let)', '', js, flags=re.M)

reader = strip_exports(reader)
usb = re.sub(r"^import .*?from './reader.js';\n", '', usb, flags=re.M)
usb = strip_exports(usb)
usb = ('const usb = (() => {\n' + usb +
       '\nreturn { supported, connect, known, open, readCamera, disconnect, platform, UsbError };\n})();\n')

start = page.index('<script type="module">') + len('<script type="module">')
end = page.index('</script>', start)
script = page[start:end]
script = re.sub(r"^import .*?;\n", '', script, flags=re.M)
page = page[:start] + '\n// ---- reader.js\n' + reader + '\n// ---- ptp-usb.js\n' + usb + '\n// ---- page\n' + script + page[end:]

logo = 'data:image/webp;base64,' + base64.b64encode((here / 'logo.webp').read_bytes()).decode()
assert page.count('/assets/images/logo.png') == 2, 'logo spots'
page = page.replace('/assets/images/logo.png', logo)

import subprocess, datetime
ver = datetime.date.today().strftime('%Y.%m.%d') + '-' + subprocess.run(['git', 'rev-list', '--count', 'HEAD'], capture_output=True, text=True, cwd=here).stdout.strip()
page = page.replace('__VERSION__', ver)
(here / 'dist').mkdir(exist_ok=True)
(here / 'dist' / 'index.html').write_text(page)
print('dist/index.html', ver, len(page) // 1024, 'KB')
