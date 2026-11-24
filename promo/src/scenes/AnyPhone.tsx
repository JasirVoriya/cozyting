import React from "react";
import { AbsoluteFill, useCurrentFrame } from "remotion";
import { C, FONT_EN, fontForLocale, uiImg, prog, easeInOut, easeOut, sceneFade, lerp, type PromoLocale } from "../theme";
import { Eyebrow, Reveal, Speaker, StagePhone } from "../components";
import { getCopy } from "../i18n";

const bez = (p0: number[], p1: number[], p2: number[], p3: number[], t: number) => {
  const u = 1 - t;
  return [0, 1].map(
    (i) => u * u * u * p0[i] + 3 * u * u * t * p1[i] + 3 * u * t * t * p2[i] + t * t * t * p3[i]
  );
};

export const AnyPhone: React.FC<{ dur: number; locale: PromoLocale }> = ({ dur, locale }) => {
  const f = useCurrentFrame();
  const copy = getCopy(locale).anyPhone;
  const fontMain = fontForLocale(locale);

  const tapFrame = 78;
  const castMorph = prog(f, 92, 130, easeInOut);
  const popCards = prog(f, 132, 178, easeOut);
  const camZoom = lerp(1.0, 1.04, prog(f, 40, 150, easeInOut));
  const camY = lerp(16, 0, prog(f, 50, 165, easeInOut));

  const phoneTarget = [1015, 585];
  const spkPos = [1690, 565];

  const otherA = prog(f, 52, 88);
  const otherDraw = prog(f, 62, 114, easeInOut);
  const otherP0 = [95 + 578, 762 + 58];
  const otherP3 = [phoneTarget[0] - 185, phoneTarget[1] + 150];
  const otherPts = Array.from({ length: 33 }, (_, k) =>
    bez(otherP0, [otherP0[0] + 95, otherP0[1]], [otherP3[0] - 95, otherP3[1]], otherP3, (k / 32) * otherDraw)
  );
  const otherD = otherPts.map((q, k) => `${k ? "L" : "M"}${q[0].toFixed(1)} ${q[1].toFixed(1)}`).join(" ");
  const otherDotT = ((f - 72) / 52) % 1;
  const otherDotPos = f > 72 ? bez(otherP0, [otherP0[0] + 95, otherP0[1]], [otherP3[0] - 95, otherP3[1]], otherP3, otherDotT) : null;

  return (
    <AbsoluteFill style={{ background: C.bg, opacity: sceneFade(f, dur, 10, 16) }}>
      <AbsoluteFill
        style={{
          background: `radial-gradient(circle at 55% 56%, rgba(46,108,246,${0.22 * castMorph}) 0%, rgba(46,108,246,0) 42%), radial-gradient(circle at 86% 54%, rgba(229,109,72,${0.16 * castMorph}) 0%, rgba(229,109,72,0) 36%)`,
        }}
      />

      {/* Top Header */}
      <div style={{ position: "absolute", left: 100, top: 64, zIndex: 20 }}>
        <Eyebrow text={copy.eyebrow} start={4} />
        <div style={{ marginTop: 14 }}>
          <Reveal
            text={copy.headline}
            start={10}
            size={copy.headlineSize}
            weight={700}
            font={fontMain}
            stagger={locale === "en" ? 1.1 : 1.8}
          />
        </div>
      </div>

      {/* Left: 6 LARGE Brand Cards + 7th Full-Width "Any Other Android Phone" Card */}
      <svg width="1920" height="1080" style={{ position: "absolute", inset: 0 }}>
        {copy.brands.map((b, i) => {
          const col = i % 2;
          const row = Math.floor(i / 2);
          const bx = 95 + col * 300;
          const by = 258 + row * 166;
          const bw = 278;
          const bh = 122;
          const a = prog(f, 12 + i * 6, 48 + i * 6);
          const p0 = [bx + bw, by + bh / 2];
          const p3 = [phoneTarget[0] - 185, phoneTarget[1] - 105 + (i - 2.5) * 38];
          const p1 = [p0[0] + 95, p0[1]];
          const p2 = [p3[0] - 95, p3[1]];
          const draw = prog(f, 24 + i * 6, 78 + i * 6, easeInOut);
          const pts = Array.from({ length: 33 }, (_, k) => bez(p0, p1, p2, p3, (k / 32) * draw));
          const d = pts.map((q, k) => `${k ? "L" : "M"}${q[0].toFixed(1)} ${q[1].toFixed(1)}`).join(" ");
          const dotT = ((f - 34 - i * 11) / 52) % 1;
          const dotPos = f > 34 + i * 11 ? bez(p0, p1, p2, p3, dotT) : null;

          return (
            <g key={b.name} opacity={a} transform={`translate(${(1 - a) * -28} 0)`}>
              <path d={d} fill="none" stroke="rgba(229,109,72,0.34)" strokeWidth={2.2} />
              {dotPos && (
                <circle
                  cx={dotPos[0]}
                  cy={dotPos[1]}
                  r={5.5}
                  fill={C.coral}
                  opacity={Math.sin(dotT * Math.PI) * draw}
                />
              )}
              <rect
                x={bx}
                y={by}
                width={bw}
                height={bh}
                rx={24}
                fill="rgba(22,28,39,0.94)"
                stroke={castMorph > 0.3 ? "rgba(229,109,72,0.62)" : "rgba(255,255,255,0.22)"}
                strokeWidth={2.2}
              />
              <circle cx={bx + 32} cy={by + 48} r={9} fill={C.coral} />
              <text
                x={bx + 56}
                y={by + 58}
                fontFamily={b.en ? fontMain : FONT_EN}
                fontSize={34}
                fontWeight={700}
                fill="#FFFFFF"
              >
                {b.name}
                {b.en ? (
                  <tspan fontFamily={FONT_EN} fontSize={22} fontWeight={600} fill="#CBD5E1" dx={10}>
                    {b.en}
                  </tspan>
                ) : null}
              </text>
              <text x={bx + 56} y={by + 96} fontFamily={FONT_EN} fontSize={19} fontWeight={500} fill="#B8C2D4">
                {b.sub}
              </text>
            </g>
          );
        })}

        {/* 7th Full-Width Card: Any Other Android Phone */}
        <g opacity={otherA} transform={`translate(${(1 - otherA) * -28} 0)`}>
          <path d={otherD} fill="none" stroke="rgba(229,109,72,0.45)" strokeWidth={2.4} />
          {otherDotPos && (
            <circle
              cx={otherDotPos[0]}
              cy={otherDotPos[1]}
              r={5.5}
              fill={C.coral}
              opacity={Math.sin(otherDotT * Math.PI) * otherDraw}
            />
          )}
          <rect
            x={95}
            y={762}
            width={578}
            height={116}
            rx={24}
            fill="rgba(229,109,72,0.12)"
            stroke="rgba(229,109,72,0.78)"
            strokeWidth={2.4}
            strokeDasharray="10 6"
          />
          <circle cx={136} cy={820} r={18} fill="rgba(229,109,72,0.24)" stroke={C.coral} strokeWidth={2} />
          <text x={136} y={829} textAnchor="middle" fontFamily={FONT_EN} fontSize={28} fontWeight={700} fill={C.coral}>
            +
          </text>
          <text x={174} y={814} fontFamily={fontMain} fontSize={locale === "ja" ? 28 : 31} fontWeight={700} fill="#FFFFFF">
            {copy.otherTitle}
          </text>
          <text x={174} y={852} fontFamily={fontMain} fontSize={19} fontWeight={500} fill="#CBD5E1">
            {copy.otherSub}
          </text>
        </g>

        {/* Stream from elevated real speaker cards out to the Xiaomi Speaker on the right */}
        {[565, 685].map((sy, idx) => {
          const p0 = [1295, sy];
          const p3 = [spkPos[0] - 85, spkPos[1] + (idx === 0 ? -20 : 20)];
          const p1 = [1420, sy];
          const p2 = [1525, p3[1]];
          const pts = Array.from({ length: 33 }, (_, k) => bez(p0, p1, p2, p3, (k / 32) * popCards));
          const d = pts.map((q, k) => `${k ? "L" : "M"}${q[0].toFixed(1)} ${q[1].toFixed(1)}`).join(" ");
          return (
            <g key={idx} opacity={popCards}>
              <path
                d={d}
                fill="none"
                stroke={C.coral}
                strokeWidth={3.2}
                strokeDasharray="8 11"
                strokeDashoffset={-f * 1.8}
              />
            </g>
          );
        })}

        <g opacity={prog(f, 48, 92)}>
          <Speaker x={spkPos[0]} y={spkPos[1]} s={2.45} active={castMorph} />
        </g>
      </svg>

      {/* Right Speaker Label — Big & Clear */}
      <div
        style={{
          position: "absolute",
          left: 1495,
          top: 718,
          width: 390,
          textAlign: "center",
          opacity: prog(f, 52, 96),
        }}
      >
        <div style={{ fontFamily: fontMain, fontSize: 32, fontWeight: 700, color: "#FFFFFF", letterSpacing: 1 }}>
          {copy.speakerTitle}
        </div>
        <div
          style={{
            marginTop: 18,
            display: "inline-block",
            padding: "10px 22px",
            borderRadius: 999,
            background: "rgba(229,109,72,0.16)",
            border: "1.5px solid rgba(229,109,72,0.55)",
            fontFamily: fontMain,
            fontSize: locale === "en" ? 22 : 24,
            fontWeight: 600,
            color: "#FF8A95",
            opacity: prog(f, 130, 170),
            whiteSpace: "nowrap",
          }}
        >
          {copy.speakerBadge}
        </div>
      </div>

      {/* Center: Real 3D Phone transitioning from 07-dark (Standby) -> 01-dark (Casting) */}
      <div
        style={{
          position: "absolute",
          left: 845,
          top: 215,
          perspective: 1800,
        }}
      >
        <div
          style={{
            transform: `translateY(${camY}px) scale(${camZoom}) rotateY(-5deg) rotateX(2deg)`,
            transformOrigin: "center 65%",
          }}
        >
          <StagePhone
            src={uiImg(locale, "07-dark.png")}
            src2={uiImg(locale, "01-dark.png")}
            fade2={castMorph}
            w={395}
            dim={popCards * 0.68}
            taps={[
              { x: 586, y: 748, start: tapFrame, color: C.coral },
              { x: 586, y: 748, start: tapFrame + 10, color: "#4C8DFF" },
            ]}
            slices={[
              {
                id: "status-pill",
                src: uiImg(locale, "01-dark.png"),
                rect: [16, 14, 646, 72],
                radius: 20,
                elevate: castMorph,
                dy: -10,
                popScale: 1.09,
                accentBorder: "rgba(52,211,153,0.75)",
              },
              {
                id: "spk-card-1",
                src: uiImg(locale, "01-dark.png"),
                rect: [48, 792, 582, 150],
                radius: 26,
                elevate: popCards,
                dx: 28,
                dy: -16,
                popScale: 1.22,
                accentBorder: "rgba(76,141,255,0.9)",
              },
              {
                id: "spk-card-2",
                src: uiImg(locale, "01-dark.png"),
                rect: [48, 958, 582, 150],
                radius: 26,
                elevate: popCards,
                dx: 28,
                dy: 14,
                popScale: 1.22,
                accentBorder: "rgba(76,141,255,0.9)",
              },
            ]}
          />
        </div>
      </div>

      {/* Bottom clear callout */}
      <div
        style={{
          position: "absolute",
          left: 100,
          bottom: 42,
          fontFamily: fontMain,
          fontSize: 26,
          fontWeight: 500,
          letterSpacing: locale === "en" ? 0.3 : 1.2,
          color: "#CBD5E1",
          opacity: prog(f, 75, 115),
        }}
      >
        {copy.bottomCallout}
      </div>
    </AbsoluteFill>
  );
};
