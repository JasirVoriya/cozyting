// Procedurally synthesises the promo soundtrack (no samples, no voice).
// 120 BPM, 33 bars (66 s) + tail. Bar boundaries line up with scene cuts in src/timeline.ts.
import { writeFileSync, mkdirSync } from "node:fs";

const SR = 48000;
const BPM = 120;
const BEAT = 60 / BPM; // 0.5 s
const BAR = BEAT * 4; // 2 s
const TOTAL = 66 + 0.0;
const N = Math.ceil(TOTAL * SR);
const L = new Float32Array(N);
const R = new Float32Array(N);
// Send buses
const revL = new Float32Array(N);
const revR = new Float32Array(N);
const dlyBus = new Float32Array(N);
// Sidechain envelope (1 = no duck)
const duck = new Float32Array(N).fill(1);

const mtof = (m) => 440 * Math.pow(2, (m - 69) / 12);
const clamp = (x, a, b) => Math.max(a, Math.min(b, x));

// ---- band-limited wavetables
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

// Simple one-pole lowpass helper (stateful)
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

let seed = 1234567;
const rnd = () => {
  seed = (seed * 1664525 + 1013904223) >>> 0;
  return seed / 4294967296 * 2 - 1;
};

// ---- arrangement
// progression per bar (4-bar loop): Fmaj9, Am9, Cmaj9, G6/9  (key: C / A minor)
const CHORDS = [
  { root: 41, notes: [53, 57, 60, 64, 67] }, // F: F A C E G
  { root: 45, notes: [57, 60, 64, 67, 71] }, // Am9: A C E G B
  { root: 48, notes: [55, 60, 64, 67, 71] }, // Cmaj9: G C E G B
  { root: 43, notes: [55, 59, 62, 64, 69] }, // G6/9: G B D E A
];
const chordAt = (bar) => CHORDS[((bar % 4) + 4) % 4];

// section intensities by bar
// 0-3 intro, 4-11 groove A, 12-16 full (multi-room), 17-20 latency, 21-23 breakdown, 24-28 full, 29-32 outro
const has = {
  kick: (b) => (b >= 4 && b <= 20) || (b >= 24 && b <= 28),
  hat: (b) => (b >= 8 && b <= 20) || (b >= 24 && b <= 28),
  clap: (b) => (b >= 12 && b <= 20) || (b >= 24 && b <= 28),
  bass: (b) => (b >= 4 && b <= 28 && !(b >= 21 && b <= 23)) ,
  arp: (b) => b >= 4 && b <= 30,
};

