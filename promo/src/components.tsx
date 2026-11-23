import React from "react";
import { AbsoluteFill, useCurrentFrame, staticFile, Img } from "remotion";
import { C, FONT_CN, FONT_EN, prog, easeOut, BEAT, lerp } from "./theme";

export const NATIVE_W = 678;
export const NATIVE_H = 1510;

/** Film grain + vignette, rendered above everything. */
export const Finish: React.FC = () => {
  const f = useCurrentFrame();
  const seed = Math.floor(f / 2) % 50;
  return (
    <AbsoluteFill style={{ pointerEvents: "none" }}>
      <AbsoluteFill
        style={{
          background: "radial-gradient(ellipse 78% 74% at 50% 48%, rgba(0,0,0,0) 58%, rgba(0,0,0,0.52) 100%)",
        }}
      />
      <svg width="1920" height="1080" style={{ position: "absolute", opacity: 0.06, mixBlendMode: "overlay" }}>
        <filter id="grain">
          <feTurbulence type="fractalNoise" baseFrequency="0.85" numOctaves="2" seed={seed} stitchTiles="stitch" />
          <feColorMatrix type="saturate" values="0" />
        </filter>
        <rect width="100%" height="100%" filter="url(#grain)" />
      </svg>
    </AbsoluteFill>
  );
};

/** Per-character reveal: rise + unblur, staggered. */
export const Reveal: React.FC<{
  text: string;
  start: number;
  size: number;
  weight?: number;
  color?: string;
  stagger?: number;
  dur?: number;
  font?: string;
  tracking?: number;
  out?: number;
  style?: React.CSSProperties;
}> = ({
  text,
  start,
  size,
  weight = 600,
  color = C.ink,
  stagger = 2,
  dur = 36,
  font = FONT_CN,
  tracking = 0,
  out,
  style,
}) => {
  const f = useCurrentFrame();
  const chars = Array.from(text);
  return (
    <div
      style={{
        display: "inline-flex",
        whiteSpace: "pre",
        fontFamily: font,
        fontSize: size,
        fontWeight: weight,
        color,
        letterSpacing: tracking,
        lineHeight: 1.2,
        ...style,
      }}
    >
      {chars.map((ch, i) => {
        const p = prog(f, start + i * stagger, start + i * stagger + dur, easeOut);
        const o = out !== undefined ? 1 - prog(f, out + i * 0.6, out + i * 0.6 + 16) : 1;
        return (
          <span
            key={i}
            style={{
              display: "inline-block",
              opacity: p * o,
              transform: `translateY(${(1 - p) * 0.42 * size + (1 - o) * -0.18 * size}px)`,
              filter: `blur(${(1 - p) * 9 + (1 - o) * 7}px)`,
            }}
          >
            {ch}
          </span>
        );
      })}
    </div>
  );
};

/** Small uppercase eyebrow label. */
export const Eyebrow: React.FC<{ text: string; start: number; color?: string; out?: number }> = ({
  text,
  start,
  color = C.coral,
  out,
}) => {
  const f = useCurrentFrame();
  const p = prog(f, start, start + 36);
  const o = out !== undefined ? 1 - prog(f, out, out + 16) : 1;
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 14, opacity: p * o }}>
      <div style={{ width: 36 * p, height: 2.5, background: color, borderRadius: 2 }} />
      <div
        style={{
          fontFamily: FONT_EN,
          fontSize: 18,
          fontWeight: 600,
          letterSpacing: 4.5,
          color,
          textTransform: "uppercase",
        }}
      >
        {text}
      </div>
    </div>
  );
};

