// Reading a camera over USB from the browser (WebUSB: Chrome and Edge on Android, Mac, Windows, Linux).
// Port of the Android app's PTP code. Only read-only operations are sent:
//  - Nikon, Sony, Canon R: the newest photo on the card (GetObjectHandles / GetObjectInfo / GetPartialObject),
//    then the same photo reader as the Photo tab.
//  - Older Canons: their own counter (property D1AC, directly or through the EOS event stream).
// Never sends Canon's 9052/9053 shutter-unit commands (an R50 showed Err 70 with them).

import { read, canonPhotoOffset } from './reader.js';

export const supported = typeof navigator !== 'undefined' && !!navigator.usb;

const VENDORS = [0x04a9 /* Canon */, 0x04b0 /* Nikon */, 0x054c /* Sony */];
const OP = {
  GetDeviceInfo: 0x1001, OpenSession: 0x1002, CloseSession: 0x1003, GetStorageIDs: 0x1004,
  GetObjectHandles: 0x1007, GetObjectInfo: 0x1008, GetObject: 0x1009, GetDevicePropValue: 0x1015, GetPartialObject: 0x101b,
  EosSetRemoteMode: 0x9114, EosSetEventMode: 0x9115, EosGetEvent: 0x9116, EosRequestProp: 0x9127,
};
const PROP_COUNTER = 0xd1ac, PROP_SERIAL = 0xd1af, EVT_PROP_CHANGED = 0xc189;
const RC_OK = 0x2001, RC_SESSION_OPEN = 0x201e;
const IMAGE_EXT = /\.(JPG|JPEG|NEF|NRW|ARW|SRF|SR2|CR2|CR3|HIF|HEIF)$/i;
const HEADER_BYTES = 512 * 1024, MAX_FULL = 80 * 1024 * 1024;

export class UsbError extends Error {
  constructor(code, message) { super(message); this.code = code; }
}

const le16 = (d, o) => d[o] | (d[o + 1] << 8);
const le32 = (d, o) => (d[o] | (d[o + 1] << 8) | (d[o + 2] << 16)) + d[o + 3] * 0x1000000;
function put32(a, o, v) { a[o] = v & 255; a[o + 1] = (v >>> 8) & 255; a[o + 2] = (v >>> 16) & 255; a[o + 3] = (v >>> 24) & 255; }

/** PTP dataset reader (little-endian, PTP strings are UCS-2 with a length byte). */
class Reader {
  constructor(d) { this.d = d; this.p = 0; }
  u8() { return this.p < this.d.length ? this.d[this.p++] : (this.p++, 0); }
  u16() { const v = this.p + 2 <= this.d.length ? le16(this.d, this.p) : 0; this.p += 2; return v; }
  u32() { const v = this.p + 4 <= this.d.length ? le32(this.d, this.p) : 0; this.p += 4; return v; }
  arr16() { const n = this.u32(); if (n > 4096) return []; return Array.from({ length: n }, () => this.u16()); }
  str() {
    const n = this.u8(); let s = '';
    for (let i = 0; i < n; i++) { const c = this.u16(); if (c) s += String.fromCharCode(c); }
    return s.trim();
  }
}

/** PTP over two bulk endpoints. [usb] is a USBDevice (or a fake with the same methods, for tests). */
export class Ptp {
  constructor(usb, epIn, epOut, timeoutMs = 15000) {
    this.usb = usb; this.epIn = epIn; this.epOut = epOut; this.tid = 0; this.pending = new Uint8Array(0); this.timeoutMs = timeoutMs;
  }
  async send(bytes) {
    const r = await this.usb.transferOut(this.epOut, bytes);
    if (r.status !== 'ok') throw new UsbError('io', "Couldn't send to the camera.");
  }
  async fill(n) {
    let empty = 0;
    while (this.pending.length < n) {
      const r = await withTimeout(this.usb.transferIn(this.epIn, 65536), this.timeoutMs);
      if (r.status === 'stall') { await this.usb.clearHalt('in', this.epIn); continue; }
      const chunk = r.data ? new Uint8Array(r.data.buffer, r.data.byteOffset, r.data.byteLength) : new Uint8Array(0);
      if (!chunk.length) { if (++empty > 8) throw new UsbError('timeout', 'The camera stopped responding.'); continue; }
      empty = 0;
      const merged = new Uint8Array(this.pending.length + chunk.length);
      merged.set(this.pending); merged.set(chunk, this.pending.length); this.pending = merged;
    }
  }
  async container() {
    await this.fill(12);
    const len = le32(this.pending, 0);
    if (len < 12 || len > 64 * 1024 * 1024) throw new UsbError('garbled', 'Garbled reply from the camera.');
    const type = le16(this.pending, 4), code = le16(this.pending, 6);
    await this.fill(len);
    const payload = this.pending.slice(12, len);
    this.pending = this.pending.slice(len);
    return { type, code, payload };
  }
  async transact(op, params = []) {
    const tid = ++this.tid;
    const cmd = new Uint8Array(12 + 4 * params.length);
    put32(cmd, 0, cmd.length); cmd[4] = 1; cmd[6] = op & 255; cmd[7] = op >> 8; put32(cmd, 8, tid);
    params.forEach((p, i) => put32(cmd, 12 + 4 * i, p));
    await this.send(cmd);
    const parts = [];
    for (;;) {
      const c = await this.container();
      if (c.type === 2) parts.push(c.payload);
      else if (c.type === 3) {
        const ps = []; for (let i = 0; i + 4 <= c.payload.length; i += 4) ps.push(le32(c.payload, i));
        const size = parts.reduce((s, p) => s + p.length, 0), data = new Uint8Array(size);
        let o = 0; for (const p of parts) { data.set(p, o); o += p.length; }
        return { code: c.code, ok: c.code === RC_OK, params: ps, data };
      } else if (c.type !== 4) throw new UsbError('garbled', 'Unexpected reply from the camera.');
    }
  }
}

