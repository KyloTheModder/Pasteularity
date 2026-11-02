// The Pasteularity intro: real 3D geometry, lit and rendered in WebGL2, driven only by the clock,
// so every run gives the same frames. Each output frame blends several renders spread across the
// shutter time and a small lens, which gives real motion blur and focus.
import { buildMp4 } from './mp4.js';

const params = new URLSearchParams(location.search);
const post = (path, body) => fetch(path, { method: 'POST', body });
const log = (m) => post('/log', String(m));
const fail = (m) => { log(`FAILED: ${m}`); post('/done', `failed: ${m}`); };
addEventListener('error', (e) => fail(`${e.message} at line ${e.lineno}`));
addEventListener('unhandledrejection', (e) => fail(e.reason?.stack ?? e.reason));

const W = 1920;
const H = 1080;
const SS = 2;
const MSAA = 4;
const FPS = 60;
const DURATION = 24.2;
const SHUTTER = 0.62;
const canvas = document.getElementById('c');
const gl = canvas.getContext('webgl2', { antialias: false, alpha: false, depth: false, preserveDrawingBuffer: true, powerPreference: 'high-performance' });
const floatBuffers = gl.getExtension('EXT_color_buffer_float');
const aniso = gl.getExtension('EXT_texture_filter_anisotropic');

// ---------- Maths

const clamp = (x, a = 0, b = 1) => Math.min(b, Math.max(a, x));
const lerp = (a, b, k) => a + (b - a) * k;
const smooth = (x) => { const k = clamp(x); return k * k * (3 - 2 * k); };
const easeOutExpo = (x) => { const k = clamp(x); return k >= 1 ? 1 : 1 - 2 ** (-9 * k); };
const easeInOut = (x) => { const k = clamp(x); return k < 0.5 ? 4 * k * k * k : 1 - (-2 * k + 2) ** 3 / 2; };
const rad = (d) => (d * Math.PI) / 180;
const rgb = (hex) => [(hex >> 16 & 255) / 255, (hex >> 8 & 255) / 255, (hex & 255) / 255];
const bump = (t, center, width) => Math.exp(-(((t - center) / width) ** 2));
const wrap = (v, period) => ((((v + period / 2) % period) + period) % period) - period / 2;

/** How a damped spring answers a push: it starts at rest, overshoots a little and settles on 1. */
function spring(t, freq, damping) {
  if (t <= 0) return 0;
  const d = freq * Math.sqrt(1 - damping * damping);
  const e = Math.exp(-damping * freq * t);
  return 1 - e * (Math.cos(d * t) + ((damping * freq) / d) * Math.sin(d * t));
}

/** A spring with a small wind-up: it leans the wrong way first, then goes. */
const leap = (t, freq, damping) => 1.05 * spring(t - 0.085, freq, damping) - 0.05 * spring(t, 20, 0.99);

const mat = {
  mul(a, b) {
    const o = new Float32Array(16);
    for (let c = 0; c < 4; c++) {
      for (let r = 0; r < 4; r++) {
        o[c * 4 + r] = a[r] * b[c * 4] + a[r + 4] * b[c * 4 + 1] + a[r + 8] * b[c * 4 + 2] + a[r + 12] * b[c * 4 + 3];
      }
    }
    return o;
  },
  chain(...ms) { return ms.reduce((a, b) => mat.mul(a, b)); },
  t: (x, y, z) => new Float32Array([1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, x, y, z, 1]),
  s: (x, y = x, z = x) => new Float32Array([x, 0, 0, 0, 0, y, 0, 0, 0, 0, z, 0, 0, 0, 0, 1]),
  rx(a) { const c = Math.cos(a), s = Math.sin(a); return new Float32Array([1, 0, 0, 0, 0, c, s, 0, 0, -s, c, 0, 0, 0, 0, 1]); },
  ry(a) { const c = Math.cos(a), s = Math.sin(a); return new Float32Array([c, 0, -s, 0, 0, 1, 0, 0, s, 0, c, 0, 0, 0, 0, 1]); },
  rz(a) { const c = Math.cos(a), s = Math.sin(a); return new Float32Array([c, s, 0, 0, -s, c, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]); },
  perspective(fovy, aspect, n, f) {
    const k = 1 / Math.tan(fovy / 2);
    return new Float32Array([k / aspect, 0, 0, 0, 0, k, 0, 0, 0, 0, (f + n) / (n - f), -1, 0, 0, (2 * f * n) / (n - f), 0]);
  },
  lookAt(e, c, u) {
    const nz = norm([e[0] - c[0], e[1] - c[1], e[2] - c[2]]);
    const nx = norm(cross(u, nz));
    const ny = cross(nz, nx);
    return new Float32Array([nx[0], ny[0], nz[0], 0, nx[1], ny[1], nz[1], 0, nx[2], ny[2], nz[2], 0,
      -dot(nx, e), -dot(ny, e), -dot(nz, e), 1]);
  },
  apply(m, p) {
    return [m[0] * p[0] + m[4] * p[1] + m[8] * p[2] + m[12], m[1] * p[0] + m[5] * p[1] + m[9] * p[2] + m[13], m[2] * p[0] + m[6] * p[1] + m[10] * p[2] + m[14]];
  },
};
const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
const cross = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
const norm = (a) => { const l = Math.hypot(...a); return [a[0] / l, a[1] / l, a[2] / l]; };

function project(m, p) {
  const x = m[0] * p[0] + m[4] * p[1] + m[8] * p[2] + m[12];
  const y = m[1] * p[0] + m[5] * p[1] + m[9] * p[2] + m[13];
  const w = m[3] * p[0] + m[7] * p[1] + m[11] * p[2] + m[15];
  return [x / w, y / w];
}

// ---------- Shaders

const HEADER = '#version 300 es\nprecision highp float;\n';
const SDF = `float sdRound(vec2 p, vec2 b, float r) { vec2 q = abs(p) - b + r; return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r; }\n`;

function program(vsBody, fsBody) {
  const compile = (type, src) => {
    const s = gl.createShader(type);
    gl.shaderSource(s, HEADER + src);
    gl.compileShader(s);
    if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s));
    return s;
  };
  const p = gl.createProgram();
  gl.attachShader(p, compile(gl.VERTEX_SHADER, vsBody));
  gl.attachShader(p, compile(gl.FRAGMENT_SHADER, fsBody));
  gl.linkProgram(p);
  if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(p));
  const u = {};
  for (let i = 0; i < gl.getProgramParameter(p, gl.ACTIVE_UNIFORMS); i++) {
    const name = gl.getActiveUniform(p, i).name;
    u[name] = gl.getUniformLocation(p, name);
  }
  return { p, u };
}

const slabProgram = program(`
layout(location=0) in vec3 aPos; layout(location=1) in vec3 aNormal;
uniform mat4 uVP, uModel; out vec3 vWorld, vN, vLocal, vLocalN;
void main() {
  vec4 w = uModel * vec4(aPos, 1.0);
  vWorld = w.xyz; vN = mat3(uModel) * aNormal; vLocal = aPos; vLocalN = aNormal;
  gl_Position = uVP * w;
}`, SDF + `
in vec3 vWorld, vN, vLocal, vLocalN; out vec4 o;
uniform vec3 uCam, uColor, uBorder, uRim; uniform vec2 uHalf;
uniform float uRadius, uBorderWidth, uOpacity, uGloss, uWall, uEdge, uRippleAmp;
uniform vec3 uSpot; uniform vec4 uLamp, uSweep, uRipple;
const vec3 L1 = normalize(vec3(-0.55, 0.78, 0.72));
const vec3 L2 = normalize(vec3(0.75, -0.15, 0.55));
void main() {
  vec3 N = normalize(vN), V = normalize(uCam - vWorld);
  float top = smoothstep(0.985, 0.999, normalize(vLocalN).z);
  vec3 base = uColor;
  if (uBorderWidth > 0.0) {
    float d = sdRound(vLocal.xy, uHalf, uRadius);
    float aa = fwidth(d);
    base = mix(base, uBorder, smoothstep(-uBorderWidth - aa, -uBorderWidth + aa, d) * top);
  }
  float flatLight = 0.62 + 0.62 * L1.z + 0.16 * L2.z;
  float light = (0.62 + 0.62 * max(dot(N, L1), 0.0) + 0.16 * max(dot(N, L2), 0.0)) / flatLight;
  vec3 c = base * light;
  c = mix(c * uWall + uRim * uEdge, c, top);
  // A soft pool of light that drifts across the surfaces, so flat faces read as lit objects.
  vec2 toSpot = vWorld.xy - uSpot.xy;
  c *= 1.0 + uSpot.z * exp(-dot(toSpot, toSpot) / 42.0);
  // The pointer carries a small light of its own.
  vec3 toLamp = vWorld - uLamp.xyz;
  float lamp = uLamp.w * exp(-dot(toLamp, toLamp) / 5.5);
  c += base * lamp * 0.9 + uRim * lamp * 0.03;
  vec3 H = normalize(L1 + V);
  float nh = max(dot(N, H), 0.0);
  // Sharp highlights belong on the bevels. On a flat face they would wash out the whole panel.
  float spec = pow(nh, 220.0) * mix(0.85, 0.05, top) + pow(nh, 24.0) * mix(0.07, 0.03, top);
  float fresnel = pow(1.0 - max(dot(N, V), 0.0), 4.0);
  c += uRim * (spec * uGloss + fresnel * 0.20);
  // A horizon in the reflections, which slides across a surface as it turns.
  vec3 R = reflect(-V, N);
  c += uRim * uGloss * 0.022 * smoothstep(0.02, 0.5, R.y) * (1.0 - smoothstep(0.5, 1.0, R.y));
  // The band of light that crosses everything during a transition.
  float band = dot(vWorld.xy, uSweep.xy) - uSweep.z;
  c += uRim * exp(-band * band / 2.6) * uSweep.w * mix(0.4, 1.0, top);
  // The ring that spreads out from a press.
  float ring = length(vWorld - uRipple.xyz) - uRipple.w;
  c += uRim * exp(-ring * ring / 0.18) * uRippleAmp * top;
  o = vec4(c * uOpacity, uOpacity);
}`);

const spriteProgram = program(`
layout(location=0) in vec2 aPos;
uniform mat4 uVP, uModel; out vec2 vUV, vLocal;
void main() { vUV = vec2(aPos.x + 0.5, 0.5 - aPos.y); vLocal = aPos; gl_Position = uVP * uModel * vec4(aPos, 0.0, 1.0); }`, SDF + `
in vec2 vUV, vLocal; out vec4 o;
uniform sampler2D uTex; uniform float uOpacity, uCorner; uniform vec3 uTint, uWipe; uniform vec2 uSize;
void main() {
  vec4 c = texture(uTex, vUV, -0.4);
  c.rgb *= uTint;
  if (uCorner > 0.0) {
    float d = sdRound(vLocal * uSize, uSize * 0.5, uCorner);
    c *= clamp(0.5 - d / fwidth(d), 0.0, 1.0);
  }
  // Reveals the sprite from the left, below a given height.
  if (uWipe.y > 0.0 && vUV.y >= uWipe.z) c *= clamp((uWipe.x - vUV.x) / uWipe.y, 0.0, 1.0);
  o = c * uOpacity;
}`);

const shadowProgram = program(`
layout(location=0) in vec2 aPos;
uniform mat4 uVP, uModel; uniform vec2 uSize; out vec2 vLocal;
void main() { vLocal = aPos * uSize; gl_Position = uVP * uModel * vec4(vLocal, 0.0, 1.0); }`, SDF + `
in vec2 vLocal; out vec4 o;
uniform vec2 uHalf; uniform float uRadius, uBlur, uStrength;
void main() {
  float d = sdRound(vLocal, uHalf, uRadius);
  float a = (1.0 - smoothstep(-uBlur, uBlur, d)) * uStrength;
  o = vec4(vec3(0.015, 0.008, 0.03) * a, a);
}`);

// Dots grow into soft discs the further they are from the focus.
const dotProgram = program(`
layout(location=0) in vec2 aCorner; layout(location=1) in vec4 iPos;
uniform mat4 uV, uP; uniform float uSize, uLens, uFocus;
out vec2 vCorner; out float vClose, vEnergy;
void main() {
  vec4 v = uV * vec4(iPos.xyz, 1.0);
  float core = uSize * (1.0 + iPos.w * 1.1);
  float blur = uLens * abs(-v.z - uFocus) / uFocus;
  float size = sqrt(core * core + blur * blur);
  v.xy += aCorner * size;
  vCorner = aCorner; vClose = iPos.w; vEnergy = (core * core) / (size * size);
  gl_Position = uP * v;
}`, `
in vec2 vCorner; in float vClose, vEnergy; out vec4 o;
uniform vec3 uBase, uHot; uniform float uOpacity;
void main() {
  float a = (1.0 - smoothstep(mix(0.74, 0.55, vEnergy), 1.0, length(vCorner))) * uOpacity;
  a *= mix(0.75, 1.0, vClose) * pow(vEnergy, 0.55);
  vec3 c = mix(uBase, uHot, vClose);
  o = vec4(c * a, a);
}`);

const ribbonProgram = program(`
layout(location=0) in vec2 aPos; layout(location=1) in vec4 aColor; layout(location=2) in float aEdge;
out vec4 vColor; out float vEdge;
void main() { vColor = aColor; vEdge = aEdge; gl_Position = vec4(aPos, 0.0, 1.0); }`, `
in vec4 vColor; in float vEdge; out vec4 o;
void main() { float a = vColor.a * (1.0 - vEdge * vEdge); o = vec4(vColor.rgb * a, a); }`);

const backgroundProgram = program(`
layout(location=0) in vec2 aPos; out vec2 vP; void main() { vP = aPos * 2.0; gl_Position = vec4(aPos * 2.0, 0.999, 1.0); }`, `
in vec2 vP; out vec4 o; uniform vec2 uCenter;
void main() {
  float d = length((vP - uCenter) * vec2(1.0, 0.72));
  vec3 inner = vec3(0.098, 0.058, 0.176), outer = vec3(0.034, 0.018, 0.066);
  o = vec4(mix(inner, outer, smoothstep(0.0, 1.45, d)), 1.0);
}`);

const FULLSCREEN = `layout(location=0) in vec2 aPos; out vec2 vUV; void main() { vUV = aPos + 0.5; gl_Position = vec4(aPos * 2.0, 0.0, 1.0); }`;

// The renders of one frame are added up in linear light, so bright things stay bright when blurred.
const addProgram = program(FULLSCREEN, `
in vec2 vUV; out vec4 o; uniform sampler2D uTex; uniform float uWeight;
void main() { o = vec4(pow(max(texture(uTex, vUV).rgb, vec3(0.0)), vec3(2.2)) * uWeight, 1.0); }`);

