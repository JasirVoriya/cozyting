// Procedurally synthesises the 24s (12 bars @ 120 BPM) Quick Panel feature spotlight soundtrack.
// Bar 0..1.5 (0-3s): poised hook + riser -> Bar 1.5..4 (3-8s): dual entry clicks ->
// Bar 4..7 (8-14s): full chorus spotlight -> Bar 7..9.5 (14-19s): volume ticks + light-theme chime ->
// Bar 9.5..12 (19-24s): warm lockup + search click fade.
import { writeFileSync, mkdirSync } from "node:fs";

const SR = 48000;
const BPM = 120;
const BEAT = 60 / BPM; // 0.5 s
const BAR = BEAT * 4; // 2.0 s
const BARS = 12;
const TOTAL = BARS * BAR; // 24.0 s
const N = Math.ceil(TOTAL * SR);

const L = new Float32Array(N);
const R = new Float32Array(N);
const revL = new Float32Array(N);
const revR = new Float32Array(N);
const dlyBus = new Float32Array(N);
const duck = new Float32Array(N).fill(1);

const mtof = (m) => 440 * Math.pow(2, (m - 69) / 12);
const clamp = (x, a, b) => Math.max(a, Math.min(b, x));

const TBL = 4096;
function makeTable(fn, harmonics) {
  const t = new Float32Array(TBL + 1);
  for (let i = 0; i <= TBL; i++) {
    let s = 0;
    for (let h = 1; h <= harmonics; h++) s += fn(h) * Math.sin((2 * Math.PI * h * i) / TBL);
    t[i] = s;
  }
  let mx = 0;
  for (const v of t) mx = Math.max(mx, Math.abs(v));
  for (let i = 0; i <= TBL; i++) t[i] /= mx;
  return t;
}
const sawSoft = makeTable((h) => (1 / h) * Math.exp(-h / 14), 48);
const triTbl = makeTable((h) => (h % 2 ? (((h - 1) / 2) % 2 ? -1 : 1) / (h * h) : 0), 31);
const lookup = (tbl, ph) => {
  const x = (ph - Math.floor(ph)) * TBL;
  const i = x | 0;
  const f = x - i;
  return tbl[i] + (tbl[i + 1] - tbl[i]) * f;
};

function lp1(cut) {
  const a = 1 - Math.exp((-2 * Math.PI * cut) / SR);
  let y = 0;
  return (x, c) => {
    if (c !== undefined) {
      const a2 = 1 - Math.exp((-2 * Math.PI * c) / SR);
      y += a2 * (x - y);
    } else y += a * (x - y);
    return y;
  };
}

let seed = 7654321;
const rnd = () => {
  seed = (seed * 1664525 + 1013904223) >>> 0;
  return (seed / 4294967296) * 2 - 1;
};

const CHORDS = [
  { root: 41, notes: [53, 57, 60, 64, 67] }, // Fmaj9
  { root: 45, notes: [57, 60, 64, 67, 71] }, // Am9
  { root: 48, notes: [55, 60, 64, 67, 71] }, // Cmaj9
  { root: 43, notes: [55, 59, 62, 64, 69] }, // G6/9
];
const chordAt = (bar) => CHORDS[((bar % 4) + 4) % 4];

function kickAt(t, gain = 1) {
  const s0 = Math.floor(t * SR);
  const len = Math.floor(0.42 * SR);
  let ph = 0;
  for (let i = 0; i < len && s0 + i < N; i++) {
    const tt = i / SR;
    const f = 44 + 110 * Math.exp(-tt * 28);
    ph += f / SR;
    const env = Math.exp(-tt * 7.5);
    const click = Math.exp(-tt * 400) * 0.24 * rnd();
    const x = (Math.sin(2 * Math.PI * ph) * env + click) * 0.55 * gain;
    L[s0 + i] += x;
    R[s0 + i] += x;
  }
  const dl = Math.floor(0.36 * SR);
  for (let i = 0; i < dl && s0 + i < N; i++) {
    const tt = i / dl;
    const g = 0.38 + 0.62 * Math.pow(tt, 0.7);
    duck[s0 + i] = Math.min(duck[s0 + i], g);
  }
}

