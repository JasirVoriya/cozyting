import React from "react";
import { AbsoluteFill, useCurrentFrame, interpolate } from "remotion";
import { C, FONT_CN, FONT_EN, prog, easeInOut, sceneFade, easeOut, lerp } from "../theme";
import { Eyebrow, Reveal, StagePhone } from "../components";

const TIERS = [
  {
    name: "标准",
    ms: 800,
    note: "抗穿墙弱网抖动能力最强 · 多音箱长时间稳听",
    color: C.ink,
    accent: "rgba(76,141,255,0.8)",
    rect: [60, 445, 558, 150] as const,
  },
  {
    name: "均衡",
    ms: 400,
    note: "日常主力档 · 双音箱同步稳定，切歌响应快一倍",
    color: "#60A5FA",
    accent: "rgba(96,165,250,0.85)",
    rect: [60, 617, 558, 150] as const,
  },
  {
    name: "低延迟",
    ms: 250,
    note: "单音箱 5GHz 强信号主力档 · 切歌与拖进度条非常跟手",
    color: "#38BDF8",
    accent: "rgba(56,189,248,0.85)",
    rect: [60, 789, 558, 150] as const,
  },
  {
    name: "极速",
    ms: 150,
    note: "同房间 5GHz 实验性极低缓冲 · 极致瞬时响应",
    color: C.coral,
    accent: "rgba(255,92,108,0.9)",
    rect: [60, 962, 558, 150] as const,
  },
];
const AT = [0, 120, 240, 360];