const bloomDownProgram = program(FULLSCREEN, `
in vec2 vUV; out vec4 o; uniform sampler2D uTex; uniform vec2 uStep; uniform float uThreshold;
void main() {
  vec3 c = texture(uTex, vUV).rgb * 4.0;
  c += texture(uTex, vUV + uStep * vec2(-1.0, -1.0)).rgb + texture(uTex, vUV + uStep * vec2(1.0, -1.0)).rgb;
  c += texture(uTex, vUV + uStep * vec2(-1.0, 1.0)).rgb + texture(uTex, vUV + uStep * vec2(1.0, 1.0)).rgb;
  c /= 8.0;
  if (uThreshold > 0.0) c *= smoothstep(uThreshold, uThreshold * 1.5, dot(c, vec3(0.2126, 0.7152, 0.0722)));
  o = vec4(c, 1.0);
}`);

const bloomUpProgram = program(FULLSCREEN, `
in vec2 vUV; out vec4 o; uniform sampler2D uTex; uniform vec2 uStep;
void main() {
  vec3 c = texture(uTex, vUV + uStep * vec2(-2.0, 0.0)).rgb + texture(uTex, vUV + uStep * vec2(2.0, 0.0)).rgb;
  c += texture(uTex, vUV + uStep * vec2(0.0, -2.0)).rgb + texture(uTex, vUV + uStep * vec2(0.0, 2.0)).rgb;
  c += (texture(uTex, vUV + uStep * vec2(-1.0, 1.0)).rgb + texture(uTex, vUV + uStep * vec2(1.0, 1.0)).rgb) * 2.0;
  c += (texture(uTex, vUV + uStep * vec2(-1.0, -1.0)).rgb + texture(uTex, vUV + uStep * vec2(1.0, -1.0)).rgb) * 2.0;
  o = vec4(c / 12.0, 1.0);
}`);

const finalProgram = program(FULLSCREEN, `
in vec2 vUV; out vec4 o; uniform sampler2D uTex, uBloom; uniform float uFade, uBloomAmount;
float noise(vec2 p) { return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715)))); }
void main() {
  vec3 c = texture(uTex, vUV).rgb + texture(uBloom, vUV).rgb * uBloomAmount;
  c = pow(max(c, vec3(0.0)), vec3(1.0 / 2.2));
  vec3 high = 0.92 + 0.08 * (1.0 - exp(-(c - 0.92) / 0.08));
  c = mix(c, high, step(vec3(0.92), c));
  float d = distance(vUV, vec2(0.5));
  c *= mix(1.0, 0.78, smoothstep(0.42, 0.85, d)) * uFade;
  c += (noise(gl_FragCoord.xy) - 0.5) / 255.0;
  o = vec4(c, 1.0);
}`);

// ---------- Geometry

const quadVao = (() => {
  const vao = gl.createVertexArray();
  gl.bindVertexArray(vao);
  const b = gl.createBuffer();
  gl.bindBuffer(gl.ARRAY_BUFFER, b);
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-0.5, -0.5, 0.5, -0.5, -0.5, 0.5, 0.5, 0.5]), gl.STATIC_DRAW);
  gl.enableVertexAttribArray(0);
  gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
  return vao;
})();

function meshFrom(vertices, indices) {
  const vao = gl.createVertexArray();
  gl.bindVertexArray(vao);
  const vb = gl.createBuffer();
  gl.bindBuffer(gl.ARRAY_BUFFER, vb);
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(vertices), gl.STATIC_DRAW);
  gl.enableVertexAttribArray(0);
  gl.vertexAttribPointer(0, 3, gl.FLOAT, false, 24, 0);
  gl.enableVertexAttribArray(1);
  gl.vertexAttribPointer(1, 3, gl.FLOAT, false, 24, 12);
  const ib = gl.createBuffer();
  gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, ib);
  gl.bufferData(gl.ELEMENT_ARRAY_BUFFER, new Uint32Array(indices), gl.STATIC_DRAW);
  return { vao, count: indices.length };
}

const slabCache = new Map();

/** A rounded rectangle with real thickness and a curved bevel. The top face is at z = thickness. */
function slabMesh(w, h, radius, thickness, bevel) {
  const key = [w, h, radius, thickness, bevel].map((v) => v.toFixed(3)).join();
  if (slabCache.has(key)) return slabCache.get(key);
  const r = Math.max(0.012, Math.min(radius, Math.min(w, h) / 2 - 0.001));
  const b = Math.min(bevel, r * 0.9, thickness * 0.9);
  const SEG = 10;
  const STEPS = 5;
  const rim = [];
  const corners = [[w / 2 - r, h / 2 - r, 0], [-(w / 2 - r), h / 2 - r, Math.PI / 2], [-(w / 2 - r), -(h / 2 - r), Math.PI], [w / 2 - r, -(h / 2 - r), Math.PI * 1.5]];
  for (const [cx, cy, a0] of corners) {
    for (let i = 0; i <= SEG; i++) {
      const a = a0 + (i / SEG) * (Math.PI / 2);
      rim.push([cx, cy, Math.cos(a), Math.sin(a)]);
    }
  }
  const rings = [];
  for (let k = 0; k <= STEPS; k++) {
    const phi = (k / STEPS) * (Math.PI / 2);
    rings.push({ inset: b * (1 - Math.sin(phi)), z: thickness - b + b * Math.cos(phi), nr: Math.sin(phi), nz: Math.cos(phi) });
  }
  rings.push({ inset: 0, z: 0, nr: 1, nz: 0 });
  const v = [0, 0, thickness, 0, 0, 1];
  for (const ring of rings) {
    for (const [cx, cy, dx, dy] of rim) {
      v.push(cx + dx * (r - ring.inset), cy + dy * (r - ring.inset), ring.z, dx * ring.nr, dy * ring.nr, ring.nz);
    }
  }
  const n = rim.length;
  const idx = [];
  const at = (ring, i) => 1 + ring * n + ((i + n) % n);
  for (let i = 0; i < n; i++) idx.push(0, at(0, i), at(0, i + 1));
  for (let j = 0; j < rings.length - 1; j++) {
    for (let i = 0; i < n; i++) {
      idx.push(at(j, i), at(j + 1, i), at(j + 1, i + 1), at(j, i), at(j + 1, i + 1), at(j, i + 1));
    }
  }
  const mesh = meshFrom(v, idx);
  slabCache.set(key, mesh);
  return mesh;
}

/** The app icon's P, extruded from the same path as ic_launcher_foreground.xml. */
const logoMesh = (() => {
  const toWorld = (x, y) => [(x - 56) / 20, (54 - y) / 20];
  const D = 0.62;
  const B = 0.05 * 20;
  const v = [];
  const idx = [];
  const addQuad = (a, b, c, d, na, nb, nc, nd) => {
    const base = v.length / 6;
    for (const [p, n] of [[a, na], [b, nb], [c, nc], [d, nd]]) v.push(...p, ...n);
    idx.push(base, base + 1, base + 2, base, base + 2, base + 3);
  };
  const front = (x, y) => [...toWorld(x, y), D];
  const up = [0, 0, 1];
  const rect = (x0, y0, x1, y1) => addQuad(front(x0, y1), front(x1, y1), front(x1, y0), front(x0, y0), up, up, up, up);
  rect(38 + B, 30 + B, 50 - B, 78 - B);
  rect(50 - B, 30 + B, 58, 41 - B);
  rect(50 - B, 51 + B, 58, 62 - B);
  const ARC = 28;
  const arc = (r, a) => [58 + r * Math.cos(a), 46 + r * Math.sin(a)];
  for (let i = 0; i < ARC; i++) {
    const a0 = -Math.PI / 2 + (i / ARC) * Math.PI;
    const a1 = -Math.PI / 2 + ((i + 1) / ARC) * Math.PI;
    addQuad(front(...arc(5 + B, a0)), front(...arc(5 + B, a1)), front(...arc(16 - B, a1)), front(...arc(16 - B, a0)), up, up, up, up);
  }
  const contour = (points, insetPoints) => {
    for (let i = 0; i < points.length; i++) {
      const j = (i + 1) % points.length;
      const [p0, n0] = points[i];
      const [p1] = points[j];
      const n1 = points[i][2] ?? n0;
      const q0 = insetPoints[i];
      const q1 = insetPoints[j];
      const wp0 = toWorld(...p0), wp1 = toWorld(...p1), wq0 = toWorld(...q0), wq1 = toWorld(...q1);
      const bw = B / 20;
      const mix0 = norm([n0[0], -n0[1], 1]), mix1 = norm([n1[0], -n1[1], 1]);
      addQuad([...wq0, D], [...wq1, D], [...wp1, D - bw], [...wp0, D - bw], mix0, mix1, mix1, mix0);
      const s0 = [n0[0], -n0[1], 0], s1 = [n1[0], -n1[1], 0];
      addQuad([...wp0, D - bw], [...wp1, D - bw], [...wp1, 0], [...wp0, 0], s0, s1, s1, s0);
    }
  };
  // Outline, with the outward normal of each edge. Curved edges carry a second normal for their far end.
  const outer = [[[38, 30], [0, -1]], [[58, 30], [0, -1], [0, -1]]];
  const outerInset = [[38 + B, 30 + B], [58, 30 + B]];
  for (let i = 0; i < ARC; i++) {
    const a0 = -Math.PI / 2 + (i / ARC) * Math.PI;
    const a1 = -Math.PI / 2 + ((i + 1) / ARC) * Math.PI;
    outer.push([arc(16, a0), [Math.cos(a0), Math.sin(a0)], [Math.cos(a1), Math.sin(a1)]]);
    outerInset.push(arc(16 - B, a0));
  }
  outer.push([[58, 62], [0, 1]], [[50, 62], [1, 0]], [[50, 78], [0, 1]], [[38, 78], [-1, 0]]);
  outerInset.push([58, 62 - B], [50 - B, 62 - B], [50 - B, 78 - B], [38 + B, 78 - B]);
  contour(outer, outerInset);
  const hole = [[[50, 41], [1, 0]], [[50, 51], [0, -1]]];
  const holeInset = [[50 - B, 41 - B], [50 - B, 51 + B]];
  for (let i = 0; i <= ARC; i++) {
    const a0 = Math.PI / 2 - (i / ARC) * Math.PI;
    const a1 = Math.PI / 2 - ((i + 1) / ARC) * Math.PI;
    hole.push([arc(5, a0), [-Math.cos(a0), -Math.sin(a0)], i < ARC ? [-Math.cos(a1), -Math.sin(a1)] : [0, 1]]);
    holeInset.push(arc(5 + B, a0));
  }
  hole.push([[58, 41], [0, 1]]);
  holeInset.push([58, 41 - B]);
  contour(hole, holeInset);
  return meshFrom(v, idx);
})();

// ---------- Textures

async function loadTexture(file) {
  const blob = await (await fetch(`build/parts/${file}`)).blob();
  const bitmap = await createImageBitmap(blob, { premultiplyAlpha: 'premultiply', colorSpaceConversion: 'none' });
  const tex = gl.createTexture();
  gl.bindTexture(gl.TEXTURE_2D, tex);
  gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, gl.RGBA, gl.UNSIGNED_BYTE, bitmap);
  gl.generateMipmap(gl.TEXTURE_2D);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR_MIPMAP_LINEAR);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
  if (aniso) gl.texParameterf(gl.TEXTURE_2D, aniso.TEXTURE_MAX_ANISOTROPY_EXT, 8);
  bitmap.close();
  return tex;
}

// ---------- The stage plan: where the whole window sits for each tab, and how it gets there

const U = 100;
const WINDOW = { w: 12.8, h: 8, r: 0.16, t: 0.5 };
const STEP = 2.45;
const FIRST = 2.8;

// pos is in world units, rot is pitch, yaw and roll in degrees. A window on the right turns its face
// toward the middle of the frame, and its caption takes the free side.
const STAGES = [
  {
    name: 'Home', pos: [1.9, -0.05, 0], rot: [-3, -15, 0.6], scale: 1, lift: 0.55,
    caption: { x: -8.8, y: 1.3, yaw: 7, out: [-1, 0] },
    enter: { start: 0, span: 0.75, wave: [0.8, 0.2], x: -1.1, y: 0, depth: 0.9, rx: 0, ry: -0.75, hx: -0.5, hy: 0, scale: 1, freq: 11, damp: 0.62, dur: 0.6 },
  },
  {
    name: 'Terminal', move: 'swing', pos: [-1.95, 0.25, 0], rot: [4, 18, -1.6], scale: 1, lift: 0.75,
    caption: { x: 5.1, y: 2.8, yaw: -7, out: [1, 0] },
    enter: { start: 0.22, span: 0.5, wave: [0.2, 0.8], x: 0, y: -0.7, depth: 0.7, rx: 0.6, ry: 0, hx: 0, hy: -0.5, scale: 1, freq: 11, damp: 0.62, dur: 0.6 },
  },
  {
    name: 'Files', move: 'tip', pos: [0.6, -1.25, -0.4], rot: [-25, -6, 1.6], scale: 0.86, lift: 0.7,
    caption: { x: -6.2, y: 4.72, yaw: 0, row: true, out: [0, 1] },
    enter: { start: 0.24, span: 0.6, wave: [0.1, 0.9], x: 0, y: 0.1, depth: 0.35, rx: -1.25, ry: 0, hx: 0, hy: 0.5, scale: 1, freq: 9.5, damp: 0.72, dur: 0.5 },
  },
  {
    name: 'Display', move: 'flip', pos: [-1.8, 0.3, 0], rot: [-5, 22, -2.2], scale: 1, lift: 0.45,
    caption: { x: 5.1, y: -0.6, yaw: -8, out: [1, 0] },
    enter: { start: 0.14, span: 0.62, wave: [0.5, 0.5], x: 0, y: 0, depth: 1.3, rx: 0.3, ry: 0, hx: 0, hy: 0, scale: 0.55, freq: 12, damp: 0.56, dur: 0.6 },
  },
  {
    name: 'Apps', move: 'whip', pos: [0.7, 1.15, -0.2], rot: [14, -10, -1.2], scale: 0.82, lift: 0.3,
    caption: { x: -4.9, y: -3.05, yaw: 0, row: true, out: [0, -1] },
    enter: { start: 0.16, span: 0.5, wave: [0.3, 0.7], x: 1.5, y: 0, depth: 0.8, rx: 0, ry: 0.65, hx: 0.5, hy: 0, scale: 1, freq: 11, damp: 0.62, dur: 0.6 },
  },
  {
    name: 'Utilities', move: 'roll', pos: [-1.9, -0.45, 0], rot: [-14, 14, -2.8], scale: 1, lift: 0.95,
    caption: { x: 4.9, y: 3, yaw: -6, out: [1, 0] },
    enter: { start: 0.24, span: 0.5, wave: [0.6, 0.4], x: 0, y: -1, depth: 0.7, rx: 0.55, ry: 0, hx: 0, hy: -0.5, scale: 1, freq: 11, damp: 0.62, dur: 0.6 },
  },
  {
    name: 'Settings', move: 'dolly', pos: [2, 0.12, 0], rot: [2, -9, 0.4], scale: 1, lift: 0.65,
    caption: { x: -8.6, y: 1.05, yaw: 7, out: [-1, 0] },
    enter: { start: 0.24, span: 0.6, wave: [0.25, 0.75], x: -0.4, y: 0, depth: 0.6, rx: 0, ry: -0.95, hx: -0.5, hy: 0, scale: 1, freq: 11, damp: 0.62, dur: 0.6 },
  },
];
// The slow glide while each screen is up: yaw and pitch in degrees over the whole stay, then x, y and z.
const DRIFT = [[2.5, 0, 0, 0, 0.5], [-3, 1, -0.25, 0, 0.4], [2.5, -1.5, 0, 0, 0.5], [-3, 0, 0, 0.15, 0.4], [2.5, 1.2, 0.2, 0, 0.5], [-2.5, -1.2, 0, 0, 0.4], [2, 0, 0, 0, 0.6]];
STAGES.forEach((s, i) => { s.index = i; s.start = FIRST + i * STEP; s.end = s.start + STEP; s.drift = DRIFT[i]; });
const FLIP = STAGES.findIndex((s) => s.move === 'flip');

