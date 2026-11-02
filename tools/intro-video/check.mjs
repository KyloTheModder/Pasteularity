// Plays a finished MP4 through the browser's video decoder and saves frames from it, which proves
// the file is valid and shows what compression did to the picture.
// Run with: node tools/intro-video/check.mjs docs/pasteularity-intro.mp4 6.2,11.7,20.6
import { spawn } from 'node:child_process';
import { createReadStream, mkdirSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { findBrowser } from './browser.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const video = resolve(process.argv[2]);
const times = process.argv[3] ?? '6,12,18';
const outDir = join(here, 'build', 'decoded');
rmSync(outDir, { recursive: true, force: true });
mkdirSync(outDir, { recursive: true });

const page = `<!doctype html><body><canvas id="c" width="1920" height="1080"></canvas><script type="module">
const post = (path, body) => fetch(path, { method: 'POST', body });
try {
  const blob = await (await fetch('/video.mp4')).blob();
  const v = document.createElement('video');
  v.muted = true;
  v.style.width = '640px';
  document.body.appendChild(v);
  v.src = URL.createObjectURL(blob);
  await new Promise((res, rej) => { v.onloadedmetadata = res; v.onerror = () => rej(new Error('decoder rejected the file: ' + v.error?.message)); });
  await post('/info', JSON.stringify({ duration: v.duration, width: v.videoWidth, height: v.videoHeight, size: blob.size }));
  const ctx = document.getElementById('c').getContext('2d');
  for (const t of ${JSON.stringify(times)}.split(',').map(Number)) {
    await new Promise((res, rej) => { v.onseeked = res; v.onerror = rej; v.currentTime = t; });
    await new Promise((res) => setTimeout(res, 150));
    ctx.drawImage(v, 0, 0, 1920, 1080);
    const png = await new Promise((r) => ctx.canvas.toBlob(r, 'image/png'));
    await post('/frame?name=t' + t.toFixed(2).padStart(5, '0'), png);
  }
  await v.play();
  const start = performance.now();
  await new Promise((res) => setTimeout(res, 1500));
  const q = v.getVideoPlaybackQuality();
  await post('/info', JSON.stringify({ playedFor: performance.now() - start, position: v.currentTime, droppedFrames: q.droppedVideoFrames, decodedFrames: q.totalVideoFrames }));
  await post('/done', 'ok');
} catch (e) { await post('/done', 'failed: ' + e.message); }
</script>`;

let finish;
const finished = new Promise((r) => { finish = r; });
const server = createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost');
  if (req.method === 'POST') {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      const body = Buffer.concat(chunks);
      res.end('ok');
      if (url.pathname === '/info') console.log(body.toString());
      else if (url.pathname === '/frame') writeFileSync(join(outDir, `${url.searchParams.get('name')}.png`), body);
      else if (url.pathname === '/done') finish(body.toString());
    });
  } else if (url.pathname === '/video.mp4') {
    res.setHeader('Content-Type', 'video/mp4');
    res.setHeader('Content-Length', statSync(video).size);
    createReadStream(video).pipe(res);
  } else {
    res.setHeader('Content-Type', 'text/html');
    res.end(page);
  }
});
await new Promise((r) => server.listen(0, '127.0.0.1', r));
const browser = spawn(findBrowser(), [
  '--headless=new', `--user-data-dir=${join(here, 'build', 'check-profile')}`, '--no-first-run',
  '--autoplay-policy=no-user-gesture-required', `http://127.0.0.1:${server.address().port}/`,
], { stdio: 'ignore' });
const result = await finished;
browser.kill();
server.close();
console.log(result);
process.exit(result === 'ok' ? 0 : 1);