function hatAt(t, gain = 1, open = false) {
  const s0 = Math.floor(t * SR);
  const len = Math.floor((open ? 0.22 : 0.06) * SR);
  let p1 = 0, p2 = 0;
  for (let i = 0; i < len && s0 + i < N; i++) {
    const n = rnd();
    const hp = n - p1;
    p1 = n;
    const hp2 = hp - p2 * 0.2;
    p2 = hp;
    const env = Math.exp(-(i / SR) * (open ? 14 : 70));
    const x = hp2 * env * 0.05 * gain;
    L[s0 + i] += x * 0.8;
    R[s0 + i] += x * 1.0;
    revL[s0 + i] += x * 0.2;
    revR[s0 + i] += x * 0.2;
  }
}

function clapAt(t, gain = 1) {
  const s0 = Math.floor(t * SR);
  const len = Math.floor(0.28 * SR);
  const bp = lp1(1800);
  let prev = 0;
  for (let i = 0; i < len && s0 + i < N; i++) {
    const tt = i / SR;
    const burst =
      [0, 0.011, 0.022].reduce((a, o) => a + (tt >= o ? Math.exp(-(tt - o) * 90) : 0), 0) * 0.5 +
      Math.exp(-tt * 18) * 0.5;
    const lpv = bp(rnd());
    const band = lpv - prev * 0.7;
    prev = lpv;
    const x = band * burst * 0.12 * gain;
    L[s0 + i] += x;
    R[s0 + i] += x;
    revL[s0 + i] += x * 0.9;
    revR[s0 + i] += x * 0.9;
  }
}

function riser(tEnd, len, gain = 1) {
  const s1 = Math.floor(tEnd * SR);
  const s0 = Math.max(0, s1 - Math.floor(len * SR));
  const filt = lp1(500);
  let prev = 0;
  for (let s = s0; s < s1 && s < N; s++) {
    const p = (s - s0) / (s1 - s0);
    const cut = 300 + 6800 * p * p;
    const lpv = filt(rnd(), cut);
    const band = lpv - prev * 0.85;
    prev = lpv;
    const x = band * p * p * 0.12 * gain;
    L[s] += x;
    R[s] += x;
    revL[s] += x * 0.6;
    revR[s] += x * 0.6;
  }
}

function impact(t, gain = 1) {
  const s0 = Math.floor(t * SR);
  const len = Math.floor(2.2 * SR);
  let ph = 0;
  const filt = lp1(800);
  for (let i = 0; i < len && s0 + i < N; i++) {
    const tt = i / SR;
    const f = 32 + 70 * Math.exp(-tt * 6);
    ph += f / SR;
    const boom = Math.sin(2 * Math.PI * ph) * Math.exp(-tt * 1.7);
    const air = filt(rnd(), 400 + 3000 * Math.exp(-tt * 3)) * Math.exp(-tt * 2.2) * 0.5;
    const x = (boom * 0.55 + air * 0.35) * gain;
    L[s0 + i] += x;
    R[s0 + i] += x;
    revL[s0 + i] += air * 0.4 * gain;
    revR[s0 + i] += air * 0.4 * gain;
  }
}

function chime(t, m, gain = 0.06, pan = 0.5) {
  const s0 = Math.floor(t * SR);
  const len = Math.floor(2.0 * SR);
  const f = mtof(m);
  for (let i = 0; i < len && s0 + i < N; i++) {
    const tt = i / SR;
    const env = clamp(tt / 0.002, 0, 1) * Math.exp(-tt * 2.3);
    const x =
      (Math.sin(2 * Math.PI * f * tt) +
        0.35 * Math.sin(2 * Math.PI * f * 2.76 * tt) * Math.exp(-tt * 4) +
        0.18 * Math.sin(2 * Math.PI * f * 5.4 * tt) * Math.exp(-tt * 8)) *
      env *
      gain;
    L[s0 + i] += x * (1 - pan);
    R[s0 + i] += x * pan;
    revL[s0 + i] += x * 0.7;
    revR[s0 + i] += x * 0.7;
  }
}

