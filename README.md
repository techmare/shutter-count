# Shutter Count

Android app to check a camera's shutter count (number of actuations) — for buying, selling and checking used cameras.

| Brand | How | Notes |
|---|---|---|
| **Nikon** | Pick a photo (JPEG / NEF) | Almost every DSLR and Z body. Z bodies include electronic-shutter shots. |
| **Sony** | Pick a photo (JPEG / ARW) | α6000–α6700, α7 / α7R / α7S / α7C series, α9, α9 II, α1, SLT/NEX. Not RX100/ZV-1 compacts or the α9 III. |
| **Canon R3, R5, R6, R6 Mark II, R7, R8, R10, R50, R100, RP, M50, M50 Mark II, M6 Mark II and PowerShot G5 X Mark II** | Pick a photo (JPEG / CR3) | Count includes electronic-shutter shots (offsets from ExifTool). |
| **Canon** | USB cable (OTG) | Older bodies (DIGIC 5 and earlier) via the standard counter. Newer bodies via an experimental method, only on cameras that list it (confirmed on the EOS 5D Mark IV). Not supported: EOS R50 (sending the commands anyway caused Err 70, cleared by a battery pull). |

**Over USB:** plug in any Nikon, Sony or Canon R body and the app reads the count from the newest photo on the card (standard photo-transfer commands only). Older Canons are read from the camera's own counter.

**Seen before:** when a serial number is already in History, the app shows the earlier check and warns if the count went down.

**Mac:** [Shutter Count for Mac](../../releases/download/mac-latest/ShutterCount-mac.zip). Drop photos on the window, or plug in a camera. First launch: right-click the app and choose Open.

**Download:** [latest APK](../../releases/latest/download/ShutterCount.apk) — open it on the phone to install or update.

## Tips
- Use an untouched file from the memory card. WhatsApp, Messenger and editing apps strip the data.
- The count in a photo is the count *when that photo was taken* — take a fresh one.
- Canon over USB: turn the camera's Wi-Fi off first, it blocks the USB port.
- To verify an experimental Canon reading: take exactly 5 photos, read again, it should rise by 5.

## Credits
- Sony maker-note offsets and cipher follow [ExifTool](https://exiftool.org) by Phil Harvey.
- Canon MPU channel framing follows [openshuttercounter](https://github.com/mvisonneau/openshuttercounter) (MIT).
