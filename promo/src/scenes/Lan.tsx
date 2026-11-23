import React from "react";
import { AbsoluteFill, useCurrentFrame } from "remotion";
import { C, fontForLocale, uiImg, prog, easeInOut, easeOut, sceneFade, type PromoLocale } from "../theme";
import { Eyebrow, Reveal, Speaker, StagePhone, UiSlice } from "../components";
import { getCopy } from "../i18n";

export const Lan: React.FC<{ dur: number; locale: PromoLocale }> = ({ dur, locale }) => {
  const f = useCurrentFrame();
  const copy = getCopy(locale).lan;
  const fontMain = fontForLocale(locale);

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
        <Eyebrow text={copy.eyebrow} start={4} />
        <div style={{ marginTop: 14 }}>
          <Reveal
            text={copy.headline}
            start={10}
            size={copy.headlineSize}
            weight={700}
            font={fontMain}
            stagger={locale === "en" ? 1.0 : 1.6}
          />
        </div>
        <div style={{ marginTop: 10 }}>
          <Reveal
            text={copy.subtitle}
            start={38}
            size={copy.subtitleSize}
            weight={450}
            font={fontMain}
            color="#CBD5E1"
            stagger={0.45}
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
          <StagePhone src={uiImg(locale, "05-dark.png")} w={355} dim={0.82} />
        </div>
      </div>

      {/* Center-Left: TWO MASSIVE Spotlighted Real UI Cards */}
      <div
        style={{
          position: "absolute",
          left: 365,
          top: 290,
          width: 650,
          display: "flex",
          flexDirection: "column",
          gap: 48,
          zIndex: 20,
        }}
      >
        {/* Spotlight Card 1: Silent Mode */}
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
          <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: 14, gap: 12 }}>
            <div
              style={{
                padding: "6px 16px",
                borderRadius: 999,
                background: "rgba(59,130,246,0.24)",
                fontFamily: fontMain,
                fontSize: 20,
                fontWeight: 700,
                color: "#93C5FD",
                whiteSpace: "nowrap",
              }}
            >
              {copy.card1Badge}
            </div>
            <div style={{ fontFamily: fontMain, fontSize: 20, fontWeight: 600, color: "#FFFFFF", whiteSpace: "nowrap" }}>
              {copy.card1Hint}
            </div>
          </div>
          <UiSlice
            src={uiImg(locale, "05-dark.png")}
            rect={copy.card1Rect}
            radius={20}
            scale={1.01}
            elevation={0.4}
            accentBorder="rgba(59,130,246,0.5)"
          />
        </div>

        {/* Spotlight Card 2: Mute Phone While Casting */}
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
          <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: 14, gap: 12 }}>
            <div
              style={{
                padding: "6px 16px",
                borderRadius: 999,
                background: "rgba(52,211,153,0.22)",
                fontFamily: fontMain,
                fontSize: 20,
                fontWeight: 700,
                color: "#6EE7B7",
                whiteSpace: "nowrap",
              }}
            >
              {copy.card2Badge}
            </div>
            <div style={{ fontFamily: fontMain, fontSize: 20, fontWeight: 600, color: "#FFFFFF", whiteSpace: "nowrap" }}>
              {copy.card2Hint}
            </div>
          </div>
          <UiSlice
            src={uiImg(locale, "05-dark.png")}
            rect={copy.card2Rect}
            radius={20}
            scale={1.01}
            elevation={0.4}
            accentBorder="rgba(52,211,153,0.5)"
          />
        </div>
      </div>

      {/* Right Column: Pure LAN · Zero Cloud Privacy Card */}
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
          <text x={355} y={182} textAnchor="middle" fontFamily={fontMain} fontSize={26} fontWeight={700} fill={C.coral}>
            {copy.zeroCloudTitle}
          </text>

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

          <g transform="translate(355 345)">
            <rect x={-64} y={-22} width={128} height={44} rx={12} fill={C.bg2} stroke="#CBD5E1" strokeWidth={2.4} />
            {[0, 1, 2].map((k) => (
              <circle key={k} cx={-26 + k * 18} cy={0} r={4.5} fill={k === 0 ? C.coral : "#64748B"} />
            ))}
            <text x={0} y={-34} textAnchor="middle" fontFamily={fontMain} fontSize={23} fontWeight={600} fill="#E2E8F0">
              {copy.routerLabel}
            </text>
          </g>

          {[
            { x: 185, y: 488, kind: "phone", label: copy.nodePhone },
            { x: 395, y: 488, kind: "spk", label: copy.nodeLiving },
            { x: 530, y: 488, kind: "spk", label: copy.nodeBedroom },
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
                    <text x={n.x} y={n.y + 68} textAnchor="middle" fontFamily={fontMain} fontSize={21} fontWeight={600} fill="#E2E8F0">
                      {n.label}
                    </text>
                  </g>
                ) : (
                  <g>
                    <Speaker x={n.x} y={n.y} s={0.88} active={prog(f, 90, 115)} />
                    <text x={n.x} y={n.y + 68} textAnchor="middle" fontFamily={fontMain} fontSize={21} fontWeight={600} fill="#E2E8F0">
                      {n.label}
                    </text>
                  </g>
                )}
              </g>
            );
          })}
        </svg>

        <div style={{ position: "absolute", bottom: 38, width: "100%", textAlign: "center", padding: "0 24px" }}>
          <div style={{ fontFamily: fontMain, fontSize: locale === "zh" ? 36 : 31, fontWeight: 700, color: "#FFFFFF" }}>
            {copy.footerTitle}
          </div>
          <div style={{ marginTop: 10, fontFamily: fontMain, fontSize: 22, fontWeight: 500, color: "#CBD5E1" }}>
            {copy.footerSub}
          </div>
        </div>
      </div>
    </AbsoluteFill>
  );
};
