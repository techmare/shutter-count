// Shutter count from a photo file (JPEG, NEF, ARW, CR2/CR3, TIFF). Port of the Android app's
// PhotoShutterReader + CameraModels. Runs in the browser and in Node (tests). Nothing leaves the phone.

// ------------------------------------------------------------------ models

export function brandOf(make) {
  const m = (make || '').toUpperCase();
  if (m.includes('NIKON')) return 'Nikon';
  if (m.includes('SONY')) return 'Sony';
  if (m.includes('CANON')) return 'Canon';
  return 'Other';
}

const roman = s => s.replace(/M2$/, ' II').replace(/M3$/, ' III').replace(/M4$/, ' IV').replace(/M5$/, ' V').replace(/M6$/, ' VI');

export function displayName(brand, model) {
  const raw = (model || '').trim();
  if (!raw) return brand === 'Other' ? 'Unknown camera' : brand;
  if (brand === 'Sony') {
    let n = raw;
    if (raw.startsWith('ILCE-')) n = 'α' + roman(raw.slice(5));
    else if (raw.startsWith('ILCA-')) n = 'α' + roman(raw.slice(5));
    else if (raw.startsWith('SLT-')) n = 'α' + raw.slice(4).replace(/^A/, '');
    else if (raw.startsWith('ILME-')) n = raw.slice(5);
    return 'Sony ' + n;
  }
  if (brand === 'Nikon') {
    let m = raw.replace(/^NIKON/, '').trim().replace(/^Z\s+/, 'Z').replace('_2', ' II').replace('_3', ' III');
    return 'Nikon ' + m;
  }
  if (brand === 'Canon') return raw.toUpperCase().startsWith('CANON') ? raw : 'Canon ' + raw;
  return raw;
}

const off = v => ({ value: v, official: true });
const est = v => ({ value: v, official: false });

function nikonRated(m) {
  const z = m.replace(/ /g, '');
  if (z === 'Z9' || z === 'Z8') return null;
  if (/^Z(6|7|6_2|7_2)$/.test(z)) return off(200000);
  if (/^Z(5|5_2|6_3|F)$/.test(z)) return est(200000);
  if (/^Z(30|50|50_2|FC)$/.test(z)) return est(100000);
  if (/^D(3|3S|3X)$/.test(m)) return off(300000);
  if (/^D(4|4S|5|6)$/.test(m)) return off(400000);
  if (/^D(500|800|800E|810|810A|850)$/.test(m)) return off(200000);
  if (m === 'DF' || /^D(300|300S|600|610|700|750|780|7000|7100|7200|7500)$/.test(m)) return off(150000);
  if (/^D(200|90|80)$/.test(m)) return off(100000);
  if (/^D(5\d00|3\d00|40|40X|60)$/.test(m)) return est(100000);
  if (/^D(2H|2HS|2X|2XS)$/.test(m)) return off(150000);
  if (/^D(70|70S|50|100|1|1H|1X)$/.test(m)) return est(50000);
  return null;
}

function sonyRated(m) {
  if (m === 'ILCE-9M3') return null;
  if (/^ILCE-(7RM2|7RM3A?|9)$/.test(m)) return off(500000);
  if (/^ILCE-(1|1M2|9M2|7RM4A?|7RM5|7SM3)$/.test(m)) return est(500000);
  if (/^ILCA-99M2$/.test(m)) return est(300000);
  if (/^ILCE-(7|7R|7S|7M2|7SM2|7M3|7M4|7M5|7C|7CM2|7CR)$/.test(m)) return est(200000);
  if (/^ILCE-(6300|6400|6500|6600|6700)$/.test(m)) return est(200000);
  if (/^ILCE-(5000|5100|6000|6100|3000|3500|QX1)$/.test(m)) return est(100000);
  if (m.startsWith('ZV-E10')) return est(100000);
  if (m.startsWith('NEX-') || m.startsWith('SLT-') || m.startsWith('ILCA-')) return est(100000);
  return null;
}

