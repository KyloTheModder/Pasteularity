// A small MP4 writer for H.264 chunks from WebCodecs. It puts the index before the media data,
// so the file starts playing before it has fully downloaded.

const text = (s) => new TextEncoder().encode(s);

function concat(parts) {
  const size = parts.reduce((n, p) => n + p.length, 0);
  const out = new Uint8Array(size);
  let at = 0;
  for (const p of parts) { out.set(p, at); at += p.length; }
  return out;
}

function u32(...values) {
  const out = new Uint8Array(values.length * 4);
  const view = new DataView(out.buffer);
  values.forEach((v, i) => view.setUint32(i * 4, v));
  return out;
}

function u16(...values) {
  const out = new Uint8Array(values.length * 2);
  const view = new DataView(out.buffer);
  values.forEach((v, i) => view.setUint16(i * 2, v));
  return out;
}

function box(type, ...children) {
  const body = concat(children);
  return concat([u32(body.length + 8), text(type), body]);
}

const fullBox = (type, version, flags, ...children) => box(type, u32((version << 24) | flags), ...children);
const zeros = (n) => new Uint8Array(n);
const MATRIX = u32(0x10000, 0, 0, 0, 0x10000, 0, 0, 0, 0x40000000);

/** frames: [{ data: Uint8Array, key: boolean }] at a constant rate. avcC is the decoder description. */
export function buildMp4({ frames, width, height, fps, avcC }) {
  const timescale = fps * 1000;
  const delta = 1000;
  const duration = frames.length * delta;

  const stsd = fullBox('stsd', 0, 0, u32(1), box('avc1',
    zeros(6), u16(1), zeros(16), u16(width, height), u32(0x480000, 0x480000), zeros(4), u16(1), zeros(32), u16(0x18, 0xffff),
    box('avcC', avcC),
    // BT.709, limited range, which is what the browser's RGB to YUV conversion produces.
    box('colr', text('nclx'), u16(1, 1, 1), new Uint8Array([0])),
    box('pasp', u32(1, 1)),
  ));
  const keys = frames.flatMap((f, i) => (f.key ? [i + 1] : []));
  const stts = fullBox('stts', 0, 0, u32(1, frames.length, delta));
  const stss = fullBox('stss', 0, 0, u32(keys.length, ...keys));
  const stsc = fullBox('stsc', 0, 0, u32(1, 1, frames.length, 1));
  const stsz = fullBox('stsz', 0, 0, u32(0, frames.length, ...frames.map((f) => f.data.length)));

  const moovWith = (mediaOffset) => box('moov',
    fullBox('mvhd', 0, 0, u32(0, 0, timescale, duration, 0x10000), u16(0x100, 0), zeros(8), MATRIX, zeros(24), u32(2)),
    box('trak',
      fullBox('tkhd', 0, 3, u32(0, 0, 1, 0, duration), zeros(8), u16(0, 0, 0, 0), MATRIX, u32(width << 16, height << 16)),
      box('mdia',
        fullBox('mdhd', 0, 0, u32(0, 0, timescale, duration), u16(0x55c4, 0)),
        fullBox('hdlr', 0, 0, u32(0), text('vide'), zeros(12), text('VideoHandler\0')),
        box('minf',
          fullBox('vmhd', 0, 1, zeros(8)),
          box('dinf', fullBox('dref', 0, 0, u32(1), fullBox('url ', 0, 1))),
          box('stbl', stsd, stts, stss, stsc, stsz, fullBox('stco', 0, 0, u32(1, mediaOffset))),
        ),
      ),
    ),
  );

  const ftyp = box('ftyp', text('isom'), u32(0x200), text('isom'), text('iso2'), text('avc1'), text('mp41'));
  const mediaOffset = ftyp.length + moovWith(0).length + 8;
  const mediaSize = frames.reduce((n, f) => n + f.data.length, 0) + 8;
  const header = u32(mediaSize);
  return new Blob([ftyp, moovWith(mediaOffset), header, text('mdat'), ...frames.map((f) => f.data)], { type: 'video/mp4' });
}
