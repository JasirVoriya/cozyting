import React from "react";
import { AbsoluteFill, useCurrentFrame } from "remotion";
import {
  C,
  FONT_EN,
  fontForLocale,
  uiImg,
  prog,
  easeInOut,
  easeOut,
  BEAT,
  lerp,
  type PromoLocale,
} from "../theme";
import { LogoMark, Ripple, StagePhone } from "../components";
import { getCopy } from "../i18n";

const PAIN_END = 120; // 2.00s (Bar 1 @ 120 BPM)
const INTRO_DUR = 360; // 6.00s (3 bars @ 120 BPM)

export const Intro: React.FC<{ locale: PromoLocale }> = ({ locale }) => {
  const f = useCurrentFrame();
  const copy = getCopy(locale).intro;
  const pain = copy.painHook;
  const fontMain = fontForLocale(locale);

  // ==================== PART 1: 0.00s – 2.00s (CALM SINGLE-SCREEN PAIN-POINT HOOK) ====================
  if (f < PAIN_END) {
    const enterDiagram = prog(f, 0, 16, easeOut);
    const enterTitle = prog(f, 3, 18, easeOut);
    const enterSub = prog(f, 10, 26, easeOut);
    const exit = prog(f, PAIN_END - 16, PAIN_END, easeInOut);
    const camScale = lerp(0.992, 1.015, f / PAIN_END);
    const blockedPulse = 0.5 + 0.5 * Math.sin(f * 0.14);

    return (
      <AbsoluteFill style={{ background: C.bg, overflow: "hidden" }}>
        {/* Understated centered glow — calm, restrained, generous negative space */}
        <AbsoluteFill
          style={{
            background:
              "radial-gradient(circle at 50% 42%, rgba(229,109,72,0.10) 0%, rgba(76,141,255,0.04) 36%, rgba(11,13,18,0) 68%)",
          }}
        />

        <AbsoluteFill
          style={{
            display: "flex",
            flexDirection: "column",
            alignItems: "center",
            justifyContent: "center",
            padding: "0 100px",
            opacity: 1 - exit,
            transform: `scale(${camScale * lerp(1, 0.96, exit)})`,
            filter: `blur(${exit * 6}px)`,
          }}
        >
          {/* Minimalist Phone --[ x ]-- Speaker Diagram */}
          <div
            style={{
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              gap: 36,
              marginBottom: 56,
              opacity: enterDiagram,
              transform: `translateY(${(1 - enterDiagram) * 18}px)`,
            }}
          >
            {/* Generic non-Xiaomi Android Phone Glyph */}
            <div style={{ display: "flex", flexDirection: "column", alignItems: "center", width: 220 }}>
              <svg width="86" height="118" viewBox="0 0 86 118">
                <rect
                  x="13"
                  y="7"
                  width="60"
                  height="104"
                  rx="14"
                  fill="rgba(22,27,38,0.95)"
                  stroke="rgba(255,255,255,0.28)"
                  strokeWidth="2.5"
                />
                <rect x="20" y="18" width="46" height="78" rx="6" fill="rgba(255,255,255,0.05)" />
                <circle cx="43" cy="102" r="2.8" fill="rgba(255,255,255,0.28)" />
                {/* subtle music note inside phone */}
                <path
                  d="M 41 66 L 41 47 L 53 44 L 53 62"
                  fill="none"
                  stroke="#94A3B8"
                  strokeWidth="2.6"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                />
                <circle cx="37.5" cy="66" r="4" fill="#94A3B8" />
                <circle cx="49.5" cy="62" r="4" fill="#94A3B8" />
              </svg>
              <div
                style={{
                  marginTop: 14,
                  fontFamily: fontMain,
                  fontSize: 22,
                  fontWeight: 600,
                  color: "#CBD5E1",
                  whiteSpace: "nowrap",
                }}
              >
                {pain.phoneLabel}
              </div>
            </div>

            {/* Broken Connection + Coral X Badge */}
            <div style={{ display: "flex", flexDirection: "column", alignItems: "center", marginTop: -28 }}>
              <div style={{ display: "flex", alignItems: "center" }}>
                <div
                  style={{
                    width: 84,
                    height: 2,
                    borderTop: "2.5px dashed rgba(255,255,255,0.22)",
                  }}
                />
                <div
                  style={{
                    width: 58,
                    height: 58,
                    borderRadius: 999,
                    background: "rgba(229,109,72,0.14)",
                    border: `2.5px solid rgba(229,109,72,${0.65 + 0.3 * blockedPulse})`,
                    boxShadow: `0 0 ${18 + 14 * blockedPulse}px rgba(229,109,72,0.32)`,
                    display: "flex",
                    alignItems: "center",
                    justifyContent: "center",
                    margin: "0 10px",
                  }}
                >
                  <svg width="24" height="24" viewBox="0 0 24 24">
                    <path
                      d="M6 6 L18 18 M18 6 L6 18"
                      stroke={C.coral}
                      strokeWidth="3"
                      strokeLinecap="round"
                    />
                  </svg>
                </div>
                <div
                  style={{
                    width: 84,
                    height: 2,
                    borderTop: "2.5px dashed rgba(255,255,255,0.22)",
                  }}
                />
              </div>
              <div
                style={{
                  marginTop: 16,
                  padding: "6px 18px",
                  borderRadius: 999,
                  background: "rgba(229,109,72,0.12)",
                  border: "1px solid rgba(229,109,72,0.45)",
                  fontFamily: fontMain,
                  fontSize: 19,
                  fontWeight: 600,
                  color: "#FDA4AF",
                  whiteSpace: "nowrap",
                }}
              >
                {pain.blockedBadge}
              </div>
            </div>

            {/* Muted Smart Speaker Glyph */}
            <div style={{ display: "flex", flexDirection: "column", alignItems: "center", width: 220 }}>
              <svg width="86" height="118" viewBox="0 0 86 118">
                <rect
                  x="17"
                  y="14"
                  width="52"
                  height="90"
                  rx="20"
                  fill="rgba(22,27,38,0.95)"
                  stroke="rgba(255,255,255,0.28)"
                  strokeWidth="2.5"
                />
                <circle cx="43" cy="68" r="14" fill="none" stroke="rgba(255,255,255,0.32)" strokeWidth="2.6" />
                <circle cx="43" cy="36" r="3.5" fill="rgba(255,255,255,0.32)" />
              </svg>
              <div
                style={{
                  marginTop: 14,
                  fontFamily: fontMain,
                  fontSize: 22,
                  fontWeight: 600,
                  color: "#CBD5E1",
                  whiteSpace: "nowrap",
                }}
              >
                {pain.speakerLabel}
              </div>
            </div>
          </div>

          {/* Single-Sentence Hero Pain-Point Question */}
          <div
            style={{
              fontFamily: fontMain,
              fontSize: pain.questionSize,
              fontWeight: 800,
              color: "#FFFFFF",
              letterSpacing: locale === "en" ? -0.6 : 1.5,
              lineHeight: 1.2,
              textAlign: "center",
              whiteSpace: "nowrap",
              opacity: enterTitle,
              transform: `translateY(${(1 - enterTitle) * 22}px)`,
              textShadow: "0 12px 40px rgba(0,0,0,0.5)",
            }}
          >
            {pain.question}
          </div>

          {/* Calm Supporting Subline */}
          <div
            style={{
              marginTop: 24,
              fontFamily: fontMain,
              fontSize: pain.sublineSize,
              fontWeight: 450,
              color: "#94A3B8",
              letterSpacing: locale === "en" ? 0.2 : 0.8,
              lineHeight: 1.4,
              textAlign: "center",
              opacity: enterSub,
              transform: `translateY(${(1 - enterSub) * 16}px)`,
            }}
          >
            {pain.subline}
          </div>
        </AbsoluteFill>
      </AbsoluteFill>
    );
  }

  // ==================== PART 2: 2.00s – 6.00s (BRAND LOCKUP + REAL HERO UI) ====================
  const bf = f - PAIN_END; // 0..239 (4.00s calm showcase)
  const snapBrand = prog(bf, 0, 16, easeOut);
  const snapTagline = prog(bf, 7, 22, easeOut);
  const snapSub = prog(bf, 13, 28, easeOut);
  const phoneSnap = prog(bf, 4, 28, easeOut);
  const popHeader = prog(bf, 28, 48, easeOut);
  const popPlayer = prog(bf, 36, 58, easeOut);
  const dropFlash = 1 - prog(bf, 0, 12, easeOut);
  const out = prog(bf, INTRO_DUR - PAIN_END - 18, INTRO_DUR - PAIN_END, easeInOut);

  const beatPulse = Math.max(0, Math.sin((bf / BEAT) * Math.PI)) * prog(bf, 0, 18);
  const floatY = Math.sin(bf / 40) * 5;
  const playProgress = prog(bf, 16, 232, (t) => t);
  const sec = 34 + Math.floor(playProgress * 5);
  const pillColors = ["#34D399", C.blue, C.coral] as const;
  const logoSize = 188;

  return (
    <AbsoluteFill style={{ background: C.bg, overflow: "hidden" }}>
      {/* Dual ambient glow: warm lamp amber + coral behind brand, soft ambient behind real hero phone */}
      <AbsoluteFill
        style={{
          background: `radial-gradient(circle at 22% 42%, rgba(255,209,102,${0.14 * snapBrand}) 0%, rgba(229,109,72,${0.12 * snapBrand}) 18%, rgba(229,109,72,0) 42%), radial-gradient(circle at 73% 54%, rgba(76,141,255,${0.16 * phoneSnap}) 0%, rgba(255,209,102,${0.05 * phoneSnap}) 24%, rgba(46,108,246,0) 46%)`,
        }}
      />

      {/* Crisp warm ripples on the 2.0s reveal beat */}
      <svg width="1920" height="1080" style={{ position: "absolute", inset: 0, opacity: 1 - out }}>
        <Ripple cx={202} cy={329} t={bf / 48} maxR={480} color={C.coral} width={2.2} />
        <Ripple cx={202} cy={329} t={(bf - 10) / 54} maxR={560} color={C.warmGold} width={1.4} />
      </svg>

      {/* Left Column: Fully Formed Logo + Brand Wordmark + Tagline + 3 Pills */}
      <div
        style={{
          position: "absolute",
          left: 120,
          top: 235,
          maxWidth: 960,
          opacity: 1 - out,
          filter: `blur(${out * 10}px)`,
        }}
      >
        <div
          style={{
            display: "flex",
            alignItems: "center",
            gap: 28,
            opacity: snapBrand,
            transform: `translateY(${(1 - snapBrand) * 20}px) scale(${lerp(0.94, 1, snapBrand)})`,
            transformOrigin: "left center",
          }}
        >
          <div style={{ width: logoSize, height: logoSize, flexShrink: 0, marginLeft: -12 }}>
            <LogoMark size={logoSize} rings={1} core={1} play={1} arc={1} pulse={beatPulse} />
          </div>
          <div style={{ display: "flex", alignItems: "baseline", gap: 24 }}>
            <div
              style={{
                fontFamily: copy.brandSecondary ? fontMain : FONT_EN,
                fontSize: copy.brandSecondary ? 118 : 108,
                fontWeight: 800,
                color: "#FFFFFF",
                letterSpacing: copy.brandSecondary ? 6 : 1.5,
                lineHeight: 1.05,
              }}
            >
              {copy.brandPrimary}
            </div>
            {copy.brandSecondary ? (
              <div
                style={{
                  fontFamily: FONT_EN,
                  fontSize: 56,
                  fontWeight: 600,
                  color: C.ink2,
                  letterSpacing: 1,
                }}
              >
                {copy.brandSecondary}
              </div>
            ) : null}
          </div>
        </div>

        {/* One-line Value Proposition */}
        <div
          style={{
            marginTop: 38,
            fontFamily: fontMain,
            fontSize: copy.headlineSize,
            fontWeight: 700,
            color: C.ink,
            letterSpacing: locale === "en" ? 0 : 1.2,
            lineHeight: 1.24,
            opacity: snapTagline,
            transform: `translateY(${(1 - snapTagline) * 16}px)`,
          }}
        >
          {copy.headline}
        </div>

        {/* Concise Subtitle */}
        <div
          style={{
            marginTop: 18,
            fontFamily: fontMain,
            fontSize: copy.subtitleSize,
            fontWeight: 450,
            color: C.ink2,
            letterSpacing: locale === "en" ? 0.2 : 0.8,
            lineHeight: 1.4,
            opacity: snapSub,
            transform: `translateY(${(1 - snapSub) * 14}px)`,
          }}
        >
          {copy.subtitle}
        </div>

        {/* 3 Live Status Pills */}
        <div style={{ marginTop: 48, display: "flex", flexWrap: "wrap", gap: 14, maxWidth: 960 }}>
          {copy.pills.map((label, i) => {
            const p = prog(bf, 20 + i * 6, 36 + i * 6, easeOut);
            const accent = pillColors[i];
            return (
              <div
                key={i}
                style={{
                  padding: "12px 24px",
                  borderRadius: 999,
                  background: "rgba(21,26,35,0.88)",
                  border: "1px solid rgba(255,255,255,0.16)",
                  fontFamily: fontMain,
                  fontSize: 21,
                  fontWeight: 500,
                  color: C.ink,
                  opacity: p,
                  transform: `translateY(${(1 - p) * 16}px)`,
                  boxShadow: "0 14px 34px rgba(0,0,0,0.38)",
                  whiteSpace: "nowrap",
                }}
              >
                <span style={{ color: accent, marginRight: 8 }}>●</span>
                {label}
              </div>
            );
          })}
        </div>
      </div>

      {/* Right Column: Hero Real Phone (01-dark.png) with Elevated Top Bar & Album Player Card */}
      <div
        style={{
          position: "absolute",
          left: 1215,
          top: 90,
          perspective: 1800,
          opacity: phoneSnap * (1 - out),
          filter: `blur(${(1 - phoneSnap) * 8 + out * 10}px)`,
        }}
      >
        <div
          style={{
            transform: `translateY(${(1 - phoneSnap) * 84 + floatY}px) rotateY(${lerp(-16, -11, phoneSnap)}deg) rotateX(${lerp(8, 4, phoneSnap)}deg)`,
            transformStyle: "preserve-3d",
          }}
        >
          <StagePhone
            src={uiImg(locale, "01-dark.png")}
            w={430}
            dim={popPlayer * 0.45}
            slices={[
              {
                id: "top-bar",
                rect: [16, 14, 646, 72],
                radius: 22,
                elevate: popHeader,
                dx: -18,
                dy: -6,
                popScale: 1.07,
                accentBorder: "rgba(52,211,153,0.55)",
              },
              {
                id: "album-player",
                rect: [32, 102, 614, 332],
                radius: 28,
                elevate: popPlayer,
                dx: -26,
                dy: 4,
                popScale: 1.09,
                accentBorder: "rgba(76,141,255,0.55)",
                overlay: (
                  <div
                    style={{
                      position: "absolute",
                      left: 22 * (430 / 678),
                      top: (314 - 102) * (430 / 678),
                      width: (218 + playProgress * 55) * (430 / 678),
                      height: 8 * (430 / 678),
                      borderRadius: 99,
                      background: "#FFFFFF",
                      boxShadow: "0 0 12px rgba(255,255,255,0.8)",
                    }}
                  />
                ),
              },
            ]}
          />
          {/* Floating live timestamp pill beside the elevated album player card */}
          <div
            style={{
              position: "absolute",
              left: -252,
              top: 204,
              zIndex: 25,
              padding: "9px 18px",
              borderRadius: 14,
              background: "rgba(15,20,30,0.94)",
              border: "1px solid rgba(76,141,255,0.5)",
              fontFamily: FONT_EN,
              fontSize: 17,
              fontWeight: 600,
              color: "#EAF0F7",
              whiteSpace: "nowrap",
              opacity: popPlayer,
              transform: `translateX(${(1 - popPlayer) * 20}px)`,
              boxShadow: "0 16px 36px rgba(0,0,0,0.5)",
            }}
          >
            01:{sec < 10 ? `0${sec}` : sec} / 04:08 · 48kHz
          </div>
        </div>
      </div>

      {/* Subtle warm reveal glow on 2.00s landing beat */}
      {dropFlash > 0.01 && (
        <AbsoluteFill
          style={{
            pointerEvents: "none",
            background: `radial-gradient(circle at 24% 38%, rgba(229,109,72,${0.18 * dropFlash}) 0%, rgba(255,255,255,${0.08 * dropFlash}) 28%, rgba(0,0,0,0) 68%)`,
          }}
        />
      )}
    </AbsoluteFill>
  );
};
