import React from "react";
import { AbsoluteFill, useCurrentFrame, interpolate } from "remotion";
import { C, FONT_CN, FONT_EN, FONT_MONO, prog, easeInOut, sceneFade, easeOut, lerp } from "../theme";
import { Eyebrow, Reveal, Speaker, StagePhone } from "../components";

const ROOMS = [
  { x: 0, y: 0, w: 300, h: 195, name: "卧室", spkX: 150, spkY: 112, t: 85 },
  { x: 300, y: 0, w: 300, h: 195, name: "书房", spkX: 450, spkY: 112, t: 115 },
  { x: 0, y: 195, w: 340, h: 215, name: "客厅", spkX: 170, spkY: 315, t: 55 },
  { x: 340, y: 195, w: 260, h: 215, name: "餐厨", spkX: 470, spkY: 315, t: 145 },
];

const TIERS = [
  {
    name: "标准",
    ms: 800,
    tag: "穿墙稳听 · 多房不卡顿",
    color: "#E2E8F0",
    accent: "rgba(96,165,250,0.9)",
    pulseSpeed: 1.0,
    rect: [60, 445, 558, 150] as const,
  },
  {
    name: "均衡",
    ms: 400,
    tag: "日常主力 · 切歌响应快一倍",
    color: "#60A5FA",
    accent: "rgba(96,165,250,0.95)",
    pulseSpeed: 1.6,
    rect: [60, 617, 558, 150] as const,
  },
  {
    name: "低延迟",
    ms: 250,
    tag: "5GHz 强网 · 拖进度条秒跟手",
    color: "#38BDF8",
    accent: "rgba(56,189,248,0.95)",
    pulseSpeed: 2.3,
    rect: [60, 789, 558, 150] as const,
  },
  {
    name: "极速",
    ms: 150,
    tag: "同房极速 · 几乎零等待",
    color: C.coral,
    accent: "rgba(255,92,108,0.95)",
    pulseSpeed: 3.2,
    rect: [60, 962, 558, 150] as const,
  },
];

// Phase 1 (0..260f): Multi-room sync light-up & 63-speaker counter
// Phase 2 (260..840f): Phone switches to 02-dark Latency Sheet while right multi-room floorplan stays LIVE and responds to each tier!
const TIER_AT = [295, 430, 565, 700];

