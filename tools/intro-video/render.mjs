// Serves the scene to a headless browser with the GPU on, and saves what it sends back.
// Stills: node tools/intro-video/render.mjs stills 3.2,6.4,9.1
// Video:  node tools/intro-video/render.mjs video docs/pasteularity-intro.mp4 [megabits]
import { spawn } from 'node:child_process';
import { createReadStream, existsSync, mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, extname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { findBrowser } from './browser.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const [mode = 'stills', arg = '3.2', megabits = '16'] = process.argv.slice(2);
const types = { '.html': 'text/html', '.js': 'text/javascript', '.json': 'application/json', '.png': 'image/png', '.mp4': 'video/mp4' };
const stillsDir = join(here, 'build', 'stills');
const videoPath = mode === 'video' ? resolve(arg) : null;

let finish;
const finished = new Promise((r) => { finish = r; });

const server = createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost');
  if (req.method === 'POST') {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      const body = Buffer.concat(chunks);
      res.setHeader('Access-Control-Allow-Origin', '*');
      res.end('ok');
      if (url.pathname === '/log') console.log(body.toString());
      else if (url.pathname === '/done') finish(body.toString());
      else if (url.pathname === '/still') {
        mkdirSync(stillsDir, { recursive: true });
        writeFileSync(join(stillsDir, `${url.searchParams.get('name')}.png`), body);
      } else if (url.pathname === '/video') {
        mkdirSync(dirname(videoPath), { recursive: true });
        writeFileSync(videoPath, body);
      }
    });
    return;
  }
  const path = url.pathname === '/' ? join(here, 'scene.html') : join(here, decodeURIComponent(url.pathname));
  if (!path.startsWith(here) || !existsSync(path)) { res.statusCode = 404; res.end(); return; }
  res.setHeader('Content-Type', types[extname(path)] ?? 'application/octet-stream');
  createReadStream(path).pipe(res);
});

await new Promise((r) => server.listen(0, '127.0.0.1', r));
const { port } = server.address();
if (mode === 'stills') rmSync(stillsDir, { recursive: true, force: true });
const query = mode === 'video' ? `mode=video&megabits=${megabits}` : `mode=stills&times=${arg}`;

const profile = join(here, 'build', 'render-profile');
const browser = spawn(findBrowser(), [
  '--headless=new', `--user-data-dir=${profile}`, '--no-first-run', '--no-default-browser-check',
  '--enable-gpu', '--use-angle=d3d11', '--ignore-gpu-blocklist', '--enable-gpu-rasterization',
  '--disable-background-timer-throttling', '--disable-renderer-backgrounding', '--disable-backgrounding-occluded-windows',
  '--disable-frame-rate-limit', '--window-size=1920,1080', `http://127.0.0.1:${port}/?${query}`,
], { stdio: 'ignore' });

const result = await finished;
browser.kill();
server.close();
console.log(result);
process.exit(result.startsWith('ok') ? 0 : 1);
