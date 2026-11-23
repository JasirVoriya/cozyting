import React from "react";
import { AbsoluteFill, useCurrentFrame } from "remotion";
import { C, FONT_CN, prog, easeInOut, easeOut, sceneFade } from "../theme";
import { Eyebrow, Reveal, Speaker, StagePhone, UiSlice } from "../components";

/**
 * Scene 05 (6s = 360f) — Decluttered, User-First:
 *   Left:  1 dimmed Settings phone + ONLY 2 HUGE zoomed real UI cards ("无感模式" & "投送时手机静音")
 *   Right: Super clean, large-font "Pure LAN · Zero Cloud" privacy card (zero dev jargon!)
 */
export const Lan: React.FC<{ dur: number }> = ({ dur }) => {
  const f = useCurrentFrame();

  const phoneIn = prog(f, 8, 48, easeOut);
  const card1In = prog(f, 32, 74, easeOut);
  const card2In = prog(f, 76, 118, easeOut);
  const rightIn = prog(f, 45, 92, easeOut);
  const house = prog(f, 65, 145, easeInOut);
  const slash = prog(f, 115, 150, easeInOut);

  return (
    <AbsoluteFill style={{ background: C.bg, opacity: sceneFade(f, dur, 10, 16) }}>
      <AbsoluteFill
        style={{
          background:
            "radial-gradient(circle at 26% 58%, rgba(37,99,235,0.20) 0%, rgba(37,99,235,0) 44%), radial-gradient(circle at 76% 56%, rgba(52,211,153,0.14) 0%, rgba(52,211,153,0) 42%)",
        }}
      />

      {/* Clean User-Friendly Header */}
      <div style={{ position: "absolute", left: 100, top: 64, zIndex: 20 }}>
        <Eyebrow text="Seamless & Private · Pure LAN" start={4} />
        <div style={{ marginTop: 14 }}>
          <Reveal text="免弹框无感直投，音频绝不出家门。" start={10} size={62} weight={700} stagger={1.6} />
        </div>
        <div style={{ marginTop: 10 }}>
          <Reveal
            text="两个核心开关，开启后无感开投、手机静音互不干扰，全程纯局域网零云端。"
            start={38}
            size={27}
            weight={450}
            color="#CBD5E1"
            stagger={0.6}
          />
        </div>
      </div>

      {/* Left: Single Strongly-Dimmed Phone as Context Anchor */}
      <div
        style={{
          position: "absolute",
          left: 100,
          top: 255,
          perspective: 1800,
          opacity: phoneIn,
          zIndex: 8,
        }}
      >
        <div style={{ transform: "rotateY(10deg) rotateX(2deg)", transformStyle: "preserve-3d" }}>
          <StagePhone src="ui/05-dark.png" w={355} dim={0.82} />
        </div>
      </div>

      {/* Center-Left: TWO MASSIVE, Effortlessly Readable Spotlighted Real UI Cards */}
      <div
        style={{
          position: "absolute",
          left: 365,
          top: 295,
          width: 650,
          display: "flex",
          flexDirection: "column",
          gap: 54,
          zIndex: 20,
        }}
      >
        {/* Spotlight Card 1: 无感模式 */}
        <div
          style={{
            opacity: card1In,
            transform: `translateX(${(1 - card1In) * -40}px) scale(${0.94 + card1In * 0.06})`,
            padding: 20,
            borderRadius: 30,
            background: "rgba(15,20,30,0.94)",
            border: "2px solid rgba(59,130,246,0.88)",
            boxShadow: "0 30px 75px rgba(0,0,0,0.68), 0 0 40px rgba(59,130,246,0.22)",
          }}
        >
          <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: 14 }}>
            <div
              style={{
                padding: "6px 18px",
                borderRadius: 999,
                background: "rgba(59,130,246,0.24)",
                fontFamily: FONT_CN,
                fontSize: 22,
                fontWeight: 700,
                color: "#93C5FD",
              }}
            >
              ① 无感模式
            </div>
            <div style={{ fontFamily: FONT_CN, fontSize: 22, fontWeight: 600, color: "#FFFFFF" }}>
              免每次点「开始录屏」确认
            </div>
          </div>
          <UiSlice
            src="ui/05-dark.png"
            rect={[42, 646, 594, 146]}
            radius={20}
            scale={1.01}
            elevation={0.4}
            accentBorder="rgba(59,130,246,0.5)"
          />
        </div>

        {/* Spotlight Card 2: 投送时手机静音 */}
        <div
          style={{
            opacity: card2In,
            transform: `translateX(${(1 - card2In) * -40}px) scale(${0.94 + card2In * 0.06})`,
            padding: 20,
            borderRadius: 30,
            background: "rgba(15,20,30,0.94)",
            border: "2px solid rgba(52,211,153,0.88)",
            boxShadow: "0 30px 75px rgba(0,0,0,0.68), 0 0 40px rgba(52,211,153,0.20)",
          }}
        >
          <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: 14 }}>
            <div
              style={{
                padding: "6px 18px",
                borderRadius: 999,
                background: "rgba(52,211,153,0.22)",
                fontFamily: FONT_CN,
                fontSize: 22,
                fontWeight: 700,
                color: "#6EE7B7",
              }}
            >
              ② 投送时手机静音
            </div>
            <div style={{ fontFamily: FONT_CN, fontSize: 22, fontWeight: 600, color: "#FFFFFF" }}>
              只静音乐 · 刷视频照常有声
            </div>
          </div>
          <UiSlice
            src="ui/05-dark.png"
            rect={[42, 1148, 594, 134]}
            radius={20}
            scale={1.01}
            elevation={0.4}
            accentBorder="rgba(52,211,153,0.5)"
          />
        </div>
      </div>

      {/* Right Column: Ultra-Clean, Big-Font Pure LAN · Zero Cloud Privacy Card */}
      <div
        style={{
          position: "absolute",
          left: 1110,
          top: 265,
          width: 710,
          height: 735,
          borderRadius: 38,
          background: "rgba(21,26,37,0.86)",
          border: "2px solid rgba(255,255,255,0.18)",
          boxShadow: "0 38px 90px rgba(0,0,0,0.52)",
          opacity: rightIn,
          transform: `translateY(${(1 - rightIn) * 26}px)`,
        }}
      >
        <svg width="710" height="735" style={{ position: "absolute", inset: 0 }}>
          {/* Crossed-out Cloud */}
          <g transform="translate(355 105) scale(0.92)">
            <path
              d="M-70 30 h140 a40 40 0 0 0 0-80 a56 56 0 0 0-104-16 a42 42 0 0 0-36 96z"
              fill="none"
              stroke="#94A3B8"
              strokeWidth={3.5}
              strokeLinejoin="round"
            />
            <line
              x1={-95}
              y1={-72}
              x2={95}
              y2={62}
              stroke={C.coral}
              strokeWidth={5.5}
              strokeLinecap="round"
              pathLength={1}
              strokeDasharray="1 1"
              strokeDashoffset={1 - slash}
            />
          </g>
          <text x={355} y={182} textAnchor="middle" fontFamily={FONT_CN} fontSize={26} fontWeight={700} fill={C.coral}>
            零云端上传 · 免账号登录
          </text>

          {/* House Roof Outline */}
          <path
            d="M 80 355 L 355 210 L 630 355 L 630 570 L 80 570 Z"
            fill="rgba(255,255,255,0.02)"
            stroke="rgba(255,255,255,0.26)"
            strokeWidth={2.6}
            strokeLinejoin="round"
            pathLength={1}
            strokeDasharray="1 1"
            strokeDashoffset={1 - house}
          />

          {/* Router */}
          <g transform="translate(355 345)">
            <rect x={-64} y={-22} width={128} height={44} rx={12} fill={C.bg2} stroke="#CBD5E1" strokeWidth={2.4} />
            {[0, 1, 2].map((k) => (
              <circle key={k} cx={-26 + k * 18} cy={0} r={4.5} fill={k === 0 ? C.coral : "#64748B"} />
            ))}
            <text x={0} y={-34} textAnchor="middle" fontFamily={FONT_CN} fontSize={23} fontWeight={600} fill="#E2E8F0">
              家庭 Wi-Fi 局域网
            </text>
          </g>

          {/* Local Nodes */}
          {[
            { x: 185, y: 488, kind: "phone", label: "你的手机" },
            { x: 395, y: 488, kind: "spk", label: "客厅音箱" },
            { x: 530, y: 488, kind: "spk", label: "卧室音箱" },
          ].map((n, i) => {
            const tt = ((f - 60 - i * 14) / 42) % 1;
            const fwd = n.kind === "phone" ? 1 - tt : tt;
            return (
              <g key={i}>
                <line x1={355} y1={367} x2={n.x} y2={n.y - 32} stroke="rgba(255,255,255,0.28)" strokeWidth={2.4} />
                {f > 60 && (
                  <circle
                    cx={355 + (n.x - 355) * fwd}
                    cy={367 + (n.y - 32 - 367) * fwd}
                    r={5.5}
                    fill={C.coral}
                    opacity={Math.sin(tt * Math.PI)}
                  />
                )}
                {n.kind === "phone" ? (
                  <g>
                    <rect
                      x={n.x - 24}
                      y={n.y - 44}
                      width={48}
                      height={84}
                      rx={11}
                      fill={C.bg2}
                      stroke="#E2E8F0"
                      strokeWidth={2.4}
                    />
                    <circle cx={n.x} cy={n.y} r={9} fill={C.coral} />
                    <text x={n.x} y={n.y + 68} textAnchor="middle" fontFamily={FONT_CN} fontSize={22} fontWeight={600} fill="#E2E8F0">
                      {n.label}
                    </text>
                  </g>
                ) : (
                  <g>
                    <Speaker x={n.x} y={n.y} s={0.88} active={prog(f, 90, 115)} />
                    <text x={n.x} y={n.y + 68} textAnchor="middle" fontFamily={FONT_CN} fontSize={22} fontWeight={600} fill="#E2E8F0">
                      {n.label}
                    </text>
                  </g>
                )}
              </g>
            );
          })}
        </svg>

        <div style={{ position: "absolute", bottom: 38, width: "100%", textAlign: "center" }}>
          <div style={{ fontFamily: FONT_CN, fontSize: 36, fontWeight: 700, color: "#FFFFFF" }}>
            纯局域网直连 · 隐私绝不出户
          </div>
          <div style={{ marginTop: 10, fontFamily: FONT_CN, fontSize: 23, fontWeight: 500, color: "#CBD5E1" }}>
            不经过任何远程服务器 · 免费开源
          </div>
        </div>
      </div>
    </AbsoluteFill>
  );
};