function tick(t, gain = 0.055) {
  const s0 = Math.floor(t * SR);
  const len = Math.floor(0.03 * SR);
  for (let i = 0; i < len && s0 + i < N; i++) {
    const tt = i / SR;
    const x = Math.sin(2 * Math.PI * 3200 * tt) * Math.exp(-tt * 260) * gain;
    L[s0 + i] += x;
    R[s0 + i] += x;
  }
}

function pluck(t, m, gain, pan) {
  const f = mtof(m);
  const s0 = Math.floor(t * SR);
  const len = Math.floor(0.65 * SR);
  let ph = Math.random();
  const filt = lp1(3000);
  for (let i = 0; i < len && s0 + i < N; i++) {
    const tt = i / SR;
    const env = clamp(tt / 0.003, 0, 1) * Math.exp(-tt * 7);
    ph += f / SR;
    const cut = 600 + 4200 * Math.exp(-tt * 12);
    const x = filt(lookup(triTbl, ph) * 0.7 + lookup(sawSoft, ph * 2.0) * 0.15, cut) * env * gain;
    L[s0 + i] += x * (1 - pan);
    R[s0 + i] += x * pan;
    dlyBus[s0 + i] += x * 0.6;
    revL[s0 + i] += x * 0.4;
    revR[s0 + i] += x * 0.4;
  }
}

// Drums (12 bars = 24s)
for (let bar = 0; bar < BARS; bar++) {
  for (let b = 0; b < 4; b++) {
    const t = bar * BAR + b * BEAT;
    const inAct1 = t < 3.0;
    const inOutro = t >= 19.0;
    if (!inOutro) {
      if (inAct1) {
        if (b % 2 === 0) kickAt(t, 0.8);
      } else {
        kickAt(t, t >= 8.0 ? 0.96 : 0.88);
      }
      if (t >= 8.0 && (b === 1 || b === 3)) clapAt(t, 0.96);
      else if (t >= 3.0 && t < 8.0 && b === 3) clapAt(t, 0.68);
      hatAt(t + BEAT / 2, inAct1 ? 0.7 : 0.95, b === 3 && bar % 2 === 1);
      if (t >= 8.0) {
        hatAt(t + BEAT / 4, 0.45);
        hatAt(t + (3 * BEAT) / 4, 0.45);
      }
    }
  }
}

// Section transitions & UI interaction foley
riser(3.0, 1.0, 0.82);
impact(3.0, 0.75);
chime(3.0, 84, 0.055, 0.46);

// Act 2: Quick Settings tile tap (4.3s) & Notification tap (6.2s)
tick(4.3, 0.06);
chime(4.34, 84, 0.048, 0.35);
tick(6.2, 0.06);
chime(6.24, 86, 0.048, 0.42);

// Act 3: Floating panel spring pop at 8.0s
riser(8.0, 1.2, 0.95);
impact(8.0, 0.85);
chime(8.02, 88, 0.06, 0.55);

// Act 4: Hardware volume key presses (15.1s & 15.7s) + light theme sweep (17.0s)
tick(15.1, 0.062);
tick(15.7, 0.065);
chime(17.0, 91, 0.058, 0.5);

// Act 5: Lockup arrival (19.0s) + GitHub search click (21.6s)
riser(19.0, 1.2, 0.85);
impact(19.0, 0.88);
chime(19.02, 84, 0.06, 0.45);
chime(19.5, 88, 0.045, 0.6);
tick(21.6, 0.058);