const TIME = {
  logoExit: 2.2,
  windowIn: 2.2,
  railIn: 2.48,
  finale: STAGES[6].end,
  logoBack: STAGES[6].end + 1.2,
  end: DURATION,
};
const RING = { radius: 12.2, scale: 0.6, omega: 0.15 };
const ringAngle = (i, t) => Math.PI / 7 + (i + 1) * ((2 * Math.PI) / 7) + RING.omega * (t - TIME.end);

const COLOR = {
  window: rgb(0x140B24), raised: rgb(0x2E1C4C), accent: rgb(0x9B5CF6), rim: rgb(0xC6B2FF),
  dotBase: rgb(0x4B3A6E), dotHot: rgb(0xB48BFF), text: rgb(0xECE2FF),
};
const WHITE = [1, 1, 1];
const NO_WIPE = [0, 0, 0];
const col = (c) => [c.r / 255, c.g / 255, c.b / 255];
const brighten = (c, k) => [Math.min(1, c[0] * k + 0.006), Math.min(1, c[1] * k + 0.006), Math.min(1, c[2] * k + 0.008)];

// Everything a button click sets off, in time order. Filled in by the screen scripts.
const PRESSES = [];
const pointerKeys = [];

// ---------- Where the window is

const WHIP_DISTANCE = 46;

/** The window's place at any moment. Each move adds its own springs on top of the last. */
function windowPose(t) {
  const first = STAGES[0];
  const arrive = spring(t - TIME.windowIn, 5.4, 0.74);
  const p = {
    x: first.pos[0],
    y: first.pos[1] - (1 - arrive) * 1.4,
    z: first.pos[2] - (1 - arrive) * 30,
    pitch: rad(first.rot[0]) + (1 - arrive) * 0.35,
    yaw: rad(first.rot[1]) + (1 - arrive) * 1.1,
    roll: rad(first.rot[2]) - (1 - arrive) * 0.25,
    scale: first.scale,
    flip: 0,
    travel: 0,
    streak: 0,
  };
  for (let i = 1; i < STAGES.length; i++) {
    const a = STAGES[i - 1];
    const b = STAGES[i];
    const tau = t - b.start;
    if (tau <= 0) break;
    const kp = leap(tau, 8.4, 0.66);
    const kr = leap(tau - 0.03, 7.2, 0.6);
    p.x += (b.pos[0] - a.pos[0]) * kp;
    p.y += (b.pos[1] - a.pos[1]) * kp;
    p.z += (b.pos[2] - a.pos[2]) * kp;
    p.pitch += rad(b.rot[0] - a.rot[0]) * kr;
    p.yaw += rad(b.rot[1] - a.rot[1]) * kr;
    p.roll += rad(b.rot[2] - a.rot[2]) * kr;
    p.scale += (b.scale - a.scale) * spring(tau - 0.05, 9, 0.7);
    const arc = bump(tau, 0.36, 0.2);
    if (b.move === 'swing') {
      p.z += 3.6 * arc;
      p.y -= 0.6 * arc;
    } else if (b.move === 'tip') {
      p.y += 1.4 * arc;
      p.pitch += rad(24) * arc;
      p.z += 1.2 * arc;
    } else if (b.move === 'flip') {
      p.flip = Math.PI * spring(tau - 0.05, 6.4, 0.74);
      p.z += 2.8 * bump(tau, 0.3, 0.22);
    } else if (b.move === 'whip') {
      // Leaves one side of the frame fast and comes back from the other, with the dots streaking past.
      const s = spring(tau - 0.04, 7.6, 0.74);
      p.x += s < 0.5 ? -WHIP_DISTANCE * s : WHIP_DISTANCE * (1 - s);
      p.travel += WHIP_DISTANCE * s;
      p.streak = bump(tau, 0.24, 0.13);
      p.yaw += 0.7 * bump(tau, 0.14, 0.1) - 0.5 * bump(tau, 0.36, 0.13);
    } else if (b.move === 'roll') {
      p.roll -= 2 * Math.PI * spring(tau - 0.05, 6.2, 0.76);
      p.z -= 8 * bump(tau, 0.36, 0.25);
    } else if (b.move === 'dolly') {
      p.yaw += rad(16) * arc;
      p.pitch -= rad(7) * arc;
    }
  }
  // Once it lands it keeps gliding slowly, carrying on from the move, until the next tab is pressed.
  for (let i = 0; i < STAGES.length; i++) {
    const s = STAGES[i];
    const from = i === 0 ? TIME.windowIn : s.start;
    if (t <= from) break;
    const next = STAGES[i + 1];
    const settled = (i === 0 ? arrive : leap(t - s.start - 0.03, 7.2, 0.6)) * (next ? 1 - leap(t - next.start - 0.03, 7.2, 0.6) : 1);
    const k = (clamp((t - from) / (s.end - from)) - 0.5) * settled;
    p.yaw += rad(s.drift[0]) * k;
    p.pitch += rad(s.drift[1]) * k;
    p.x += s.drift[2] * k;
    p.y += s.drift[3] * k;
    p.z += s.drift[4] * k;
  }
  // It never quite stops: a slow float, and a push back whenever something is pressed.
  p.x += 0.05 * Math.sin(t * 0.53 + 1);
  p.y += 0.07 * Math.sin(t * 0.71);
  p.z += 0.1 * Math.sin(t * 0.37);
  p.pitch += rad(0.6 * Math.sin(t * 0.63 + 1));
  p.yaw += rad(0.8 * Math.sin(t * 0.47));
  p.roll += rad(0.3 * Math.sin(t * 0.39 + 2));
  for (const press of PRESSES) {
    const tau = t - press.t;
    if (tau <= 0 || tau > 1.5) continue;
    const k = Math.exp(-4.5 * tau) * Math.sin(13 * tau) * (press.tab ? 0.6 : 1);
    p.pitch -= k * 0.05 * (press.y / 4);
    p.yaw += k * 0.05 * (press.x / 6.4);
    p.z -= k * 0.16;
  }
  // At the end the last screen moves out to take its place in the ring.
  if (t > TIME.finale) {
    const q = spring(t - TIME.finale - 0.02, 4.4, 0.86);
    const phi = ringAngle(6, t);
    p.x = lerp(p.x, RING.radius * Math.sin(phi), q);
    p.y = lerp(p.y, -1, q);
    p.z = lerp(p.z, RING.radius * Math.cos(phi), q);
    p.yaw = lerp(p.yaw, Math.sin(phi) * 0.5, q);
    p.pitch = lerp(p.pitch, 0, q);
    p.roll = lerp(Math.atan2(Math.sin(p.roll), Math.cos(p.roll)), 0, q);
    p.scale = lerp(p.scale, RING.scale, q);
  }
  return p;
}

const rootOf = (p) => mat.chain(mat.t(p.x, p.y, p.z), mat.rz(p.roll), mat.ry(p.yaw), mat.rx(p.pitch), mat.s(p.scale));

/** The window turned about its own middle. Home to Files live on one face and the rest on the other. */
function sideRoot(pose, root, screenIndex) {
  const phi = screenIndex < FLIP ? pose.flip : pose.flip - Math.PI;
  if (phi === 0) return root;
  const mid = -WINDOW.t / 2;
  return mat.chain(root, mat.t(0, 0, mid), mat.ry(phi), mat.t(0, 0, -mid));
}

/** Layers of the window pull apart in flight, come back together, and bounce once as they land. */
function liftAt(t) {
  let e = 0;
  const arrive = t - TIME.windowIn;
  if (arrive > 0 && arrive < 2) e += STAGES[0].lift * smooth(arrive / 0.25) * Math.abs(1 - spring(arrive - 0.42, 8, 0.46));
  for (let i = 1; i < STAGES.length; i++) {
    const tau = t - STAGES[i].start;
    if (tau <= 0) break;
    if (tau < 2) e += STAGES[i].lift * smooth(tau / 0.18) * Math.abs(1 - spring(tau - 0.32, 9.5, 0.46));
  }
  return e;
}

function flightAmount(t) {
  let f = bump(t - TIME.windowIn, 0.34, 0.3);
  for (let i = 1; i < STAGES.length; i++) f += bump(t - STAGES[i].start, 0.33, 0.25);
  return Math.min(1, f);
}

// ---------- Building the parts

function buildPart(raw, byId) {
  const r = raw.slabRect;
  const parent = raw.parent ? byId[raw.parent] : null;
  const parentTop = parent ? parent.top : 0;
  const cx = (r.x + r.w / 2 - 640) / U;
  const cy = (400 - (r.y + r.h / 2)) / U;
  const part = {
    raw, parent, parentTop, level: parent ? parent.level + 1 : 1,
    cx, cy, w: r.w / U, h: r.h / U,
    radius: raw.radius / U, face: col(raw.face), border: raw.border ? col(raw.border) : null, borderWidth: raw.borderWidth / U,
    kind: raw.kind, thick: 0, bevel: 0.02, gap: 0.02, gloss: 1, wall: 0.72,
    // The float runs across the window as one slow wave, so the parts breathe together.
    phase: cx * 0.55 + cy * 0.4, float: { container: 0.05, slab: 0.085, ghost: 0.07 }[raw.kind] ?? 0.03,
    hoverable: raw.tag === 'button' || !!raw.row || !!raw.input,
    presses: [], delay: 0, exitDir: [0, 0], exitDelay: 0,
  };
  if (raw.kind === 'container') {
    part.gap = 0.1; part.thick = 0.18; part.bevel = 0.04; part.gloss = 0.8;
  } else if (raw.kind === 'slab') {
    part.gap = 0.06; part.thick = raw.primary ? 0.18 : 0.12; part.bevel = 0.03; part.gloss = raw.primary ? 1.5 : 1.1;
    if (raw.primary) { part.wall = 0.62; part.edge = 0.03; }
    if (raw.tag === 'other' && raw.border) { part.thick = 0.04; part.bevel = 0.014; part.gloss = 0.5; }
    if (raw.slabRect.w > 300 && raw.tag === 'button') { part.thick = 0.2; part.bevel = 0.05; }
    if (raw.track) { part.gap = 0.01; part.thick = 0.015; part.bevel = 0.006; part.gloss = 0.3; }
  } else if (raw.kind === 'ghost') {
    part.gap = 0.04; part.thick = 0.06; part.bevel = 0.02; part.face = brighten(part.face, 1.14); part.gloss = 0.7;
  } else {
    part.gap = 0.03;
    part.layers = raw.fontSize >= 18 ? 4 : 0;
  }
  part.zBase = parentTop + part.gap;
  part.top = part.zBase + part.thick;
  if (raw.sprite) {
    const s = raw.sprite;
    part.sx = (s.x + s.w / 2 - 640) / U - part.cx;
    part.sy = (400 - (s.y + s.h / 2)) / U - part.cy;
    part.sw = s.w / U;
    part.sh = s.h / U;
  }
  if (raw.altRect) {
    const s = raw.altRect;
    part.ax = (s.x + s.w / 2 - 640) / U - part.cx;
    part.ay = (400 - (s.y + s.h / 2)) / U - part.cy;
    part.aw = s.w / U;
    part.ah = s.h / U;
  }
  byId[raw.id] = part;
  return part;
}

async function loadPartTextures(part) {
  const raw = part.raw;
  if (raw.sprite) part.tex = await loadTexture(raw.sprite.file);
  if (raw.spriteOn) part.texOn = await loadTexture(raw.spriteOn);
  if (raw.spriteOff) part.texOff = await loadTexture(raw.spriteOff);
  if (raw.spriteAlt) part.texAlt = await loadTexture(raw.spriteAlt);
  if (raw.progressSprites) part.progress = await Promise.all(raw.progressSprites.map(loadTexture));
}

const assets = { screens: [], rail: {}, titles: {} };

const find = (screen, test, what) => {
  const part = screen.parts.find(test);
  if (!part) throw new Error(`${screen.name} has no ${what}`);
  return part;
};
const startingWith = (screen, text) => find(screen, (p) => p.raw.text.startsWith(text), text);
const labelled = (screen, text) => find(screen, (p) => p.kind === 'text' && p.raw.text === text, text);

/** Turns a row of choices into one where a single highlight slides over to the pressed choice. */
function slidingChoice(screen, group, toIndex, at) {
  const parts = screen.parts.filter((p) => p.raw.group === group);
  const from = parts.find((p) => p.raw.selected);
  const plain = parts.find((p) => !p.raw.selected);
  for (const p of parts) {
    p.face = plain.face; p.border = plain.border; p.borderWidth = plain.borderWidth;
    p.thick = plain.thick; p.gloss = plain.gloss; p.wall = plain.wall; p.edge = plain.edge;
    p.top = p.zBase + p.thick;
    p.spriteLater = true;
  }
  screen.pills.push({ parts, from, to: parts[toIndex], at });
  return parts[toIndex];
}

const QUICK_LINE = { freq: 16, damp: 0.8, dur: 0.3, depth: 0.1, x: 0, y: -0.1, rx: 0, ry: 0, hx: 0, hy: 0, scale: 1 };
const DROP_DOWN = { freq: 11, damp: 0.74, dur: 0.4, depth: 0.2, x: 0, y: 0, rx: -1.2, ry: 0, hx: 0, hy: 0.5, scale: 1 };
const CONTAINER_IN = { freq: 10, damp: 0.72, dur: 0.6, depth: 0.6, x: 0, y: 0, rx: 0, ry: 0, hx: 0, hy: 0, scale: 0.93 };
const RAIL_IN = { freq: 11, damp: 0.62, dur: 0.6, depth: 0.8, x: -0.9, y: 0, rx: 0, ry: -0.5, hx: -0.5, hy: 0, scale: 1 };

