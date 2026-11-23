import React from "react";
import { AbsoluteFill, useCurrentFrame, interpolate } from "remotion";
import { C, FONT_EN, FONT_MONO, fontForLocale, uiImg, prog, easeInOut, sceneFade, easeOut, lerp, type PromoLocale } from "../theme";
import { Eyebrow, Reveal, Speaker, StagePhone } from "../components";
import { getCopy } from "../i18n";

const TIER_AT = [295, 430, 565, 700];

export const MultiRoomLatency: React.FC<{ dur: number; locale: PromoLocale }> = ({ dur, locale }) => {
  const f = useCurrentFrame();
  const copy = getCopy(locale).multiRoom;
  const fontMain = fontForLocale(locale);
  const TIERS = copy.tiers;
  const ROOMS = copy.rooms;

  const popSpk1 = prog(f, 16, 62, easeOut) * (1 - prog(f, 245, 275, easeInOut));
  const popSpk2 = prog(f, 34, 80, easeOut) * (1 - prog(f, 245, 275, easeInOut));
  const latencyMode = prog(f, 255, 295, easeInOut);
  const walls = prog(f, 10, 80, easeInOut);

  const vol1 = Math.round(lerp(62, 78, Math.sin(prog(f, 90, 240, easeInOut) * Math.PI)));
  const vol2 = Math.round(lerp(48, 60, Math.sin(prog(f, 120, 250, easeInOut) * Math.PI)));

  const count = Math.round(
    interpolate(f, [150, 245], [2, 63], { extrapolateLeft: "clamp", extrapolateRight: "clamp", easing: easeOut })
  );

  let tierIdx = 0;
  TIER_AT.forEach((a, i) => (f >= a ? (tierIdx = i) : null));
  const prevMs = tierIdx === 0 ? 800 : TIERS[tierIdx - 1].ms;
  const tierProg = prog(f, TIER_AT[tierIdx], TIER_AT[tierIdx] + 36, easeOut);
  const currentMs = latencyMode < 0.2 ? 800 : Math.round(lerp(prevMs, TIERS[tierIdx].ms, tierProg));
  const activeTier = TIERS[tierIdx];

  const pillPos = interpolate(
    f,
    [295, 320, 430, 456, 565, 591, 700, 726],
    [0, 0, 0, 1, 1, 2, 2, 3],
    { extrapolateLeft: "clamp", extrapolateRight: "clamp", easing: easeInOut }
  );

  const tierSlices = TIERS.map((t, i) => {
    const enter = prog(f, TIER_AT[i], TIER_AT[i] + 24, easeOut);
    const leave = i < 3 ? prog(f, TIER_AT[i + 1], TIER_AT[i + 1] + 20, easeInOut) : 0;
    const elevate = Math.max(0, enter - leave) * latencyMode;
    return {
      id: `tier-${i}`,
      src: uiImg(locale, "02-dark.png"),
      rect: t.rect,
      radius: 22,
      elevate,
      dx: 48,
      dy: -4,
      popScale: 1.22,
      accentBorder: t.accent,
    };
  });

  const allSlices = [
    {
      id: "sound-pro-card",
      src: uiImg(locale, "01-dark.png"),
      rect: [48, 792, 582, 150] as const,
      radius: 26,
      elevate: popSpk1,
      dx: 44,
      dy: -16,
      popScale: 1.2,
      accentBorder: "rgba(59,130,246,0.92)",
      overlay: (
        <div
          style={{
            position: "absolute",
            right: 14,
            bottom: 12,
            padding: "3px 10px",
            borderRadius: 8,
            background: "#2563EB",
            color: "#FFFFFF",
            fontFamily: FONT_MONO,
            fontSize: 14,
            fontWeight: 700,
          }}
        >
          {vol1}%
        </div>
      ),
    },
    {
      id: "xiaoai-pro-card",
      src: uiImg(locale, "01-dark.png"),
      rect: [48, 958, 582, 150] as const,
      radius: 26,
      elevate: popSpk2,
      dx: 44,
      dy: 12,
      popScale: 1.2,
      accentBorder: "rgba(59,130,246,0.92)",
      overlay: (
        <div
          style={{
            position: "absolute",
            right: 14,
            bottom: 12,
            padding: "3px 10px",
            borderRadius: 8,
            background: "#2563EB",
            color: "#FFFFFF",
            fontFamily: FONT_MONO,
            fontSize: 14,
            fontWeight: 700,
          }}
        >
          {vol2}%
        </div>
      ),
    },
    ...tierSlices,
  ];

  const PW = 162;
  const activeAccentColor = latencyMode > 0.5 ? (tierIdx === 3 ? C.coral : activeTier.color) : C.coral;

  return (
    <AbsoluteFill style={{ background: C.bg, opacity: sceneFade(f, dur, 8, 16) }}>
      <AbsoluteFill
        style={{
          background: `radial-gradient(circle at 24% 55%, rgba(46,108,246,0.20) 0%, rgba(46,108,246,0) 42%), radial-gradient(circle at 72% 48%, rgba(255,92,108,${0.10 + 0.08 * (tierIdx / 3)}) 0%, rgba(255,92,108,0) 44%)`,
        }}
      />

      {/* Top Headline spanning across right column */}
      <div style={{ position: "absolute", left: 685, top: 56, width: 1160, zIndex: 20 }}>
        <Eyebrow text={copy.eyebrow} start={4} />
        <div style={{ marginTop: 12, display: "flex", alignItems: "baseline", gap: 16, flexWrap: "nowrap" }}>
          <Reveal
            text={copy.headlinePart1}
            start={10}
            size={copy.headlineSize}
            weight={700}
            font={fontMain}
            stagger={locale === "en" ? 1.0 : 1.6}
          />
          <Reveal
            text={copy.headlinePart2}
            start={30}
            size={copy.headlineSize}
            weight={700}
            font={fontMain}
            stagger={locale === "en" ? 0.9 : 1.4}
            color={C.coral}
          />
        </div>
      </div>

      {/* LEFT: Real Phone transitioning from 01-dark -> 02-dark */}
      <div style={{ position: "absolute", left: 105, top: 90, perspective: 1800, zIndex: 15 }}>
        <div style={{ transform: "rotateY(9deg) rotateX(2deg)", transformStyle: "preserve-3d" }}>
          <StagePhone
            src={uiImg(locale, "01-dark.png")}
            src2={uiImg(locale, "02-dark.png")}
            fade2={latencyMode}
            w={420}
            dim={0.62}
            taps={[
              { x: 520, y: 50, start: 245, color: "#60A5FA" },
              ...TIERS.map((t, i) => ({
                x: 340,
                y: t.rect[1] + 75,
                start: TIER_AT[i] + 2,
                color: i === 3 ? C.coral : "#60A5FA",
              })),
            ]}
            slices={allSlices}
          />
        </div>
      </div>

      {/* RIGHT STAGE — Top Row: Dynamic Mode Panel */}
      <div
        style={{
          position: "absolute",
          left: 685,
          top: 210,
          width: 1145,
          height: 310,
          borderRadius: 32,
          background: "rgba(20,26,38,0.86)",
          border: `2px solid ${latencyMode > 0.5 ? activeTier.accent : "rgba(255,255,255,0.16)"}`,
          boxShadow: "0 28px 70px rgba(0,0,0,0.48)",
          padding: "28px 36px",
          overflow: "hidden",
        }}
      >
        {/* Phase 1 Banner: Multi-room 63-speaker capacity */}
        {1 - latencyMode > 0.001 && (
          <div
            style={{
              position: "absolute",
              inset: "28px 36px",
              opacity: 1 - latencyMode,
              display: "flex",
              alignItems: "center",
              justifyContent: "space-between",
              gap: 24,
            }}
          >
            <div>
              <div style={{ fontFamily: fontMain, fontSize: 31, fontWeight: 700, color: "#FFFFFF" }}>
                {copy.phase1Title}
              </div>
              <div style={{ marginTop: 14, fontFamily: fontMain, fontSize: 23, color: "#CBD5E1", lineHeight: 1.45 }}>
                {copy.phase1Sub}
              </div>
            </div>
            <div style={{ display: "flex", alignItems: "baseline", gap: 14, flexShrink: 0 }}>
              <span style={{ fontFamily: fontMain, fontSize: 26, color: "#CBD5E1" }}>{copy.countPrefix}</span>
              <span
                style={{
                  fontFamily: FONT_EN,
                  fontSize: 128,
                  fontWeight: 800,
                  color: C.coral,
                  lineHeight: 1,
                  fontVariantNumeric: "tabular-nums",
                }}
              >
                {count}
              </span>
              <span style={{ fontFamily: fontMain, fontSize: 26, color: "#CBD5E1" }}>{copy.countSuffix}</span>
            </div>
          </div>
        )}

        {/* Phase 2 Live 4-Tier Hot-Switch Control Panel */}
        {latencyMode > 0.001 && (
          <div
            style={{
              position: "absolute",
              inset: "26px 36px",
              opacity: latencyMode,
              display: "flex",
              flexDirection: "column",
              justifyContent: "space-between",
            }}
          >
            <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
              <div
                style={{
                  width: PW * 4 + 14,
                  height: 64,
                  borderRadius: 32,
                  background: "rgba(255,255,255,0.06)",
                  border: "1.5px solid rgba(255,255,255,0.16)",
                  position: "relative",
                }}
              >
                <div
                  style={{
                    position: "absolute",
                    top: 6,
                    left: 7 + pillPos * PW,
                    width: PW,
                    height: 50,
                    borderRadius: 25,
                    background: tierIdx === 3 ? C.coral : "#FFFFFF",
                  }}
                />
                {TIERS.map((t, i) => (
                  <div
                    key={t.name}
                    style={{
                      position: "absolute",
                      top: 6,
                      left: 7 + i * PW,
                      width: PW,
                      height: 50,
                      display: "flex",
                      alignItems: "center",
                      justifyContent: "center",
                      fontFamily: fontMain,
                      fontSize: 23,
                      fontWeight: 700,
                      color: Math.abs(pillPos - i) < 0.5 ? (tierIdx === 3 ? "#FFFFFF" : C.bg) : "#CBD5E1",
                    }}
                  >
                    {t.name}
                  </div>
                ))}
              </div>

              <div style={{ display: "flex", alignItems: "baseline", gap: 10 }}>
                <span
                  style={{
                    fontFamily: FONT_EN,
                    fontSize: 108,
                    fontWeight: 800,
                    color: activeTier.color,
                    lineHeight: 0.95,
                    letterSpacing: -3,
                    fontVariantNumeric: "tabular-nums",
                  }}
                >
                  {currentMs}
                </span>
                <span style={{ fontFamily: FONT_EN, fontSize: 38, fontWeight: 600, color: "#CBD5E1" }}>ms</span>
              </div>
            </div>

            <div style={{ fontFamily: fontMain, fontSize: 30, fontWeight: 700, color: "#FFFFFF" }}>
              {copy.effectPrefix}
              <span style={{ color: activeTier.color }}>{activeTier.tag}</span>
            </div>

            <div>
              <div
                style={{
                  height: 14,
                  borderRadius: 7,
                  background: "rgba(255,255,255,0.10)",
                  position: "relative",
                  overflow: "hidden",
                }}
              >
                <div
                  style={{
                    width: `${(currentMs / 900) * 100}%`,
                    height: "100%",
                    borderRadius: 7,
                    background: tierIdx === 3 ? C.coral : activeTier.color,
                    boxShadow: `0 0 22px ${activeTier.color}`,
                  }}
                />
              </div>
              <div
                style={{
                  display: "flex",
                  justifyContent: "space-between",
                  marginTop: 10,
                  fontFamily: fontMain,
                  fontSize: 18,
                  fontWeight: 600,
                  color: "#94A3B8",
                }}
              >
                {copy.barLabels.map((lb) => (
                  <span key={lb}>{lb}</span>
                ))}
              </div>
            </div>
          </div>
        )}
      </div>

      {/* RIGHT STAGE — Bottom Row: LIVE Multi-Room Floorplan + Waveform Monitor */}
      <div
        style={{
          position: "absolute",
          left: 685,
          top: 550,
          width: 1145,
          height: 455,
          borderRadius: 32,
          background: "rgba(17,22,32,0.88)",
          border: "1.5px solid rgba(255,255,255,0.16)",
          padding: "22px 32px",
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
          gap: 32,
        }}
      >
        <svg width="600" height="410" style={{ flexShrink: 0, overflow: "visible" }}>
          {ROOMS.map((r, i) => {
            const active = prog(f, r.t, r.t + 16);
            return (
              <g key={i}>
                <rect
                  x={r.x}
                  y={r.y}
                  width={r.w}
                  height={r.h}
                  fill={`rgba(255,92,108,${0.02 + 0.05 * active})`}
                  stroke={active > 0 ? activeAccentColor : C.line2}
                  strokeOpacity={active > 0 ? 0.55 : 0.4}
                  strokeWidth={2.2}
                  pathLength={1}
                  strokeDasharray="1 1"
                  strokeDashoffset={1 - walls}
                />
                <text
                  x={r.x + 18}
                  y={r.y + 38}
                  fontFamily={fontMain}
                  fontSize={24}
                  fontWeight={700}
                  fill={active > 0 ? "#FFFFFF" : C.ink3}
                >
                  {r.name}
                </text>
                {active > 0.5 && (
                  <g transform={`translate(${r.x + r.w - 104} ${r.y + 14})`}>
                    <rect
                      x={0}
                      y={0}
                      width={90}
                      height={30}
                      rx={15}
                      fill={tierIdx === 3 && latencyMode > 0.5 ? "rgba(255,92,108,0.28)" : "rgba(59,130,246,0.26)"}
                      stroke={activeAccentColor}
                      strokeWidth={1.3}
                    />
                    <text
                      x={45}
                      y={21}
                      textAnchor="middle"
                      fontFamily={FONT_MONO}
                      fontSize={16}
                      fontWeight={700}
                      fill="#FFFFFF"
                    >
                      {currentMs}ms
                    </text>
                  </g>
                )}
              </g>
            );
          })}

          {ROOMS.map((r, i) => {
            const active = prog(f, r.t, r.t + 14);
            return (
              <g key={`spk-${i}`} opacity={prog(f, 25 + i * 6, 55 + i * 6)}>
                <Speaker x={r.spkX} y={r.spkY} s={1.08} active={active} />
              </g>
            );
          })}
        </svg>

        <div style={{ flex: 1, display: "flex", flexDirection: "column", justifyContent: "space-between", height: "100%", padding: "12px 0" }}>
          <div>
            <div style={{ fontFamily: fontMain, fontSize: 27, fontWeight: 700, color: "#FFFFFF" }}>
              {copy.waveTitle}
            </div>
            <div style={{ marginTop: 8, fontFamily: fontMain, fontSize: 20, color: "#94A3B8" }}>
              {latencyMode > 0.5 ? copy.waveSubLatency : copy.waveSubSync}
            </div>
          </div>

          <div style={{ position: "relative", display: "flex", flexDirection: "column", gap: 18 }}>
            {copy.waveRooms.map((roomName) => {
              const speed = latencyMode > 0.5 ? activeTier.pulseSpeed : 1.0;
              const pts: string[] = [];
              for (let x = 0; x <= 290; x += 4) {
                const v =
                  Math.sin(x * 0.08 - f * 0.22 * speed) * 0.6 +
                  Math.sin(x * 0.21 - f * 0.45 * speed) * 0.4;
                pts.push(`${x ? "L" : "M"}${x} ${(22 + v * 15).toFixed(1)}`);
              }
              return (
                <div key={roomName} style={{ display: "flex", alignItems: "center", gap: 14 }}>
                  <div style={{ width: copy.waveLabelWidth, fontFamily: fontMain, fontSize: 22, fontWeight: 600, color: "#E2E8F0", whiteSpace: "nowrap" }}>
                    {roomName}
                  </div>
                  <svg width={290} height={44}>
                    <path d={pts.join(" ")} fill="none" stroke={activeAccentColor} strokeWidth={3} strokeLinecap="round" />
                  </svg>
                </div>
              );
            })}
            <div
              style={{
                position: "absolute",
                left: copy.waveLabelWidth + 14 + 145,
                top: -6,
                width: 3,
                height: 178,
                background: C.coral,
                borderRadius: 2,
                boxShadow: `0 0 12px ${C.coral}`,
              }}
            />
          </div>

          <div
            style={{
              padding: "12px 18px",
              borderRadius: 16,
              background: "rgba(255,255,255,0.05)",
              border: "1px solid rgba(255,255,255,0.12)",
              fontFamily: fontMain,
              fontSize: 20,
              fontWeight: 600,
              color: "#E2E8F0",
              textAlign: "center",
              whiteSpace: "nowrap",
            }}
          >
            {latencyMode > 0.5 ? copy.waveStatusLatency(activeTier.name, currentMs) : copy.waveStatusSync}
          </div>
        </div>
      </div>
    </AbsoluteFill>
  );
};