/** The MiaoCast driver mark, drawn in parts so it can be built up. */
export const LogoMark: React.FC<{
  size: number;
  rings: number;
  core: number;
  play: number;
  arc: number;
  pulse?: number;
}> = ({ size, rings, core, play, arc, pulse = 0 }) => {
  const r = (a: number, b: number) => Math.max(0, Math.min(1, (rings - a) / (b - a)));
  const k = 1 + pulse * 0.04;
  return (
    <svg width={size} height={size} viewBox="0 0 512 512" style={{ overflow: "visible" }}>
      <g transform={`translate(226 256) scale(${k}) translate(-226 -256)`}>
        <circle cx="226" cy="256" r={110 * easeOut(r(0, 0.5))} fill={C.ring1} />
        <circle cx="226" cy="256" r={93 * easeOut(r(0.15, 0.65))} fill={C.ring2} />
        <circle cx="226" cy="256" r={72 * easeOut(r(0.3, 0.85))} fill={C.ring3} />
        <circle cx="226" cy="256" r={42 * core} fill={C.coral} />
        <path
          d="M 214.5 240.5 L 214.5 271.5 Q 214.5 279.5 222.0 275.2 L 247.0 260.3 Q 254.5 256 247.0 251.7 L 222.0 236.8 Q 214.5 232.5 214.5 240.5 Z"
          fill="#FFFFFF"
          opacity={play}
          transform={`translate(232 256) scale(${0.4 + 0.6 * play}) translate(-232 -256)`}
        />
      </g>
      <path
        d="M 327.6 147.0 A 149 149 0 0 1 327.6 365.0"
        fill="none"
        stroke={C.coral}
        strokeWidth="38"
        strokeLinecap="round"
        pathLength={1}
        strokeDasharray="1 2"
        strokeDashoffset={1 - arc}
        opacity={arc > 0.001 ? 1 : 0}
      />
    </svg>
  );
};

/** Expanding ring pulse. */
export const Ripple: React.FC<{
  cx: number;
  cy: number;
  t: number;
  maxR: number;
  color?: string;
  width?: number;
}> = ({ cx, cy, t, maxR, color = C.ink, width = 1.5 }) => {
  if (t <= 0 || t >= 1) return null;
  const e = easeOut(t);
  return (
    <circle cx={cx} cy={cy} r={e * maxR} fill="none" stroke={color} strokeWidth={width} opacity={(1 - t) * 0.65} />
  );
};

/** Generic smart-speaker glyph (cylinder style). */
export const Speaker: React.FC<{ x: number; y: number; s?: number; active?: number; label?: string }> = ({
  x,
  y,
  s = 1,
  active = 0,
  label,
}) => {
  const f = useCurrentFrame();
  const beatT = (f % BEAT) / BEAT;
  return (
    <g transform={`translate(${x} ${y}) scale(${s})`}>
      {active > 0.5 && (
        <>
          <Ripple cx={0} cy={0} t={beatT} maxR={90} color={C.coral} width={2} />
          <Ripple cx={0} cy={0} t={(beatT + 0.5) % 1} maxR={90} color={C.coral} width={1.2} />
        </>
      )}
      <circle r={44} fill={active > 0 ? `rgba(255,92,108,${0.12 * active})` : "transparent"} />
      <rect
        x={-22}
        y={-30}
        width={44}
        height={60}
        rx={14}
        fill={C.bg2}
        stroke={active > 0 ? C.coral : C.line2}
        strokeWidth={2.5}
      />
      <circle cx={0} cy={6} r={11} fill="none" stroke={active > 0 ? C.coral : C.ink3} strokeWidth={2.5} />
      <circle cx={0} cy={-17} r={3} fill={active > 0 ? C.coral : C.ink3} />
      {label && (
        <text
          y={44 + 28 / s}
          textAnchor="middle"
          fontFamily={FONT_CN}
          fontSize={22 / s}
          fontWeight={500}
          letterSpacing={1 / s}
          fill={active > 0 ? C.ink2 : C.ink3}
        >
          {label}
        </text>
      )}
    </g>
  );
};

/**
 * Pixel-accurate cutout slice from a real 678x1510 screenshot.
 * Renders the exact region [x, y, w, h] scaled by `scale`, with crisp rounded corners & 3D elevation shadow.
 */