function withTimeout(promise, ms) {
  let t; return Promise.race([promise, new Promise((_, rej) => { t = setTimeout(() => rej(new UsbError('timeout', 'The camera stopped responding.')), ms); })]).finally(() => clearTimeout(t));
}

export function parseDeviceInfo(d) {
  const r = new Reader(d);
  r.u16(); const ext = r.u32(); r.u16(); r.str(); r.u16();
  const operations = new Set(r.arr16()); r.arr16(); const properties = new Set(r.arr16()); r.arr16(); r.arr16();
  return { vendorExtensionId: ext, operations, properties, manufacturer: r.str(), model: r.str(), version: r.str(), serial: r.str() };
}

function parseObjectInfo(handle, d) {
  const r = new Reader(d);
  r.u32(); const format = r.u16(); r.u16(); const size = r.u32(); r.u16(); r.u32(); r.u32(); r.u32(); r.u32(); r.u32(); r.u32(); r.u32(); r.u16(); r.u32(); r.u32();
  return { handle, format, size, name: r.str(), captured: r.str() };
}
const array32 = d => { if (d.length < 4) return []; const n = le32(d, 0); if (4 + n * 4 > d.length) return []; return Array.from({ length: n }, (_, i) => le32(d, 4 + 4 * i)); };

/** Value bytes of a property-changed record in a Canon EOS GetEvent dataset. */
export function findProp(d, wanted) {
  let p = 0;
  while (p + 8 <= d.length) {
    const size = le32(d, p), type = le32(d, p + 4);
    if (size < 8 || type === 0 || p + size > d.length) break;
    if (type === EVT_PROP_CHANGED && size >= 12 && le32(d, p + 8) === wanted) return d.slice(p + 12, p + size);
    p += size;
  }
  return null;
}
const ascii = b => { let s = ''; for (const c of b) { if (!c) break; s += String.fromCharCode(c); } s = s.trim(); return /^[A-Za-z0-9]{4,32}$/.test(s) ? s : null; };
const isDeviceId = s => s.length >= 20 && /^[0-9a-f]+$/i.test(s);
const isCanonR = m => /\bEOS R\w*$/i.test((m || '').trim());

/** Asks the browser for the camera (shows Chrome's device chooser) and opens it. */
export async function connect() {
  if (!supported) throw new UsbError('unsupported', 'This browser cannot use USB.');
  let device;
  try {
    device = await navigator.usb.requestDevice({ filters: [...VENDORS.map(vendorId => ({ vendorId })), { classCode: 6 }] });
  } catch (e) {
    throw new UsbError('cancelled', 'No camera chosen.');
  }
  return open(device);
}

/** Cameras already allowed on this site (no chooser needed). */
export async function known() {
  if (!supported) return [];
  return (await navigator.usb.getDevices()).filter(d => VENDORS.includes(d.vendorId));
}