export const MultiRoomLatency: React.FC<{ dur: number }> = ({ dur }) => {
  const f = useCurrentFrame();

  const popSpk1 = prog(f, 16, 62, easeOut) * (1 - prog(f, 245, 275, easeInOut));
  const popSpk2 = prog(f, 34, 80, easeOut) * (1 - prog(f, 245, 275, easeInOut));
  const latencyMode = prog(f, 255, 295, easeInOut);
  const walls = prog(f, 10, 80, easeInOut);

  const vol1 = Math.round(lerp(62, 78, Math.sin(prog(f, 90, 240, easeInOut) * Math.PI)));
  const vol2 = Math.round(lerp(48, 60, Math.sin(prog(f, 120, 250, easeInOut) * Math.PI)));

  const count = Math.round(
    interpolate(f, [150, 245], [2, 63], { extrapolateLeft: "clamp", extrapolateRight: "clamp", easing: easeOut })
  );

  // Determine current latency tier in Phase 2
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
      src: "ui/02-dark.png",
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
      src: "ui/01-dark.png",
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
      src: "ui/01-dark.png",
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
        <Eyebrow text="Multi-Room Sync × 4-Tier Hot-Switch Latency" start={4} />
        <div style={{ marginTop: 12, display: "flex", alignItems: "baseline", gap: 18 }}>
          <Reveal text="全屋多房同频合唱，" start={10} size={56} weight={700} stagger={1.6} />
          <Reveal text="播着歌就能热切 4 档延迟。" start={30} size={56} weight={700} stagger={1.4} color={C.coral} />
        </div>
      </div>

      {/* LEFT: Real Phone transitioning seamlessly from 01-dark (Selected Speakers) -> 02-dark (4-Tier Latency Dialog) */}
      <div style={{ position: "absolute", left: 105, top: 90, perspective: 1800, zIndex: 15 }}>
        <div style={{ transform: "rotateY(9deg) rotateX(2deg)", transformStyle: "preserve-3d" }}>
          <StagePhone
            src="ui/01-dark.png"
            src2="ui/02-dark.png"
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

      {/* RIGHT STAGE — Top Row: Dynamic Mode Panel (Multi-Room 63-Speaker Banner -> Live 4-Tier Latency Control & Buffer Bar) */}
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
            }}
          >
            <div>
              <div style={{ fontFamily: FONT_CN, fontSize: 32, fontWeight: 700, color: "#FFFFFF" }}>
                多房间同步播放 · 每台独立调音量
              </div>
              <div style={{ marginTop: 14, fontFamily: FONT_CN, fontSize: 25, color: "#CBD5E1" }}>
                客厅、卧室、书房、餐厨一拍齐响，实时显示每台音箱延迟。
              </div>
            </div>
            <div style={{ display: "flex", alignItems: "baseline", gap: 14, flexShrink: 0 }}>
              <span style={{ fontFamily: FONT_CN, fontSize: 28, color: "#CBD5E1" }}>最多</span>
              <span
                style={{
                  fontFamily: FONT_EN,
                  fontSize: 132,
                  fontWeight: 800,
                  color: C.coral,
                  lineHeight: 1,
                  fontVariantNumeric: "tabular-nums",
                }}
              >
                {count}
              </span>
              <span style={{ fontFamily: FONT_CN, fontSize: 28, color: "#CBD5E1" }}>台同频</span>
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
            {/* Top row: 4-tier pill selector + giant ms readout */}
            <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
              {/* 4-tier segmented bar */}
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
                      fontFamily: FONT_CN,
                      fontSize: 25,
                      fontWeight: 700,
                      color: Math.abs(pillPos - i) < 0.5 ? (tierIdx === 3 ? "#FFFFFF" : C.bg) : "#CBD5E1",
                    }}
                  >
                    {t.name}
                  </div>
                ))}
              </div>

              {/* Giant live ms readout */}
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

            {/* Middle: Clear user scenario description */}
            <div style={{ fontFamily: FONT_CN, fontSize: 32, fontWeight: 700, color: "#FFFFFF" }}>
              当前效果：<span style={{ color: activeTier.color }}>{activeTier.tag}</span>
            </div>

            {/* Bottom: Visual shrinking buffer bar showing latency difference */}
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
                  fontFamily: FONT_EN,
                  fontSize: 18,
                  fontWeight: 600,
                  color: "#94A3B8",
                }}
              >
                <span>150 ms 极速</span>
                <span>250 ms 低延迟</span>
                <span>400 ms 均衡</span>
                <span>800 ms 标准</span>
              </div>
            </div>
          </div>
        )}
      </div>

      {/* RIGHT STAGE — Bottom Row: LIVE Multi-Room Floorplan + Real-Time Sync Response Card (Stays alive across BOTH phases!) */}
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
        {/* Multi-Room Floorplan SVG (600x410) */}
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
                  fontFamily={FONT_CN}
                  fontSize={25}
                  fontWeight={700}
                  fill={active > 0 ? "#FFFFFF" : C.ink3}
                >
                  {r.name}
                </text>
                {/* Live ms pill in EVERY room updating with latency tier! */}
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

          {/* Animated speakers inside each room */}
          {ROOMS.map((r, i) => {
            const active = prog(f, r.t, r.t + 14);
            return (
              <g key={`spk-${i}`} opacity={prog(f, 25 + i * 6, 55 + i * 6)}>
                <Speaker x={r.spkX} y={r.spkY} s={1.08} active={active} />
              </g>
            );
          })}
        </svg>

        {/* Right Live Sync Waveform Monitor showing speed & phase lock across all rooms */}
        <div style={{ flex: 1, display: "flex", flexDirection: "column", justifyContent: "space-between", height: "100%", padding: "12px 0" }}>
          <div>
            <div style={{ fontFamily: FONT_CN, fontSize: 28, fontWeight: 700, color: "#FFFFFF" }}>
              全屋音箱实时同频波形
            </div>
            <div style={{ marginTop: 8, fontFamily: FONT_CN, fontSize: 21, color: "#94A3B8" }}>
              {latencyMode > 0.5 ? "切换档位不断连，全屋音箱同步生效" : "客厅 / 卧室 / 书房 / 餐厨精准对齐"}
            </div>
          </div>

          {/* 3 phase-locked room rows whose wave velocity accelerates with lower latency! */}
          <div style={{ position: "relative", display: "flex", flexDirection: "column", gap: 18 }}>
            {["客厅音箱", "卧室音箱", "书房音箱"].map((roomName, i) => {
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
                  <div style={{ width: 102, fontFamily: FONT_CN, fontSize: 23, fontWeight: 600, color: "#E2E8F0" }}>
                    {roomName}
                  </div>
                  <svg width={290} height={44}>
                    <path d={pts.join(" ")} fill="none" stroke={activeAccentColor} strokeWidth={3} strokeLinecap="round" />
                  </svg>
                </div>
              );
            })}
            {/* Vertical phase-lock hairline */}
            <div
              style={{
                position: "absolute",
                left: 116 + 145,
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
              padding: "12px 20px",
              borderRadius: 16,
              background: "rgba(255,255,255,0.05)",
              border: "1px solid rgba(255,255,255,0.12)",
              fontFamily: FONT_CN,
              fontSize: 22,
              fontWeight: 600,
              color: "#E2E8F0",
              textAlign: "center",
            }}
          >
            {latencyMode > 0.5
              ? `⚡ 热切至 ${activeTier.name} (${currentMs}ms) · 全屋不断音`
              : "● 全屋 4 房间已精准锁相"}
          </div>
        </div>
      </div>
    </AbsoluteFill>
  );
};