// What happens on each screen after it lands. `at` is when the pointer arrives, in seconds after the
// tab opened, and a press lands 0.05 seconds later.
const SCRIPTS = {
  Home(s) {
    const off = slidingChoice(s, 0, 1, 1.5);
    labelled(s, 'On').altAt = 1.53;
    s.stops = [{ at: 0.98, part: startingWith(s, 'settingsOpen'), dx: -1.4 }, { at: 1.43, part: off, press: true }];
  },
  Terminal(s) {
    const lines = s.parts.filter((p) => p.raw.line);
    const run = startingWith(s, 'play_arrowRun');
    const field = find(s, (p) => p.raw.input, 'input');
    const pressAt = 1.41;
    lines.forEach((line, k) => { line.style = QUICK_LINE; line.fixedDelay = 0.36 + k * 0.03; });
    lines.at(-2).fixedDelay = pressAt + 0.05;
    lines.at(-1).fixedDelay = pressAt + 0.13;
    const CHARS = 24;
    const CHAR_W = 9.03;
    const TEXT_X = 152;
    const TEXT_Y = 754.5;
    const typeFrom = 0.62;
    const typeTo = 1.16;
    field.spriteLater = true;
    field.after = (m, a, t) => {
      const clock = t - s.start;
      const typed = clock >= pressAt ? 0 : Math.floor(clamp((clock - typeFrom) / (typeTo - typeFrom)) * CHARS + 0.001);
      const sm = mat.mul(m, mat.t(field.sx, field.sy, field.thick + 0.004));
      // The label is a placeholder until the first letter, then floats up to the top of the field.
      const hasText = clock < pressAt ? smooth((clock - typeFrom) / 0.07) : 0;
      if (hasText < 1) drawSprite(field.texAlt, sm, field.sw, field.sh, a.opacity * (1 - hasText));
      if (hasText > 0) {
        const edge = (TEXT_X + typed * CHAR_W - field.raw.sprite.x) / field.raw.sprite.w;
        drawSprite(field.tex, sm, field.sw, field.sh, a.opacity * hasText, WHITE, 0, [edge, 0.002, 0.45]);
      }
      const blink = clock < typeTo || Math.sin(clock * 15) > 0;
      if (clock >= typeFrom - 0.1 && clock < pressAt + 0.8 && blink) {
        const x = (TEXT_X + typed * CHAR_W + 1 - 640) / U - field.cx;
        const y = (400 - lerp(748, TEXT_Y, hasText)) / U - field.cy;
        const mesh = slabMesh(0.02, 0.2, 0.01, 0.012, 0.004);
        drawSlab(mesh, mat.mul(m, mat.t(x, y, field.thick + 0.004)), { color: COLOR.text, w: 0.02, h: 0.2, radius: 0.01, opacity: a.opacity, gloss: 0, wall: 1, edge: 0 });
      }
    };
    s.stops = [{ at: pressAt - 0.05, part: run, press: true }, { at: 1.88, part: startingWith(s, 'Storage'), dx: -0.4 }];
  },
  Files(s) {
    const pressAt = 1.07;
    const bar = find(s, (p) => p.kind === 'container' && p.raw.text.startsWith('Copying'), 'copy bar');
    const label = find(s, (p) => p.raw.progressLabel, 'copy label');
    const track = find(s, (p) => p.raw.track, 'copy track');
    const cancel = find(s, (p) => p.parent === bar && p.raw.text === 'Cancel', 'cancel');
    [bar, label, track, cancel].forEach((p, i) => { p.style = DROP_DOWN; p.fixedDelay = pressAt + 0.04 + (i ? 0.05 : 0); });
    const from = pressAt + 0.14;
    const to = pressAt + 0.98;
    const progressAt = (clock) => clamp((clock - from) / (to - from));
    label.update = (a, clock) => { a.tex = label.progress[Math.floor(progressAt(clock) * 10)]; };
    track.after = (m, a, t) => {
      const k = progressAt(t - s.start);
      if (k <= 0) return;
      const mesh = slabMesh(track.w, track.h, 0.02, 0.022, 0.006);
      const model = mat.chain(m, mat.t((-track.w / 2) * (1 - k), 0, track.thick), mat.s(k, 1, 1));
      drawSlab(mesh, model, { color: COLOR.accent, w: track.w, h: track.h, radius: 0.02, opacity: a.opacity, gloss: 1.4, wall: 0.8, edge: 0.05 });
    };
    s.stops = [{ at: pressAt - 0.05, part: startingWith(s, 'content_copyCopy'), press: true }, { at: 1.68, part: startingWith(s, 'inventory_2main'), dx: -1.6 }];
  },
  Display(s) {
    const rate = slidingChoice(s, 0, 2, 1.15);
    const size = slidingChoice(s, 1, 3, 1.71);
    labelled(s, '90 Hz').altAt = 1.18;
    labelled(s, '1832 x 1832').altAt = 1.74;
    s.stops = [{ at: 1.08, part: rate, press: true }, { at: 1.64, part: size, press: true }];
  },
  Apps(s) {
    const pressAt = 1.05;
    const rows = s.parts.filter((p) => p.raw.row);
    const from = rows.find((p) => p.raw.text.includes('Sky Garden'));
    const to = rows.find((p) => p.raw.text.includes('Orbit Golf'));
    const highlight = from.face;
    for (const row of rows) {
      row.kind = 'text'; row.thick = 0; row.border = null; row.gap = 0.05;
      row.zBase = row.parentTop + row.gap;
      row.top = row.zBase;
    }
    rows[0].before = (root, t, E, states) => {
      const a = states.get(from.parent);
      if (!a) return;
      const k = spring(t - s.start - pressAt - 0.02, 14, 0.62);
      const y = lerp(from.cy, to.cy, k);
      const z = from.parentTop + E * (from.level - 1) + 0.01 + a.z;
      const mesh = slabMesh(from.w, from.h, 0.012, 0.024, 0.008);
      drawSlab(mesh, mat.mul(root, mat.t(from.cx + a.x, y + a.y, z)), { color: highlight, w: from.w, h: from.h, radius: 0.012, opacity: a.opacity, gloss: 0.5, wall: 1, edge: 0.04 });
    };
    labelled(s, 'Sky Garden').altAt = pressAt + 0.06;
    startingWith(s, 'com.example.skygardenVersion').altAt = pressAt + 0.09;
    s.stops = [{ at: pressAt - 0.05, part: to, dx: -1.4, press: true }, { at: 1.55, part: startingWith(s, 'play_arrowLaunch'), press: true }];
  },
  Utilities(s) {
    const warning = slidingChoice(s, 1, 3, 1.6);
    const lines = s.parts.filter((p) => p.raw.line);
    s.parts = s.parts.filter((p) => !p.raw.line);
    let rank = 0;
    for (const line of lines) {
      line.index = line.raw.logIndex;
      line.kept = /\s[WE]\s/.test(line.raw.text);
      line.rank = line.kept ? rank++ : -1;
    }
    s.log = { lines, box: lines[0].parent, filterAt: 1.62, scrollFrom: 0.5 };
    s.stops = [{ at: 1, part: startingWith(s, 'pausePause') }, { at: 1.52, part: warning, press: true }];
  },
  Settings(s) {
    const wired = slidingChoice(s, 0, 1, 1.09);
    s.stops = [{ at: 1.02, part: wired, press: true }, { at: 1.58, part: startingWith(s, 'refreshReconnect'), press: true }];
  },
};

/** Works out when each part arrives, and how it leaves when the next tab is pressed. */
function arrange(screen, openedFrom, nextTab) {
  const xs = screen.parts.map((p) => p.cx);
  const ys = screen.parts.map((p) => p.cy);
  const [x0, x1, y0, y1] = [Math.min(...xs), Math.max(...xs), Math.min(...ys), Math.max(...ys)];
  const { start, span, wave } = screen.enter;
  for (const p of screen.parts) {
    const nx = (p.cx - x0) / Math.max(0.01, x1 - x0);
    const ny = (y1 - p.cy) / Math.max(0.01, y1 - y0);
    // New parts follow behind the wave that cleared the old ones, mixed with a direction of their own.
    const r = Math.hypot(p.cx - openedFrom[0], p.cy - openedFrom[1]) / 13;
    p.delay = p.fixedDelay ?? start + span * (0.5 * r + 0.5 * (wave[0] * nx + wave[1] * ny)) + (p.parent ? 0.06 : 0);
    if (nextTab) {
      const dx = p.cx - nextTab[0];
      const dy = p.cy - nextTab[1];
      const d = Math.max(0.01, Math.hypot(dx, dy));
      p.exitDir = [dx / d, dy / d];
      p.exitDelay = d / 42;
    }
  }
  if (screen.log) {
    for (const line of screen.log.lines) line.delay = screen.log.box.delay + 0.1 + line.index * 0.012;
  }
}

async function loadAssets() {
  const manifest = await (await fetch('build/parts/parts.json')).json();
  const byId = {};
  for (const p of manifest.rail) {
    const part = buildPart(p, byId);
    await loadPartTextures(part);
    if (p.kind === 'container') assets.rail.base = part;
    else if (p.slabRect.x > 112) assets.rail.chip = part;
    else if (p.tag === 'button') (assets.rail.items ??= []).push(part);
    else assets.rail.mark = part;
  }
  const { base, mark, items, chip } = assets.rail;
  mark.delay = 0.08;
  chip.delay = 0.5;
  // The status chip has a slow pulse, like something that is really connected.
  chip.update = (a, clock) => { a.glow += 0.7 * Math.max(0, Math.sin(clock * 2.1)) ** 8; };
  items.forEach((item, i) => {
    item.delay = 0.12 + i * 0.05;
    item.plate = {
      ...item, kind: 'ghost', tex: null, face: brighten(base.face, 1.12), thick: 0.04, zBase: base.top + 0.03,
      bevel: 0.016, gloss: 0.6, border: null, borderWidth: 0, gap: 0.03, top: base.top + 0.07,
    };
  });

  assets.log = manifest.log;
  for (const stage of STAGES) {
    const raw = manifest.screens.find((s) => s.name === stage.name);
    const screen = { ...stage, parts: [], pills: [], stops: [] };
    for (const p of raw.parts) {
      const part = buildPart(p, byId);
      await loadPartTextures(part);
      screen.parts.push(part);
    }
    screen.flat = await loadTexture(raw.flat);
    SCRIPTS[stage.name](screen);
    const next = STAGES[stage.index + 1];
    if (!next) screen.exit = 'collapse';
    else if (next.move === 'flip') { screen.exit = 'hold'; screen.holdFor = 0.3; }
    else if (next.move === 'whip') { screen.exit = 'hold'; screen.holdFor = 0.24; }
    else screen.exit = 'blow';
    const openedFrom = stage.index === 0 ? [mark.cx, mark.cy] : [items[stage.index].cx, items[stage.index].cy];
    arrange(screen, openedFrom, next ? [items[next.index].cx, items[next.index].cy] : null);
    screen.parts.sort((a, b) => a.zBase - b.zBase);
    assets.screens.push(screen);
  }

  for (const [key, t] of Object.entries(manifest.titles)) {
    const title = { w: t.w / U, h: t.h / U };
    if (t.letters) title.letters = await Promise.all(t.letters.map(async (l) => ({ x: l.x / U, y: l.y / U, w: l.w / U, h: l.h / U, tex: await loadTexture(l.file) })));
    else title.tex = await loadTexture(t.file);
    assets.titles[key] = title;
  }

  // The pointer's route and every press along it.
  const POINTER_Z = 0.75;
  assets.screens.forEach((screen, i) => {
    for (const stop of screen.stops) {
      const x = stop.part.cx + (stop.dx ?? 0);
      const y = stop.part.cy + (stop.dy ?? 0);
      const t = screen.start + stop.at;
      pointerKeys.push({ t, screen: i, x, y, z: POINTER_Z });
      if (stop.press) {
        stop.part.presses.push(t + 0.05);
        PRESSES.push({ t: t + 0.05, x, y, screen: i, tab: false });
      }
    }
    const next = assets.screens[i + 1];
    if (next) {
      const item = items[i + 1];
      pointerKeys.push({ t: next.start - 0.06, screen: i, x: item.cx + 0.05, y: item.cy + 0.04, z: POINTER_Z, holdAt: next.start });
      item.presses.push(next.start);
      PRESSES.push({ t: next.start, x: item.cx, y: item.cy, screen: i, tab: true });
    }
  });
  PRESSES.sort((a, b) => a.t - b.t);
}

// ---------- Backdrop dots

const dots = (() => {
  let seed = 11;
  const rnd = () => { seed = (seed * 16807) % 2147483647; return seed / 2147483647; };
  const list = [];
  for (let y = -11; y <= 11; y++) {
    for (let x = -18; x <= 18; x++) {
      list.push({
        x: x * 2.3 + (rnd() - 0.5) * 1.9, y: y * 2.3 + (rnd() - 0.5) * 1.9, z: -7 - rnd() * 13,
        f1: 0.2 + rnd() * 0.35, f2: 0.2 + rnd() * 0.35, p1: rnd() * 6.28, p2: rnd() * 6.28,
      });
    }
  }
  return list;
})();
const DOT_PERIOD = [2.3 * 37, 2.3 * 23];

// A few near-camera specks, far out of focus, that sweep past when the view moves.
const dust = (() => {
  let seed = 29;
  const rnd = () => { seed = (seed * 16807) % 2147483647; return seed / 2147483647; };
  const list = [];
  for (let i = 0; i < 34; i++) list.push({ x: (rnd() - 0.5) * 22, y: (rnd() - 0.5) * 13, z: 6 + rnd() * 7, f: 0.15 + rnd() * 0.25, p: rnd() * 6.28 });
  return list;
})();

function dotMesh(count) {
  const vao = gl.createVertexArray();
  gl.bindVertexArray(vao);
  const corners = gl.createBuffer();
  gl.bindBuffer(gl.ARRAY_BUFFER, corners);
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
  gl.enableVertexAttribArray(0);
  gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
  const instances = gl.createBuffer();
  gl.bindBuffer(gl.ARRAY_BUFFER, instances);
  const data = new Float32Array(count * 4);
  gl.bufferData(gl.ARRAY_BUFFER, data, gl.DYNAMIC_DRAW);
  gl.enableVertexAttribArray(1);
  gl.vertexAttribPointer(1, 4, gl.FLOAT, false, 0, 0);
  gl.vertexAttribDivisor(1, 1);
  return { vao, instances, data, count };
}

const backdropDots = dotMesh(dots.length);
const dustDots = dotMesh(dust.length);
const pointerDot = dotMesh(1);