// ---- PAD
function renderPad() {
  for (let bar = 0; bar < 33; bar++) {
    const ch = chordAt(bar);
    const t0 = bar * BAR;
    const dur = BAR + 0.6;
    const level = bar < 4 ? 0.05 + 0.03 * (bar / 3) : bar >= 21 && bar <= 23 ? 0.085 : bar >= 29 ? 0.09 : 0.075;
    const notes = bar >= 29 && bar === 32 ? ch.notes : ch.notes;
    notes.forEach((m, ni) => {
      for (let v = 0; v < 3; v++) {
        const det = (v - 1) * 0.09 + (ni % 2 ? 0.02 : -0.02);
        const f = mtof(m + det);
        const pan = clamp(0.5 + (v - 1) * 0.35 + (ni - 2) * 0.06, 0, 1);
        let ph = Math.random();
        const filt = lp1(900);
        const s0 = Math.floor(t0 * SR);
        const s1 = Math.min(N, Math.floor((t0 + dur) * SR));
        for (let s = s0; s < s1; s++) {
          const t = (s - s0) / SR;
          const att = clamp(t / 0.45, 0, 1);
          const rel = clamp((dur - t) / 0.6, 0, 1);
          const env = Math.sin((att * Math.PI) / 2) * Math.sin((rel * Math.PI) / 2);
          // filter opens over the track
          const gt = s / SR;
          const cutBase = gt < 8 ? 500 + gt * 120 : 1400;
          const cut = cutBase + 500 * Math.sin(2 * Math.PI * gt * 0.07 + ni);
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
}

// ---- KICK + sidechain
function kickAt(t, gain = 1) {
  const s0 = Math.floor(t * SR);
  const len = Math.floor(0.42 * SR);
  let ph = 0;
  for (let i = 0; i < len && s0 + i < N; i++) {
    const tt = i / SR;
    const f = 44 + 110 * Math.exp(-tt * 28);
    ph += f / SR;
    const env = Math.exp(-tt * 7.5);
    const click = Math.exp(-tt * 400) * 0.25 * rnd();
    const x = (Math.sin(2 * Math.PI * ph) * env + click) * 0.55 * gain;
    L[s0 + i] += x;
    R[s0 + i] += x;
  }
  // sidechain duck
  const dl = Math.floor(0.38 * SR);
  for (let i = 0; i < dl && s0 + i < N; i++) {
    const tt = i / dl;
    const g = 0.35 + 0.65 * Math.pow(tt, 0.7);
    duck[s0 + i] = Math.min(duck[s0 + i], g);
  }
}

function hatAt(t, gain = 1, open = false) {
  const s0 = Math.floor(t * SR);
  const len = Math.floor((open ? 0.25 : 0.06) * SR);
  let p1 = 0, p2 = 0;
  for (let i = 0; i < len && s0 + i < N; i++) {
    const n = rnd();
    // crude highpass
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
  const len = Math.floor(0.3 * SR);
  const bp = lp1(1800);
  let prev = 0;
  for (let i = 0; i < len && s0 + i < N; i++) {
    const tt = i / SR;
    const burst = [0, 0.011, 0.022].reduce((a, o) => a + (tt >= o ? Math.exp(-(tt - o) * 90) : 0), 0) * 0.5 + Math.exp(-tt * 18) * 0.5;
    const n = rnd();
    const lpv = bp(n);
    const band = lpv - prev * 0.7;
    prev = lpv;
    const x = band * burst * 0.12 * gain;
    L[s0 + i] += x;
    R[s0 + i] += x;
    revL[s0 + i] += x * 0.9;
    revR[s0 + i] += x * 0.9;
  }
}

// ---- BASS
function renderBass() {
  for (let bar = 0; bar < 33; bar++) {
    if (!has.bass(bar)) continue;
    const ch = chordAt(bar);
    for (let e = 0; e < 8; e++) {
      // offbeat 8ths plus downbeat
      if (e % 2 === 0 && e !== 0) continue;
      const t = bar * BAR + e * (BEAT / 2);
      const dur = e === 0 ? BEAT * 0.9 : BEAT * 0.42;
      const f = mtof(ch.root - 12 + 12);
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
}

// ---- ARP (pluck)
function pluck(t, m, gain, pan) {
  const f = mtof(m);
  const s0 = Math.floor(t * SR);
  const len = Math.floor(0.7 * SR);
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

function renderArp() {
  const pattern = [0, 2, 1, 3, 2, 4, 3, 1];
  for (let bar = 0; bar < 33; bar++) {
    if (!has.arp(bar)) continue;
    const ch = chordAt(bar);
    const steps = bar >= 12 && bar <= 20 ? 16 : bar >= 24 && bar <= 28 ? 16 : 8;
    const g = bar >= 29 ? 0.05 * (1 - (bar - 29) / 2) : bar >= 21 && bar <= 23 ? 0.075 : 0.06;
    for (let st = 0; st < steps; st++) {
      const t = bar * BAR + st * (BAR / steps);
      const idx = pattern[st % pattern.length];
      const m = ch.notes[idx] + 12;
      pluck(t, m, g * (st % 4 === 0 ? 1 : 0.7), st % 2 ? 0.68 : 0.32);
    }
  }
}

// ---- FX: risers, impacts, chimes
function riser(tEnd, len, gain = 1) {
  const s1 = Math.floor(tEnd * SR);
  const s0 = Math.max(0, s1 - Math.floor(len * SR));
  const filt = lp1(500);
  let prev = 0;
  for (let s = s0; s < s1 && s < N; s++) {
    const p = (s - s0) / (s1 - s0);
    const cut = 300 + 7000 * p * p;
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
  const len = Math.floor(2.5 * SR);
  let ph = 0;
  const filt = lp1(800);
  for (let i = 0; i < len && s0 + i < N; i++) {
    const tt = i / SR;
    const f = 32 + 70 * Math.exp(-tt * 6);
    ph += f / SR;
    const boom = Math.sin(2 * Math.PI * ph) * Math.exp(-tt * 1.6);
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
  const len = Math.floor(2.2 * SR);
  const f = mtof(m);
  for (let i = 0; i < len && s0 + i < N; i++) {
    const tt = i / SR;
    const env = clamp(tt / 0.002, 0, 1) * Math.exp(-tt * 2.2);
    const x =
      (Math.sin(2 * Math.PI * f * tt) +
        0.35 * Math.sin(2 * Math.PI * f * 2.76 * tt) * Math.exp(-tt * 4) +
        0.18 * Math.sin(2 * Math.PI * f * 5.4 * tt) * Math.exp(-tt * 8)) *
      env * gain;
    L[s0 + i] += x * (1 - pan);
    R[s0 + i] += x * pan;
    revL[s0 + i] += x * 0.7;
    revR[s0 + i] += x * 0.7;
  }
}

function tick(t, gain = 0.05) {
  const s0 = Math.floor(t * SR);
  const len = Math.floor(0.03 * SR);
  for (let i = 0; i < len && s0 + i < N; i++) {
    const tt = i / SR;
    const x = Math.sin(2 * Math.PI * 3200 * tt) * Math.exp(-tt * 260) * gain;
    L[s0 + i] += x;
    R[s0 + i] += x;
  }
}

// ---- build
// drums first (they write sidechain)
for (let bar = 0; bar < 33; bar++) {
  for (let b = 0; b < 4; b++) {
    const t = bar * BAR + b * BEAT;
    if (has.kick(bar)) {
      // groove A: half-time-ish feel bars 4-7
      if (bar >= 8 || b % 2 === 0) kickAt(t, bar < 8 ? 0.85 : 1);
    }
    if (has.clap(bar) && (b === 1 || b === 3)) clapAt(t);
    if (has.hat(bar)) {
      hatAt(t + BEAT / 2, 1, b === 3 && bar % 2 === 1);
      if (bar >= 12) {
        hatAt(t + BEAT / 4, 0.45);
        hatAt(t + (3 * BEAT) / 4, 0.45);
      }
    }
  }
}
// breath before big sections: remove nothing, just risers
riser(4 * BAR, 2.0, 0.8);
riser(12 * BAR, 2.0, 1.1);
riser(24 * BAR, 4.0, 1.1);
riser(29 * BAR, 2.0, 1.0);
impact(4 * BAR, 0.5);
impact(12 * BAR, 0.8);
impact(24 * BAR, 0.8);
impact(29 * BAR, 1.0);

// logo build chimes in intro (beat-aligned)
chime(1 * BAR, 76, 0.05, 0.45);
chime(1 * BAR + BEAT * 1.5, 79, 0.04, 0.55);
chime(2 * BAR + BEAT * 2, 84, 0.05, 0.5);
// multi-room: speakers light up one per beat starting bar 13
[0, 1, 2, 3, 4].forEach((k) => chime(13 * BAR + k * BEAT, [72, 76, 79, 83, 84][k], 0.045, 0.2 + k * 0.15));
// latency countdown ticks (each tier on a bar start of 17..20, plus 16ths)
for (let bar = 17; bar <= 20; bar++) {
  const rate = [4, 8, 8, 16][bar - 17];
  for (let i = 0; i < rate; i++) tick(bar * BAR + i * (BAR / rate), 0.03);
}
// single app filtering: soft "denied" thuds for blocked sources at bars 9/10
[9 * BAR + BEAT, 9 * BAR + BEAT * 3, 10 * BAR + BEAT].forEach((t) => chime(t, 52, 0.03, 0.5));
// final logo chime
chime(29 * BAR + 0.02, 84, 0.06, 0.5);
chime(29 * BAR + BEAT, 88, 0.04, 0.6);

renderPad();
renderBass();
renderArp();

// ---- delay (dotted 8th) into reverb + mix
{
  const d = Math.floor(BEAT * 0.75 * SR);
  const buf = new Float32Array(N);
  for (let s = 0; s < N; s++) {
    const fb = s >= d ? buf[s - d] : 0;
    buf[s] = dlyBus[s] + fb * 0.38;
    const out = s >= d ? buf[s - d] * 0.5 : 0;
    // ping-pong-ish
    L[s] += out * ((Math.floor(s / d) % 2) ? 0.3 : 0.7) * 0.45;
    R[s] += out * ((Math.floor(s / d) % 2) ? 0.7 : 0.3) * 0.45;
    revL[s] += out * 0.2;
    revR[s] += out * 0.2;
  }
}

// ---- Freeverb-style reverb
function freeverb(input, spread) {
  const combs = [1557, 1617, 1491, 1422, 1277, 1356, 1188, 1116].map((n) => n + spread);
  const aps = [556, 441, 341, 225].map((n) => n + spread);
  const out = new Float32Array(N);
  const room = 0.88, damp = 0.35;
  const cbufs = combs.map((n) => ({ b: new Float32Array(Math.round(n * SR / 44100)), i: 0, f: 0 }));
  const abufs = aps.map((n) => ({ b: new Float32Array(Math.round(n * SR / 44100)), i: 0 }));
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

// ---- master: mix, gentle glue, fades, soft clip
const out = new Int16Array(N * 2);
let peak = 0;
const mixL = new Float32Array(N), mixR = new Float32Array(N);
for (let s = 0; s < N; s++) {
  mixL[s] = L[s] + wetL[s] * 2.2;
  mixR[s] = R[s] + wetR[s] * 2.2;
}
for (let s = 0; s < N; s++) peak = Math.max(peak, Math.abs(mixL[s]), Math.abs(mixR[s]));
const norm = 0.92 / Math.tanh(peak * 1.15 / peak) / peak;
for (let s = 0; s < N; s++) {
  const t = s / SR;
  const fadeIn = clamp(t / 0.8, 0, 1);
  const fadeOut = clamp((TOTAL - t) / 3.5, 0, 1);
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
writeFileSync(new URL("../public/music.wav", import.meta.url), wav(out, 2));
console.log("wrote public/music.wav", (N / SR).toFixed(1) + "s", "peak", peak.toFixed(3));