export const UiSlice: React.FC<{
  src: string;
  src2?: string;
  mix?: number;
  rect: readonly [number, number, number, number]; // [x, y, w, h] in 678x1510 space
  radius?: number; // in 678x1510 space
  scale?: number; // display scale relative to 678px phone width
  elevation?: number; // 0..1 shadow & highlight intensity
  accentBorder?: string;
  style?: React.CSSProperties;
  children?: React.ReactNode;
}> = ({
  src,
  src2,
  mix = 0,
  rect,
  radius = 24,
  scale = 1,
  elevation = 1,
  accentBorder,
  style,
  children,
}) => {
  const [rx, ry, rw, rh] = rect;
  const w = rw * scale;
  const h = rh * scale;
  const r = radius * scale;
  const borderCol = accentBorder || `rgba(255,255,255,${0.12 + 0.22 * elevation})`;
  return (
    <div
      style={{
        width: w,
        height: h,
        borderRadius: r,
        overflow: "hidden",
        position: "relative",
        boxShadow:
          elevation > 0.01
            ? `0 ${28 * elevation}px ${65 * elevation}px rgba(0,0,0,${0.62 * elevation}), 0 0 0 ${1.5 * elevation}px ${borderCol}`
            : "none",
        ...style,
      }}
    >
      <Img
        src={staticFile(src)}
        style={{
          position: "absolute",
          left: -rx * scale,
          top: -ry * scale,
          width: NATIVE_W * scale,
          height: NATIVE_H * scale,
          maxWidth: "none",
          display: "block",
        }}
      />
      {src2 && mix > 0.001 && (
        <Img
          src={staticFile(src2)}
          style={{
            position: "absolute",
            left: -rx * scale,
            top: -ry * scale,
            width: NATIVE_W * scale,
            height: NATIVE_H * scale,
            maxWidth: "none",
            display: "block",
            opacity: mix,
          }}
        />
      )}
      {children}
    </div>
  );
};

/** Simulated finger touch tap ripple at native (x, y) on the phone screen. */
export const TapRipple: React.FC<{
  x: number;
  y: number;
  start: number;
  scale?: number;
  color?: string;
}> = ({ x, y, start, scale = 1, color = C.coral }) => {
  const f = useCurrentFrame();
  const dt = f - start;
  if (dt < 0 || dt > 38) return null;
  const t = dt / 38;
  const e = easeOut(t);
  const press = dt < 10 ? dt / 10 : Math.max(0, 1 - (dt - 10) / 14);
  return (
    <div
      style={{
        position: "absolute",
        left: x * scale,
        top: y * scale,
        pointerEvents: "none",
        transform: "translate(-50%, -50%)",
      }}
    >
      {/* outer expanding wave */}
      <div
        style={{
          width: (24 + e * 96) * scale,
          height: (24 + e * 96) * scale,
          borderRadius: 999,
          border: `${2.5 * scale}px solid ${color}`,
          opacity: (1 - t) * 0.9,
          position: "absolute",
          left: "50%",
          top: "50%",
          transform: "translate(-50%, -50%)",
        }}
      />
      {/* central touch dot */}
      <div
        style={{
          width: 30 * scale,
          height: 30 * scale,
          borderRadius: 999,
          background: color,
          boxShadow: `0 0 ${20 * scale}px ${color}`,
          opacity: press * 0.85,
          position: "absolute",
          left: "50%",
          top: "50%",
          transform: `translate(-50%, -50%) scale(${0.7 + 0.4 * press})`,
        }}
      />
    </div>
  );
};

export interface FloatingSliceSpec {
  id: string;
  src?: string;
  rect: readonly [number, number, number, number];
  radius?: number;
  elevate: number; // 0..1: 0 = flush on phone screen, 1 = popped out
  dx?: number; // extra offset in screen px when elevated
  dy?: number;
  popScale?: number; // multiplier when elevated (default 1.08)
  accentBorder?: string;
  overlay?: React.ReactNode;
}

/**
 * Titanium-framed phone that holds real UI screenshots, supports smooth crossfade/wipe between screens,
 * dims its base layer when cards pop out, and elevates real UI slices directly from their native coordinates.
 */