function canonRated(m) {
  const s = m.replace(/^EOS/, '').trim().replace(/\s+/g, ' ').replace('R6M2', 'R6 MARK II');
  if (/^-?1D X MARK (II|III)$/.test(s)) return off(500000);
  if (/^-?1D X$/.test(s)) return off(400000);
  if (/^-?1D MARK IV$/.test(s)) return off(300000);
  if (/^R(1|3|5|5 MARK II)$/.test(s)) return off(500000);
  if (s === 'R5 C') return est(500000);
  if (s === 'R6') return off(300000);
  if (/^R6 MARK (II|III)$/.test(s)) return est(300000);
  if (s === 'R') return off(200000);
  if (s === 'RP') return off(100000);
  if (s === 'R7') return est(200000);
  if (/^R(8|10|50|50 V|100)$/.test(s)) return est(100000);
  if (/^(5D( MARK (II|III|IV))?|5DS R?)$/.test(s)) return off(150000);
  if (s === '7D MARK II') return off(200000);
  if (s === '7D') return off(150000);
  if (/^(6D( MARK II)?|60D|70D|80D|750D|760D)$/.test(s)) return off(100000);
  if (s === '90D') return est(100000);
  if (/^(\d{2}D|\d{3}D|\d{4}D|M\d.*|REBEL.*|KISS.*)$/.test(s)) return est(100000);
  return null;
}

export function rating(brand, model) {
  const m = (model || '').toUpperCase().trim();
  if (!m) return null;
  if (brand === 'Nikon') return nikonRated(m.replace(/^NIKON/, '').trim());
  if (brand === 'Sony') return sonyRated(m);
  if (brand === 'Canon') return canonRated(m.replace(/^CANON/, '').trim());
  return null;
}

/** Where Canon R bodies keep their counter in CameraInfo (maker note 0x000D). */
export function canonPhotoOffset(model) {
  const m = model || '';
  if (/\bEOS (R6m2|R6 Mark II|R8|R50)$/.test(m)) return 0x0d29;
  if (/\bEOS R[56]$/.test(m)) return 0x0af1;
  // found by comparing two R100 photos 5 shots apart (623 -> 628), kept counting after a card format
  // the M50 Mark II uses the same spot: two of its photos 4 shots apart went 8908 -> 8912
  if (/\bEOS (R100|M50m2|M50 Mark II)$/.test(m)) return 0x0acf;
  // found by comparing two RP photos 4 shots apart (2343 -> 2347)
  if (/\bEOS RP$/.test(m)) return 0x0960;
  // 250D (Rebel SL3 / Kiss X10 / 200D Mark II): two photos 4 shots apart went 2780 -> 2784
  if (/\bEOS (250D|Rebel SL3|Kiss X10|200D Mark II)$/.test(m)) return 0x094c;
  // From review-site sample JPGs (Imaging Resource), checked against the known R5/R6/R50/R8 spots
  if (/\bEOS (R7|R10)$/.test(m)) return 0x0c93;
  if (/\bEOS M6 Mark II$/.test(m)) return 0x0ac7;
  if (/\bEOS (M50|Kiss M)$/.test(m)) return 0x0188;
  if (/\bEOS R3$/.test(m)) return 0x0fa9;
  // ExifTool (CameraInfoG5XII), JPEG files
  if (/\bPowerShot G5 X Mark II$/.test(m)) return 0x0293;
  return null;
}

export const WEAR = [
  { key: 'likeNew', en: 'Like new', fr: 'Comme neuf', below: 0.10, tone: 'ok' },
  { key: 'good', en: 'Good', fr: 'Bon état', below: 0.40, tone: 'ok' },
  { key: 'used', en: 'Used', fr: 'Usure moyenne', below: 0.75, tone: 'mid' },
  { key: 'high', en: 'High wear', fr: 'Usure élevée', below: 1.0000001, tone: 'high' },
  { key: 'beyond', en: 'Beyond rating', fr: 'Au-delà de la durée nominale', below: Infinity, tone: 'high' },
];
export const wear = (count, rated) => WEAR.find(w => count / rated < w.below);
export const wearPercent = (count, rated) => Math.floor(count * 100 / rated);

