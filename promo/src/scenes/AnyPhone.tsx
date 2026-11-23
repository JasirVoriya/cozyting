import React from "react";
import { AbsoluteFill, useCurrentFrame } from "remotion";
import { C, FONT_CN, FONT_EN, prog, easeInOut, easeOut, sceneFade, lerp } from "../theme";
import { Eyebrow, Reveal, Speaker, StagePhone } from "../components";

const bez = (p0: number[], p1: number[], p2: number[], p3: number[], t: number) => {
  const u = 1 - t;
  return [0, 1].map(
    (i) => u * u * u * p0[i] + 3 * u * u * t * p1[i] + 3 * u * t * t * p2[i] + t * t * t * p3[i]
  );
};

const BRANDS = [
  { name: "HUAWEI", sub: "HarmonyOS / Android" },
  { name: "SAMSUNG", sub: "Galaxy · One UI" },
  { name: "OPPO", sub: "Find · ColorOS" },
  { name: "vivo", sub: "X Series · OriginOS" },
  { name: "OnePlus", sub: "ColorOS / OxygenOS" },
  { name: "Pixel", sub: "Android 10 – 16+" },
];

export const AnyPhone: React.FC<{ dur: number }> = ({ dur }) => {
  const f = useCurrentFrame();

  // At f=88, user taps the speaker checkbox on 07-dark.png (standby) -> transitions to 01-dark.png (casting 2 speakers)
  const tapFrame = 78;
  const castMorph = prog(f, 92, 130, easeInOut);
  const popCards = prog(f, 132, 178, easeOut);
  const camZoom = lerp(1.0, 1.04, prog(f, 40, 150, easeInOut));
  const camY = lerp(18, 0, prog(f, 50, 165, easeInOut));

  const phoneTarget = [940, 590];
  const spkPos = [1675, 585];

  return (
    <AbsoluteFill style={{ background: C.bg, opacity: sceneFade(f, dur, 10, 16) }}>
      <AbsoluteFill
        style={{
          background: `radial-gradient(circle at 54% 56%, rgba(46,108,246,${0.18 * castMorph}) 0%, rgba(46,108,246,0) 40%), radial-gradient(circle at 85% 55%, rgba(255,92,108,${0.14 * castMorph}) 0%, rgba(255,92,108,0) 35%)`,
        }}
      />

      {/* Top Header */}
      <div style={{ position: "absolute", left: 120, top: 82, zIndex: 20 }}>
        <Eyebrow text="Any Android · Brand Free" start={4} />
        <div style={{ marginTop: 16 }}>
          <Reveal text="不是小米手机，也能一键妙播。" start={10} size={60} weight={650} stagger={2} />
        </div>
      </div>

      {/* Left: 6 Brand Badges streaming into the Real Phone, then out from the Real Speaker Cards to the Speaker */}
      <svg width="1920" height="1080" style={{ position: "absolute", inset: 0 }}>
        {BRANDS.map((b, i) => {
          const bx = 130 + (i % 2) * 230;
          const by = 330 + Math.floor(i / 2) * 175;
          const a = prog(f, 14 + i * 6, 52 + i * 6);
          const p0 = [bx + 195, by + 38];
          const p3 = [phoneTarget[0] - 180, phoneTarget[1] - 60 + (i - 2.5) * 36];
          const p1 = [p0[0] + 130, p0[1]];
          const p2 = [p3[0] - 130, p3[1]];
          const draw = prog(f, 26 + i * 6, 82 + i * 6, easeInOut);
          const pts = Array.from({ length: 33 }, (_, k) => bez(p0, p1, p2, p3, (k / 32) * draw));
          const d = pts.map((q, k) => `${k ? "L" : "M"}${q[0].toFixed(1)} ${q[1].toFixed(1)}`).join(" ");
          const dotT = ((f - 36 - i * 11) / 56) % 1;
          const dotPos = f > 36 + i * 11 ? bez(p0, p1, p2, p3, dotT) : null;

          return (
            <g key={b.name} opacity={a} transform={`translate(${(1 - a) * -28} 0)`}>
              <path d={d} fill="none" stroke="rgba(255,92,108,0.26)" strokeWidth={1.6} />
              {dotPos && (
                <circle
                  cx={dotPos[0]}
                  cy={dotPos[1]}
                  r={4.2}
                  fill={C.coral}
                  opacity={Math.sin(dotT * Math.PI) * draw}
                />
              )}
              <rect
                x={bx}
                y={by}
                width={195}
                height={78}
                rx={18}
                fill="rgba(21,26,35,0.9)"
                stroke={castMorph > 0.3 ? "rgba(255,92,108,0.45)" : C.line2}
                strokeWidth={1.6}
              />
              <circle cx={bx + 26} cy={by + 39} r={7} fill={C.coral} />
              <text x={bx + 46} y={by + 35} fontFamily={FONT_EN} fontSize={20} fontWeight={700} fill={C.ink}>
                {b.name}
              </text>
              <text x={bx + 46} y={by + 58} fontFamily={FONT_EN} fontSize={13.5} fill={C.ink2}>
                {b.sub}
              </text>
            </g>
          );
        })}

        {/* Stream from the Real Phone's elevated speaker cards out to the Xiaomi Speaker on the right */}
        {[535, 645].map((sy, idx) => {
          const p0 = [1190, sy];
          const p3 = [spkPos[0] - 75, spkPos[1] + (idx === 0 ? -18 : 18)];
          const p1 = [1340, sy];
          const p2 = [1480, p3[1]];
          const pts = Array.from({ length: 33 }, (_, k) => bez(p0, p1, p2, p3, (k / 32) * popCards));
          const d = pts.map((q, k) => `${k ? "L" : "M"}${q[0].toFixed(1)} ${q[1].toFixed(1)}`).join(" ");
          return (
            <g key={idx} opacity={popCards}>
              <path
                d={d}
                fill="none"
                stroke={C.coral}
                strokeWidth={2.4}
                strokeDasharray="6 10"
                strokeDashoffset={-f * 1.6}
              />
            </g>
          );
        })}

        <g opacity={prog(f, 50, 95)}>
          <Speaker x={spkPos[0]} y={spkPos[1]} s={2.25} active={castMorph} label="小米 / 小爱音箱" />
        </g>
      </svg>

      {/* Center: Real 3D Phone transitioning from 07-dark (Standby) -> 01-dark (Casting) on touch tap */}
      <div
        style={{
          position: "absolute",
          left: 805,
          top: 220,
          perspective: 1800,
        }}
      >
        <div
          style={{
            transform: `translateY(${camY}px) scale(${camZoom}) rotateY(-6deg) rotateX(3deg)`,
            transformOrigin: "center 65%",
          }}
        >
          <StagePhone
            src="ui/07-dark.png"
            src2="ui/01-dark.png"
            fade2={castMorph}
            w={385}
            dim={popCards * 0.45}
            taps={[
              { x: 586, y: 748, start: tapFrame, color: C.coral },
              { x: 586, y: 748, start: tapFrame + 10, color: "#4C8DFF" },
            ]}
            slices={[
              {
                id: "status-pill",
                src: "ui/01-dark.png",
                rect: [16, 14, 646, 72],
                radius: 20,
                elevate: castMorph,
                dy: -8,
                popScale: 1.06,
                accentBorder: "rgba(52,211,153,0.6)",
              },
              {
                id: "spk-card-1",
                src: "ui/01-dark.png",
                rect: [48, 792, 582, 150],
                radius: 26,
                elevate: popCards,
                dx: 28,
                dy: -10,
                popScale: 1.12,
                accentBorder: "rgba(76,141,255,0.7)",
              },
              {
                id: "spk-card-2",
                src: "ui/01-dark.png",
                rect: [48, 958, 582, 150],
                radius: 26,
                elevate: popCards,
                dx: 28,
                dy: 4,
                popScale: 1.12,
                accentBorder: "rgba(76,141,255,0.7)",
              },
            ]}
          />
        </div>
      </div>

      {/* Bottom caption & right callout */}
      <div
        style={{
          position: "absolute",
          left: 120,
          bottom: 52,
          fontFamily: FONT_CN,
          fontSize: 22,
          letterSpacing: 2.5,
          color: C.ink2,
          opacity: prog(f, 80, 120),
        }}
      >
        实机界面：从「本机播放」轻触勾选音箱，即刻建立 48kHz 局域网音频直连
      </div>
      <div style={{ position: "absolute", left: 1465, top: 820, width: 360, textAlign: "center" }}>
        <Reveal text="免 Root · 免登录小米账号" start={145} size={27} weight={600} color={C.coral} stagger={1.4} />
      </div>
    </AbsoluteFill>
  );
};