export const StagePhone: React.FC<{
  src: string;
  src2?: string;
  fade2?: number; // 0..1 opacity crossfade to src2
  wipe2?: number; // 0..1 circular wipe to src2
  wipeOrigin?: string;
  w?: number; // rendered screen width (default 440)
  dim?: number; // 0..1 dim/blur background screenshot so popped slices stand out
  slices?: FloatingSliceSpec[];
  taps?: { x: number; y: number; start: number; color?: string }[];
  screenOverlay?: React.ReactNode;
  style?: React.CSSProperties;
}> = ({
  src,
  src2,
  fade2 = 0,
  wipe2 = 0,
  wipeOrigin = "85% 4%",
  w = 440,
  dim = 0,
  slices = [],
  taps = [],
  screenOverlay,
  style,
}) => {
  const s = w / NATIVE_W;
  const h = NATIVE_H * s;
  const bez = w * 0.032;
  const activeSrc = fade2 > 0.5 && src2 ? src2 : src;

  return (
    <div
      style={{
        width: w + bez * 2,
        height: h + bez * 2,
        borderRadius: w * 0.145,
        padding: bez,
        background: "linear-gradient(145deg, #343A46 0%, #161920 38%, #232833 72%, #12151B 100%)",
        boxShadow:
          "0 60px 130px rgba(0,0,0,0.68), 0 0 0 1.5px rgba(255,255,255,0.14) inset, 0 2px 24px rgba(76,141,255,0.08)",
        position: "relative",
        ...style,
      }}
    >
      {/* inner screen */}
      <div
        style={{
          width: w,
          height: h,
          borderRadius: w * 0.118,
          overflow: "hidden",
          position: "relative",
          background: "#0B0E14",
        }}
      >
        <div
          style={{
            position: "absolute",
            inset: 0,
            filter: dim > 0.01 ? `brightness(${1 - dim * 0.48}) blur(${dim * 2.5}px)` : undefined,
            transform: dim > 0.01 ? `scale(${1 - dim * 0.015})` : undefined,
          }}
        >
          <Img src={staticFile(src)} style={{ width: "100%", height: "100%", display: "block" }} />
          {src2 && fade2 > 0.001 && (
            <Img
              src={staticFile(src2)}
              style={{
                position: "absolute",
                inset: 0,
                width: "100%",
                height: "100%",
                opacity: fade2,
              }}
            />
          )}
          {src2 && wipe2 > 0.001 && (
            <Img
              src={staticFile(src2)}
              style={{
                position: "absolute",
                inset: 0,
                width: "100%",
                height: "100%",
                clipPath: `circle(${wipe2 * 150}% at ${wipeOrigin})`,
              }}
            />
          )}
          {screenOverlay}
        </div>

        {/* subtle glass reflection */}
        <div
          style={{
            position: "absolute",
            inset: 0,
            background: "linear-gradient(115deg, rgba(255,255,255,0.07) 0%, rgba(255,255,255,0) 34%)",
            pointerEvents: "none",
          }}
        />

        {/* touch ripples inside screen */}
        {taps.map((tp, idx) => (
          <TapRipple key={idx} x={tp.x} y={tp.y} start={tp.start} scale={s} color={tp.color} />
        ))}
      </div>

      {/* Elevated real UI slices that pop out of the screen bounds */}
      {slices.map((sl) => {
        if (sl.elevate <= 0.001) return null;
        const [rx, ry] = sl.rect;
        const e = sl.elevate;
        const pScale = lerp(1, sl.popScale ?? 1.08, e);
        const dx = (sl.dx ?? 0) * e;
        const dy = (sl.dy ?? 0) * e;
        return (
          <div
            key={sl.id}
            style={{
              position: "absolute",
              left: bez + rx * s,
              top: bez + ry * s,
              transform: `translate(${dx}px, ${dy}px) scale(${pScale})`,
              transformOrigin: "center center",
              zIndex: 10,
            }}
          >
            <UiSlice
              src={sl.src ?? activeSrc}
              rect={sl.rect}
              radius={sl.radius ?? 24}
              scale={s}
              elevation={e}
              accentBorder={sl.accentBorder}
            >
              {sl.overlay}
            </UiSlice>
          </div>
        );
      })}
    </div>
  );
};

/** Legacy Phone wrapper for compatibility */
export const Phone = StagePhone;
export const Logo: React.FC<{ size: number }> = ({ size }) => (
  <Img src={staticFile("logo.svg")} style={{ width: size, height: size }} />
);