// ------------------------------------------------------------------- TIFF

const typeSize = t => ([1, 2, 6, 7].includes(t) ? 1 : [3, 8].includes(t) ? 2 : [4, 9, 11, 13].includes(t) ? 4 : [5, 10, 12].includes(t) ? 8 : 1);

class Tiff {
  constructor(buf, base, little) { this.buf = buf; this.base = base; this.little = little; }
  u8(p) { return p >= 0 && p < this.buf.length ? this.buf[p] : null; }
  u16(p) {
    if (p < 0 || p + 2 > this.buf.length) return null;
    const a = this.buf[p], b = this.buf[p + 1];
    return this.little ? a | (b << 8) : (a << 8) | b;
  }
  u32(p) {
    if (p < 0 || p + 4 > this.buf.length) return null;
    const b = this.buf;
    return this.little ? (b[p] | (b[p + 1] << 8) | (b[p + 2] << 16)) + b[p + 3] * 0x1000000
      : b[p] * 0x1000000 + ((b[p + 1] << 16) | (b[p + 2] << 8) | b[p + 3]);
  }
  readIfd(pos) {
    const count = this.u16(pos);
    if (!count || count > 1000 || pos + 2 + count * 12 > this.buf.length) return null;
    const out = [];
    for (let i = 0; i < count; i++) {
      const e = pos + 2 + i * 12;
      const tag = this.u16(e), type = this.u16(e + 2), n = this.u32(e + 4);
      if (tag == null || type == null || n == null) return null;
      const size = typeSize(type) * n;
      let valuePos;
      if (size <= 4) valuePos = e + 8;
      else { const o = this.u32(e + 8); if (o == null) return null; valuePos = this.base + o; }
      out.push({ tag, type, n, valuePos, size });
    }
    return out;
  }
  looksLikeIfd(pos) { const e = this.readIfd(pos); return !!e && e.length > 0 && e.every(x => x.type >= 1 && x.type <= 13); }
  bytes(e) {
    if (!e || e.size <= 0 || e.valuePos < 0 || e.valuePos + e.size > this.buf.length) return null;
    return this.buf.subarray(e.valuePos, e.valuePos + e.size);
  }
  string(e) {
    const b = this.bytes(e); if (!b) return null;
    let end = b.indexOf(0); if (end < 0) end = b.length;
    let s = ''; for (let i = 0; i < end; i++) s += String.fromCharCode(b[i]);
    s = s.trim(); return s || null;
  }
  int(e) {
    if (!e) return null;
    if (e.type === 3 || e.type === 8) return this.u16(e.valuePos);
    if (e.type === 4 || e.type === 9 || e.type === 13) return this.u32(e.valuePos);
    if (e.type === 1 || e.type === 7) return this.u8(e.valuePos);
    return null;
  }
}
const find = (ifd, tag) => (ifd || []).find(e => e.tag === tag) || null;

function startsWith(buf, pos, s) {
  if (pos < 0 || pos + s.length > buf.length) return false;
  for (let i = 0; i < s.length; i++) if (buf[pos + i] !== s.charCodeAt(i)) return false;
  return true;
}

// Sony 0x9050: c = b^3 mod 249 for bytes 2..247
const DEC = new Uint8Array(256), ENC = new Uint8Array(256);
for (let i = 0; i < 256; i++) { DEC[i] = i; ENC[i] = i; }
for (let b = 2; b <= 247; b++) { const c = (b * b * b) % 249; DEC[c] = b; ENC[b] = c; }
export const sonyDecipher = src => src.map(x => DEC[x]);
export const sonyEncipher = src => src.map(x => ENC[x]);

// ----------------------------------------------------------------- reader

const TAG_MAKE = 0x010f, TAG_MODEL = 0x0110, TAG_SOFTWARE = 0x0131, TAG_EXIF = 0x8769;
const TAG_MAKERNOTE = 0x927c, TAG_DATE = 0x9003, TAG_BODY_SERIAL = 0xa431;

