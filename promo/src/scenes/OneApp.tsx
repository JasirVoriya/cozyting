import React from "react";
import { AbsoluteFill, useCurrentFrame } from "remotion";
import { C, FONT_CN, FONT_EN, prog, easeInOut, easeOut, sceneFade, lerp } from "../theme";
import { Eyebrow, Reveal, Ripple, Speaker, StagePhone } from "../components";

const LANE_X0 = 675;
const GATE_X = 1230;
const SPK_X = 1665;
const ROWS = [
  { y: 430, label: "云音乐 · 自动识别", color: C.coral },
  { y: 585, label: "微信语音通话", color: "#6B7686" },
  { y: 710, label: "短视频外放", color: "#6B7686" },
  { y: 835, label: "游戏音效", color: "#6B7686" },
] as const;

const wave = (
  x0: number,
  x1: number,
  y: number,
  f: number,
  amp: number,
  noisy: boolean,
  winA: number,
  winB: number
) => {
  if (x1 - x0 < 2) return "";
  const pts: string[] = [];
  for (let x = x0; x <= x1; x += 4) {
    const w = Math.sin(Math.PI * Math.min(1, Math.max(0, (x - winA) / (winB - winA))));
    const ph = x * 0.055 - f * 0.32;
    const v = noisy
      ? Math.sin(ph * 1.7) * 0.6 + Math.sin(ph * 4.3 + 1) * 0.4
      : Math.sin(ph) * (0.75 + 0.25 * Math.sin(x * 0.011 - f * 0.05));
    pts.push(`${pts.length ? "L" : "M"}${x.toFixed(1)} ${(y + v * amp * w).toFixed(1)}`);
  }
  return pts.join(" ");
};

