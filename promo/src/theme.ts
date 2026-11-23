import { Easing, interpolate } from "remotion";

export const FPS = 60;
export const BEAT = 30; // frames @120 BPM
export const BAR = 120;

// Scene boundaries (frames). Every cut lands on a bar line of the soundtrack.
export const T = {
  intro: [0, 4 * BAR],
  anyPhone: [4 * BAR, 7 * BAR],
  oneApp: [7 * BAR, 12 * BAR],
  multiRoom: [12 * BAR, 17 * BAR],
  latency: [17 * BAR, 21 * BAR],
  lan: [21 * BAR, 24 * BAR],
  ui: [24 * BAR, 29 * BAR],
  outro: [29 * BAR, 33 * BAR],
} as const;
export const TOTAL_FRAMES = 33 * BAR;

export const C = {
  bg: "#0B0D12",
  bg2: "#11141B",
  ink: "#F2F3F5",
  ink2: "#9AA3B2",
  ink3: "#5B6475",
  line: "rgba(255,255,255,0.10)",
  line2: "rgba(255,255,255,0.18)",
  coral: "#FF5C6C",
  coralSoft: "rgba(255,92,108,0.16)",
  blue: "#4C8DFF",
  amber: "#FBBF24",
  ring1: "#363C49",
  ring2: "#101218",
  ring3: "#242934",
};

export const FONT_CN = `"PingFang SC", "SF Pro Display", -apple-system, "Helvetica Neue", sans-serif`;
export const FONT_EN = `"SF Pro Display", -apple-system, "Helvetica Neue", "PingFang SC", sans-serif`;
export const FONT_MONO = `"SF Mono", "SFNSMono", ui-monospace, Menlo, monospace`;

export const easeOut = Easing.bezier(0.16, 1, 0.3, 1);
export const easeInOut = Easing.bezier(0.65, 0, 0.35, 1);
export const easeIn = Easing.bezier(0.7, 0, 0.84, 0);

/** 0→1 progress between frames a and b with easing. */
export const prog = (f: number, a: number, b: number, ease = easeOut) =>
  interpolate(f, [a, b], [0, 1], { extrapolateLeft: "clamp", extrapolateRight: "clamp", easing: ease });

export const lerp = (a: number, b: number, t: number) => a + (b - a) * t;

/** Fade a whole scene in/out at its edges. */
export const sceneFade = (f: number, dur: number, inLen = 14, outLen = 14) =>
  Math.min(prog(f, 0, inLen, easeInOut), 1 - prog(f, dur - outLen, dur, easeInOut));