function drawDots(mesh, size, lens, opacity, base, hot) {
  const dp = dotProgram;
  gl.useProgram(dp.p);
  gl.uniformMatrix4fv(dp.u.uV, false, frame.v);
  gl.uniformMatrix4fv(dp.u.uP, false, frame.p);
  gl.uniform1f(dp.u.uSize, size);
  gl.uniform1f(dp.u.uLens, lens);
  gl.uniform1f(dp.u.uFocus, frame.focus);
  gl.uniform3fv(dp.u.uBase, base);
  gl.uniform3fv(dp.u.uHot, hot);
  gl.uniform1f(dp.u.uOpacity, opacity);
  gl.bindVertexArray(mesh.vao);
  gl.bindBuffer(gl.ARRAY_BUFFER, mesh.instances);
  gl.bufferSubData(gl.ARRAY_BUFFER, 0, mesh.data);
  gl.drawArraysInstanced(gl.TRIANGLE_STRIP, 0, 4, mesh.count);
}

const ribbonVao = gl.createVertexArray();
const ribbonBuffer = gl.createBuffer();
gl.bindVertexArray(ribbonVao);
gl.bindBuffer(gl.ARRAY_BUFFER, ribbonBuffer);
gl.enableVertexAttribArray(0);
gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 28, 0);
gl.enableVertexAttribArray(1);
gl.vertexAttribPointer(1, 4, gl.FLOAT, false, 28, 8);
gl.enableVertexAttribArray(2);
gl.vertexAttribPointer(2, 1, gl.FLOAT, false, 28, 24);

function drawRibbons(segments) {
  if (!segments.length) return;
  const data = new Float32Array(segments.length * 42);
  let o = 0;
  const push = (x, y, c, a, e) => { data[o++] = x; data[o++] = y; data[o++] = c[0]; data[o++] = c[1]; data[o++] = c[2]; data[o++] = a; data[o++] = e; };
  for (const s of segments) {
    const dx = (s.b[0] - s.a[0]) * W;
    const dy = (s.b[1] - s.a[1]) * H;
    const len = Math.hypot(dx, dy) || 1;
    const wa = (s.widthA ?? s.width) * 1.5;
    const wb = (s.widthB ?? s.width) * 1.5;
    const nx = -dy / len, ny = dx / len;
    const ax = (nx * wa) / W, ay = (ny * wa) / H, bx = (nx * wb) / W, by = (ny * wb) / H;
    const aa = s.alphaA ?? s.alpha;
    const ab = s.alphaB ?? s.alpha;
    push(s.a[0] - ax, s.a[1] - ay, s.color, aa, -1); push(s.a[0] + ax, s.a[1] + ay, s.color, aa, 1); push(s.b[0] + bx, s.b[1] + by, s.color, ab, 1);
    push(s.a[0] - ax, s.a[1] - ay, s.color, aa, -1); push(s.b[0] + bx, s.b[1] + by, s.color, ab, 1); push(s.b[0] - bx, s.b[1] - by, s.color, ab, -1);
  }
  gl.useProgram(ribbonProgram.p);
  gl.bindVertexArray(ribbonVao);
  gl.bindBuffer(gl.ARRAY_BUFFER, ribbonBuffer);
  gl.bufferData(gl.ARRAY_BUFFER, data, gl.DYNAMIC_DRAW);
  gl.drawArrays(gl.TRIANGLES, 0, segments.length * 6);
}

// ---------- Frame buffers

const RW = W * SS;
const RH = H * SS;
const FORMAT = floatBuffers ? gl.RGBA16F : gl.RGBA8;
const msaaFbo = gl.createFramebuffer();
const colorRb = gl.createRenderbuffer();
gl.bindRenderbuffer(gl.RENDERBUFFER, colorRb);
gl.renderbufferStorageMultisample(gl.RENDERBUFFER, MSAA, FORMAT, RW, RH);
const depthRb = gl.createRenderbuffer();
gl.bindRenderbuffer(gl.RENDERBUFFER, depthRb);
gl.renderbufferStorageMultisample(gl.RENDERBUFFER, MSAA, gl.DEPTH_COMPONENT24, RW, RH);
gl.bindFramebuffer(gl.FRAMEBUFFER, msaaFbo);
gl.framebufferRenderbuffer(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.RENDERBUFFER, colorRb);
gl.framebufferRenderbuffer(gl.FRAMEBUFFER, gl.DEPTH_ATTACHMENT, gl.RENDERBUFFER, depthRb);

function makeTarget(w, h) {
  const tex = gl.createTexture();
  gl.bindTexture(gl.TEXTURE_2D, tex);
  gl.texStorage2D(gl.TEXTURE_2D, 1, FORMAT, w, h);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
  const fbo = gl.createFramebuffer();
  gl.bindFramebuffer(gl.FRAMEBUFFER, fbo);
  gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, tex, 0);
  return { tex, fbo, w, h };
}

const resolved = makeTarget(RW, RH);
const accum = makeTarget(W, H);
const bloomLevels = [2, 4, 8, 16, 32].map((d) => makeTarget(Math.round(W / d), Math.round(H / d)));

// ---------- Drawing

const frame = {
  vp: null, v: null, p: null, eye: null, focus: 21, lens: 0.05, spot: [0, 0, 0],
  pose: null, root: null, front: null, back: null, appFade: 1, E: 0,
  pointer: { world: [0, 0, 0], local: null, hover: 0, opacity: 0, ring: 0 },
  ripples: [], shocks: [], travel: 0,
};

/** Sends this render's camera and lights to the shaders that use them. */
function applyCamera(lamp, sweep, ripple, rippleAmp) {
  const sl = slabProgram;
  gl.useProgram(sl.p);
  gl.uniformMatrix4fv(sl.u.uVP, false, frame.vp);
  gl.uniform3fv(sl.u.uCam, frame.eye);
  gl.uniform3fv(sl.u.uRim, COLOR.rim);
  gl.uniform4fv(sl.u.uLamp, lamp);
  gl.uniform4fv(sl.u.uSweep, sweep);
  gl.uniform4fv(sl.u.uRipple, ripple);
  gl.uniform1f(sl.u.uRippleAmp, rippleAmp);
  gl.useProgram(spriteProgram.p);
  gl.uniformMatrix4fv(spriteProgram.u.uVP, false, frame.vp);
  gl.useProgram(shadowProgram.p);
  gl.uniformMatrix4fv(shadowProgram.u.uVP, false, frame.vp);
}

function drawSlab(mesh, model, o) {
  const { p, u } = slabProgram;
  gl.useProgram(p);
  gl.uniformMatrix4fv(u.uModel, false, model);
  gl.uniform3fv(u.uColor, o.color);
  gl.uniform3fv(u.uBorder, o.border ?? o.color);
  gl.uniform2f(u.uHalf, o.w / 2, o.h / 2);
  gl.uniform1f(u.uRadius, o.radius);
  gl.uniform1f(u.uBorderWidth, o.border ? o.borderWidth : 0);
  gl.uniform1f(u.uOpacity, o.opacity);
  gl.uniform1f(u.uGloss, o.gloss ?? 1);
  gl.uniform1f(u.uWall, o.wall ?? 0.72);
  gl.uniform1f(u.uEdge, o.edge ?? 0.06);
  gl.uniform3f(u.uSpot, frame.spot[0], frame.spot[1], frame.spot[2] * (o.spot ?? 1));
  gl.depthMask(o.opacity > 0.995);
  gl.bindVertexArray(mesh.vao);
  gl.drawElements(gl.TRIANGLES, mesh.count, gl.UNSIGNED_INT, 0);
}

function drawSprite(tex, model, w, h, opacity, tint = WHITE, corner = 0, wipe = NO_WIPE) {
  const { p, u } = spriteProgram;
  gl.useProgram(p);
  gl.uniformMatrix4fv(u.uModel, false, mat.mul(model, mat.s(w, h, 1)));
  gl.uniform1f(u.uOpacity, opacity);
  gl.uniform3fv(u.uTint, tint);
  gl.uniform1f(u.uCorner, corner);
  gl.uniform2f(u.uSize, w, h);
  gl.uniform3fv(u.uWipe, wipe);
  gl.activeTexture(gl.TEXTURE0);
  gl.bindTexture(gl.TEXTURE_2D, tex);
  gl.depthMask(false);
  gl.bindVertexArray(quadVao);
  gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
}

function drawShadow(model, w, h, radius, blur, strength) {
  const { p, u } = shadowProgram;
  gl.useProgram(p);
  gl.uniformMatrix4fv(u.uModel, false, model);
  gl.uniform2f(u.uSize, w + blur * 4, h + blur * 4);
  gl.uniform2f(u.uHalf, w / 2, h / 2);
  gl.uniform1f(u.uRadius, Math.min(radius, Math.min(w, h) / 2));
  gl.uniform1f(u.uBlur, blur);
  gl.uniform1f(u.uStrength, strength);
  gl.depthMask(false);
  gl.bindVertexArray(quadVao);
  gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
}

/** Text with a few darker copies stacked behind it, so it has visible depth at an angle. */
function drawExtruded(tex, model, w, h, opacity, slices, spacing, front = WHITE) {
  const back = [0.3, 0.2, 0.62];
  for (let i = slices; i >= 1; i--) {
    const k = 1 - i / (slices + 1);
    drawSprite(tex, mat.mul(model, mat.t(0, 0, -i * spacing)), w, h, opacity, [back[0] * (0.45 + k * 0.55), back[1] * (0.45 + k * 0.55), back[2] * (0.45 + k * 0.55)]);
  }
  drawSprite(tex, model, w, h, opacity, front);
}

function clearDepth() {
  gl.depthMask(true);
  gl.clear(gl.DEPTH_BUFFER_BIT);
}

// ---------- How a part moves

const EXIT = 0.24;

/** Everything that moves a part at this moment: arriving, leaving, floating, and the pointer. */
function partState(part, screen, t, parentZ = 0) {
  const S = part.style ?? (part.kind === 'container' ? CONTAINER_IN : screen.enter);
  const clock = t - screen.start;
  const tIn = clock - part.delay;
  if (tIn <= 0) return null;
  const left = 1 - spring(tIn, S.freq, S.damp);
  const a = {
    opacity: smooth(tIn / 0.2),
    x: S.x * left, y: S.y * left,
    // The fall to the surface does not overshoot, so a part never sinks through what is under it.
    z: S.depth * (1 - easeOutExpo(tIn / S.dur)),
    rx: S.rx * left, ry: S.ry * left, hx: S.hx, hy: S.hy,
    scale: 1 + (S.scale - 1) * left,
    glow: 0, altK: 0, tex: null,
  };
  const gone = t - screen.end;
  if (gone > 0) {
    if (screen.exit === 'blow') {
      const k = (gone - part.exitDelay) / EXIT;
      if (k >= 1) return null;
      if (k > 0) {
        const push = k * k;
        a.x += part.exitDir[0] * 1.7 * push;
        a.y += part.exitDir[1] * 1.7 * push;
        a.z += 2.2 * push;
        a.rx -= part.exitDir[1] * 1.0 * push;
        a.ry += part.exitDir[0] * 1.0 * push;
        a.opacity *= 1 - smooth(k * 1.1);
      }
    } else if (screen.exit === 'hold') {
      if (gone > screen.holdFor) return null;
    } else if (screen.exit === 'collapse') {
      const k = gone / 0.24;
      if (k >= 1) return null;
      a.opacity *= 1 - smooth(k);
    }
  }
  // Only ever floats upward, and a part rides on whatever it sits on, so it cannot sink into it.
  a.z += part.float * (0.5 + 0.5 * Math.sin(t * 1.5 + part.phase));
  if (parentZ) a.z += parentZ;
  // Small parts wobble. Big ones stay flat, or their far edge would cut through what sits on them.
  if (part.kind !== 'container') {
    a.rx += 0.006 * Math.sin(t * 0.9 + part.phase * 1.7);
    a.ry += 0.006 * Math.min(1, 1.2 / part.w) * Math.sin(t * 0.8 + part.phase * 2.3);
  }
  for (const r of frame.ripples) {
    const d = Math.hypot(part.cx - r.x, part.cy - r.y) - r.radius;
    a.z += r.amp * Math.exp(-((d / r.width) ** 2));
  }
  const local = frame.pointer.local;
  if (part.hoverable && local && frame.pointer.hover > 0) {
    const ox = local[0] - part.cx;
    const oy = local[1] - part.cy;
    const gap = Math.hypot(Math.max(Math.abs(ox) - part.w / 2, 0), Math.max(Math.abs(oy) - part.h / 2, 0));
    const near = (1 - smooth(gap / 0.5)) * frame.pointer.hover;
    if (near > 0) {
      a.z += 0.15 * near;
      a.glow += near;
      // It leans away from the pointer, as if being pressed on.
      a.rx -= 0.05 * near * Math.min(1, 1.6 / part.h) * clamp(oy / (part.h / 2), -1, 1);
      a.ry += 0.05 * near * Math.min(1, 1.6 / part.w) * clamp(ox / (part.w / 2), -1, 1);
    }
  }
  for (const press of part.presses) {
    a.z -= 0.1 * bump(t, press + 0.03, 0.06);
    a.glow += 0.8 * bump(t, press + 0.04, 0.12);
  }
  if (part.altAt !== undefined) a.altK = smooth((clock - part.altAt) / 0.14);
  part.update?.(a, clock);
  return a;
}

function partMatrix(part, root, a, E) {
  let m = mat.mul(root, mat.t(part.cx + a.x, part.cy + a.y, part.zBase + E * part.level + a.z));
  if (a.rx !== 0 || a.ry !== 0) {
    const hx = a.hx * part.w;
    const hy = a.hy * part.h;
    m = mat.chain(m, mat.t(hx, hy, 0), mat.rx(a.rx), mat.ry(a.ry), mat.t(-hx, -hy, 0));
  }
  return a.scale !== 1 ? mat.mul(m, mat.s(a.scale)) : m;
}

function drawText(part, tex, model, w, h, opacity, tint) {
  if (part.layers) drawExtruded(tex, model, w, h, opacity, part.layers, 0.011, tint);
  else drawSprite(tex, model, w, h, opacity, tint);
}

function drawPart(part, root, a, E, withSprite = true) {
  if (a.opacity <= 0.002) return null;
  const m = partMatrix(part, root, a, E);
  if (part.thick > 0) {
    const floor = part.parentTop + E * (part.level - 1);
    const lift = Math.max(0, part.gap + E + a.z);
    const blur = 0.045 + lift * 0.32 + (part.kind === 'container' ? 0.05 : 0);
    const off = 0.018 + lift * 0.36;
    const sm = mat.mul(root, mat.t(part.cx + a.x + off * 0.55, part.cy + a.y - off, floor + 0.004));
    drawShadow(sm, part.w * a.scale, part.h * a.scale, part.radius, blur, (a.opacity * (part.kind === 'container' ? 0.5 : 0.62)) / (1 + lift * 1.4));
    const color = a.glow > 0 ? brighten(part.face, 1 + 0.16 * a.glow) : part.face;
    const mesh = slabMesh(part.w, part.h, part.radius, part.thick, part.bevel);
    drawSlab(mesh, m, {
      color, border: part.border, borderWidth: part.borderWidth, w: part.w, h: part.h,
      radius: part.radius, opacity: a.opacity, gloss: part.gloss, wall: part.wall, edge: part.edge,
    });
  }
  if (part.tex && withSprite) {
    const glow = 1 + 0.14 * a.glow;
    const tint = a.glow > 0 ? [glow, glow, glow] : WHITE;
    const z = part.thick + 0.004;
    const sm = mat.mul(m, mat.t(part.sx, part.sy, z));
    if (a.altK < 1) drawText(part, a.tex ?? part.tex, sm, part.sw, part.sh, a.opacity * (1 - a.altK), tint);
    if (a.altK > 0) drawText(part, part.texAlt, mat.mul(m, mat.t(part.ax, part.ay, z)), part.aw, part.ah, a.opacity * a.altK, tint);
  }
  return m;
}

