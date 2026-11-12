# Midox Shop shutter count page

The "Nombre de déclenchements" page for **midoxshop.ma/declenchements/**. Visitors pick a photo taken by
their camera and see its shutter count and wear. The page text is in French; nothing is uploaded, the photo
is read in the visitor's browser.

## What is in this folder

| File | What it is |
| --- | --- |
| `index.html` | The page (markup, styles and the page script) |
| `reader.js` | Reads the shutter count from a photo (Canon, Nikon, Sony, Fujifilm) |
| `ptp-usb.js` | Reads a camera over a USB cable (Chrome on Android) |
| `logo.webp` | The shop logo used in the header and on the shared result image |
| `apercu.png` | The preview image shown when the link is shared (WhatsApp, Facebook) |
| `for-testing-on-phone-only/index.html` | The same page as **one single file**, for testing only (see below) |
| `build.py` | Regenerates the copies and the single file after a change |

## Putting it online

Upload these five files together, in the same folder, so the page is served at `/declenchements/`:

`index.html`, `reader.js`, `ptp-usb.js`, `logo.webp`, `apercu.png`

- The page loads its scripts and logo with relative paths, so the address must end with a slash
  (`/declenchements/`) or be `/declenchements/index.html`.
- On a Next.js site, files in `public/declenchements/` are served at `/declenchements/index.html`, not at
  `/declenchements/`. Add a rewrite from `/declenchements` to `/declenchements/index.html` (with
  `trailingSlash` handling), or turn the page into a route.
- Then link to the page from the site menu or footer, add it to `sitemap.xml`, and request indexing in
  Google Search Console.

## for-testing-on-phone-only

One self-contained HTML file with the scripts and logo inside it. Send it to a phone and open it from the
Files app to try the page without a server. The separated files cannot be opened that way (browsers block
script files loaded from `file://`). **Do not deploy this one**: use the separated files above.

## USB reading

The "Brancher l'appareil en USB" button only shows on Android (Chrome), where nothing needs setting up.
On a computer in the shop, add `?usb=1` to the address to show it. Only read commands are sent to the camera.

## Changing the page

`reader.js` and `ptp-usb.js` here are copies of `../web/reader.js` and `../web/ptp-usb.js`, which are shared
with the other web version and covered by the tests in `../web/test/`. Edit those, edit `index.html` here,
then run:

```
python3 midox-shutter-count-website/build.py
node web/test/reader.test.mjs && node web/test/usb.test.mjs
```
