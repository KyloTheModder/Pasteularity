// Cuts every button and label out of docs/interactive-preview.html as a transparent sprite and
// records where it sits, so the 3D scene can fade each one in by itself. It also captures the
// other look of anything that changes on screen, like a selected and unselected choice.
// Run with: node tools/intro-video/capture-parts.mjs
import { spawn } from 'node:child_process';
import { mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { findBrowser } from './browser.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const out = join(here, 'build', 'parts');
const preview = resolve(here, '..', '..', 'docs', 'interactive-preview.html');
const profile = join(here, 'build', 'capture-profile');
const PORT = 9341;
const SPRITE_SCALE = 3;
const FLAT_SCALE = 2;
const LOG_LINES = 48;

rmSync(out, { recursive: true, force: true });
mkdirSync(out, { recursive: true });

const browser = spawn(findBrowser(), [
  '--headless=new', `--remote-debugging-port=${PORT}`, '--remote-allow-origins=*',
  `--user-data-dir=${profile}`, '--no-first-run', '--no-default-browser-check',
  '--force-device-scale-factor=1', '--hide-scrollbars', '--disable-lcd-text', 'about:blank',
], { stdio: 'ignore' });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function connect() {
  for (let i = 0; i < 80; i++) {
    try {
      const list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
      const page = list.find((t) => t.type === 'page');
      if (page) return page.webSocketDebuggerUrl;
    } catch { /* still starting */ }
    await sleep(250);
  }
  throw new Error('Chrome did not start');
}

const socket = new WebSocket(await connect());
await new Promise((r) => { socket.onopen = r; });
let nextId = 0;
const waiting = new Map();
socket.onmessage = (e) => {
  const m = JSON.parse(e.data);
  if (m.id && waiting.has(m.id)) { waiting.get(m.id)(m); waiting.delete(m.id); }
};
const send = (method, params = {}) => new Promise((res, rej) => {
  const id = ++nextId;
  waiting.set(id, (m) => (m.error ? rej(new Error(`${method}: ${m.error.message}`)) : res(m.result)));
  socket.send(JSON.stringify({ id, method, params }));
});
const run = async (expression) => {
  const r = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
  if (r.exceptionDetails) throw new Error(`${expression.slice(0, 80)}: ${r.exceptionDetails.exception?.description ?? r.exceptionDetails.text}`);
  return r.result.value;
};

await send('Page.enable');
await send('Runtime.enable');
await send('Emulation.setDeviceMetricsOverride', { width: 1400, height: 1100, deviceScaleFactor: 1, mobile: false });
await send('Emulation.setDefaultBackgroundColorOverride', { color: { r: 0, g: 0, b: 0, a: 0 } });
// Freezes the simulated logcat so a sprite never changes between measuring and capturing it.
await send('Page.addScriptToEvaluateOnNewDocument', { source: 'window.setInterval = () => 0;' });
await send('Page.navigate', { url: pathToFileURL(preview).href });
await sleep(1500);
await run('document.fonts.ready.then(() => Promise.all([document.fonts.load("600 64px Roboto"), document.fonts.load("500 16px Roboto"), document.fonts.load("400 15px Roboto"), document.fonts.load("15px \'Roboto Mono\'"), document.fonts.load("24px \'Material Symbols Outlined\'", "home")]))');

await run(String.raw`(() => {
  const style = document.createElement('style');
  style.textContent = ${'`'}
    html, body, .stage-wrap, #wrap, #panel, #app { background: transparent !important; outline: none !important; }
    .sim, #web { display: none !important; }
    * { transition: none !important; animation: none !important; caret-color: transparent !important; }
    .iso * { visibility: hidden !important; }
    .iso .iso-on, .iso .iso-on * { visibility: visible !important; }
    .iso .iso-on { background: transparent !important; border-color: transparent !important; }
    .iso .iso-on input { background: transparent !important; border-color: transparent !important; }
    .opaque #panel { background: #140B24 !important; }
  ${'`'};
  document.head.appendChild(style);
  document.body.style.padding = '0';
  document.body.style.margin = '0';
  fit();

  // Sample apps with made-up names, so the video does not feature other people's products.
  S.apps.list = [
    ['com.example.skygarden', 'Sky Garden', '2.4.1', false],
    ['com.example.orbitgolf', 'Orbit Golf', '1.9.0', false],
    ['com.example.neondrums', 'Neon Drums', '3.2.0', false],
    ['com.example.racer', 'Track Racer', '1402', false],
    ['com.example.player', 'Video Player', '3.6.0', false],
  ].map(([pkg, label, ver, sys]) => ({ pkg, label, ver, sys }));
  S.apps.sel = 'com.example.skygarden';
  node(OBB).c = {
    'com.example.neondrums': dir({ 'main.320.com.example.neondrums.obb': file(780 * MB) }),
    'com.example.orbitgolf': dir(),
    'com.example.skygarden': dir({ 'main.241.com.example.skygarden.obb': file(1.4 * GB) }),
    'main.1402.com.example.racer.obb': file(2 * GB),
    'patch.1402.com.example.racer.obb': file(304 * MB),
  };

  const CONTAINER = '.rail,.panel,.box,.bar';
  const ATOMIC = '.rail-mark,.rail-item,.chip,.action-card,.btn,.choice,.place,.snippet,.crumb,.field,.frow,.approw,.appicon,.warn,.track,.mono-out > div';
  const GHOST = '.btn,.rail-item,.place,.crumb';
  const parse = (c) => { const m = c.match(/[\d.]+/g).map(Number); return { r: m[0], g: m[1], b: m[2], a: m[3] === undefined ? 1 : m[3] }; };
  const over = (c, under) => ({ r: c.r * c.a + under.r * (1 - c.a), g: c.g * c.a + under.g * (1 - c.a), b: c.b * c.a + under.b * (1 - c.a) });
  const WINDOW = { r: 20, g: 11, b: 36 };

  window.partsFor = (rootSelector) => {
    const origin = document.getElementById('panel').getBoundingClientRect();
    const parts = [];
    const groups = new Map();
    const relative = (r) => ({ x: r.left - origin.left, y: r.top - origin.top, w: r.width, h: r.height });
    const add = (el, type, parent, rect = el.getBoundingClientRect()) => {
      const id = 'p' + (window.nextPart = (window.nextPart || 0) + 1);
      el.dataset.part = id;
      const target = el.matches('.field') ? el.querySelector('input') : el;
      const cs = getComputedStyle(target);
      const bg = parse(cs.backgroundColor);
      const borderWidth = parseFloat(cs.borderTopWidth) || 0;
      const under = parent ? parent.face : WINDOW;
      const visibleBg = bg.a > 0.02;
      let kind = type;
      if (type === 'atomic') {
        if (visibleBg || borderWidth > 0) kind = 'slab';
        else if (el.matches(GHOST)) kind = 'ghost';
        else kind = 'text';
      }
      const part = {
        id, kind, parent: parent ? parent.id : null,
        rect: relative(rect),
        slabRect: relative(el.getBoundingClientRect()),
        radius: parseFloat(cs.borderTopLeftRadius) || 0,
        face: visibleBg ? over(bg, under) : under,
        border: borderWidth > 0 ? over(parse(cs.borderTopColor), under) : null,
        borderWidth,
        tag: el.matches('.btn,.choice,.action-card,.chip,.rail-item,.place,.snippet,.crumb') ? 'button' : (type === 'container' ? 'container' : 'other'),
        fontSize: parseFloat(getComputedStyle(el).fontSize) || 15,
        primary: el.matches('.btn.primary,.choice.on,.chip.ok'),
        text: el.textContent.trim().slice(0, 60),
        row: el.matches('.frow,.approw'),
        line: el.matches('.mono-out > div'),
        command: el.matches('.mono-out > .cmd'),
        input: el.matches('.field.mono'),
        track: el.matches('.track'),
        progressLabel: el.matches('#op-bar .label'),
      };
      if (el.matches('.choice')) {
        const box = el.closest('.choices');
        if (!groups.has(box)) groups.set(box, groups.size);
        part.group = groups.get(box);
        part.choiceIndex = [...box.children].indexOf(el);
        part.selected = el.classList.contains('on');
      }
      parts.push(part);
      return part;
    };
    const clipOf = (el) => el.parentElement && el.parentElement.closest('.scroll,.mono-out');
    const outside = (el) => {
      if (el.parentElement && el.parentElement.closest('#log-out')) return false;
      const clip = clipOf(el);
      if (!clip) return false;
      const c = clip.getBoundingClientRect();
      const r = el.getBoundingClientRect();
      return r.bottom > c.bottom + 0.5 || r.top < c.top - 0.5;
    };
    const visit = (el, parent) => {
      const cs = getComputedStyle(el);
      if (cs.display === 'none') return;
      const r = el.getBoundingClientRect();
      if (r.width < 1 || r.height < 1 || outside(el)) return;
      if (el.matches(CONTAINER)) {
        const p = add(el, 'container', parent);
        for (const c of el.children) visit(c, p);
        return;
      }
      if (el.matches(ATOMIC)) { add(el, 'atomic', parent); return; }
      const hasText = [...el.childNodes].some((n) => n.nodeType === 3 && n.textContent.trim());
      if (hasText) {
        const range = document.createRange();
        range.selectNodeContents(el);
        add(el, 'text', parent, range.getBoundingClientRect());
        return;
      }
      for (const c of el.children) visit(c, parent);
    };
    for (const root of document.querySelectorAll(rootSelector)) visit(root, null);
    return parts;
  };

  window.isolate = (id) => {
    document.documentElement.classList.add('iso');
    document.querySelectorAll('.iso-on').forEach((e) => e.classList.remove('iso-on'));
    document.querySelector('[data-part="' + id + '"]').classList.add('iso-on');
  };
  window.stopIsolating = () => {
    document.documentElement.classList.remove('iso');
    document.querySelectorAll('.iso-on').forEach((e) => e.classList.remove('iso-on'));
  };
  window.setChoice = (id, on) => document.querySelector('[data-part="' + id + '"]').classList.toggle('on', on);
  window.setPartText = (id, text) => { document.querySelector('[data-part="' + id + '"]').textContent = text; };
  window.nextFrame = () => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
})()`);

async function capture(clip, scale, file) {
  const shot = await send('Page.captureScreenshot', {
    format: 'png', fromSurface: true, captureBeyondViewport: false, optimizeForSpeed: true,
    clip: { x: clip.x, y: clip.y, width: clip.w, height: clip.h, scale },
  });
  const path = join(out, file);
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, Buffer.from(shot.data, 'base64'));
}

const originTop = await run(`(() => { const r = document.getElementById('panel').getBoundingClientRect(); return { x: r.left, y: r.top }; })()`);
const PAD = 3;
const clipFor = (r) => ({ x: originTop.x + r.x - PAD, y: originTop.y + r.y - PAD, w: r.w + PAD * 2, h: r.h + PAD * 2 });

async function captureSprites(parts, folder) {
  for (const part of parts) {
    if (part.kind === 'container' || part.track) continue;
    await run(`isolate('${part.id}')`);
    const r = part.rect;
    const clip = clipFor(r);
    part.sprite = { file: `${folder}/${part.id}.png`, x: r.x - PAD, y: r.y - PAD, w: clip.w, h: clip.h };
    await capture(clip, SPRITE_SCALE, part.sprite.file);
    if (part.group !== undefined) {
      part.spriteOn = `${folder}/${part.id}-on.png`;
      part.spriteOff = `${folder}/${part.id}-off.png`;
      await run(`setChoice('${part.id}', true)`);
      await capture(clip, SPRITE_SCALE, part.spriteOn);
      await run(`setChoice('${part.id}', false)`);
      await capture(clip, SPRITE_SCALE, part.spriteOff);
      await run(`setChoice('${part.id}', ${part.selected})`);
    }
  }
  await run('stopIsolating()');
}

// What each screen shows when it lands, and what changes after someone interacts with it.
const prepare = {
  Home: `S.dest = 'Home'; S.home.adv = true; S.home.prox = false;`,
  Terminal: `S.dest = 'Terminal'; S.term.lines = []; termRun('id'); termRun('df -h /sdcard'); termRun('pm list packages -3'); termRun('getprop ro.product.model'); S.term.input = 'getprop ro.product.model';`,
  Files: `S.dest = 'Files'; S.files.path = OBB; S.files.sel = [OBB + '/com.example.skygarden']; S.files.op = { label: 'Copying', progress: 0 };`,
  Display: `S.dest = 'Display';`,
  Apps: `S.dest = 'Apps'; S.apps.sel = 'com.example.skygarden';`,
  Utilities: `S.dest = 'Utilities'; S.util.tab = 'Logcat'; S.util.log.running = true;
    (() => {
      const d = new Date(2026, 10, 1, 16, 35, 54);
      const z = (n, l = 2) => String(n).padStart(l, '0');
      const lines = [];
      while (lines.length < ${LOG_LINES}) {
        const entry = fakeLog();
        if (LEVELS.indexOf(entry.level) < LEVELS.indexOf('Info')) continue;
        d.setMilliseconds(d.getMilliseconds() + 40 + lines.length * 3 % 90);
        entry.raw = z(d.getMonth() + 1) + '-' + z(d.getDate()) + ' ' + z(d.getHours()) + ':' + z(d.getMinutes()) + ':' + z(d.getSeconds()) + '.' + z(d.getMilliseconds(), 3) + entry.raw.slice(18);
        lines.push(entry);
      }
      S.util.log.lines = lines;
    })();`,
  Settings: `S.dest = 'Settings'; S.pref = 'Auto';`,
};
const afterInteraction = {
  Home: `S.home.prox = true;`,
  Display: `S.props[P.refresh] = '120'; S.props[P.w] = '2048'; S.props[P.h] = '2048';`,
  Apps: `S.apps.sel = 'com.example.orbitgolf';`,
  Settings: `S.pref = 'Adb';`,
};
// The cards at the end of the video show each screen as it was left.
const whenLeft = {
  ...afterInteraction,
  Terminal: `S.term.input = '';`,
  Files: `S.files.op = null;`,
};

const manifest = { spriteScale: SPRITE_SCALE, flatScale: FLAT_SCALE, screens: [], rail: null, titles: {} };

async function captureAlternates(name, parts) {
  if (!afterInteraction[name]) return;
  await run(`${afterInteraction[name]} render(); nextFrame()`);
  const changed = await run(`partsFor('.main .top h2, .main .content')`);
  for (let i = 0; i < parts.length; i++) {
    if (parts[i].kind !== 'text' || !changed[i] || changed[i].text === parts[i].text) continue;
    await run(`isolate('${changed[i].id}')`);
    parts[i].spriteAlt = `${name}/${parts[i].id}-alt.png`;
    const r = parts[i].rect;
    const wider = { x: r.x - Math.max(0, changed[i].rect.w - r.w), y: r.y, w: Math.max(r.w, changed[i].rect.w), h: r.h };
    parts[i].altRect = { x: wider.x - PAD, y: wider.y - PAD, w: wider.w + PAD * 2, h: wider.h + PAD * 2 };
    await capture(clipFor(wider), SPRITE_SCALE, parts[i].spriteAlt);
  }
  await run('stopIsolating()');
}

for (const [name, setup] of Object.entries(prepare)) {
  await run(`${setup} render(); nextFrame()`);
  if (name === 'Utilities') await run(`document.getElementById('log-out').scrollTop = 0; nextFrame()`);
  const parts = await run(`partsFor('.main .top h2, .main .content')`);

  if (name === 'Utilities') {
    // The log scrolls in the video, so each line is slid into view on its own to be captured.
    const lines = parts.filter((p) => p.line);
    const first = lines[0].rect;
    const lineHeight = lines[1].rect.y - first.y;
    await captureSprites(parts.filter((p) => !p.line), name);
    for (let i = 0; i < lines.length; i++) {
      await run(`(() => { const el = document.querySelector('[data-part="${lines[i].id}"]'); el.style.transform = 'translateY(${-i * lineHeight}px)'; isolate('${lines[i].id}'); })()`);
      lines[i].logIndex = i;
      lines[i].sprite = { file: `${name}/${lines[i].id}.png`, x: first.x - PAD, y: first.y - PAD, w: first.w + PAD * 2, h: first.h + PAD * 2 };
      await capture(clipFor(first), SPRITE_SCALE, lines[i].sprite.file);
      await run(`document.querySelector('[data-part="${lines[i].id}"]').style.transform = ''`);
    }
    await run('stopIsolating()');
    manifest.log = { x: first.x, y: first.y, lineHeight };
  } else {
    await captureSprites(parts, name);
  }

  if (name === 'Terminal') {
    // After Run, the input is empty again.
    await run(`S.term.input = ''; render(); nextFrame()`);
    const field = parts.find((p) => p.input);
    const again = await run(`partsFor('.main .content')`);
    const empty = again.find((p) => p.input);
    await run(`isolate('${empty.id}')`);
    field.spriteAlt = `${name}/${field.id}-alt.png`;
    field.altRect = field.sprite;
    await capture(clipFor(field.rect), SPRITE_SCALE, field.spriteAlt);
    await run('stopIsolating()');
    await run(`S.term.input = 'getprop ro.product.model'; render(); nextFrame()`);
  }

  if (name === 'Files') {
    // The copy bar counts up, so its label is captured at each step.
    const label = parts.find((p) => p.progressLabel);
    label.progressSprites = [];
    const fixed = { ...label.rect, w: 140 };
    label.sprite = { file: label.sprite.file, x: fixed.x - PAD, y: fixed.y - PAD, w: fixed.w + PAD * 2, h: fixed.h + PAD * 2 };
    for (let k = 0; k <= 10; k++) {
      await run(`setPartText('${label.id}', 'Copying ${k * 10}%'); isolate('${label.id}')`);
      const file = `${name}/${label.id}-${k}.png`;
      await capture(clipFor(fixed), SPRITE_SCALE, file);
      label.progressSprites.push(file);
    }
    await run('stopIsolating()');
  }

  await captureAlternates(name, parts);
  await run(`${setup} ${whenLeft[name] ?? ''} render(); nextFrame()`);
  await run(`document.documentElement.classList.add('opaque'); nextFrame()`);
  const flat = `${name}/flat.png`;
  await capture({ x: originTop.x, y: originTop.y, w: 1280, h: 800 }, FLAT_SCALE, flat);
  await run(`document.documentElement.classList.remove('opaque')`);
  manifest.screens.push({ name, parts, flat });
  console.log(`${name}: ${parts.length} parts`);
}

// The rail and status chip are shared between screens. Each rail button needs an on and an off look.
await run(`S.dest = 'Home'; render(); nextFrame()`);
const railParts = await run(`partsFor('.rail, .main .top .chip')`);
await captureSprites(railParts, 'Rail');
const names = Object.keys(prepare);
const railItems = railParts.filter((p) => p.tag === 'button' && p.slabRect.x < 112);
for (let i = 0; i < names.length; i++) {
  await run(`S.dest = ${JSON.stringify(names[i])}; render(); nextFrame()`);
  const id = await run(`(() => { const el = document.querySelector('.rail-item.on'); const id = 'on' + ${i}; el.dataset.part = id; return id; })()`);
  await run(`isolate('${id}')`);
  const base = railItems[i].sprite;
  railItems[i].spriteOn = `Rail/on-${i}.png`;
  await capture({ x: originTop.x + base.x, y: originTop.y + base.y, w: base.w, h: base.h }, SPRITE_SCALE, railItems[i].spriteOn);
  await run('stopIsolating()');
}
// Home's first capture has it selected, so take its off look from another screen.
await run(`S.dest = 'Settings'; render(); nextFrame()`);
await run(`(() => { document.querySelectorAll('.rail-item')[0].dataset.part = 'offHome'; })()`);
await run(`isolate('offHome')`);
const homeBase = railItems[0].sprite;
railItems[0].sprite = { ...homeBase, file: 'Rail/off-0.png' };
await capture({ x: originTop.x + homeBase.x, y: originTop.y + homeBase.y, w: homeBase.w, h: homeBase.h }, SPRITE_SCALE, 'Rail/off-0.png');
await run('stopIsolating()');
manifest.rail = railParts;

// Big type for the logo and captions, set in the app's own typeface.
await run(`(() => {
  const host = document.createElement('div');
  host.id = 'titles';
  host.style.cssText = 'position:fixed;left:40px;top:40px;width:1200px;font-family:Roboto,sans-serif;color:#ECE2FF;';
  document.body.appendChild(host);
  window.setTitle = (html, css) => {
    host.innerHTML = '<div id="title-text" data-part="title" style="display:inline-block;' + css + '">' + html + '</div>';
    return nextFrame().then(() => {
      const r = document.getElementById('title-text').getBoundingClientRect();
      return { x: r.left, y: r.top, w: r.width, h: r.height };
    });
  };
})()`);

async function titleSprite(key, text, css) {
  const r = await run(`setTitle(${JSON.stringify(text)}, ${JSON.stringify(css)})`);
  await run(`isolate('title')`);
  const file = `Titles/${key}.png`;
  const clip = { x: r.x - PAD, y: r.y - PAD, w: r.w + PAD * 2, h: r.h + PAD * 2 };
  await capture(clip, SPRITE_SCALE, file);
  await run('stopIsolating()');
  manifest.titles[key] = { file, w: clip.w, h: clip.h };
}

async function letterSprites(key, word, css) {
  const html = [...word].map((c, i) => `<span data-letter="${i}">${c}</span>`).join('');
  const rect = await run(`setTitle(${JSON.stringify(html)}, ${JSON.stringify(css)})`);
  manifest.titles[key] = { w: rect.w, h: rect.h, letters: [] };
  for (let i = 0; i < word.length; i++) {
    await run(`document.querySelector('[data-letter="${i}"]').dataset.part = 'L${i}'; isolate('L${i}')`);
    const r = await run(`(() => { const r = document.querySelector('[data-letter="${i}"]').getBoundingClientRect(); return { x: r.left, y: r.top, w: r.width, h: r.height }; })()`);
    const clip = { x: r.x - PAD, y: r.y - PAD, w: r.w + PAD * 2, h: r.h + PAD * 2 };
    const file = `Titles/${key}-${i}.png`;
    await capture(clip, SPRITE_SCALE, file);
    manifest.titles[key].letters.push({ file, x: clip.x - rect.x, y: clip.y - rect.y, w: clip.w, h: clip.h });
  }
  await run('stopIsolating()');
}

await letterSprites('word', 'Pasteularity', 'font-size:120px;font-weight:600;line-height:1.1;letter-spacing:-1px;white-space:nowrap;');
await titleSprite('tagline', 'The no-root toolbox for Meta Quest', 'font-size:34px;font-weight:400;color:#C9BCE4;white-space:nowrap;');
await titleSprite('link', 'dsc.gg/libpaste', 'font-size:34px;font-weight:500;color:#ECE2FF;white-space:nowrap;');

const captions = [
  ['Home', 'Hidden Android settings, reboot options and the proximity sensor.'],
  ['Terminal', 'A real shell without root, with history and saved snippets.'],
  ['Files', 'Open Android/obb and Android/data, and sideload OBBs.'],
  ['Display', 'Refresh rate, resolution, foveation and clock levels.'],
  ['Apps', 'Install an APK with its OBB, then launch, export or remove.'],
  ['Utilities', 'Logcat, screen capture, Wi-Fi ADB and reversible tweaks.'],
  ['Settings', 'Connect through Wireless debugging or ByteZuku.'],
];
for (const [name, line] of captions) {
  await letterSprites(`cap-${name}`, name, 'font-size:68px;font-weight:600;letter-spacing:-0.5px;white-space:nowrap;');
  await titleSprite(`sub-${name}`, line, 'font-size:27px;line-height:38px;font-weight:400;color:#C9BCE4;width:400px;');
}

writeFileSync(join(out, 'parts.json'), JSON.stringify(manifest, null, 1));
const total = manifest.screens.reduce((n, s) => n + s.parts.length, 0) + manifest.rail.length;
console.log(`Captured ${total} parts`);
socket.close();
browser.kill();