const PILL_T = 0.18;

/** The selected highlight in a row of choices, sliding across, with the labels on top of it. */
function drawPill(pill, screen, root, t, E, states) {
  const clock = t - screen.start - pill.at;
  const k = spring(clock, 14, 0.6);
  const A = states.get(k < 0.5 ? pill.from : pill.to) ?? states.get(pill.from);
  if (!A) return;
  const { from, to } = pill;
  const cx = lerp(from.cx, to.cx, k);
  const w = lerp(from.w, to.w, k) * (1 + 0.16 * bump(clock, 0.09, 0.08));
  let m = mat.mul(root, mat.t(cx + A.x, from.cy + A.y, from.zBase + E * from.level + A.z));
  if (A.rx !== 0 || A.ry !== 0) {
    const hx = A.hx * from.w;
    const hy = A.hy * from.h;
    m = mat.chain(m, mat.t(hx, hy, 0), mat.rx(A.rx), mat.ry(A.ry), mat.t(-hx, -hy, 0));
  }
  if (A.scale !== 1) m = mat.mul(m, mat.s(A.scale));
  const mesh = slabMesh(to.w, to.h, to.radius, PILL_T, 0.03);
  drawSlab(mesh, mat.mul(m, mat.s(w / to.w, 1, 1)), {
    color: COLOR.accent, w: to.w, h: to.h, radius: to.radius, opacity: A.opacity, gloss: 1.5, wall: 0.62, edge: 0.03,
  });
  for (const part of pill.parts) {
    const a = states.get(part);
    if (!a) continue;
    const on = clamp(1 - Math.abs(cx - part.cx) / (part.w * 0.62));
    const sm = mat.mul(partMatrix(part, root, a, E), mat.t(part.sx, part.sy, PILL_T + 0.02));
    if (on < 1) drawSprite(part.texOff, sm, part.sw, part.sh, a.opacity * (1 - on));
    if (on > 0) drawSprite(part.texOn, sm, part.sw, part.sh, a.opacity * on);
  }
}

const LOG_RATE = 9;
const LOG_ROWS = 21;

/** Logcat: lines keep streaming upward, and pressing Warning clears the rest and closes the gaps. */
function drawLog(screen, root, t, E, states) {
  const { lines, box, filterAt, scrollFrom } = screen.log;
  const A = states.get(box);
  if (!A) return;
  const clock = t - screen.start;
  const scrolled = Math.max(0, clock - scrollFrom) * LOG_RATE;
  // The rate rises and falls a little, because logs arrive in bursts.
  const scroll = scrolled + 0.5 * Math.sin(scrolled * 0.8);
  const atFilter = Math.max(0, filterAt - scrollFrom) * LOG_RATE;
  const filtered = clock - filterAt;
  const lh = assets.log.lineHeight / U;
  const z = box.zBase + E * box.level + box.thick + 0.024 + A.z;
  for (const line of lines) {
    let row = line.index - scroll;
    let op = smooth((clock - line.delay) / 0.12);
    if (filtered > 0) {
      if (!line.kept) {
        op *= 1 - smooth(filtered / 0.16);
      } else if (line.index - atFilter > LOG_ROWS) {
        // This line arrives after the filter was set, straight into its compact place.
        row = line.rank;
        op = smooth((scroll - (line.index - LOG_ROWS)) * 2);
      } else {
        row = lerp(row, line.rank, spring(filtered - line.rank * 0.012, 13, 0.74));
      }
    }
    const edges = clamp((row + 0.55) / 0.35) * clamp((LOG_ROWS - 0.15 - row) / 0.6);
    const opacity = op * edges * A.opacity;
    if (opacity <= 0.002) continue;
    const s = line.raw.sprite;
    const x = (s.x + s.w / 2 - 640) / U;
    const y = (400 - (s.y + s.h / 2)) / U - row * lh;
    drawSprite(line.tex, mat.mul(root, mat.t(x + A.x, y + A.y, z)), s.w / U, s.h / U, opacity);
  }
}

function drawScreen(screen, t) {
  const root = sideRoot(frame.pose, frame.root, screen.index);
  const E = frame.E;
  const states = new Map();
  for (const part of screen.parts) {
    part.before?.(root, t, E, states);
    const a = partState(part, screen, t, part.parent ? states.get(part.parent)?.z : 0);
    if (!a) continue;
    states.set(part, a);
    const m = drawPart(part, root, a, E, !part.spriteLater);
    if (m) part.after?.(m, a, t);
  }
  for (const pill of screen.pills) drawPill(pill, screen, root, t, E, states);
  if (screen.log) drawLog(screen, root, t, E, states);
}

// ---------- The camera

function camera(t) {
  const F = TIME.finale;
  const intro = { yaw: rad(-13 + t * 1.6), pitch: rad(5), dist: 16.4 - t * 0.22, fov: 28 };
  // The camera leans after the window, a beat behind it, and widens out as the window flies.
  let cx = STAGES[0].pos[0];
  let cy = STAGES[0].pos[1];
  let fov = 28;
  let back = 0;
  for (let i = 1; i < STAGES.length; i++) {
    const tau = t - STAGES[i].start;
    if (tau <= 0) break;
    const k = spring(tau - 0.05, 4.2, 0.85);
    cx += (STAGES[i].pos[0] - STAGES[i - 1].pos[0]) * k;
    cy += (STAGES[i].pos[1] - STAGES[i - 1].pos[1]) * k;
    const flight = bump(tau, 0.3, 0.24);
    fov += (STAGES[i].move === 'dolly' ? 36 : 7) * flight;
    back += (STAGES[i].move === 'dolly' ? 0 : 1.4) * flight;
  }
  const app = {
    yaw: rad(cx * 0.42 + 0.22 * Math.sin(t * 0.83 + 0.4) + 0.12 * Math.sin(t * 1.91)),
    pitch: rad(3 + cy * 0.4 + 0.16 * Math.sin(t * 0.71) + 0.1 * Math.sin(t * 1.37 + 2)),
    dist: (22 * Math.tan(rad(14))) / Math.tan(rad(fov / 2)) + back - clamp((t - 3) / 17) * 0.6,
    fov,
  };
  const ending = { yaw: rad(-5 + (t - F) * 1.5), pitch: rad(12), dist: 35 - (t - F) * 0.28, fov: 30 };
  const toApp = easeInOut((t - 2.2) / 1.05);
  const toEnd = spring(t - F - 0.04, 3.4, 0.92);
  const mix = (key) => lerp(lerp(intro[key], app[key], toApp), ending[key], toEnd);
  const target = [
    lerp(lerp(0, cx * 0.1, toApp), 0, toEnd),
    lerp(lerp(0.05, cy * 0.08, toApp), -0.15, toEnd),
    0,
  ];
  const yaw = mix('yaw');
  const pitch = mix('pitch');
  const dist = mix('dist');
  const eye = [
    target[0] + dist * Math.sin(yaw) * Math.cos(pitch),
    target[1] + dist * Math.sin(pitch),
    target[2] + dist * Math.cos(yaw) * Math.cos(pitch),
  ];
  const tilt = rad(0.12 * Math.sin(t * 0.59));
  return { eye, target, up: [Math.sin(tilt), Math.cos(tilt), 0], fov: mix('fov'), toApp, toEnd };
}

// ---------- The pointer

const cursorEase = (u) => smooth(u) * 0.35 + easeOutExpo(u) * 0.65;

function keyWorld(key, t) {
  if (key.holdAt !== undefined && t >= key.holdAt) {
    if (!key.held) {
      const pose = windowPose(key.holdAt);
      key.held = mat.apply(sideRoot(pose, rootOf(pose), key.screen), [key.x, key.y, key.z]);
    }
    return key.held;
  }
  return mat.apply(sideRoot(frame.pose, frame.root, key.screen), [key.x, key.y, key.z]);
}

/** Where the pointer is now. It stays put in space when a tab is pressed and the window jumps away. */
function pointerState(t) {
  const first = pointerKeys[0];
  const opacity = smooth((t - (first.t - 0.55)) / 0.3) * (1 - smooth((t - (TIME.finale - 0.4)) / 0.3));
  if (opacity <= 0.002) return { world: [0, 0, 0], local: null, hover: 0, opacity: 0 };
  let i = pointerKeys.length - 1;
  while (i >= 0 && pointerKeys[i].t > t) i--;
  if (i < 0) {
    const u = clamp((t - (first.t - 0.55)) / 0.55);
    const e = cursorEase(u);
    const to = keyWorld(first, t);
    const from = [10.5, -7.5, 3];
    return { world: [lerp(from[0], to[0], e), lerp(from[1], to[1], e), lerp(from[2], to[2], e)], local: [first.x, first.y], hover: smooth((u - 0.6) / 0.4), opacity };
  }
  const A = pointerKeys[i];
  const B = pointerKeys[i + 1];
  if (!B) return { world: keyWorld(A, t), local: [A.x, A.y], hover: 1, opacity };
  const dur = Math.min(B.t - A.t - 0.04, 0.46);
  const u = clamp((t - (B.t - dur)) / dur);
  const e = cursorEase(u);
  const a = keyWorld(A, t);
  const b = keyWorld(B, t);
  const arc = 0.14 * Math.sin(Math.PI * e);
  const world = [
    lerp(a[0], b[0], e) - (b[1] - a[1]) * arc,
    lerp(a[1], b[1], e) + (b[0] - a[0]) * arc,
    lerp(a[2], b[2], e),
  ];
  const detached = A.holdAt !== undefined && t >= A.holdAt;
  return {
    world,
    local: detached ? [B.x, B.y] : [lerp(A.x, B.x, e), lerp(A.y, B.y, e)],
    hover: detached ? smooth((u - 0.6) / 0.4) : 1,
    opacity,
  };
}

/** A VR controller's pointer: a beam rising from below the frame, ending in a dot and a ring. */
function drawPointer(t) {
  const { world, opacity } = frame.pointer;
  if (opacity <= 0.002) return;
  const end = project(frame.vp, world);
  const hand = [0.42, -1.25];
  const tail = [lerp(end[0], hand[0], 0.3), lerp(end[1], hand[1], 0.3)];
  gl.disable(gl.DEPTH_TEST);
  gl.disable(gl.CULL_FACE);
  const segments = [{ a: tail, b: end, widthA: 3.6, widthB: 2.2, alphaA: 0, alphaB: 0.55 * opacity, color: COLOR.rim }];
  // The ring tightens as the pointer presses, then spreads out and fades.
  let squeeze = 0;
  let spread = 0;
  for (const press of PRESSES) {
    const tau = t - press.t;
    squeeze += bump(tau, 0.02, 0.06);
    if (tau > 0 && tau < 0.5) spread = Math.max(spread, 1 - tau / 0.5);
  }
  const ring = (radius, alpha) => {
    const STEPS = 28;
    for (let i = 0; i < STEPS; i++) {
      const a0 = (i / STEPS) * Math.PI * 2;
      const a1 = ((i + 1) / STEPS) * Math.PI * 2;
      segments.push({
        a: [end[0] + (Math.cos(a0) * radius * 2) / W, end[1] + (Math.sin(a0) * radius * 2) / H],
        b: [end[0] + (Math.cos(a1) * radius * 2) / W, end[1] + (Math.sin(a1) * radius * 2) / H],
        width: 1.6, alpha, color: COLOR.rim,
      });
    }
  };
  ring(15 - 6 * squeeze, 0.65 * opacity);
  if (spread > 0) ring(15 + (1 - spread) * 34, 0.5 * spread * spread * opacity);
  drawRibbons(segments);
  pointerDot.data.set([world[0], world[1], world[2], 0]);
  drawDots(pointerDot, 0.075 * (1 - 0.25 * squeeze), 0, opacity * 1.2, WHITE, WHITE);
  gl.enable(gl.CULL_FACE);
  gl.enable(gl.DEPTH_TEST);
}

// ---------- Backdrop

function attractAt(t) {
  const intro = [Math.sin(t * 0.9) * 7, 1.2 + Math.sin(t * 1.3 + 1) * 3.2, -9];
  const end = [Math.sin(t * 0.8) * 13, 1.5 + Math.cos(t * 0.6) * 4, -9];
  // In the app the web stays on the free side of the frame, and swings across whenever the window does.
  let x = STAGES[0].caption.x * 1.3;
  let y = STAGES[0].caption.y * 0.7;
  for (let i = 1; i < STAGES.length; i++) {
    const tau = t - STAGES[i].start;
    if (tau <= 0) break;
    const k = spring(tau - 0.1, 3.6, 0.8);
    x += (STAGES[i].caption.x - STAGES[i - 1].caption.x) * 1.3 * k;
    y += (STAGES[i].caption.y - STAGES[i - 1].caption.y) * 0.7 * k;
  }
  const app = [x + Math.sin(t * 0.55) * 1.8, y + Math.sin(t * 0.41) * 1.6, -8];
  const inApp = easeInOut((t - 2.6) / 1.1) * (1 - easeInOut((t - TIME.finale) / 1.2));
  return [0, 1, 2].map((i) => lerp(t < TIME.finale ? intro[i] : end[i], app[i], inApp));
}