// Pad
for (let bar = 0; bar < BARS; bar++) {
  const ch = chordAt(bar);
  const t0 = bar * BAR;
  const dur = BAR + 0.55;
  const level = bar >= 9 ? 0.092 : 0.078;
  ch.notes.forEach((m, ni) => {
    for (let v = 0; v < 3; v++) {
      const det = (v - 1) * 0.09 + (ni % 2 ? 0.02 : -0.02);
      const f = mtof(m + det);
      const pan = clamp(0.5 + (v - 1) * 0.35 + (ni - 2) * 0.06, 0, 1);
      let ph = Math.random();
      const filt = lp1(1300);
      const s0 = Math.floor(t0 * SR);
      const s1 = Math.min(N, Math.floor((t0 + dur) * SR));
      for (let s = s0; s < s1; s++) {
        const t = (s - s0) / SR;
        const att = clamp(t / (bar === 0 ? 0.04 : 0.3), 0, 1);
        const rel = clamp((dur - t) / 0.55, 0, 1);
        const env = Math.sin((att * Math.PI) / 2) * Math.sin((rel * Math.PI) / 2);
        const gt = s / SR;
        const cut = 1400 + 480 * Math.sin(2 * Math.PI * gt * 0.08 + ni);
        ph += f / SR;
        const x = filt(lookup(sawSoft, ph), cut) * env * level * 0.32;
        const d = duck[s];
        L[s] += x * (1 - pan) * d;
        R[s] += x * pan * d;
        revL[s] += x * (1 - pan) * 0.5 * d;
        revR[s] += x * pan * 0.5 * d;
      }
    }
  });
}

// Bass
for (let bar = 0; bar < 10; bar++) {
  const ch = chordAt(bar);
  for (let e = 0; e < 8; e++) {
    if (e % 2 === 0 && e !== 0) continue;
    const t = bar * BAR + e * (BEAT / 2);
    if (t >= 19.0) continue;
    const dur = e === 0 ? BEAT * 0.9 : BEAT * 0.42;
    const f = mtof(ch.root);
    const s0 = Math.floor(t * SR);
    const len = Math.floor(dur * SR);
    let ph = 0;
    const filt = lp1(300);
    for (let i = 0; i < len && s0 + i < N; i++) {
      const tt = i / SR;
      const env = clamp(tt / 0.008, 0, 1) * clamp((dur - tt) / 0.03, 0, 1) * Math.exp(-tt * 1.5);
      ph += f / SR;
      const x = (Math.sin(2 * Math.PI * ph) * 0.8 + filt(lookup(sawSoft, ph), 420) * 0.5) * env * 0.2;
      L[s0 + i] += x * duck[s0 + i];
      R[s0 + i] += x * duck[s0 + i];
    }
  }
}

// Arp
const pattern = [0, 2, 1, 3, 2, 4, 3, 1];
for (let bar = 1; bar < 11; bar++) {
  const ch = chordAt(bar);
  const steps = bar >= 4 && bar <= 8 ? 16 : 8;
  const g = bar >= 9 ? 0.045 * (1 - (bar - 9) / 2.2) : 0.062;
  for (let st = 0; st < steps; st++) {
    const t = bar * BAR + st * (BAR / steps);
    if (t < 3.0) continue;
    const idx = pattern[st % pattern.length];
    pluck(t, ch.notes[idx] + 12, g * (st % 4 === 0 ? 1 : 0.72), st % 2 ? 0.68 : 0.32);
  }
}

// Delay
{
  const d = Math.floor(BEAT * 0.75 * SR);
  const buf = new Float32Array(N);
  for (let s = 0; s < N; s++) {
    const fb = s >= d ? buf[s - d] : 0;
    buf[s] = dlyBus[s] + fb * 0.36;
    const out = s >= d ? buf[s - d] * 0.48 : 0;
    L[s] += out * (Math.floor(s / d) % 2 ? 0.3 : 0.7) * 0.44;
    R[s] += out * (Math.floor(s / d) % 2 ? 0.7 : 0.3) * 0.44;
    revL[s] += out * 0.2;
    revR[s] += out * 0.2;
  }
}

