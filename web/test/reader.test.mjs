// node web/test/reader.test.mjs [photos...]  — synthetic checks plus any real photos given.
import { read, sonyEncipher, sonyDecipher, displayName, rating, wear } from '../reader.js';
import fs from 'node:fs';
import assert from 'node:assert/strict';

class Buf {
  constructor() { this.b = new Uint8Array(16384); this.pos = 0; }
  put(a) { for (const x of a) this.b[this.pos++] = x; }
  u16(v) { this.put([v & 255, (v >> 8) & 255]); }
  u32(v) { this.put([v & 255, (v >> 8) & 255, (v >> 16) & 255, (v >>> 24) & 255]); }
  str(s) { this.put([...Buffer.from(s, 'latin1'), 0]); }
  entry(tag, type, n, v) { this.u16(tag); this.u16(type); this.u32(n); this.u32(v); }
}
function sony(model, offset, count, header = true) {
  const t = new Buf();
  t.put([...Buffer.from('II')]); t.u16(42); t.u32(8);
  t.pos = 8; t.u16(3);
  t.entry(0x10f, 2, 5, 200); t.entry(0x110, 2, model.length + 1, 220); t.entry(0x8769, 4, 1, 300); t.u32(0);
  t.pos = 200; t.str('SONY'); t.pos = 220; t.str(model);
  t.pos = 300; t.u16(1); t.entry(0x927c, 7, (header ? 12 : 0) + 18, 400); t.u32(0);
  t.pos = 400;
  if (header) t.put([...Buffer.from('SONY DSC '), 0, 0, 0]);
  t.u16(1); t.entry(0x9050, 7, 944, 1000); t.u32(0);
  const plain = new Uint8Array(944);
  for (let i = 0; i < 3; i++) plain[offset + i] = (count >> (8 * i)) & 255;
  plain[offset + 3] = 0x55;
  t.pos = 1000; t.put(sonyEncipher(plain));
  return t.b;
}
function canon(model, offset, count) {
  const t = new Buf();
  t.put([...Buffer.from('II')]); t.u16(42); t.u32(8);
  t.pos = 8; t.u16(3);
  t.entry(0x10f, 2, 6, 200); t.entry(0x110, 2, model.length + 1, 220); t.entry(0x8769, 4, 1, 300); t.u32(0);
  t.pos = 200; t.str('Canon'); t.pos = 220; t.str(model);
  t.pos = 300; t.u16(1); t.entry(0x927c, 7, 30, 400); t.u32(0);
  t.pos = 400; t.u16(2); t.entry(0x000c, 4, 1, 123456789); t.entry(0x000d, 7, offset + 64, 1000); t.u32(0);
  t.pos = 1000 + offset; t.u32(count);
  return t.b;
}

const all = new Uint8Array(256).map((_, i) => i);
assert.deepEqual(sonyDecipher(sonyEncipher(all)), all);
assert.equal(sonyEncipher(new Uint8Array([7]))[0], 0x5e);
for (const [m, o, c] of [['ILCE-7M3', 0x3a, 23456], ['ILCE-7M4', 0x3a, 777], ['ILCE-6400', 0x3a, 9999], ['ILCE-6700', 0x0a, 4321], ['ILCE-6000', 0x4c, 87654], ['SLT-A99V', 0x32, 5000]])
  for (const h of [true, false]) assert.equal(read(sony(m, o, c, h)).count, c, m);
assert.equal(read(sony('ILCE-9M3', 0x3a, 10)).count, null);
assert.equal(read(canon('Canon EOS R50', 0x0d29, 12345)).count, 12345);
assert.equal(read(canon('Canon EOS R100', 0x0acf, 623)).count, 623);
assert.equal(read(canon('Canon EOS RP', 0x0960, 2347)).count, 2347);
assert.equal(read(canon('Canon EOS M50m2', 0x0acf, 8912)).count, 8912);
assert.equal(read(canon('Canon EOS R6', 0x0af1, 45678)).count, 45678);
assert.equal(read(canon('Canon EOS R8', 0x0d29, 777)).serial, '123456789');
assert.equal(read(canon('Canon EOS 5D Mark IV', 0x0d29, 5)).count, null);
assert.equal(read(new Uint8Array(0)).count, null);
assert.equal(read(new Uint8Array(5000).map((_, i) => (i * 31) & 255)).count, null);
assert.deepEqual(rating('Nikon', 'NIKON D750'), { value: 150000, official: true });
assert.deepEqual(rating('Canon', 'Canon EOS 4000D'), { value: 100000, official: false });
assert.equal(rating('Nikon', 'NIKON Z 8'), null);
assert.equal(displayName('Nikon', 'NIKON Z 6_2'), 'Nikon Z6 II');
assert.equal(displayName('Sony', 'ILCE-7M3'), 'Sony α7 III');
assert.equal(wear(5000, 100000).fr, 'Comme neuf');
assert.equal(wear(120000, 100000).key, 'beyond');
console.log('synthetic: ok');

for (const f of process.argv.slice(2)) {
  const r = read(new Uint8Array(fs.readFileSync(f)));
  console.log(f.split('/').pop(), '→', r.displayName, '| count', r.count, '| SN', r.serial, '|', r.problem || '');
}
