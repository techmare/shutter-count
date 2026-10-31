// Simulated cameras speaking PTP, to test ptp-usb.js without hardware.
import assert from 'node:assert/strict';
import { readCamera, Ptp, findProp } from '../ptp-usb.js';

const u16 = v => [v & 255, (v >> 8) & 255], u32 = v => [v & 255, (v >>> 8) & 255, (v >>> 16) & 255, (v >>> 24) & 255];
const pstr = s => s ? [s.length + 1, ...[...s].flatMap(c => u16(c.charCodeAt(0))), 0, 0] : [0];
const arr16 = a => [...u32(a.length), ...a.flatMap(u16)];
const arr32 = a => [...u32(a.length), ...a.flatMap(u32)];
function container(type, code, tid, payload = []) { return new Uint8Array([...u32(12 + payload.length), ...u16(type), ...u16(code), ...u32(tid), ...payload]); }

function canonTiff(model, offset, count, serial) {
  const b = new Uint8Array(16384); let p = 0;
  const put = a => { for (const x of a) b[p++] = x; };
  const entry = (t, ty, n, v) => put([...u16(t), ...u16(ty), ...u32(n), ...u32(v)]);
  put([73, 73, ...u16(42), ...u32(8)]); p = 8; put(u16(3));
  entry(0x10f, 2, 6, 200); entry(0x110, 2, model.length + 1, 220); entry(0x8769, 4, 1, 300); put(u32(0));
  p = 200; put([...Buffer.from('Canon'), 0]); p = 220; put([...Buffer.from(model), 0]);
  p = 300; put(u16(1)); entry(0x927c, 7, 30, 400); put(u32(0));
  p = 400; put(u16(2)); entry(0x000c, 4, 1, serial); entry(0x000d, 7, offset + 64, 1000); put(u32(0));
  p = 1000 + offset; put(u32(count));
  return b;
}

class FakeCamera {
  constructor({ manufacturer = 'Canon.Inc', ext = 11, model, ops, photo, d1ac, events, serial = 'a'.repeat(32) }) {
    Object.assign(this, { manufacturer, ext, model, ops, photo, d1ac, events, serial });
    this.out = []; this.sent = [];
  }
  async transferOut(ep, data) {
    const d = data instanceof Uint8Array ? data : new Uint8Array(data);
    const op = d[6] | (d[7] << 8), tid = d[8] | (d[9] << 8), params = [];
    for (let i = 12; i + 4 <= d.length; i += 4) params.push(d[i] | (d[i + 1] << 8) | (d[i + 2] << 16) | (d[i + 3] << 24) >>> 0);
    this.sent.push(op);
    const reply = (code, data, ps = []) => {
      const parts = [];
      if (data) parts.push(container(2, op, tid, [...data]));
      parts.push(container(3, code, tid, ps.flatMap(u32)));
      // hand it out in awkward 7-byte and 512-byte pieces, like a real pipe can
      const all = Uint8Array.from(parts.flatMap(x => [...x]));
      for (let i = 0; i < all.length;) { const n = this.out.length % 2 ? 7 : 512; this.out.push(all.slice(i, i + n)); i += n; }
    };
    switch (op) {
      case 0x1002: case 0x1003: return reply(0x2001), { status: 'ok' };
      case 0x1001: return reply(0x2001, [...u16(100), ...u32(this.ext), ...u16(100), ...pstr(''), ...u16(0), ...arr16(this.ops), ...arr16([]), ...arr16([0xd303]), ...arr16([]), ...arr16([]), ...pstr(this.manufacturer), ...pstr(this.model), ...pstr('1.0.0'), ...pstr(this.serial)]), { status: 'ok' };
      case 0x1004: return reply(0x2001, arr32([0x00010001])), { status: 'ok' };
      case 0x1007: return reply(0x2001, arr32(this.photo ? [5, 6] : [])), { status: 'ok' };
      case 0x1008: {
        const name = params[0] === 6 ? 'IMG_0412.JPG' : 'IMG_0411.CR3';
        return reply(0x2001, [...u32(0x00010001), ...u16(0x3801), ...u16(0), ...u32(this.photo.length), ...u16(0), ...u32(0), ...u32(0), ...u32(0), ...u32(0), ...u32(0), ...u32(0), ...u32(0), ...u16(0), ...u32(0), ...u32(0), ...pstr(name), ...pstr('20261030T235900')]), { status: 'ok' };
      }
      case 0x101b: assert.equal(params[0], 6, 'reads the JPEG of the pair'); return reply(0x2001, this.photo.slice(0, params[2])), { status: 'ok' };
      case 0x1015: return this.d1ac == null ? reply(0x2005) : reply(0x2001, u32(this.d1ac)), { status: 'ok' };
      case 0x9114: case 0x9115: case 0x9127: return reply(0x2001), { status: 'ok' };
      case 0x9116: return reply(0x2001, this.events.shift() || [...u32(8), ...u32(0)]), { status: 'ok' };
      default: return reply(0x2005), { status: 'ok' };
    }
  }
  async transferIn() { const d = this.out.shift(); return { status: 'ok', data: new DataView((d || new Uint8Array(0)).buffer) }; }
}
const conn = cam => ({ ptp: new Ptp(cam, 1, 2), device: cam, iface: 0 });
const STD = [0x1001, 0x1002, 0x1003, 0x1004, 0x1007, 0x1008, 0x1009, 0x101b, 0x1015];