function drawBackdrop(t) {
  const { p, u } = backgroundProgram;
  gl.disable(gl.DEPTH_TEST);
  gl.disable(gl.BLEND);
  gl.useProgram(p);
  gl.uniform2f(u.uCenter, Math.sin(t * 0.2) * 0.15 + frame.pose.x * 0.012, 0.05);
  gl.bindVertexArray(quadVao);
  gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
  gl.enable(gl.BLEND);
  gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);

  const fade = smooth(t / 0.9);
  const attract = attractAt(t);
  const REACH = 8.5;
  const LINK = 4.2;
  const PULL = 0.16;
  const strength = smooth((t - 0.2) / 1.0);
  const near = [];
  const data = backdropDots.data;
  dots.forEach((d, i) => {
    let x = wrap(d.x + Math.sin(t * d.f1 + d.p1) * 0.4 - frame.travel, DOT_PERIOD[0]);
    let y = wrap(d.y + Math.cos(t * d.f2 + d.p2) * 0.4, DOT_PERIOD[1]);
    let z = d.z;
    // Each press pushes the dots outward in a ring.
    for (const s of frame.shocks) {
      const ox = x - s.x;
      const oy = y - s.y;
      const dist = Math.max(0.01, Math.hypot(ox, oy));
      const wave = s.amp * Math.exp(-(((dist - s.radius) / 2.6) ** 2));
      x += (ox / dist) * wave;
      y += (oy / dist) * wave;
      z += wave * 0.5;
    }
    const close = Math.max(0, 1 - Math.hypot(x - attract[0], y - attract[1], z - attract[2]) / REACH) * strength;
    x += (attract[0] - x) * close * PULL;
    y += (attract[1] - y) * close * PULL;
    z += (attract[2] - z) * close * PULL;
    data[i * 4] = x; data[i * 4 + 1] = y; data[i * 4 + 2] = z; data[i * 4 + 3] = close;
    if (close > 0) near.push({ pos: [x, y, z], close });
  });
  const segments = [];
  const center = project(frame.vp, attract);
  for (let i = 0; i < near.length; i++) {
    const a = project(frame.vp, near[i].pos);
    segments.push({ a, b: center, width: 1.5, alpha: near[i].close * 0.42 * fade, color: COLOR.accent });
    for (let j = i + 1; j < near.length; j++) {
      const gap = Math.hypot(near[i].pos[0] - near[j].pos[0], near[i].pos[1] - near[j].pos[1], near[i].pos[2] - near[j].pos[2]);
      if (gap < LINK) {
        segments.push({ a, b: project(frame.vp, near[j].pos), width: 1.5, alpha: Math.min(near[i].close, near[j].close) * (1 - gap / LINK) * 1.1 * fade, color: COLOR.accent });
      }
    }
  }
  drawRibbons(segments);
  // Dots smear thin at whip speed, so they get brighter to stay visible as streaks.
  drawDots(backdropDots, 0.062, 0.1 + frame.lens * 2.6, fade * (1 + 3 * frame.pose.streak), COLOR.dotBase, COLOR.dotHot);
}

function drawDust(t) {
  const data = dustDots.data;
  dust.forEach((d, i) => {
    data[i * 4] = wrap(d.x + Math.sin(t * d.f + d.p) * 0.5 - frame.travel, 22);
    data[i * 4 + 1] = d.y + Math.cos(t * d.f * 1.3 + d.p) * 0.4;
    data[i * 4 + 2] = d.z;
    data[i * 4 + 3] = 0;
  });
  gl.disable(gl.DEPTH_TEST);
  drawDots(dustDots, 0.028, 0.16 + frame.lens * 0.6, 0.2 * smooth(t / 0.9), COLOR.dotHot, COLOR.dotHot);
  gl.enable(gl.DEPTH_TEST);
}

// ---------- Scene pieces

const P_W = 1.8;
const P_GAP = 0.5;
const lockupLeft = () => -(P_W + P_GAP + assets.titles.word.w) / 2;

/** The logo lockup: the extruded P with the name beside it, each letter arriving on its own. */
function drawLockup(root, tIn, opacity, showTagline, showLink, leaving = 0, withLogo = true) {
  const T = assets.titles;
  const left = lockupLeft();
  const logoIn = spring(tIn - 0.02, 5.8, 0.72);
  const logoFade = smooth((tIn - 0.02) / 0.55) * opacity;
  if (withLogo && logoFade > 0.002) {
    const spin = (1 - logoIn) * 1.15;
    const m = mat.chain(root, mat.t(left + P_W / 2, 0.08, (1 - logoIn) * -3.5), mat.ry(spin), mat.t(0, 0, -0.31));
    gl.disable(gl.CULL_FACE);
    drawSlab(logoMesh, m, { color: COLOR.accent, w: 10, h: 10, radius: 0, opacity: logoFade, gloss: 0.9, wall: 0.55, edge: 0.03, spot: 0 });
    gl.enable(gl.CULL_FACE);
  }
  const wordX = left + P_W + P_GAP;
  T.word.letters.forEach((l, i) => {
    const e = tIn - 0.32 - i * 0.042;
    if (e <= 0) return;
    const land = spring(e, 9.5, 0.62);
    const fall = 1 - easeOutExpo(e / 0.8);
    let a = smooth(e / 0.3) * opacity;
    const x = wordX + l.x + l.w / 2;
    const y = 0.34 + T.word.h / 2 - l.y - l.h / 2;
    const bottom = -l.h / 2;
    let m = mat.t(x, y, fall * 1.5);
    if (leaving > 0) {
      // The letters get thrown outward as the window comes through them.
      const k = clamp((leaving - i * 0.014) / 0.36);
      const push = k * k;
      a *= 1 - smooth(k * 1.15);
      m = mat.chain(m, mat.t((i - 5.5) * 0.5 * push, (i % 2 ? 0.9 : -0.9) * push, 4.4 * push), mat.ry((i - 5.5) * 0.17 * push), mat.rx((i % 2 ? -1 : 1) * 0.7 * push));
    }
    if (a <= 0.002) return;
    m = mat.chain(root, m, mat.t(0, bottom, 0), mat.rx((1 - land) * 0.9), mat.t(0, -bottom, 0));
    drawExtruded(l.tex, m, l.w, l.h, a, 16, 0.017);
  });
  const small = (item, y, delay) => {
    const e = tIn - delay;
    if (e <= 0) return;
    const land = 1 - easeOutExpo(e / 0.7);
    const a = smooth(e / 0.42) * opacity * (1 - smooth(leaving / 0.16));
    if (a <= 0.002) return;
    const m = mat.mul(root, mat.t(wordX + item.w / 2 + 0.04, y, land * 0.9));
    drawExtruded(item.tex, m, item.w, item.h, a, 3, 0.01);
  };
  if (showTagline) small(T.tagline, -0.66, 0.95);
  if (showLink) small(T.link, -1.22, 1.25);
}

/** The P leaves the lockup, spins across and becomes the mark at the top of the rail. */
function drawFlyingLogo(t, lockupRoot) {
  const k = easeInOut((t - TIME.logoExit) / 0.74);
  if (k <= 0 || k >= 1) return;
  const { mark } = assets.rail;
  const from = mat.apply(lockupRoot, [lockupLeft() + P_W / 2, 0.08, 0]);
  const to = mat.apply(frame.front, [mark.cx, mark.cy, 0.3]);
  const pos = [lerp(from[0], to[0], k), lerp(from[1], to[1], k), lerp(from[2], to[2], k) + 2.6 * Math.sin(Math.PI * k)];
  const pose = frame.pose;
  const m = mat.chain(mat.t(...pos), mat.rz(pose.roll * k), mat.ry(pose.yaw * k + k * Math.PI * 2), mat.rx(pose.pitch * k), mat.s(lerp(1, 0.15, k)), mat.t(0, 0, -0.31));
  gl.disable(gl.CULL_FACE);
  drawSlab(logoMesh, m, { color: COLOR.accent, w: 10, h: 10, radius: 0, opacity: 1 - smooth((k - 0.9) / 0.1), gloss: 0.9, wall: 0.55, edge: 0.03, spot: 0 });
  gl.enable(gl.CULL_FACE);
}

function drawBody(model, thickness, opacity, dim = 1, gloss = 1.6) {
  const mesh = slabMesh(WINDOW.w, WINDOW.h, WINDOW.r, thickness, 0.05);
  drawSlab(mesh, mat.mul(model, mat.t(0, 0, -thickness)), {
    color: dim < 1 ? COLOR.window.map((v) => v * Math.max(dim, 0.6)) : COLOR.window, w: WINDOW.w, h: WINDOW.h, radius: WINDOW.r,
    opacity, gloss: gloss * dim, wall: 1.3, edge: 0.14 * dim,
  });
}

function isFlipping() {
  const phi = frame.pose.flip;
  return phi > 0.0005 && Math.abs(phi - Math.PI) > 0.0005;
}

function drawWindow(t) {
  const fade = smooth((t - TIME.windowIn) / 0.4);
  if (fade <= 0.002) return;
  if (isFlipping()) {
    // Two half-thick shells back to back, so both faces of the window exist while it turns. The long
    // flat edges would catch the light all at once and flash, so they are less shiny here.
    drawBody(frame.front, WINDOW.t / 2, fade, 1, 0.5);
    drawBody(frame.back, WINDOW.t / 2, fade, 1, 0.5);
  } else {
    drawBody(frame.root, WINDOW.t, fade);
  }
}

function railHighlight(t, items) {
  let y = items[0].cy;
  let stretch = 0;
  for (let i = 1; i < items.length; i++) {
    const tau = t - STAGES[i].start;
    if (tau <= 0) break;
    y += (items[i].cy - items[i - 1].cy) * spring(tau, 13, 0.62);
    stretch += bump(tau, 0.1, 0.09);
  }
  return { y, stretch };
}

const RAIL_SCREEN = { name: 'Rail', start: TIME.railIn, end: Infinity, exit: 'none', enter: RAIL_IN };

function drawRail(t, root) {
  const { base, mark, items, chip } = assets.rail;
  const fade = frame.appFade;
  const state = (part, parentZ = 0) => {
    const a = partState(part, RAIL_SCREEN, t, parentZ);
    if (a) a.opacity *= fade;
    return a;
  };
  const baseA = state(base);
  if (baseA) drawPart(base, root, baseA, frame.E);
  const baseZ = baseA ? baseA.z : 0;
  const markA = state(mark, baseZ);
  if (markA) drawPart(mark, root, markA, frame.E);
  const { y: highlightY, stretch } = railHighlight(t, items);
  const itemH = items[0].h;
  const selectedLabels = [];
  items.forEach((item, i) => {
    const a = state(item, baseZ);
    if (!a) return;
    // Each tab rides a little behind the one above it.
    const E = liftAt(t - i * 0.018);
    drawPart(item.plate, root, { ...a, glow: a.glow * 0.5 }, E);
    const sm = mat.mul(partMatrix(item.plate, root, a, E), mat.t(item.sx, item.sy, 0.11));
    const selected = clamp(1 - Math.abs(highlightY - item.cy) / (itemH * 0.75));
    const glow = 1 + 0.14 * a.glow;
    const tint = a.glow > 0 ? [glow, glow, glow] : WHITE;
    if (selected < 1) drawSprite(item.tex, sm, item.sw, item.sh, a.opacity * (1 - selected), tint);
    if (selected > 0) selectedLabels.push([item, sm, a.opacity * selected, tint]);
  });
  const hFade = smooth((t - STAGES[0].start) / 0.4) * fade;
  if (hFade > 0.002) {
    const first = items[0];
    const E = liftAt(t - 0.05);
    const mesh = slabMesh(first.w, first.h, first.radius, 0.06, 0.02);
    const m = mat.chain(root, mat.t(first.cx, highlightY, first.plate.top + E * first.level + baseZ), mat.s(1, 1 + 0.45 * stretch, 1));
    drawSlab(mesh, m, { color: COLOR.raised, w: first.w, h: first.h, radius: first.radius, opacity: hFade, gloss: 1.3 });
  }
  // The selected label always sits on the highlight, even while the two bob separately.
  gl.disable(gl.DEPTH_TEST);
  for (const [item, sm, opacity, tint] of selectedLabels) drawSprite(item.texOn, sm, item.sw, item.sh, opacity * hFade, tint);
  gl.enable(gl.DEPTH_TEST);
  const chipA = state(chip);
  if (chipA) drawPart(chip, root, chipA, frame.E);
}

/** Each caption arrives a letter at a time, and gets pushed off the frame by the next move. */
function drawCaption(screen, t) {
  const tIn = t - screen.start - 0.34;
  const tOut = t - screen.end;
  if (tIn <= 0 || tOut > 0.5) return;
  const c = screen.caption;
  const T = assets.titles;
  const title = T[`cap-${screen.name}`];
  const sub = T[`sub-${screen.name}`];
  const root = mat.chain(mat.t(c.x, c.y + Math.sin(t * 0.9 + screen.index) * 0.03, 0.2), mat.ry(rad(c.yaw)), mat.s(0.9));
  const [ox, oy] = c.out;
  title.letters.forEach((l, j) => {
    const e = tIn - j * 0.028;
    if (e <= 0) return;
    const k = clamp((tOut - j * 0.012) / 0.26);
    if (k >= 1) return;
    const left = 1 - spring(e, 12, 0.58);
    const push = k * k;
    const flash = 1 + 0.5 * bump(e, 0.14, 0.12);
    const m = mat.chain(
      root,
      mat.t(l.x + l.w / 2 + ox * 1.3 * left + ox * 4 * push, -l.y - l.h / 2 + oy * 1.3 * left + oy * 3 * push, (1 - easeOutExpo(e / 0.5)) * 1.1 + push * 1.4),
      mat.ry(left * 1.15 * (ox || 0.6) - ox * 0.9 * push),
      mat.rx(left * 0.6 * oy + oy * 0.7 * push),
    );
    drawExtruded(l.tex, m, l.w, l.h, smooth(e / 0.14) * (1 - smooth(k * 1.2)), 8, 0.014, [flash, flash, flash]);
  });
  const e = tIn - 0.2;
  if (e > 0 && tOut < 0.2) {
    const reveal = easeOutExpo(e / 0.65);
    const x = c.row ? title.w + 0.35 : 0.02;
    const y = c.row ? -(title.h - sub.h) / 2 : -title.h - 0.02;
    const k = clamp(tOut / 0.2);
    const m = mat.mul(root, mat.t(x + sub.w / 2 + ox * 3 * k * k, y - sub.h / 2 + oy * 2 * k * k, 0));
    drawSprite(sub.tex, m, sub.w, sub.h, smooth(e / 0.2) * (1 - smooth(k)), WHITE, 0, [reveal * 1.2, 0.18, 0]);
  }
}

// The seven screens fan out from the last one and circle the logo.
function drawFinale(t) {
  const F = TIME.finale;
  const dim = 1 - 0.74 * smooth((t - TIME.logoBack) / 1.1);
  const cards = assets.screens.map((s, i) => {
    if (i === 6) return { s, m: frame.root, pos: [frame.pose.x, frame.pose.y, frame.pose.z], opacity: 1, flat: smooth((t - F) / 0.24) };
    // Each card spirals out from behind the last screen and slows into its place on the ring.
    const q = spring(t - F - 0.26 - (5 - i) * 0.055, 4.6, 0.82);
    const phi = ringAngle(i, t) + (1 - q) * 1.9;
    const radius = lerp(0.6, RING.radius, q);
    const pos = [radius * Math.sin(phi), -1 - (1 - q) * 0.5, radius * Math.cos(phi)];
    const yaw = Math.sin(ringAngle(i, t)) * 0.5 + (1 - q) * 0.9;
    const scale = lerp(RING.scale * 0.5, RING.scale, q);
    return { s, m: mat.chain(mat.t(...pos), mat.ry(yaw), mat.s(scale)), pos, opacity: smooth(q * 3), flat: 1 };
  });
  const depth = (c) => Math.hypot(c.pos[0] - frame.eye[0], c.pos[1] - frame.eye[1], c.pos[2] - frame.eye[2]);
  cards.sort((a, b) => depth(b) - depth(a));
  for (const c of cards) {
    if (c.opacity <= 0.002) continue;
    drawBody(c.m, WINDOW.t, c.opacity, dim);
    drawSprite(c.s.flat, mat.mul(c.m, mat.t(0, 0, 0.004)), WINDOW.w, WINDOW.h, c.opacity * c.flat, [dim, dim, dim], WINDOW.r);
    clearDepth();
  }
}

