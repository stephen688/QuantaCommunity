/**
 * Generate 81×81 PNG tabBar icons from SVG sources (app.json requires PNG).
 * Run: node scripts/gen-tabbar-png.mjs
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { deflateSync } from 'node:zlib';

const __dirname = dirname(fileURLToPath(import.meta.url));
const outDir = join(__dirname, '../miniprogram/assets/tabbar');

const ICONS = [
  'home',
  'home-active',
  'follow',
  'follow-active',
  'message',
  'message-active',
  'mine',
  'mine-active',
];

function crc32(buf) {
  let c = ~0;
  for (let i = 0; i < buf.length; i += 1) {
    c ^= buf[i];
    for (let k = 0; k < 8; k += 1) {
      c = c & 1 ? (0xedb88320 ^ (c >>> 1)) : c >>> 1;
    }
  }
  return ~c >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length, 0);
  const typeBuf = Buffer.from(type, 'ascii');
  const crcBuf = Buffer.alloc(4);
  crcBuf.writeUInt32BE(crc32(Buffer.concat([typeBuf, data])), 0);
  return Buffer.concat([len, typeBuf, data, crcBuf]);
}

function parseHexColor(hex) {
  const h = hex.replace('#', '');
  return [parseInt(h.slice(0, 2), 16), parseInt(h.slice(2, 4), 16), parseInt(h.slice(4, 6), 16)];
}

function setPixel(rgba, size, x, y, rgb, alpha = 255) {
  if (x < 0 || y < 0 || x >= size || y >= size) return;
  const i = (y * size + x) * 4;
  rgba[i] = rgb[0];
  rgba[i + 1] = rgb[1];
  rgba[i + 2] = rgb[2];
  rgba[i + 3] = alpha;
}

function drawLine(rgba, size, x0, y0, x1, y1, rgb, width) {
  const dx = Math.abs(x1 - x0);
  const dy = Math.abs(y1 - y0);
  const sx = x0 < x1 ? 1 : -1;
  const sy = y0 < y1 ? 1 : -1;
  let err = dx - dy;
  let x = x0;
  let y = y0;
  while (true) {
    for (let oy = -Math.floor(width / 2); oy <= Math.floor(width / 2); oy += 1) {
      for (let ox = -Math.floor(width / 2); ox <= Math.floor(width / 2); ox += 1) {
        setPixel(rgba, size, x + ox, y + oy, rgb);
      }
    }
    if (x === x1 && y === y1) break;
    const e2 = 2 * err;
    if (e2 > -dy) {
      err -= dy;
      x += sx;
    }
    if (e2 < dx) {
      err += dx;
      y += sy;
    }
  }
}

function strokeColorFromSvg(svg) {
  const m = svg.match(/stroke="(#[0-9a-fA-F]{6})"/);
  return m ? m[1] : '#8c939e';
}

function renderSvgToRgba(svg, size) {
  const rgba = new Uint8Array(size * size * 4);
  const color = parseHexColor(strokeColorFromSvg(svg));
  const s = size / 24;
  const stroke = svg.includes('stroke-width="2"') ? 2.2 * s : 1.9 * s;

  const line = (x0, y0, x1, y1) => drawLine(rgba, size, Math.round(x0 * s), Math.round(y0 * s), Math.round(x1 * s), Math.round(y1 * s), color, stroke);
  const circle = (cx, cy, r) => {
    const steps = 64;
    for (let i = 0; i < steps; i += 1) {
      const a0 = (i / steps) * Math.PI * 2;
      const a1 = ((i + 1) / steps) * Math.PI * 2;
      line(cx + Math.cos(a0) * r, cy + Math.sin(a0) * r, cx + Math.cos(a1) * r, cy + Math.sin(a1) * r);
    }
  };

  if (svg.includes('M3 10.5')) {
    line(3, 10.5, 12, 3);
    line(12, 3, 21, 10.5);
    line(5, 10, 5, 20);
    line(5, 20, 19, 20);
    line(19, 20, 19, 10);
  } else if (svg.includes('M16 21v-2')) {
    circle(9, 7, 4);
    line(5, 17, 5, 21);
    line(5, 21, 13, 21);
    line(13, 21, 13, 17);
    circle(16, 3.13, 4);
    line(18, 17, 18, 21);
    line(18, 21, 22, 21);
    line(22, 21, 22, 17.13);
  } else if (svg.includes('M7.9 20')) {
    circle(12, 11, 9);
    line(4, 16.1, 2, 22);
    line(2, 22, 7.9, 20);
  } else if (svg.includes('M6 20v-1')) {
    circle(12, 8, 4);
    line(6, 19, 6, 20);
    line(6, 20, 18, 20);
    line(18, 20, 18, 19);
  }

  return rgba;
}

function encodePng(rgba, width, height) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8;
  ihdr[9] = 6;
  ihdr[10] = 0;
  ihdr[11] = 0;
  ihdr[12] = 0;

  const stride = width * 4 + 1;
  const raw = Buffer.alloc(stride * height);
  for (let y = 0; y < height; y += 1) {
    const rowStart = y * stride;
    raw[rowStart] = 0;
    for (let x = 0; x < width; x += 1) {
      const src = (y * width + x) * 4;
      const dst = rowStart + 1 + x * 4;
      raw[dst] = rgba[src];
      raw[dst + 1] = rgba[src + 1];
      raw[dst + 2] = rgba[src + 2];
      raw[dst + 3] = rgba[src + 3];
    }
  }

  const signature = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
  return Buffer.concat([
    signature,
    chunk('IHDR', ihdr),
    chunk('IDAT', deflateSync(raw)),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

for (const name of ICONS) {
  const svg = readFileSync(join(outDir, `${name}.svg`), 'utf8');
  const png = encodePng(renderSvgToRgba(svg, 81), 81, 81);
  writeFileSync(join(outDir, `${name}.png`), png);
}

console.log(`Generated ${ICONS.length} PNG icons in ${outDir}`);