const MISSING_MAKERNOTE = "This photo is missing the camera's private data (maker notes). That usually means it was converted, resized or sent through an app like WhatsApp. On iPhone, pick the photo with “Choose File” from the Files app, not from Photos.";
const CANON_UNSUPPORTED = "This Canon doesn't store a readable shutter count in photos. Photo reading works for the EOS R3, R5, R6, R6 Mark II, R7, R8, R10, R50, R100, RP, 250D, M50, M50 Mark II, M6 Mark II and PowerShot G5 X Mark II. Older Canons need the Android or Mac app with a USB cable.";

function failure(problem) {
  return { brand: 'Other', make: null, model: null, displayName: 'Unknown camera', count: null, rated: null, ratedOfficial: false,
    serial: null, dateTaken: null, software: null, details: [], warnings: [], problem };
}

function result(ctx, count, { serial = ctx.bodySerial, details = [], warnings = [], problem = null } = {}) {
  const r = rating(ctx.brand, ctx.model);
  return {
    brand: ctx.brand, make: ctx.make, model: ctx.model, displayName: displayName(ctx.brand, ctx.model),
    count, rated: r ? r.value : null, ratedOfficial: r ? r.official : false, serial: serial || null,
    dateTaken: ctx.dateTaken, software: ctx.software, details, warnings, problem,
    includesElectronic: ctx.brand === 'Canon' || (ctx.brand === 'Nikon' && /\bZ/.test(ctx.model || '')),
  };
}

function findTiffStart(d) {
  if (d.length < 8) return null;
  if ((d[0] === 0x49 && d[1] === 0x49) || (d[0] === 0x4d && d[1] === 0x4d)) return 0;
  if (d[0] !== 0xff || d[1] !== 0xd8) return null;
  let p = 2;
  while (p + 4 <= d.length) {
    if (d[p] !== 0xff) { p++; continue; }
    const marker = d[p + 1];
    if (marker === 0xff) { p++; continue; }
    if (marker === 0xd9 || marker === 0xda) return null;
    if ((marker >= 0xd0 && marker <= 0xd7) || marker === 0x01) { p += 2; continue; }
    const len = (d[p + 2] << 8) | d[p + 3];
    if (len < 2) return null;
    if (marker === 0xe1 && startsWith(d, p + 4, 'Exif\0\0') && p + 18 <= d.length) return p + 10;
    p += 2 + len;
  }
  return null;
}

const isCr3 = d => d.length > 12 && startsWith(d, 4, 'ftyp') && startsWith(d, 8, 'crx ');
const isHeif = d => d.length > 12 && startsWith(d, 4, 'ftyp') && /^(heic|heix|hevc|heim|heis|mif1|msf1)$/.test(String.fromCharCode(...d.subarray(8, 12)));