export async function open(device) {
  try { await device.open(); } catch (e) { throw new UsbError('busy', e.message); }
  if (!device.configuration) await device.selectConfiguration(1);
  // the still-image (PTP) interface; some Sony models use a vendor class with the same endpoints
  let pick = null;
  for (const intf of device.configuration.interfaces) for (const alt of intf.alternates) {
    const bulkIn = alt.endpoints.find(e => e.type === 'bulk' && e.direction === 'in');
    const bulkOut = alt.endpoints.find(e => e.type === 'bulk' && e.direction === 'out');
    if (!bulkIn || !bulkOut) continue;
    const score = alt.interfaceClass === 6 ? 2 : alt.interfaceClass === 0xff ? 1 : 0;
    if (score && (!pick || score > pick.score)) pick = { score, intf, alt, bulkIn, bulkOut };
  }
  if (!pick) { await device.close().catch(() => {}); throw new UsbError('nointerface', 'This device has no camera (PTP) connection.'); }
  try {
    await device.claimInterface(pick.intf.interfaceNumber);
    if (pick.intf.alternates.length > 1) await device.selectAlternateInterface(pick.intf.interfaceNumber, pick.alt.alternateSetting);
  } catch (e) {
    await device.close().catch(() => {});
    throw new UsbError('busy', e.message);   // another program (macOS, Windows driver) holds the camera
  }
  return { device, ptp: new Ptp(device, pick.bulkIn.endpointNumber, pick.bulkOut.endpointNumber), iface: pick.intf.interfaceNumber };
}

/**
 * Reads the shutter count. [status] gets short progress texts.
 * Returns { camera, serial, count, method, reading?, photoFile?, photoDate?, problem?, log }.
 */
export async function readCamera(conn, status = () => {}) {
  const { ptp } = conn, log = [];
  const hex = v => v.toString(16).toUpperCase().padStart(4, '0');
  try {
    status('session');
    let r = await ptp.transact(OP.OpenSession, [1]);
    if (!r.ok && r.code !== RC_SESSION_OPEN) throw new UsbError('refused', `Camera refused the connection (${hex(r.code)}).`);
    r = await ptp.transact(OP.GetDeviceInfo);
    if (!r.ok) throw new UsbError('refused', 'The camera did not describe itself.');
    const info = parseDeviceInfo(r.data);
    const model = info.model || 'Camera';
    const isCanon = /canon/i.test(info.manufacturer) || info.vendorExtensionId === 11;
    const camera = isCanon && !/^canon/i.test(model) ? 'Canon ' + model : model;
    const usbSerial = info.serial && !isDeviceId(info.serial) ? info.serial : null;
    log.push(`Camera: ${info.manufacturer} / ${model} / firmware ${info.version}`);

    if (!isCanon || canonPhotoOffset(camera) != null) {
      status('photo');
      return { ...(await newestPhoto(ptp, info, camera, usbSerial, log)), log };
    }
    if (isCanonR(camera) || /^3-/.test((info.version || '').trim())) {   // newer generation: count is in its JPGs; the RP and 250D froze on the event commands
      return { camera, serial: usbSerial, count: null, method: null, log,
        problem: 'canon-r-unknown' };
    }
    status('counter');
    const res = await canonCounter(ptp, info.operations, log);
    return { camera, serial: res.serial || usbSerial, count: res.count, method: res.method, log, problem: res.count == null ? 'canon-no-counter' : null };
  } finally {
    try { await ptp.transact(OP.CloseSession); } catch {}
  }
}

