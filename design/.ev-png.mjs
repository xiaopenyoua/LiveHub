// Evidence Collector · 自带 PNG 解码/编码（不复用 design/.png.mjs，独立实现）
import { deflateSync, inflateSync } from 'node:zlib';

const T = (() => { const t = new Int32Array(256);
  for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1; t[n] = c; }
  return t; })();
const crc32 = b => { let c = ~0; for (let i = 0; i < b.length; i++) c = T[(c ^ b[i]) & 255] ^ (c >>> 8); return (~c) >>> 0; };
const ck = (type, data) => {
  const l = Buffer.alloc(4); l.writeUInt32BE(data.length);
  const b = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const c = Buffer.alloc(4); c.writeUInt32BE(crc32(b));
  return Buffer.concat([l, b, c]);
};

/** 返回 {w,h,ch,ct,bd,interlace,data} ；ch = 通道数 */
export function decodePNG(buf) {
  if (buf.readUInt32BE(0) !== 0x89504E47 || buf.readUInt32BE(4) !== 0x0D0A1A0A) throw new Error('not png');
  let off = 8, w, h, ct, bd, interlace = 0, idat = [], trns = null;
  while (off + 8 <= buf.length) {
    const len = buf.readUInt32BE(off), type = buf.toString('ascii', off + 4, off + 8);
    const d = buf.subarray(off + 8, off + 8 + len);
    if (type === 'IHDR') { w = d.readUInt32BE(0); h = d.readUInt32BE(4); bd = d[8]; ct = d[9]; interlace = d[12]; }
    else if (type === 'IDAT') idat.push(d);
    else if (type === 'tRNS') trns = d;
    else if (type === 'IEND') break;
    off += 12 + len;
  }
  if (bd !== 8) throw new Error('bitDepth ' + bd);
  const ch = { 0: 1, 2: 3, 4: 2, 6: 4 }[ct];
  if (!ch) throw new Error('colorType ' + ct);
  if (interlace) throw new Error('interlaced');
  const raw = inflateSync(Buffer.concat(idat));
  const stride = w * ch, out = Buffer.alloc(h * stride);
  let p = 0;
  for (let y = 0; y < h; y++) {
    const ft = raw[p++];
    const line = raw.subarray(p, p + stride); p += stride;
    const cur = out.subarray(y * stride, (y + 1) * stride);
    const prev = y ? out.subarray((y - 1) * stride, y * stride) : Buffer.alloc(stride);
    for (let i = 0; i < stride; i++) {
      const a = i >= ch ? cur[i - ch] : 0, b = prev[i], c = i >= ch ? prev[i - ch] : 0;
      let v = line[i];
      if (ft === 1) v += a; else if (ft === 2) v += b;
      else if (ft === 3) v += (a + b) >> 1;
      else if (ft === 4) { const q = a + b - c, pa = Math.abs(q - a), pb = Math.abs(q - b), pc = Math.abs(q - c); v += (pa <= pb && pa <= pc) ? a : (pb <= pc ? b : c); }
      else if (ft !== 0) throw new Error('filter ' + ft);
      cur[i] = v & 255;
    }
  }
  return { w, h, ch, ct, bd, interlace, hasTRNS: !!trns, data: out };
}

/** 统一成 RGBA 缓冲 */
export function toRGBA(img) {
  const { w, h, ch, data } = img, o = Buffer.alloc(w * h * 4);
  for (let i = 0; i < w * h; i++) {
    if (ch === 4) { o[i * 4] = data[i * 4]; o[i * 4 + 1] = data[i * 4 + 1]; o[i * 4 + 2] = data[i * 4 + 2]; o[i * 4 + 3] = data[i * 4 + 3]; }
    else if (ch === 3) { o[i * 4] = data[i * 3]; o[i * 4 + 1] = data[i * 3 + 1]; o[i * 4 + 2] = data[i * 3 + 2]; o[i * 4 + 3] = 255; }
    else if (ch === 1) { const g = data[i]; o[i * 4] = g; o[i * 4 + 1] = g; o[i * 4 + 2] = g; o[i * 4 + 3] = 255; }
    else { o[i * 4] = o[i * 4 + 1] = o[i * 4 + 2] = data[i * 2]; o[i * 4 + 3] = data[i * 2 + 1]; }
  }
  return o;
}

export function encodeRGB(w, h, rgb) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 2;
  const stride = w * 3, raw = Buffer.alloc((stride + 1) * h);
  for (let y = 0; y < h; y++) { raw[y * (stride + 1)] = 0; rgb.copy(raw, y * (stride + 1) + 1, y * stride, (y + 1) * stride); }
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
    ck('IHDR', ihdr), ck('IDAT', deflateSync(raw, { level: 9 })), ck('IEND', Buffer.alloc(0))]);
}