// 1. Canon R50: newest photo, JPEG of a RAW+JPEG pair, read with the photo reader
let cam = new FakeCamera({ model: 'Canon EOS R50', ops: STD, photo: canonTiff('Canon EOS R50', 0x0d29, 12345, 123456789) });
let r = await readCamera(conn(cam));
assert.equal(r.count, 12345); assert.equal(r.serial, '123456789'); assert.equal(r.photoFile, 'IMG_0412.JPG'); assert.equal(r.photoDate, '30/10/2026 23:59');
assert.ok(!cam.sent.includes(0x9052) && !cam.sent.includes(0x9114), 'no Canon vendor commands for a photo read');
console.log('R50 over USB:', r.count, r.serial, r.photoFile);

// 2. Canon 4000D: D1AC not supported directly, counter + serial through EOS events
const ev = [...u32(16), ...u32(0xc189), ...u32(0xd1ac), ...u32(535), ...u32(25), ...u32(0xc189), ...u32(0xd1af), ...Buffer.from('283032001879'), 0, ...u32(8), ...u32(0)];
cam = new FakeCamera({ model: 'Canon EOS 4000D', ops: [...STD, 0x9114, 0x9115, 0x9116, 0x9127], events: [ev] });
r = await readCamera(conn(cam));
assert.equal(r.count, 535); assert.equal(r.serial, '283032001879'); assert.equal(r.method, 'event');
assert.deepEqual(cam.sent.slice(-3), [0x9115, 0x9114, 0x1003], 'event and remote mode switched back off, session closed');
assert.ok(!cam.sent.includes(0x9052));
console.log('4000D over USB:', r.count, r.serial, r.method);

// 3. Canon EOS R (no known spot): nothing beyond DeviceInfo is sent
cam = new FakeCamera({ model: 'Canon EOS R', ops: [...STD, 0x9114, 0x9115, 0x9116], photo: null });
r = await readCamera(conn(cam));
assert.equal(r.count, null); assert.equal(r.problem, 'canon-r-unknown');
assert.deepEqual(cam.sent, [0x1002, 0x1001, 0x1003]);
console.log('EOS R: refused safely');

// 4. Nikon with no photo on the card
cam = new FakeCamera({ manufacturer: 'Nikon Corporation', ext: 10, model: 'D3500', ops: STD, photo: null });
r = await readCamera(conn(cam));
assert.equal(r.problem, 'no-photo');
console.log('usb: ok');