export function read(input) {
  const data = input instanceof Uint8Array ? input : new Uint8Array(input);
  if (isCr3(data)) return readCr3(data);
  const start = findTiffStart(data);
  if (start == null) {
    if (isHeif(data)) return failure('This is a HEIC/HEIF file, which this page can’t read. Use the JPEG or RAW file from the camera.');
    if (data[0] === 0x89 && data[1] === 0x50) return failure('This is a PNG (a screenshot or an exported image). Use the original JPEG or RAW file from the camera.');
    return failure('This file has no camera data (EXIF). Use the original JPEG or RAW file straight from the camera.');
  }
  let little;
  if (data[start] === 0x49 && data[start + 1] === 0x49) little = true;
  else if (data[start] === 0x4d && data[start + 1] === 0x4d) little = false;
  else return failure('Unrecognised photo format.');
  const t = new Tiff(data, start, little);
  const o = t.u32(start + 4);
  const ifd0 = o == null ? null : t.readIfd(start + o);
  if (!ifd0) return failure('Corrupt photo data.');
  const make = t.string(find(ifd0, TAG_MAKE)), model = t.string(find(ifd0, TAG_MODEL)), software = t.string(find(ifd0, TAG_SOFTWARE));
  const exifOff = t.int(find(ifd0, TAG_EXIF));
  const exif = exifOff != null ? t.readIfd(start + exifOff) : null;
  const ctx = {
    tiff: t, brand: brandOf(make), make, model, software,
    dateTaken: t.string(find(exif, TAG_DATE)), bodySerial: t.string(find(exif, TAG_BODY_SERIAL)),
  };
  const mn = find(exif, TAG_MAKERNOTE);
  const warnings = [];
  if (software && /photoshop|lightroom|snapseed|gimp|capture one|picsart|whatsapp|instagram/i.test(software)) {
    warnings.push(`This photo was edited with ${software}. Edited photos may have missing or altered data; use an untouched file from the camera.`);
  }
  if (ctx.brand === 'Nikon') return readNikon(ctx, mn, warnings);
  if (ctx.brand === 'Sony') return readSony(ctx, mn, warnings);
  if (ctx.brand === 'Canon') return readCanon(ctx, mn, warnings);
  const apple = /apple/i.test(make || '');
  return result(ctx, null, {
    warnings,
    problem: apple ? 'This is an iPhone photo. Pick a photo taken by the camera.'
      : !make ? 'This photo has no camera make. It may come from a phone, an editor or a messaging app.'
      : `${make} cameras are not supported. This page reads Nikon, Sony and recent Canon R photos.`,
  });
}

function readCanon(ctx, mn, warnings) {
  const offset = canonPhotoOffset(ctx.model);
  if (offset == null) return result(ctx, null, { warnings, problem: CANON_UNSUPPORTED });
  if (!mn) return result(ctx, null, { warnings, problem: MISSING_MAKERNOTE });
  const t = new Tiff(ctx.tiff.buf, ctx.tiff.base, ctx.tiff.little);
  if (!t.looksLikeIfd(mn.valuePos)) t.little = !t.little;
  const ifd = t.readIfd(mn.valuePos);
  if (!ifd) return result(ctx, null, { warnings, problem: MISSING_MAKERNOTE });
  return canonResult(ctx, t, ifd, offset, warnings);
}

function canonResult(ctx, t, ifd, offset, warnings) {
  const info = t.bytes(find(ifd, 0x000d));
  if (!info || info.length < offset + 4) return result(ctx, null, { warnings, problem: MISSING_MAKERNOTE });
  const count = new Tiff(info, 0, t.little).u32(offset);
  if (!count || count > 5000000) {
    return result(ctx, null, { warnings, problem: "Couldn't find a valid shutter count in this Canon photo. Try a JPEG or CR3 taken just now." });
  }
  const sn = t.int(find(ifd, 0x000c));
  return result(ctx, count, {
    serial: sn > 0 ? String(sn) : ctx.bodySerial, warnings,
    details: [['Note', 'On this Canon the count includes electronic-shutter shots, so wear may look higher than the mechanical shutter’s real use.']],
  });
}

function readCr3(d) {
  const box = type => {
    const limit = Math.min(d.length - 8, 4 * 1024 * 1024);
    for (let i = 4; i < limit; i++) if (d[i] === type.charCodeAt(0) && startsWith(d, i, type)) return i + 4;
    return null;
  };
  const tiffAt = s => {
    if (s == null || s + 8 > d.length) return null;
    const t = new Tiff(d, s, d[s] === 0x49);
    const o = t.u32(s + 4); if (o == null) return null;
    const ifd = t.readIfd(s + o); return ifd ? [t, ifd] : null;
  };
  const c1 = tiffAt(box('CMT1'));
  if (!c1) return failure("Couldn't read this CR3 file.");
  const [t1, ifd0] = c1;
  const c2 = tiffAt(box('CMT2'));
  const ctx = {
    tiff: t1, brand: 'Canon', make: t1.string(find(ifd0, TAG_MAKE)), model: t1.string(find(ifd0, TAG_MODEL)),
    software: t1.string(find(ifd0, TAG_SOFTWARE)),
    dateTaken: c2 ? c2[0].string(find(c2[1], TAG_DATE)) : null, bodySerial: c2 ? c2[0].string(find(c2[1], TAG_BODY_SERIAL)) : null,
  };
  const warnings = [];
  const offset = canonPhotoOffset(ctx.model);
  if (offset == null) return result(ctx, null, { warnings, problem: CANON_UNSUPPORTED });
  const c3 = tiffAt(box('CMT3'));
  if (!c3) return result(ctx, null, { warnings, problem: MISSING_MAKERNOTE });
  // most Canons leave the CameraInfo block (and so the count) out of their CR3 files
  if (!find(c3[1], 0x000d)) return result(ctx, null, { warnings, problem: "This camera keeps its shutter count in its JPG photos, not in its RAW (CR3) files. Pick a JPG taken by the camera, or shoot RAW+JPG." });
  return canonResult(ctx, c3[0], c3[1], offset, warnings);
}