async function newestPhoto(ptp, info, camera, usbSerial, log) {
  const ops = info.operations, hex = v => v.toString(16).toUpperCase().padStart(4, '0');
  let r = await ptp.transact(OP.GetStorageIDs);
  log.push(`GetStorageIDs: ${hex(r.code)}`);
  const storages = r.ok ? array32(r.data).filter(s => (s & 0xffff) !== 0) : [];
  const handles = [];
  for (const sid of storages) {
    r = await ptp.transact(OP.GetObjectHandles, [sid, 0, 0]);
    if (!r.ok || !array32(r.data).length) r = await ptp.transact(OP.GetObjectHandles, [sid, 0, 0xffffffff]);
    const h = r.ok ? array32(r.data) : [];
    log.push(`GetObjectHandles: ${hex(r.code)}, ${h.length} objects`);
    handles.push(...h);
  }
  const infos = [];
  for (const h of handles.sort((a, b) => a - b).slice(-40).reverse()) {
    const ir = await ptp.transact(OP.GetObjectInfo, [h]);
    if (!ir.ok) continue;
    const oi = parseObjectInfo(h, ir.data);
    if (oi.format !== 0x3001 && IMAGE_EXT.test(oi.name)) infos.push(oi);
  }
  if (!infos.length) return { camera, serial: usbSerial, count: null, method: 'photo', problem: 'no-photo' };
  infos.sort((a, b) => (b.captured.localeCompare(a.captured)) || (b.handle - a.handle));
  const best = infos.find(i => i.captured === infos[0].captured && /\.jpe?g$/i.test(i.name)) || infos[0];
  log.push(`Newest photo: ${best.name} (${best.captured}, ${best.size} bytes)`);
  let bytes = null, partial = false;
  if (ops.has(OP.GetPartialObject)) {
    r = await ptp.transact(OP.GetPartialObject, [best.handle, 0, HEADER_BYTES]);
    log.push(`GetPartialObject: ${hex(r.code)}, ${r.data.length} bytes`);
    if (r.ok && r.data.length) { bytes = r.data; partial = r.data.length < best.size; }
  }
  let reading = bytes ? read(bytes) : null;
  if ((!reading || (reading.count == null && partial)) && ops.has(OP.GetObject) && best.size <= MAX_FULL) {
    r = await ptp.transact(OP.GetObject, [best.handle]);
    log.push(`GetObject: ${hex(r.code)}, ${r.data.length} bytes`);
    if (r.ok && r.data.length) reading = read(r.data);
  }
  if (!reading) return { camera, serial: usbSerial, count: null, method: 'photo', problem: 'no-transfer', photoFile: best.name };
  const m = /^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})/.exec(best.captured);
  return {
    camera: reading.make ? reading.displayName : camera, serial: reading.serial || usbSerial, count: reading.count, method: 'photo', reading,
    photoFile: best.name, photoDate: m ? `${m[3]}/${m[2]}/${m[1]} ${m[4]}:${m[5]}` : null,
    problem: reading.count == null ? reading.problem : null,
  };
}

/** Older Canon EOS bodies: property D1AC, then the EOS event stream (which also carries the body serial). */
async function canonCounter(ptp, ops, log) {
  const hex = v => v.toString(16).toUpperCase().padStart(4, '0');
  let count = null, serial = null, method = null;
  let r = await ptp.transact(OP.GetDevicePropValue, [PROP_COUNTER]);
  log.push(`GetDevicePropValue D1AC: ${hex(r.code)}, ${r.data.length} bytes`);
  if (r.ok && r.data.length >= 4 && le32(r.data, 0) >= 1 && le32(r.data, 0) <= 20000000) { count = le32(r.data, 0); method = 'property'; }
  if ([OP.EosSetRemoteMode, OP.EosSetEventMode, OP.EosGetEvent].every(o => ops.has(o))) {
    const remote = await ptp.transact(OP.EosSetRemoteMode, [1]);
    const event = await ptp.transact(OP.EosSetEventMode, [1]);
    log.push(`SetRemoteMode: ${hex(remote.code)}, SetEventMode: ${hex(event.code)}`);
    try {
      if (remote.ok && event.ok) {
        if (ops.has(OP.EosRequestProp)) for (const p of [PROP_COUNTER, PROP_SERIAL]) {
          const q = await ptp.transact(OP.EosRequestProp, [p]); log.push(`RequestDevicePropValue ${hex(p)}: ${hex(q.code)}`);
        }
        for (let i = 0; i < 4; i++) {
          const e = await ptp.transact(OP.EosGetEvent);
          if (!e.ok) { log.push(`GetEvent: ${hex(e.code)}`); break; }
          const c = findProp(e.data, PROP_COUNTER);
          if (count == null && c && c.length >= 4 && le32(c, 0) >= 1 && le32(c, 0) <= 20000000) { count = le32(c, 0); method = 'event'; }
          const s = findProp(e.data, PROP_SERIAL);
          if (!serial && s) serial = ascii(s);
          log.push(`GetEvent #${i + 1}: ${e.data.length} bytes`);
          if ((count != null && serial) || e.data.length <= 8) break;
        }
      }
    } finally {
      await ptp.transact(OP.EosSetEventMode, [0]).catch(() => {});
      await ptp.transact(OP.EosSetRemoteMode, [0]).catch(() => {});
    }
  } else log.push('EOS event stream: not offered');
  return { count, serial, method };
}

export async function disconnect(conn) {
  try { await conn.device.releaseInterface(conn.iface); } catch {}
  try { await conn.device.close(); } catch {}
}

/** Which system the visitor is on, for the right "the camera is busy" help. */
export function platform() {
  const ua = navigator.userAgent || '';
  if (/Android/i.test(ua)) return 'android';
  if (/Mac OS X/i.test(ua)) return 'mac';
  if (/Windows/i.test(ua)) return 'windows';
  if (/Linux|CrOS/i.test(ua)) return 'linux';
  return 'other';
}
