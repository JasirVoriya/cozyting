import { Easing, interpolate } from "remotion";

export type PromoLocale = "zh" | "en" | "ja";

export const FPS = 60;
export const BEAT = 30; // frames @120 BPM
export const BAR = 120;

// Scene boundaries (frames). Every cut lands on a bar line of the 120 BPM soundtrack.
// Intro (6s / 3 bars): 0-2s (0..120) calm pain-point question; 2-6s (120..360) brand lockup + real UI.
export const T = {
  intro: [0, 3 * BAR],
  anyPhone: [3 * BAR, 6 * BAR],
  oneApp: [6 * BAR, 11 * BAR],
  multiRoomLatency: [11 * BAR, 18 * BAR],
  lan: [18 * BAR, 21 * BAR],
  ui: [21 * BAR, 24 * BAR],
  search: [24 * BAR, 26 * BAR],
  outro: [26 * BAR, 30 * BAR],
} as const;
export const TOTAL_FRAMES = 30 * BAR;

export const C = {
  bg: "#0B0D12",
  bg2: "#11141B",
  ink: "#F2F3F5",
  ink2: "#A8B2C2",
  ink3: "#687387",
  line: "rgba(255,255,255,0.10)",
  line2: "rgba(255,255,255,0.20)",
  coral: "#E56D48",
  coralSoft: "rgba(229,109,72,0.16)",
  blue: "#4C8DFF",
  emerald: "#34D399",
  amber: "#FBBF24",
  warmGold: "#FFD166",
  amberSill: "#EE9B48",
  tileDark: "#1B1F2B",
  wallDark: "#474D5F",
  catInk: "#20232F",
  catNose: "#EF8B65",
  ring1: "#363C49",
  ring2: "#101218",
  ring3: "#242934",
};

export const FONT_CN = `"PingFang SC", "Hiragino Sans GB", "Noto Sans CJK SC", "SF Pro Display", -apple-system, sans-serif`;
export const FONT_EN = `"SF Pro Display", -apple-system, "Helvetica Neue", "Inter", sans-serif`;
export const FONT_JA = `"Hiragino Sans", "Hiragino Kaku Gothic ProN", "Yu Gothic", "Noto Sans CJK JP", "SF Pro Display", -apple-system, sans-serif`;
export const FONT_MONO = `"SF Mono", "SFNSMono", ui-monospace, Menlo, monospace`;

export function fontForLocale(locale: PromoLocale): string {
  switch (locale) {
    case "en":
      return FONT_EN;
    case "ja":
      return FONT_JA;
    default:
      return FONT_CN;
  }
}

export function uiImg(locale: PromoLocale, file: string): string {
  return `ui/${locale}/${file}`;
}

export const easeOut = Easing.bezier(0.16, 1, 0.3, 1);
export const easeInOut = Easing.bezier(0.65, 0, 0.35, 1);
export const easeIn = Easing.bezier(0.7, 0, 0.84, 0);

/** 0→1 progress between frames a and b with easing. */
export const prog = (f: number, a: number, b: number, ease = easeOut) =>
  interpolate(f, [a, b], [0, 1], { extrapolateLeft: "clamp", extrapolateRight: "clamp", easing: ease });

export const lerp = (a: number, b: number, t: number) => a + (b - a) * t;

/** Fade a whole scene in/out at its edges. */
export const sceneFade = (f: number, dur: number, inLen = 14, outLen = 14) =>
  Math.min(prog(f, 0, inLen, easeInOut), outLen > 0 ? 1 - prog(f, dur - outLen, dur, easeInOut) : 1);
