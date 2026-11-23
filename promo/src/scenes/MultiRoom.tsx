import React from "react";
import { AbsoluteFill, useCurrentFrame, interpolate } from "remotion";
import { C, FONT_CN, FONT_EN, FONT_MONO, prog, easeInOut, sceneFade, easeOut, lerp } from "../theme";
import { Eyebrow, Reveal, Speaker, StagePhone } from "../components";

const ROOMS = [
  { x: 730, y: 220, w: 280, h: 255, name: "卧室", spkIdx: 2 },
  { x: 1010, y: 220, w: 260, h: 255, name: "书房", spkIdx: 1 },
  { x: 730, y: 475, w: 340, h: 350, name: "客厅", spkIdx: 0 },
  { x: 1070, y: 475, w: 200, h: 350, name: "餐厨", spkIdx: 3 },
];
// speaker positions + light-up frame (one per beat from bar 13)
const SPK = [
  { x: 895, y: 650, t: 60 },
  { x: 1140, y: 355, t: 90 },
  { x: 870, y: 355, t: 120 },
  { x: 1170, y: 650, t: 150 },
];

export const MultiRoom: React.FC<{ dur: number }> = ({ dur }) => {
  const f = useCurrentFrame();

  const popSpk1 = prog(f, 18, 65, easeOut);
  const popSpk2 = prog(f, 36, 85, easeOut);
  const walls = prog(f, 10, 95, easeInOut);

  // Live animated volume slider values on the two elevated real speaker cards
  const vol1 = Math.round(lerp(62, 76, Math.sin(prog(f, 110, 290, easeInOut) * Math.PI)));
  const vol2 = Math.round(lerp(48, 58, Math.sin(prog(f, 160, 340, easeInOut) * Math.PI)));

  const count = Math.round(
    interpolate(f, [260, 380], [2, 63], { extrapolateLeft: "clamp", extrapolateRight: "clamp", easing: easeOut })
  );

  const card1Anchor = [625, 555];
  const card2Anchor = [625, 685];

  return (
    <AbsoluteFill style={{ background: C.bg, opacity: sceneFade(f, dur, 8, 16) }}>
      <AbsoluteFill
        style={{
          background:
            "radial-gradient(circle at 24% 55%, rgba(46,108,246,0.18) 0%, rgba(46,108,246,0) 42%), radial-gradient(circle at 55% 52%, rgba(255,92,108,0.10) 0%, rgba(255,92,108,0) 40%)",
        }}
      />

      {/* Left: Real Phone (01-dark.png) with the Two Real White Selected Speaker Cards Elevated */}
      <div
        style={{
          position: "absolute",
          left: 115,
          top: 95,
          perspective: 1800,
          zIndex: 10,
        }}
      >
        <div
          style={{
            transform: "translateY(-35px) scale(1.03) rotateY(10deg) rotateX(2deg)",
            transformOrigin: "center 70%",
            transformStyle: "preserve-3d",
          }}
        >
          <StagePhone
            src="ui/01-dark.png"
            w={405}
            dim={popSpk1 * 0.48}
            slices={[
              {
                id: "sound-pro-card",
                rect: [48, 792, 582, 150],
                radius: 26,
                elevate: popSpk1,
                dx: 48,
                dy: -14,
                popScale: 1.15,
                accentBorder: "rgba(37,99,235,0.85)",
                overlay: (
                  <div
                    style={{
                      position: "absolute",
                      right: 14,
                      bottom: 12,
                      padding: "2px 9px",
                      borderRadius: 8,
                      background: "#2563EB",
                      color: "#FFFFFF",
                      fontFamily: FONT_MONO,
                      fontSize: 13,
                      fontWeight: 700,
                    }}
                  >
                    {vol1}%
                  </div>
                ),
              },
              {
                id: "xiaoai-pro-card",
                rect: [48, 958, 582, 150],
                radius: 26,
                elevate: popSpk2,
                dx: 48,
                dy: 8,
                popScale: 1.15,
                accentBorder: "rgba(37,99,235,0.85)",
                overlay: (
                  <div
                    style={{
                      position: "absolute",
                      right: 14,
                      bottom: 12,
                      padding: "2px 9px",
                      borderRadius: 8,
                      background: "#2563EB",
                      color: "#FFFFFF",
                      fontFamily: FONT_MONO,
                      fontSize: 13,
                      fontWeight: 700,
                    }}
                  >
                    {vol2}%
                  </div>
                ),
              },
            ]}
          />
        </div>
      </div>

      {/* Center: Architectural Multi-Room Floorplan directly tethered to the two elevated real speaker cards */}
      <svg width="1920" height="1080" style={{ position: "absolute", inset: 0, pointerEvents: "none" }}>
        {ROOMS.map((r, i) => {
          const active = prog(f, SPK[r.spkIdx].t, SPK[r.spkIdx].t + 16);
          return (
            <g key={i}>
              <rect
                x={r.x}
                y={r.y}
                width={r.w}
                height={r.h}
                fill={`rgba(255,92,108,${0.015 + 0.035 * active})`}
                stroke={active > 0 ? "rgba(255,92,108,0.36)" : C.line2}
                strokeWidth={2}
                pathLength={1}
                strokeDasharray="1 1"
                strokeDashoffset={1 - walls}
              />
              <text
                x={r.x + 20}
                y={r.y + 36}
                fontFamily={FONT_CN}
                fontSize={20}
                fontWeight={500}
                letterSpacing={3}
                fill={active > 0 ? C.ink : C.ink3}
                opacity={prog(f, 30 + i * 6, 70 + i * 6)}
              >
                {r.name}
              </text>
            </g>
          );
        })}
        {/* Audio sync lines from the elevated real speaker cards into each room's speaker */}
        {SPK.map((s, i) => {
          const origin = i % 2 === 0 ? card1Anchor : card2Anchor;
          const a = prog(f, s.t - 16, s.t + 4);
          return (
            <line
              key={i}
              x1={origin[0]}
              y1={origin[1]}
              x2={origin[0] + (s.x - origin[0]) * a}
              y2={origin[1] + (s.y - origin[1]) * a}
              stroke={C.coral}
              strokeOpacity={0.65}
              strokeWidth={2}
              strokeDasharray="5 9"
              strokeDashoffset={-f * 1.4}
            />
          );
        })}
        {SPK.map((s, i) => (
          <g key={i} opacity={prog(f, 40 + i * 6, 80 + i * 6)}>
            <Speaker x={s.x} y={s.y} s={1.08} active={prog(f, s.t, s.t + 10)} />
          </g>
        ))}
      </svg>

      {/* Right Column: Headline, Phase-Locked Waveforms (551 ms), and 63-Speaker Counter */}
      <div style={{ position: "absolute", left: 1335, top: 155, width: 510 }}>
        <Eyebrow text="Multi-Room Sync" start={8} />
        <div style={{ marginTop: 20, display: "flex", flexDirection: "column" }}>
          <Reveal text="全屋音箱，" start={16} size={72} weight={700} stagger={3} />
          <Reveal text="毫秒同频。" start={30} size={72} weight={700} stagger={3} color={C.coral} />
        </div>
        <div style={{ marginTop: 14 }}>
          <Reveal
            text="每台音箱独立音量滑块 · 实时 ms 延迟可见"
            start={60}
            size={24}
            weight={400}
            color={C.ink2}
            stagger={1}
          />
        </div>

        {/* Phase-aligned waveform rows matching the real "551 ms" badge on 01-dark.png */}
        <div style={{ marginTop: 42, position: "relative" }}>
          {[
            { name: "Sound Pro", vol: `${vol1}%` },
            { name: "客厅 Pro", vol: `${vol2}%` },
            { name: "书房音箱", vol: "54%" },
          ].map((row, i) => {
            const a = prog(f, 150 + i * 10, 190 + i * 10);
            const pts: string[] = [];
            for (let x = 0; x <= 260; x += 3) {
              const v = Math.sin(x * 0.09 - f * 0.25) * 0.6 + Math.sin(x * 0.23 - f * 0.6) * 0.4;
              const env = 0.5 + 0.5 * Math.sin(x * 0.02 - f * 0.08);
              pts.push(`${x ? "L" : "M"}${x} ${(20 + v * 15 * env).toFixed(1)}`);
            }
            return (
              <div
                key={row.name}
                style={{
                  display: "flex",
                  alignItems: "center",
                  gap: 14,
                  height: 54,
                  opacity: a,
                  transform: `translateX(${(1 - a) * 28}px)`,
                }}
              >
                <div style={{ width: 96, fontFamily: FONT_CN, fontSize: 19, color: C.ink2 }}>{row.name}</div>
                <svg width={260} height={40}>
                  <path d={pts.join(" ")} fill="none" stroke={C.ink} strokeWidth={2} opacity={0.88} />
                </svg>
                <div
                  style={{
                    padding: "3px 10px",
                    borderRadius: 99,
                    background: "rgba(37,99,235,0.22)",
                    fontFamily: FONT_MONO,
                    fontSize: 17,
                    fontWeight: 600,
                    color: "#60A5FA",
                  }}
                >
                  551 ms
                </div>
              </div>
            );
          })}
          <div
            style={{
              position: "absolute",
              left: 110 + 130,
              top: -6,
              width: 2,
              height: 174,
              background: C.coral,
              opacity: prog(f, 185, 210) * 0.85,
              borderRadius: 2,
            }}
          />
        </div>

        <div
          style={{
            marginTop: 42,
            display: "flex",
            alignItems: "baseline",
            gap: 16,
            opacity: prog(f, 240, 275),
          }}
        >
          <div style={{ fontFamily: FONT_CN, fontSize: 26, color: C.ink2 }}>最多</div>
          <div
            style={{
              fontFamily: FONT_EN,
              fontSize: 124,
              fontWeight: 700,
              color: C.ink,
              fontVariantNumeric: "tabular-nums",
              lineHeight: 1,
              width: 160,
            }}
          >
            {count}
          </div>
          <div style={{ fontFamily: FONT_CN, fontSize: 26, color: C.ink2 }}>台音箱同频合唱</div>
        </div>
      </div>
    </AbsoluteFill>
  );
};