function readNikon(ctx, mn, warnings) {
  if (!mn) return result(ctx, null, { warnings, problem: MISSING_MAKERNOTE });
  const buf = ctx.tiff.buf, start = mn.valuePos;
  if (start < 0 || start + 18 > buf.length) return result(ctx, null, { warnings, problem: MISSING_MAKERNOTE });
  let t, ifd;
  if (startsWith(buf, start, 'Nikon\0\x02')) {
    const hdr = start + 10;
    t = new Tiff(buf, hdr, buf[hdr] === 0x49);
    const o = t.u32(hdr + 4);
    ifd = o == null ? null : t.readIfd(hdr + o);
  } else if (startsWith(buf, start, 'Nikon\0\x01')) {
    return result(ctx, null, { warnings, problem: 'This older Nikon model does not record a shutter count.' });
  } else {
    t = new Tiff(buf, ctx.tiff.base, ctx.tiff.little);
    if (!t.looksLikeIfd(start)) t.little = !t.little;
    ifd = t.readIfd(start);
  }
  if (!ifd) return result(ctx, null, { warnings, problem: MISSING_MAKERNOTE });
  const serial = t.string(find(ifd, 0x001d)) || ctx.bodySerial;
  const count = t.int(find(ifd, 0x00a7));
  if (count == null || count === 4294965247) {
    return result(ctx, null, { serial, warnings, problem: "This Nikon photo does not contain a shutter count (some older or compact models don't record it)." });
  }
  const details = /\sZ/.test((ctx.model || '').toUpperCase()) ? [['Note', 'On Nikon Z cameras this count includes electronic-shutter shots.']] : [];
  return result(ctx, count, { serial, details, warnings });
}

const sonyA = /^(DSC-|Stellar|ILCE-(1|6100|6300|6400|6500|6600|6700|7C|7M3|7M4|7M5|7RM2|7RM3A?|7RM4A?|7RM5|7RM6|7SM2|7SM3|9|9M2)|ILCA-99M2|ILME-(FX2|FX3)|ZV-)/;
const sonyB = /^(ILCE-(6100A?|6300|6400A?|6500|6600|7C|7M3|7RM2|7RM3A?|7RM4A?|7SM2|9|9M2)|ILCA-99M2|ZV-E10)\b/;
const sonyC = /^(ILCE-(1\b|7M4|7RM5|7SM3)|ILME-FX3)/;
const sonyD = /^(ILCE-(6700|7CM2|7CR)|ILME-FX2|ZV-(E1|E10M2))\b/;
const sonyDMech = /^(ILCE-(1M2|7M5|7RM6))/;