// ---------- One render

function prepareRender(t, lensXY, jitter) {
  const cam = camera(t);
  const F = TIME.finale;
  const pose = windowPose(t);
  const root = rootOf(pose);
  frame.pose = pose;
  frame.root = root;
  frame.front = sideRoot(pose, root, 0);
  frame.back = sideRoot(pose, root, FLIP);
  frame.travel = pose.travel;
  frame.E = liftAt(t);
  frame.appFade = 1 - smooth((t - F) / 0.26);

  // Focus trails just behind the window, so it goes soft in flight and snaps sharp as it lands.
  const flight = flightAmount(t);
  const lag = windowPose(t - 0.09);
  const forward = norm([cam.target[0] - cam.eye[0], cam.target[1] - cam.eye[1], cam.target[2] - cam.eye[2]]);
  const inApp = (1 - cam.toEnd) * cam.toApp;
  const focusPoint = [
    lag.x * inApp,
    lag.y * inApp + 2.5 * cam.toEnd,
    lag.z * inApp + 14.6 * cam.toEnd,
  ];
  frame.focus = Math.max(4, dot([focusPoint[0] - cam.eye[0], focusPoint[1] - cam.eye[1], focusPoint[2] - cam.eye[2]], forward));
  frame.lens = lerp(0.05 + 0.3 * flight * cam.toApp, 0.12, cam.toEnd);

  const right = norm(cross(forward, cam.up));
  const lensUp = cross(right, forward);
  const eye = [0, 1, 2].map((i) => cam.eye[i] + (right[i] * lensXY[0] + lensUp[i] * lensXY[1]) * frame.lens);
  const aim = [0, 1, 2].map((i) => cam.eye[i] + forward[i] * frame.focus);
  frame.eye = eye;
  frame.p = mat.perspective(rad(cam.fov), W / H, 2, 160);
  frame.p[8] = -jitter[0];
  frame.p[9] = -jitter[1];
  frame.v = mat.lookAt(eye, aim, cam.up);
  frame.vp = mat.mul(frame.p, frame.v);
  frame.spot = [Math.sin(t * 0.42) * 5 - 1, 1.6 + Math.cos(t * 0.31) * 2.4, 0.3];

  // Nobody holds a controller perfectly still.
  frame.pointer = pointerState(t);
  const held = frame.pointer.world;
  const w = [
    held[0] + 0.02 * Math.sin(t * 3.1) + 0.012 * Math.sin(t * 7.3 + 1),
    held[1] + 0.02 * Math.cos(t * 2.7) + 0.012 * Math.sin(t * 6.1 + 2),
    held[2],
  ];
  frame.pointer.world = w;
  const lamp = [w[0], w[1], w[2] - 0.5, 0.11 * frame.pointer.opacity];

  frame.ripples = [];
  frame.shocks = [];
  const landing = t - TIME.windowIn;
  if (landing > 0 && landing < 1.4) frame.shocks.push({ x: 0, y: 0, radius: 16 * landing, amp: 1.8 * Math.exp(-landing * 2.2) });
  let ripple = [0, 0, 0, 0];
  let rippleAmp = 0;
  let sweep = [0.82, 0.57, 0, 0];
  // Between moves, a faint sheen drifts across the panels.
  const stay = Math.floor((t - FIRST) / STEP);
  if (stay >= 0 && stay < STAGES.length) {
    const k = (t - STAGES[stay].start - 0.85) / 1.5;
    if (k > 0 && k < 1) sweep = [stay % 2 ? -0.82 : 0.82, 0.57, lerp(-20, 20, k), 0.03 * Math.sin(Math.PI * k)];
  }
  for (const press of PRESSES) {
    const tau = t - press.t;
    if (tau <= 0 || tau > 1.2) continue;
    const speed = press.tab ? 20 : 11;
    frame.ripples.push({ x: press.x, y: press.y, radius: speed * tau, amp: (press.tab ? 0.24 : 0.11) * Math.exp(-tau * (press.tab ? 2.2 : 4)), width: press.tab ? 1 : 0.6 });
    const at = mat.apply(sideRoot(pose, root, press.screen), [press.x, press.y, 0.2]);
    ripple = [at[0], at[1], at[2], speed * tau];
    rippleAmp = (press.tab ? 0.11 : 0.06) * Math.exp(-tau * 3.4) * smooth(tau / 0.05);
    if (press.tab) {
      frame.shocks.push({ x: at[0], y: at[1], radius: 18 * tau, amp: 1.5 * Math.exp(-tau * 2.4) });
      const k = (tau - 0.06) / 0.75;
      if (k > 0 && k < 1) {
        const dir = press.screen % 2 ? -1 : 1;
        sweep = [0.82 * dir, 0.57, lerp(-17, 17, easeInOut(k)), 0.16 * Math.sin(Math.PI * k)];
      }
    }
  }
  applyCamera(lamp, sweep, ripple, rippleAmp);
}

function renderScene(t, lensXY, jitter) {
  prepareRender(t, lensXY, jitter);
  gl.bindFramebuffer(gl.FRAMEBUFFER, msaaFbo);
  gl.viewport(0, 0, RW, RH);
  gl.clearColor(0, 0, 0, 1);
  gl.depthMask(true);
  gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);
  gl.disable(gl.CULL_FACE);
  drawBackdrop(t);

  gl.enable(gl.DEPTH_TEST);
  gl.depthFunc(gl.LEQUAL);
  gl.enable(gl.CULL_FACE);
  gl.cullFace(gl.BACK);

  const F = TIME.finale;
  const lockupRoot = mat.ry(Math.sin(t * 0.5 + 0.4) * 0.06);
  if (t < TIME.logoExit + 0.7) {
    drawLockup(lockupRoot, t - 0.12, 1, true, false, Math.max(0, t - TIME.logoExit), t < TIME.logoExit);
    clearDepth();
  }
  if (t >= F) {
    drawFinale(t);
    clearDepth();
  }
  if (t >= TIME.windowIn && frame.appFade > 0.002) {
    if (t < F) drawWindow(t);
    if (isFlipping()) {
      drawRail(t, frame.front);
      drawRail(t, frame.back);
    } else {
      drawRail(t, t < STAGES[FLIP].start ? frame.front : frame.back);
    }
    for (const screen of assets.screens) {
      if (t >= screen.start - 0.001 && t < screen.end + (screen.exit === 'hold' ? screen.holdFor : 1)) drawScreen(screen, t);
    }
  }
  for (const screen of assets.screens) drawCaption(screen, t);
  if (t < F) drawPointer(t);
  if (t > TIME.logoExit && t < TIME.logoExit + 0.8) {
    clearDepth();
    drawFlyingLogo(t, lockupRoot);
  }
  if (t > TIME.logoBack) {
    const e = t - TIME.logoBack;
    clearDepth();
    const m = mat.chain(mat.t(0, 2.5, 14.6), mat.s(0.96), mat.rx(rad(-7)), mat.ry(Math.sin(t * 0.5) * 0.06));
    drawShadow(mat.mul(m, mat.t(0, -0.2, -0.8)), 10, 3.4, 1, 1.9, 0.62 * smooth(e / 0.8));
    drawLockup(m, e, 1, true, true);
  }
  drawDust(t);
}

// ---------- One frame

const BLOOM_THRESHOLD = 0.6;
const BLOOM_AMOUNT = 0.022;

function fullscreen(target, prog) {
  gl.bindFramebuffer(gl.FRAMEBUFFER, target ? target.fbo : null);
  gl.viewport(0, 0, target ? target.w : W, target ? target.h : H);
  gl.useProgram(prog.p);
  gl.bindVertexArray(quadVao);
}

function runBloom() {
  gl.disable(gl.BLEND);
  gl.activeTexture(gl.TEXTURE0);
  let source = accum;
  bloomLevels.forEach((level, i) => {
    fullscreen(level, bloomDownProgram);
    gl.bindTexture(gl.TEXTURE_2D, source.tex);
    gl.uniform2f(bloomDownProgram.u.uStep, 0.5 / level.w, 0.5 / level.h);
    gl.uniform1f(bloomDownProgram.u.uThreshold, i === 0 ? BLOOM_THRESHOLD : 0);
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    source = level;
  });
  gl.enable(gl.BLEND);
  gl.blendFunc(gl.ONE, gl.ONE);
  for (let i = bloomLevels.length - 1; i > 0; i--) {
    const to = bloomLevels[i - 1];
    fullscreen(to, bloomUpProgram);
    gl.bindTexture(gl.TEXTURE_2D, bloomLevels[i].tex);
    gl.uniform2f(bloomUpProgram.u.uStep, 0.5 / to.w, 0.5 / to.h);
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
  }
  gl.disable(gl.BLEND);
}

const halton = (i, base) => { let f = 1, r = 0; for (let n = i; n > 0; n = Math.floor(n / base)) { f /= base; r += f * (n % base); } return r; };

function renderFrame(t) {
  const samples = flightAmount(t) > 0.04 ? 32 : t >= TIME.finale ? 24 : 12;
  gl.bindFramebuffer(gl.FRAMEBUFFER, accum.fbo);
  gl.clearColor(0, 0, 0, 1);
  gl.clear(gl.COLOR_BUFFER_BIT);
  for (let i = 0; i < samples; i++) {
    const when = t + (((i + 0.5) / samples - 0.5) * SHUTTER) / FPS;
    // The lens samples are shuffled against time, so the two kinds of blur do not line up in streaks.
    const li = (i * 7 + 3) % samples;
    const r = Math.sqrt((li + 0.5) / samples);
    const angle = li * 2.39996323;
    const jitter = [((halton(i + 1, 2) - 0.5) * 2) / W, ((halton(i + 1, 3) - 0.5) * 2) / H];
    renderScene(when, [r * Math.cos(angle), r * Math.sin(angle)], jitter);

    gl.disable(gl.DEPTH_TEST);
    gl.disable(gl.CULL_FACE);
    gl.disable(gl.BLEND);
    gl.bindFramebuffer(gl.READ_FRAMEBUFFER, msaaFbo);
    gl.bindFramebuffer(gl.DRAW_FRAMEBUFFER, resolved.fbo);
    gl.blitFramebuffer(0, 0, RW, RH, 0, 0, RW, RH, gl.COLOR_BUFFER_BIT, gl.NEAREST);

    fullscreen(accum, addProgram);
    gl.enable(gl.BLEND);
    gl.blendFunc(gl.ONE, gl.ONE);
    gl.activeTexture(gl.TEXTURE0);
    gl.bindTexture(gl.TEXTURE_2D, resolved.tex);
    gl.uniform1f(addProgram.u.uWeight, 1 / samples);
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    gl.disable(gl.BLEND);
  }
  runBloom();

  fullscreen(null, finalProgram);
  gl.activeTexture(gl.TEXTURE1);
  gl.bindTexture(gl.TEXTURE_2D, bloomLevels[0].tex);
  gl.activeTexture(gl.TEXTURE0);
  gl.bindTexture(gl.TEXTURE_2D, accum.tex);
  gl.uniform1i(finalProgram.u.uTex, 0);
  gl.uniform1i(finalProgram.u.uBloom, 1);
  gl.uniform1f(finalProgram.u.uBloomAmount, BLOOM_AMOUNT);
  gl.uniform1f(finalProgram.u.uFade, smooth(t / 0.5) * (1 - smooth((t - (TIME.end - 0.4)) / 0.4)));
  gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
}

// ---------- Output

async function renderStills(times) {
  for (const t of times) {
    renderFrame(t);
    gl.finish();
    const blob = await new Promise((r) => canvas.toBlob(r, 'image/png'));
    await post(`/still?name=t${t.toFixed(2).padStart(5, '0')}`, blob);
  }
}

async function renderVideo(megabits) {
  const config = {
    // The hardware encoder ignores low targets in variable mode, so small files need constant mode.
    codec: 'avc1.640033', width: W, height: H, framerate: FPS, bitrate: megabits * 1e6, bitrateMode: megabits < 5 ? 'constant' : 'variable',
    latencyMode: 'quality', hardwareAcceleration: 'prefer-hardware', avc: { format: 'avc' },
  };
  const support = await VideoEncoder.isConfigSupported(config);
  log(`H.264 hardware encoder: ${support.supported}`);
  if (!support.supported) config.hardwareAcceleration = 'no-preference';
  const frames = [];
  let avcC = null;
  let failure = null;
  const encoder = new VideoEncoder({
    output(chunk, meta) {
      const data = new Uint8Array(chunk.byteLength);
      chunk.copyTo(data);
      frames.push({ data, key: chunk.type === 'key', ts: chunk.timestamp });
      if (meta?.decoderConfig?.description) {
        const d = meta.decoderConfig.description;
        avcC = new Uint8Array(d.buffer ?? d, d.byteOffset ?? 0, d.byteLength);
      }
    },
    error(e) { failure = e; },
  });
  encoder.configure(config);
  const total = Math.round(DURATION * FPS);
  const started = performance.now();
  for (let i = 0; i < total; i++) {
    if (failure) throw failure;
    renderFrame(i / FPS);
    const vf = new VideoFrame(canvas, { timestamp: Math.round((i * 1e6) / FPS), duration: Math.round(1e6 / FPS) });
    encoder.encode(vf, { keyFrame: i % (FPS * 2) === 0 });
    vf.close();
    while (encoder.encodeQueueSize > 6) await new Promise((r) => setTimeout(r, 2));
    if (i % 60 === 0) {
      await new Promise((r) => setTimeout(r, 0));
      log(`frame ${i}/${total}, ${((performance.now() - started) / 1000).toFixed(1)}s`);
    }
  }
  await encoder.flush();
  encoder.close();
  if (failure) throw failure;
  frames.sort((a, b) => a.ts - b.ts);
  const mp4 = buildMp4({ frames, width: W, height: H, fps: FPS, avcC });
  log(`encoded ${frames.length} frames, ${(mp4.size / 1e6).toFixed(1)} MB, profile byte ${avcC[1].toString(16)}`);
  await post('/video', mp4);
}

async function main() {
  const debug = gl.getExtension('WEBGL_debug_renderer_info');
  log(`GPU: ${debug ? gl.getParameter(debug.UNMASKED_RENDERER_WEBGL) : 'unknown'}, float buffers: ${!!floatBuffers}`);
  await loadAssets();
  if (params.get('mode') === 'video') await renderVideo(Number(params.get('megabits')) || 16);
  else await renderStills(params.get('times').split(',').map(Number));
  await post('/done', 'ok');
}

main().catch((e) => fail(e.stack ?? e));