export const Latency: React.FC<{ dur: number }> = ({ dur }) => {
  const f = useCurrentFrame();
  let idx = 0;
  AT.forEach((a, i) => (f >= a ? (idx = i) : null));
  const prev = idx === 0 ? 1000 : TIERS[idx - 1].ms;
  const p = prog(f, AT[idx], AT[idx] + 40, easeOut);
  const value = Math.round(lerp(prev, TIERS[idx].ms, p));

  const pillPos = interpolate(f, [0, 20, 120, 148, 240, 268, 360, 388], [0, 0, 0, 1, 1, 2, 2, 3], {
    extrapolateRight: "clamp",
    easing: easeInOut,
  });
  const PW = 185;
  const appear = prog(f, 0, 26);

  // Brief opening transition: 01-dark top-right tap -> 02-dark latency dialog
  const dialogIn = prog(f, 0, 22, easeOut);

  // Build the 4 real latency tier card slices from 02-dark.png; active tier pops out toward the right!
  const tierSlices = TIERS.map((t, i) => {
    const enter = prog(f, AT[i], AT[i] + 24, easeOut);
    const leave = i < 3 ? prog(f, AT[i + 1], AT[i + 1] + 22, easeInOut) : 0;
    const elevate = Math.max(0, enter - leave);
    return {
      id: `tier-${i}`,
      src: "ui/02-dark.png",
      rect: t.rect,
      radius: 22,
      elevate,
      dx: 48,
      dy: -4,
      popScale: 1.15,
      accentBorder: t.accent,
    };
  });

  // Y coordinate on 1080p stage corresponding to the currently popped tier card
  const activeCardY = 392 + idx * 108;

  return (
    <AbsoluteFill style={{ background: C.bg, opacity: sceneFade(f, dur, 8, 16) }}>
      <AbsoluteFill
        style={{
          background: `radial-gradient(circle at 66% 52%, rgba(255,92,108,${0.05 + 0.09 * (idx / 3)}) 0%, rgba(255,92,108,0) 44%), radial-gradient(circle at 26% 52%, rgba(37,99,235,0.16) 0%, rgba(37,99,235,0) 42%)`,
        }}
      />

      {/* Left: Real Phone displaying 02-dark.png (《延迟档位说明》弹窗) with 4 Real Tier Cards popping out one by one */}
      <div
        style={{
          position: "absolute",
          left: 155,
          top: 78,
          perspective: 1800,
          zIndex: 10,
        }}
      >
        <div
          style={{
            transform: "translateY(-35px) rotateY(11deg) rotateX(2deg)",
            transformStyle: "preserve-3d",
          }}
        >
          <StagePhone
            src="ui/01-dark.png"
            src2="ui/02-dark.png"
            fade2={dialogIn}
            w={420}
            dim={0.38}
            taps={TIERS.map((t, i) => ({
              x: 340,
              y: t.rect[1] + 75,
              start: AT[i] + 2,
              color: i === 3 ? C.coral : "#60A5FA",
            }))}
            slices={tierSlices}
          />
        </div>
      </div>

      {/* Dynamic connector line from the currently elevated real tier card to the giant ms readout */}
      <svg width="1920" height="1080" style={{ position: "absolute", inset: 0, pointerEvents: "none" }}>
        <path
          d={`M 635 ${activeCardY} C 745 ${activeCardY}, 770 495, 875 495`}
          fill="none"
          stroke={idx === 3 ? C.coral : "#60A5FA"}
          strokeWidth={2.4}
          strokeDasharray="6 8"
          strokeDashoffset={-f * 1.5}
          opacity={0.75}
        />
        <circle cx={635} cy={activeCardY} r={6} fill={idx === 3 ? C.coral : "#60A5FA"} />
        <circle cx={875} cy={495} r={6} fill={idx === 3 ? C.coral : "#60A5FA"} />
      </svg>

      {/* Right Column: Segmented Tier Bar, Giant Tabular Millisecond Counter, and Live Gauge */}
      <div style={{ position: "absolute", left: 880, top: 105, width: 920 }}>
        <Eyebrow text="Hot-Switch Latency · Real-Time TIME_OFFSET" start={0} />
        <div style={{ marginTop: 16 }}>
          <Reveal text="四档播放延迟，投送中免重连热切。" start={6} size={54} weight={650} stagger={1.8} />
        </div>

        {/* segmented control */}
        <div
          style={{
            marginTop: 34,
            width: PW * 4 + 16,
            height: 68,
            borderRadius: 34,
            background: "rgba(255,255,255,0.05)",
            border: `1px solid ${C.line}`,
            position: "relative",
            opacity: appear,
          }}
        >
          <div
            style={{
              position: "absolute",
              top: 6,
              left: 8 + pillPos * PW,
              width: PW,
              height: 54,
              borderRadius: 27,
              background: idx === 3 ? C.coral : C.ink,
            }}
          />
          {TIERS.map((t, i) => (
            <div
              key={t.name}
              style={{
                position: "absolute",
                top: 6,
                left: 8 + i * PW,
                width: PW,
                height: 54,
                display: "flex",
                alignItems: "center",
                justifyContent: "center",
                fontFamily: FONT_CN,
                fontSize: 24,
                fontWeight: 600,
                color: Math.abs(pillPos - i) < 0.5 ? (idx === 3 ? "#FFFFFF" : C.bg) : C.ink2,
              }}
            >
              {t.name}
            </div>
          ))}
        </div>

        {/* giant number */}
        <div style={{ marginTop: 22, display: "flex", alignItems: "baseline", gap: 18 }}>
          <div
            style={{
              fontFamily: FONT_EN,
              fontSize: 255,
              fontWeight: 700,
              letterSpacing: -8,
              color: TIERS[idx].color,
              fontVariantNumeric: "tabular-nums",
              lineHeight: 1,
              opacity: appear,
            }}
          >
            {value}
          </div>
          <div style={{ fontFamily: FONT_EN, fontSize: 76, fontWeight: 500, color: C.ink2, opacity: appear }}>
            ms
          </div>
        </div>

        {/* tier description matching the real 02-dark card text */}
        <div style={{ marginTop: 12, height: 48, position: "relative" }}>
          {TIERS.map((t, i) => {
            const o = i === idx ? prog(f, AT[i] + 4, AT[i] + 26) : 0;
            return (
              <div
                key={i}
                style={{
                  position: "absolute",
                  fontFamily: FONT_CN,
                  fontSize: 28,
                  color: C.ink,
                  letterSpacing: 1.5,
                  opacity: o,
                  transform: `translateY(${(1 - o) * 12}px)`,
                }}
              >
                {t.note}
              </div>
            );
          })}
        </div>

        {/* latency bar */}
        <div style={{ marginTop: 34, width: 840, opacity: appear }}>
          <div style={{ height: 8, borderRadius: 4, background: "rgba(255,255,255,0.09)", position: "relative" }}>
            <div
              style={{
                position: "absolute",
                left: 0,
                top: 0,
                height: 8,
                width: `${(value / 1000) * 100}%`,
                borderRadius: 4,
                background: idx === 3 ? C.coral : TIERS[idx].color,
              }}
            />
            <div
              style={{
                position: "absolute",
                left: `calc(${(value / 1000) * 100}% - 10px)`,
                top: -6,
                width: 20,
                height: 20,
                borderRadius: 10,
                background: idx === 3 ? C.coral : TIERS[idx].color,
                boxShadow: `0 0 18px ${idx === 3 ? C.coral : TIERS[idx].color}`,
              }}
            />
          </div>
          <div
            style={{
              display: "flex",
              justifyContent: "space-between",
              marginTop: 14,
              fontFamily: FONT_EN,
              fontSize: 17,
              color: C.ink3,
            }}
          >
            {[0, 150, 250, 400, 800, 1000].map((v) => (
              <span key={v}>{v} ms</span>
            ))}
          </div>
        </div>
      </div>
    </AbsoluteFill>
  );
};