function readSony(ctx, mn, warnings) {
  const model = ctx.model || '';
  if (model.startsWith('DSC-') || model.startsWith('ZV-1')) {
    return result(ctx, null, { warnings, problem: "Sony compact cameras (RX100, ZV-1 and similar) don't record a shutter count in photos." });
  }
  if (!mn) return result(ctx, null, { warnings, problem: MISSING_MAKERNOTE });
  const buf = ctx.tiff.buf, start = mn.valuePos;
  if (start < 0 || start + 14 > buf.length) return result(ctx, null, { warnings, problem: MISSING_MAKERNOTE });
  const ifdPos = ['SONY DSC ', 'SONY CAM ', 'SONY MOBILE'].some(h => startsWith(buf, start, h)) ? start + 12 : start;
  const t = new Tiff(buf, ctx.tiff.base, ctx.tiff.little);
  if (!t.looksLikeIfd(ifdPos)) t.little = !t.little;
  const ifd = t.readIfd(ifdPos);
  if (!ifd) return result(ctx, null, { warnings, problem: MISSING_MAKERNOTE });
  const raw = t.bytes(find(ifd, 0x9050));
  if (!raw) return result(ctx, null, { warnings, problem: 'This Sony photo has no shutter-count block. The model may not record it, or the file was resized by an app (use the original file).' });

  let table;
  if (!sonyA.test(model)) table = 'A';
  else if (sonyB.test(model)) table = 'B';
  else if (sonyC.test(model)) table = 'C';
  else if (sonyD.test(model)) table = 'D';
  else if (sonyDMech.test(model) && raw.length >= 5 && [0, 1, 2, 3, 4].every(i => raw[i] === 0)) table = 'D';
  if (!table) return result(ctx, null, { warnings, problem: `The ${displayName('Sony', model)} does not store a readable shutter count in photos (or uses the electronic shutter only).` });

  const d = sonyDecipher(raw);
  const u32 = o => (o + 4 <= d.length ? (d[o] | (d[o + 1] << 8) | (d[o + 2] << 16)) + d[o + 3] * 0x1000000 : null);
  const masked = o => { const v = u32(o); return v == null ? null : v % 0x1000000; };
  const details = [];
  let count = null;
  if (table === 'A') {
    const flash = ((d[0x31] || 0) & 1) === 1;
    const sc = masked(0x32);
    const old = /^(ILCE-(7(R|S|M2)?|[56]000|5100|QX1))\b/.test(model);
    const sc2 = !flash && old ? masked(0x4c) : null;
    let sc3 = null;
    if (/^(ILCE-(5100|QX1)|ILCA-(68|77M2))/.test(model)) sc3 = u32(0x1a0);
    else if (/^(SLT-A(58|99V?)|HV|NEX-(3N|5R|5T|6|VG900|VG30E)|ILCE-([35]000|3500))\b/.test(model)) sc3 = u32(0x1aa);
    else if (/^(SLT-A(37|57|65|77)V?|Lunar|NEX-(F3|5N|7|VG20E))/.test(model)) sc3 = u32(0x1bd);
    if (!(sc3 > 0)) sc3 = null;
    count = sc2 > 0 ? sc2 : sc3 != null && sc != null && sc3 > sc ? sc3 : sc;
    if (sc2 == null && flash && old) warnings.push('The flash fired in this photo. On this model the most accurate counter is skipped when the flash fires; take a new photo without flash for the best reading.');
    if (count != null && count < 65536 && sc2 == null && sc3 == null && model.startsWith('ILCE-7')) warnings.push('On this model the count can roll over after 65,536. If the camera looks heavily used, add 65,536.');
  } else if (table === 'B' || table === 'C') {
    count = masked(0x3a);
  } else {
    count = masked(0x0a);
    details.push(['Note', 'Counts mechanical-shutter shots only (electronic/silent shots are not included).']);
  }
  if (!count) return result(ctx, null, { warnings, problem: "Couldn't find a shutter count in this Sony photo. If you used the silent/electronic shutter, take a new photo with the mechanical shutter." });
  return result(ctx, count, { details, warnings });
}

/** "2025:03:18 14:55:02" → "18/03/2025 14:55" */
export function prettyExifDate(s) {
  const m = /^(\d{4}):(\d{2}):(\d{2})[ T](\d{2}):(\d{2})/.exec(s || '');
  return m ? `${m[3]}/${m[2]}/${m[1]} ${m[4]}:${m[5]}` : s;
}

export const fmtCount = n => String(Math.round(n)).replace(/\B(?=(\d{3})+(?!\d))/g, ' ');
