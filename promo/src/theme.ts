import { Easing, interpolate } from "remotion";

export type PromoLocale = "zh" | "en" | "ja";

export const FPS = 60;
export const BEAT = 30; // frames @120 BPM
export const BAR = 120;

// Scene boundaries (frames). Every cut lands on a bar line of the 120 BPM soundtrack.
export const T = {
  intro: [0, 4 * BAR],
  anyPhone: [4 * BAR, 7 * BAR],
  oneApp: [7 * BAR, 12 * BAR],
  multiRoomLatency: [12 * BAR, 19 * BAR],
  lan: [19 * BAR, 22 * BAR],
  ui: [22 * BAR, 25 * BAR],
  search: [25 * BAR, 27 * BAR],
  outro: [27 * BAR, 31 * BAR],
} as const;
export const TOTAL_FRAMES = 31 * BAR;

export const C = {
  bg: "#0B0D12",
  bg2: "#11141B",
  ink: "#F2F3F5",
  ink2: "#A8B2C2",
  ink3: "#687387",
  line: "rgba(255,255,255,0.10)",
  line2: "rgba(255,255,255,0.20)",
  coral: "#FF5C6C",
  coralSoft: "rgba(255,92,108,0.16)",
  blue: "#4C8DFF",
  emerald: "#34D399",
  amber: "#FBBF24",
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