// Freeverb
function freeverb(input, spread) {
  const combs = [1557, 1617, 1491, 1422, 1277, 1356, 1188, 1116].map((n) => n + spread);
  const aps = [556, 441, 341, 225].map((n) => n + spread);
  const out = new Float32Array(N);
  const room = 0.87, damp = 0.35;
  const cbufs = combs.map((n) => ({ b: new Float32Array(Math.round((n * SR) / 44100)), i: 0, f: 0 }));
  const abufs = aps.map((n) => ({ b: new Float32Array(Math.round((n * SR) / 44100)), i: 0 }));
  for (let s = 0; s < N; s++) {
    const x = input[s] * 0.015;
    let acc = 0;
    for (const c of cbufs) {
      const y = c.b[c.i];
      c.f = y * (1 - damp) + c.f * damp;
      c.b[c.i] = x + c.f * room;
      c.i = (c.i + 1) % c.b.length;
      acc += y;
    }
    for (const a of abufs) {
      const bo = a.b[a.i];
      const y = -acc + bo;
      a.b[a.i] = acc + bo * 0.5;
      a.i = (a.i + 1) % a.b.length;
      acc = y;
    }
    out[s] = acc;
  }
  return out;
}
const wetL = freeverb(revL, 0);
const wetR = freeverb(revR, 23);

const out = new Int16Array(N * 2);
let peak = 0;
const mixL = new Float32Array(N), mixR = new Float32Array(N);
for (let s = 0; s < N; s++) {
  mixL[s] = L[s] + wetL[s] * 2.1;
  mixR[s] = R[s] + wetR[s] * 2.1;
}
for (let s = 0; s < N; s++) peak = Math.max(peak, Math.abs(mixL[s]), Math.abs(mixR[s]));
const norm = 0.92 / Math.tanh((peak * 1.15) / peak) / peak;
for (let s = 0; s < N; s++) {
  const t = s / SR;
  const fadeIn = clamp(t / 0.015, 0, 1);
  const fadeOut = clamp((TOTAL - t) / 2.2, 0, 1);
  const g = fadeIn * Math.pow(fadeOut, 1.5);
  const l = Math.tanh(mixL[s] * norm * 1.15) * 0.95 * g;
  const r = Math.tanh(mixR[s] * norm * 1.15) * 0.95 * g;
  out[s * 2] = Math.round(clamp(l, -1, 1) * 32767);
  out[s * 2 + 1] = Math.round(clamp(r, -1, 1) * 32767);
}

function wav(int16, channels) {
  const dataLen = int16.length * 2;
  const buf = Buffer.alloc(44 + dataLen);
  buf.write("RIFF", 0);
  buf.writeUInt32LE(36 + dataLen, 4);
  buf.write("WAVE", 8);
  buf.write("fmt ", 12);
  buf.writeUInt32LE(16, 16);
  buf.writeUInt16LE(1, 20);
  buf.writeUInt16LE(channels, 22);
  buf.writeUInt32LE(SR, 24);
  buf.writeUInt32LE(SR * channels * 2, 28);
  buf.writeUInt16LE(channels * 2, 32);
  buf.writeUInt16LE(16, 34);
  buf.write("data", 36);
  buf.writeUInt32LE(dataLen, 40);
  Buffer.from(int16.buffer).copy(buf, 44);
  return buf;
}

mkdirSync(new URL("../public/", import.meta.url), { recursive: true });
writeFileSync(new URL("../public/quick-panel-music.wav", import.meta.url), wav(out, 2));
console.log("wrote public/quick-panel-music.wav", (N / SR).toFixed(1) + "s", "peak", peak.toFixed(3));
