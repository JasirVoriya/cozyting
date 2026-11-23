import React from "react";
import { AbsoluteFill, useCurrentFrame } from "remotion";
import { C, FONT_CN, prog, easeInOut, easeOut, sceneFade } from "../theme";
import { Eyebrow, Reveal, Speaker, StagePhone } from "../components";

export const Lan: React.FC<{ dur: number }> = ({ dur }) => {
  const f = useCurrentFrame();

  // Phone 1 (05-dark.png Settings): elevates "无感模式 (无线调试)", "单 App 隔离 + 打开 4 步配对引导", and "投送时手机静音"
  const popEngine = prog(f, 14, 52, easeOut);
  const popMute = prog(f, 34, 72, easeOut);

  // Tap "打开 4 步配对引导" at f=75 -> Phone 2 (04-dark.png 4-Step Wireless Guide) glides in and elevates Step 3
  const guidePhoneIn = prog(f, 80, 130, easeOut);
  const popStep3 = prog(f, 125, 170, easeOut);

  // Right side: Pure LAN · Zero Cloud topology badge
  const house = prog(f, 40, 120, easeInOut);
  const slash = prog(f, 95, 130, easeInOut);

  return (
    <AbsoluteFill style={{ background: C.bg, opacity: sceneFade(f, dur, 12, 16) }}>
      <AbsoluteFill
        style={{
          background:
            "radial-gradient(circle at 28% 58%, rgba(37,99,235,0.18) 0%, rgba(37,99,235,0) 42%), radial-gradient(circle at 62% 58%, rgba(52,211,153,0.12) 0%, rgba(52,211,153,0) 38%)",
        }}
      />

      {/* Top Header */}
      <div style={{ position: "absolute", left: 115, top: 72, zIndex: 20 }}>
        <Eyebrow text="Wireless Debug Engine · Pure LAN Zero Cloud" start={4} />
        <div style={{ marginTop: 14 }}>
          <Reveal text="内置无线调试无感采集，真 · 单 App 静音。" start={10} size={56} weight={650} stagger={1.8} />
        </div>
        <div style={{ marginTop: 10 }}>
          <Reveal
            text="免 Root · 免电脑 · 免 Shizuku，本机 4 步向导完成配对，全链路纯局域网零云端。"
            start={45}
            size={25}
            weight={400}
            color={C.ink2}
            stagger={0.9}
          />
        </div>
      </div>

      {/* Left Phone: 05-dark.png (Settings Core: Seamless Wireless Engine + Single-App Isolation + Phone Mute) */}
      <div
        style={{
          position: "absolute",
          left: 115,
          top: 235,
          perspective: 1800,
          zIndex: 10,
        }}
      >
        <div
          style={{
            transform: "translateY(0px) rotateY(8deg) rotateX(2deg)",
            transformStyle: "preserve-3d",
          }}
        >
          <StagePhone
            src="ui/05-dark.png"
            w={365}
            dim={popEngine * 0.45}
            taps={[{ x: 195, y: 1098, start: 68, color: "#4C8DFF" }]}
            slices={[
              {
                id: "engine-switch",
                rect: [46, 656, 586, 56],
                radius: 18,
                elevate: popEngine,
                dx: 22,
                dy: -8,
                popScale: 1.1,
                accentBorder: "rgba(59,130,246,0.85)",
              },
              {
                id: "single-app-scope",
                rect: [46, 990, 586, 140],
                radius: 20,
                elevate: popEngine,
                dx: 26,
                dy: 0,
                popScale: 1.1,
                accentBorder: "rgba(59,130,246,0.85)",
              },
              {
                id: "phone-mute-card",
                rect: [46, 1156, 586, 120],
                radius: 22,
                elevate: popMute,
                dx: 26,
                dy: 10,
                popScale: 1.1,
                accentBorder: "rgba(52,211,153,0.75)",
              },
            ]}
          />
        </div>
      </div>

      {/* Center Phone: 04-dark.png (4-Step Wireless Debugging Guide) gliding in on tap */}
      <div
        style={{
          position: "absolute",
          left: 615,
          top: 235,
          perspective: 1800,
          zIndex: 12,
          opacity: guidePhoneIn,
          filter: `blur(${(1 - guidePhoneIn) * 10}px)`,
        }}
      >
        <div
          style={{
            transform: `translateX(${(1 - guidePhoneIn) * 70}px) translateY(0px) rotateY(-4deg) rotateX(2deg)`,
            transformStyle: "preserve-3d",
          }}
        >
          <StagePhone
            src="ui/04-dark.png"
            w={365}
            dim={popStep3 * 0.45}
            slices={[
              {
                id: "step1-done",
                rect: [24, 448, 630, 116],
                radius: 24,
                elevate: popStep3 * 0.7,
                dx: 14,
                popScale: 1.04,
                accentBorder: "rgba(52,211,153,0.65)",
              },
              {
                id: "step2-done",
                rect: [24, 584, 630, 116],
                radius: 24,
                elevate: popStep3 * 0.7,
                dx: 14,
                popScale: 1.04,
                accentBorder: "rgba(52,211,153,0.65)",
              },
              {
                id: "step3-pair",
                rect: [22, 718, 634, 370],
                radius: 28,
                elevate: popStep3,
                dx: 28,
                dy: 6,
                popScale: 1.11,
                accentBorder: "rgba(59,130,246,0.9)",
              },
            ]}
          />
        </div>
      </div>

      {/* Right Column: Pure LAN · Zero Cloud Architectural Topology Card */}
      <div
        style={{
          position: "absolute",
          left: 1125,
          top: 245,
          width: 680,
          height: 730,
          borderRadius: 34,
          background: "rgba(21,26,35,0.72)",
          border: `1px solid ${C.line2}`,
          boxShadow: "0 40px 90px rgba(0,0,0,0.45)",
          opacity: prog(f, 35, 85),
        }}
      >
        <svg width="680" height="730" style={{ position: "absolute", inset: 0 }}>
          {/* Crossed-out Cloud */}
          <g transform="translate(340 105) scale(0.76)">
            <path
              d="M-70 30 h140 a40 40 0 0 0 0-80 a56 56 0 0 0-104-16 a42 42 0 0 0-36 96z"
              fill="none"
              stroke={C.ink3}
              strokeWidth={3}
              strokeLinejoin="round"
            />
            <line
              x1={-90}
              y1={-70}
              x2={90}
              y2={60}
              stroke={C.coral}
              strokeWidth={4.5}
              strokeLinecap="round"
              pathLength={1}
              strokeDasharray="1 1"
              strokeDashoffset={1 - slash}
            />
          </g>
          <text x={340} y={168} textAnchor="middle" fontFamily={FONT_CN} fontSize={17} fill={C.coral}>
            0 云端请求 · 免账号登录
          </text>

          {/* House Roof Outline */}
          <path
            d="M 85 335 L 340 195 L 595 335 L 595 575 L 85 575 Z"
            fill="rgba(255,255,255,0.015)"
            stroke={C.line2}
            strokeWidth={2.2}
            strokeLinejoin="round"
            pathLength={1}
            strokeDasharray="1 1"
            strokeDashoffset={1 - house}
          />

          {/* Router */}
          <g transform="translate(340 345)">
            <rect x={-52} y={-18} width={104} height={36} rx={10} fill={C.bg2} stroke={C.ink2} strokeWidth={2} />
            {[0, 1, 2].map((k) => (
              <circle key={k} cx={-20 + k * 14} cy={0} r={3} fill={k === 0 ? C.coral : C.ink3} />
            ))}
            <text x={0} y={-28} textAnchor="middle" fontFamily={FONT_CN} fontSize={16} fill={C.ink2}>
              家庭 Wi-Fi 局域网
            </text>
          </g>

          {/* Local connections */}
          {[
            { x: 185, y: 490, kind: "phone", label: "本机环回采集" },
            { x: 385, y: 490, kind: "spk", label: "客厅音箱" },
            { x: 505, y: 490, kind: "spk", label: "卧室音箱" },
          ].map((n, i) => {
            const tt = ((f - 70 - i * 15) / 45) % 1;
            const fwd = n.kind === "phone" ? 1 - tt : tt;
            return (
              <g key={i}>
                <line x1={340} y1={363} x2={n.x} y2={n.y - 25} stroke={C.line2} strokeWidth={1.8} />
                {f > 70 && (
                  <circle
                    cx={340 + (n.x - 340) * fwd}
                    cy={363 + (n.y - 25 - 363) * fwd}
                    r={4}
                    fill={C.coral}
                    opacity={Math.sin(tt * Math.PI)}
                  />
                )}
                {n.kind === "phone" ? (
                  <g>
                    <rect
                      x={n.x - 22}
                      y={n.y - 40}
                      width={44}
                      height={78}
                      rx={10}
                      fill={C.bg2}
                      stroke={C.ink2}
                      strokeWidth={2}
                    />
                    <circle cx={n.x} cy={n.y} r={8} fill={C.coral} />
                    <text x={n.x} y={n.y + 62} textAnchor="middle" fontFamily={FONT_CN} fontSize={16} fill={C.ink2}>
                      {n.label}
                    </text>
                  </g>
                ) : (
                  <Speaker x={n.x} y={n.y} s={0.78} active={prog(f, 110, 130)} label={n.label} />
                )}
              </g>
            );
          })}
        </svg>

        <div style={{ position: "absolute", bottom: 38, width: "100%", textAlign: "center" }}>
          <div style={{ fontFamily: FONT_CN, fontSize: 30, fontWeight: 650, color: C.ink }}>
            纯局域网直连 · 音频绝不出户
          </div>
          <div style={{ marginTop: 10, fontFamily: FONT_CN, fontSize: 19, color: C.ink2, letterSpacing: 1.5 }}>
            127.0.0.1 本机无线调试握手 · AGPL-3.0 开源可审计
          </div>
        </div>
      </div>
    </AbsoluteFill>
  );
};