export const OneApp: React.FC<{ dur: number }> = ({ dur }) => {
  const f = useCurrentFrame();

  // Starts on 01-dark.png elevating the "音源 · 云音乐 [自动识别]" card, taps "管理" at f=52 -> switches to 03-dark.png (常用音源与选择)
  const popSourceEntry = prog(f, 12, 45, easeOut) * (1 - prog(f, 62, 85, easeInOut));
  const pageMorph = prog(f, 62, 98, easeInOut);
  const popCloudMusic = prog(f, 102, 145, easeOut);
  const popHifi = prog(f, 128, 170, easeOut);

  const head = LANE_X0 + (SPK_X - 88 - LANE_X0) * prog(f, 110, 215, easeInOut);
  const spkOn = prog(f, 200, 225);

  const blocked: [number, number, string][] = [
    [1, 225, "语音已隔离"],
    [2, 285, "短视频已隔离"],
    [3, 345, "游戏音已隔离"],
    [1, 420, "语音已隔离"],
    [2, 455, "短视频已隔离"],
    [3, 490, "游戏音已隔离"],
  ];
  const SPEED = 5.0;
  const LEN = 330;

  return (
    <AbsoluteFill style={{ background: C.bg, opacity: sceneFade(f, dur, 12, 18) }}>
      <AbsoluteFill
        style={{
          background: `radial-gradient(circle at 26% 56%, rgba(37,99,235,${0.18 * pageMorph}) 0%, rgba(37,99,235,0) 40%), radial-gradient(circle at 86% 42%, rgba(255,92,108,${0.15 * spkOn}) 0%, rgba(255,92,108,0) 35%)`,
        }}
      />

      {/* Header */}
      <div style={{ position: "absolute", left: 120, top: 80, zIndex: 20 }}>
        <Eyebrow text="Single-App Capture · Auto Follow" start={6} />
        <div style={{ marginTop: 16 }}>
          <Reveal text="只投你选的音乐 App，常用音源自动跟随。" start={14} size={58} weight={650} stagger={1.8} />
        </div>
        <div style={{ marginTop: 12 }}>
          <Reveal
            text="实机「常用音源与选择」：独立开启自动识别，微信语音、短视频与游戏音效全部留在手机本地。"
            start={120}
            size={26}
            weight={400}
            color={C.ink2}
            stagger={0.9}
          />
        </div>
      </div>

      {/* Left: Real Phone transitioning from 01-dark (Main Source Card) -> 03-dark (App Picker Page) */}
      <div
        style={{
          position: "absolute",
          left: 145,
          top: 215,
          perspective: 1800,
          zIndex: 10,
        }}
      >
        <div
          style={{
            transform: `translateY(${lerp(-35, 10, pageMorph)}px) rotateY(9deg) rotateX(2deg)`,
            transformStyle: "preserve-3d",
          }}
        >
          <StagePhone
            src="ui/01-dark.png"
            src2="ui/03-dark.png"
            fade2={pageMorph}
            w={415}
            dim={Math.max(popSourceEntry, popCloudMusic) * 0.45}
            taps={[{ x: 576, y: 500, start: 48, color: "#4C8DFF" }]}
            slices={[
              {
                id: "main-source-card",
                src: "ui/01-dark.png",
                rect: [48, 458, 582, 148],
                radius: 24,
                elevate: popSourceEntry,
                dx: 24,
                popScale: 1.1,
                accentBorder: "rgba(76,141,255,0.65)",
              },
              {
                id: "picker-cloud-music",
                src: "ui/03-dark.png",
                rect: [22, 276, 634, 168],
                radius: 26,
                elevate: popCloudMusic,
                dx: 42,
                dy: -6,
                popScale: 1.13,
                accentBorder: "rgba(255,92,108,0.75)",
              },
              {
                id: "picker-hifi",
                src: "ui/03-dark.png",
                rect: [22, 631, 634, 165],
                radius: 26,
                elevate: popHifi,
                dx: 24,
                dy: 4,
                popScale: 1.07,
                accentBorder: "rgba(76,141,255,0.6)",
              },
            ]}
          />
        </div>
      </div>

      {/* Right: Linked Audio Signal Filter Gate connected directly to the elevated real UI cards */}
      <svg width="1920" height="1080" style={{ position: "absolute", inset: 0, pointerEvents: "none" }}>
        {/* UID filter gate */}
        <g opacity={prog(f, 70, 115)}>
          <line
            x1={GATE_X}
            y1={350}
            x2={GATE_X}
            y2={900}
            stroke="rgba(255,92,108,0.45)"
            strokeWidth={2.5}
            strokeDasharray="4 10"
            strokeLinecap="round"
          />
          <circle cx={GATE_X} cy={ROWS[0].y} r={10} fill={C.coral} opacity={prog(f, 120, 150)} />
          <rect
            x={GATE_X - 115}
            y={292}
            width={230}
            height={40}
            rx={20}
            fill="rgba(255,92,108,0.14)"
            stroke="rgba(255,92,108,0.55)"
          />
          <text
            x={GATE_X}
            y={318}
            textAnchor="middle"
            fontFamily={FONT_EN}
            fontSize={16}
            fontWeight={600}
            letterSpacing={2}
            fill={C.coral}
          >
            SINGLE-UID GATE
          </text>
        </g>

        {/* Lane labels on the left of the waveforms */}
        {ROWS.map((r, idx) => {
          const op = prog(f, 100 + idx * 14, 135 + idx * 14);
          return (
            <g key={idx} opacity={op}>
              <circle cx={LANE_X0 - 12} cy={r.y} r={5} fill={idx === 0 ? C.coral : "#6B7686"} />
              <text
                x={LANE_X0 + 8}
                y={r.y - 26}
                fontFamily={FONT_CN}
                fontSize={18}
                fontWeight={500}
                fill={idx === 0 ? C.coral : C.ink3}
              >
                {r.label}
              </text>
            </g>
          );
        })}

        {/* Music lane flowing out from the elevated real "云音乐 (自动识别: 允许)" card */}
        <path
          d={wave(LANE_X0, head, ROWS[0].y, f, 22, false, LANE_X0 - 40, head + 40)}
          fill="none"
          stroke={C.coral}
          strokeWidth={3.4}
          strokeLinecap="round"
        />
        <path
          d={wave(LANE_X0, head, ROWS[0].y, f + 6, 14, false, LANE_X0 - 40, head + 40)}
          fill="none"
          stroke={C.coral}
          strokeWidth={1.6}
          opacity={0.45}
        />

        {/* Blocked non-music lanes */}
        {blocked.map(([lane, tb, tag], k) => {
          const y = ROWS[lane].y;
          const h = GATE_X + SPEED * (f - tb);
          const x0 = Math.max(LANE_X0, h - LEN);
          const x1 = Math.min(h, GATE_X);
          const burstT = (f - tb) / 52;
          return (
            <g key={k}>
              {x1 > x0 && (
                <path
                  d={wave(x0, x1, y, f + k * 13, 24, true, h - LEN, h)}
                  fill="none"
                  stroke="#8892A4"
                  strokeWidth={2.6}
                  strokeLinecap="round"
                />
              )}
              <Ripple cx={GATE_X} cy={y} t={burstT} maxR={64} color={C.coral} width={2.2} />
              {burstT > 0 && burstT < 1.9 && (
                <g
                  transform={`translate(${GATE_X + 74} ${y})`}
                  opacity={Math.min(1, burstT * 5) * (1 - Math.max(0, burstT - 1.3) / 0.6)}
                >
                  <rect
                    x={-64}
                    y={-19}
                    width={128}
                    height={38}
                    rx={19}
                    fill="rgba(21,26,35,0.9)"
                    stroke="rgba(255,92,108,0.4)"
                  />
                  <text x={0} y={6} textAnchor="middle" fontFamily={FONT_CN} fontSize={17} fontWeight={500} fill={C.ink}>
                    × {tag}
                  </text>
                </g>
              )}
            </g>
          );
        })}

        <g opacity={prog(f, 80, 120)}>
          <Speaker x={SPK_X} y={ROWS[0].y} s={2.05} active={spkOn} label="纯净音乐输出" />
        </g>
      </svg>
    </AbsoluteFill>
  );
};
