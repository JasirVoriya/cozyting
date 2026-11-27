import React from "react";
import { AbsoluteFill, useCurrentFrame } from "remotion";
import { C, FONT_MONO, fontForLocale, uiImg, prog, easeInOut, easeOut, sceneFade, lerp, type PromoLocale } from "../theme";
import { Eyebrow, Reveal, StagePhone, TapRipple } from "../components";
import { getCopy } from "../i18n";

/** Minimal crisp monochrome cozy-house glyph matching Android ic_stat_miplay.xml */
const HouseGlyph: React.FC<{ size?: number; color?: string }> = ({ size = 28, color = "#FFFFFF" }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none">
    <path
      d="M4.2 11.2L12 5.2L19.8 11.2"
      stroke={color}
      strokeWidth={2.2}
      strokeLinecap="round"
      strokeLinejoin="round"
    />
    <path
      d="M6.4 10.6V17.8C6.4 19 7.3 19.8 8.5 19.8H15.5C16.7 19.8 17.6 19 17.6 17.8V10.6"
      stroke={color}
      strokeWidth={2}
      strokeLinecap="round"
      strokeLinejoin="round"
    />
    <circle cx={12} cy={14.5} r={2.2} fill={color} />
    <path d="M16.2 4.8V7.4" stroke={color} strokeWidth={2} strokeLinecap="round" />
  </svg>
);

export const QuickPanel: React.FC<{ dur: number; locale: PromoLocale }> = ({ dur, locale }) => {
  const f = useCurrentFrame();
  const copy = getCopy(locale).quickPanel;
  const fontMain = fontForLocale(locale);

  // Staggered entrance synced to 3-bar (360f) beat grid:
  // Bar 0 (0..120): Quick Settings Tile + Notification cards enter & get tapped
  // Bar 1 (120..240): Floating QuickCastPanel phone springs into focus
  // Bar 2 (240..360): Hardware Volume + key presses twice -> 58% -> 63% -> 68%
  const tileIn = prog(f, 8, 44, easeOut);
  const notifIn = prog(f, 34, 72, easeOut);
  const volCardIn = prog(f, 185, 226, easeOut);

  const phoneIn = prog(f, 16, 62, easeOut);
  const panelPop = prog(f, 92, 134, easeOut);

  // Two crisp hardware Volume+ presses at f=258 (4.3s) and f=288 (4.8s)
  const press1 = prog(f, 254, 263, easeOut) * (1 - prog(f, 264, 276, easeInOut));
  const press2 = prog(f, 284, 293, easeOut) * (1 - prog(f, 294, 308, easeInOut));
  const volPress = Math.max(press1, press2);
  const volPct = Math.round(
    58 + 5 * prog(f, 256, 274, easeOut) + 5 * prog(f, 286, 306, easeOut)
  );
  const volBadgePop = prog(f, 246, 266, easeOut);

  const floatY = Math.sin(f / 32) * 5;
  const cardGlow = prog(f, 110, 150, easeOut);

  return (
    <AbsoluteFill style={{ background: C.bg, opacity: sceneFade(f, dur, 10, 14), overflow: "hidden" }}>
      {/* Ambient warm coral + cool blue depth glow */}
      <AbsoluteFill
        style={{
          background:
            "radial-gradient(circle at 73% 56%, rgba(76,141,255,0.22) 0%, rgba(76,141,255,0) 44%), radial-gradient(circle at 26% 52%, rgba(229,109,72,0.16) 0%, rgba(229,109,72,0) 46%)",
        }}
      />

      {/* Header */}
      <div style={{ position: "absolute", left: 100, top: 58, zIndex: 20 }}>
        <Eyebrow text={copy.eyebrow} start={4} />
        <div style={{ marginTop: 12 }}>
          <Reveal
            text={copy.headline}
            start={10}
            size={copy.headlineSize}
            weight={700}
            font={fontMain}
            stagger={locale === "en" ? 0.9 : 1.5}
          />
        </div>
        <div style={{ marginTop: 10 }}>
          <Reveal
            text={copy.subtitle}
            start={32}
            size={copy.subtitleSize}
            weight={450}
            font={fontMain}
            color="#CBD5E1"
            stagger={0.4}
          />
        </div>
      </div>

      {/* Left Column: 3 Real Entry & Interaction Cards */}
      <div
        style={{
          position: "absolute",
          left: 100,
          top: 266,
          width: 790,
          display: "flex",
          flexDirection: "column",
          gap: 24,
          zIndex: 20,
        }}
      >
        {/* Card 1: Android Quick Settings Tile */}
        <div
          style={{
            opacity: tileIn,
            transform: `translateX(${(1 - tileIn) * -36}px) scale(${0.96 + tileIn * 0.04})`,
            padding: "20px 24px",
            borderRadius: 28,
            background: "rgba(17,22,34,0.92)",
            border: "2px solid rgba(229,109,72,0.78)",
            boxShadow: "0 24px 60px rgba(0,0,0,0.52), 0 0 32px rgba(229,109,72,0.16)",
            position: "relative",
          }}
        >
          <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: 14, gap: 12 }}>
            <div
              style={{
                padding: "5px 15px",
                borderRadius: 999,
                background: "rgba(229,109,72,0.22)",
                fontFamily: fontMain,
                fontSize: 19,
                fontWeight: 700,
                color: "#FCA588",
                whiteSpace: "nowrap",
              }}
            >
              {copy.tileBadge}
            </div>
            <div style={{ fontFamily: fontMain, fontSize: 19, fontWeight: 600, color: "#E2E8F0", whiteSpace: "nowrap" }}>
              {copy.tileHint}
            </div>
          </div>

          {/* Realistic Android Quick Settings Tile row */}
          <div style={{ display: "flex", alignItems: "center", gap: 16 }}>
            <div
              style={{
                position: "relative",
                flex: "0 0 330px",
                height: 82,
                borderRadius: 24,
                background: "linear-gradient(135deg, #E56D48 0%, #D95938 100%)",
                boxShadow: "0 12px 28px rgba(229,109,72,0.38)",
                padding: "0 22px",
                display: "flex",
                alignItems: "center",
                gap: 16,
              }}
            >
              <div
                style={{
                  width: 46,
                  height: 46,
                  borderRadius: 999,
                  background: "rgba(255,255,255,0.18)",
                  display: "flex",
                  alignItems: "center",
                  justifyContent: "center",
                  flexShrink: 0,
                }}
              >
                <HouseGlyph size={28} color="#FFFFFF" />
              </div>
              <div style={{ overflow: "hidden" }}>
                <div style={{ fontFamily: fontMain, fontSize: 22, fontWeight: 700, color: "#FFFFFF", lineHeight: 1.2 }}>
                  {copy.tileTitle}
                </div>
                <div style={{ fontFamily: fontMain, fontSize: 16, fontWeight: 600, color: "rgba(255,255,255,0.88)", marginTop: 3 }}>
                  {copy.tileSub}
                </div>
              </div>
              <TapRipple x={165} y={41} start={58} color="#FFFFFF" />
            </div>

            {/* Subtle inactive neighbor tiles for authentic Quick Settings shade feel */}
            <div
              style={{
                flex: 1,
                height: 82,
                borderRadius: 24,
                background: "rgba(255,255,255,0.06)",
                border: "1px solid rgba(255,255,255,0.09)",
                padding: "0 18px",
                display: "flex",
                alignItems: "center",
                gap: 14,
                opacity: 0.72,
              }}
            >
              <div style={{ width: 38, height: 38, borderRadius: 999, background: "rgba(255,255,255,0.12)" }} />
              <div style={{ display: "flex", flexDirection: "column", gap: 8, flex: 1 }}>
                <div style={{ width: "68%", height: 11, borderRadius: 6, background: "rgba(255,255,255,0.22)" }} />
                <div style={{ width: "44%", height: 9, borderRadius: 6, background: "rgba(255,255,255,0.12)" }} />
              </div>
            </div>
          </div>
        </div>

        {/* Card 2: Ongoing Casting Notification Direct Entry */}
        <div
          style={{
            opacity: notifIn,
            transform: `translateX(${(1 - notifIn) * -36}px) scale(${0.96 + notifIn * 0.04})`,
            padding: "20px 24px",
            borderRadius: 28,
            background: "rgba(17,22,34,0.92)",
            border: "2px solid rgba(76,141,255,0.78)",
            boxShadow: "0 24px 60px rgba(0,0,0,0.52), 0 0 32px rgba(76,141,255,0.16)",
            position: "relative",
          }}
        >
          <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: 14, gap: 12 }}>
            <div
              style={{
                padding: "5px 15px",
                borderRadius: 999,
                background: "rgba(76,141,255,0.22)",
                fontFamily: fontMain,
                fontSize: 19,
                fontWeight: 700,
                color: "#93C5FD",
                whiteSpace: "nowrap",
              }}
            >
              {copy.notifBadge}
            </div>
            <div style={{ fontFamily: fontMain, fontSize: 19, fontWeight: 600, color: "#E2E8F0", whiteSpace: "nowrap" }}>
              {copy.notifHint}
            </div>
          </div>

          <div
            style={{
              position: "relative",
              borderRadius: 20,
              background: "rgba(255,255,255,0.06)",
              border: "1px solid rgba(255,255,255,0.12)",
              padding: "16px 20px",
              display: "flex",
              alignItems: "center",
              justifyContent: "space-between",
              gap: 16,
            }}
          >
            <div style={{ display: "flex", alignItems: "center", gap: 14, minWidth: 0 }}>
              <div
                style={{
                  width: 44,
                  height: 44,
                  borderRadius: 14,
                  background: "rgba(76,141,255,0.22)",
                  border: "1px solid rgba(76,141,255,0.5)",
                  display: "flex",
                  alignItems: "center",
                  justifyContent: "center",
                  flexShrink: 0,
                }}
              >
                <HouseGlyph size={24} color="#93C5FD" />
              </div>
              <div style={{ minWidth: 0 }}>
                <div style={{ fontFamily: fontMain, fontSize: 20, fontWeight: 700, color: "#FFFFFF" }}>
                  {copy.notifTitle}
                </div>
                <div style={{ fontFamily: fontMain, fontSize: 16, fontWeight: 500, color: "#CBD5E1", marginTop: 3 }}>
                  {copy.notifSub}
                </div>
              </div>
            </div>
            <div
              style={{
                padding: "9px 18px",
                borderRadius: 999,
                background: "rgba(76,141,255,0.26)",
                border: "1.5px solid rgba(147,197,253,0.65)",
                fontFamily: fontMain,
                fontSize: 17,
                fontWeight: 700,
                color: "#FFFFFF",
                whiteSpace: "nowrap",
              }}
            >
              {copy.notifAction}
            </div>
            <TapRipple x={360} y={42} start={86} color="#60A5FA" />
          </div>
        </div>

        {/* Card 3: Hardware Volume Key Direct Control */}
        <div
          style={{
            opacity: volCardIn,
            transform: `translateY(${(1 - volCardIn) * 26}px) scale(${0.96 + volCardIn * 0.04})`,
            padding: "20px 24px",
            borderRadius: 28,
            background: "rgba(17,22,34,0.94)",
            border: `2px solid ${volPress > 0.1 ? "rgba(52,211,153,0.95)" : "rgba(52,211,153,0.65)"}`,
            boxShadow: `0 24px 60px rgba(0,0,0,0.52), 0 0 ${28 + volPress * 26}px rgba(52,211,153,${0.16 + volPress * 0.22})`,
          }}
        >
          <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: 14, gap: 12 }}>
            <div
              style={{
                padding: "5px 15px",
                borderRadius: 999,
                background: "rgba(52,211,153,0.2)",
                fontFamily: fontMain,
                fontSize: 19,
                fontWeight: 700,
                color: "#6EE7B7",
                whiteSpace: "nowrap",
              }}
            >
              {copy.volBadge}
            </div>
            <div style={{ fontFamily: fontMain, fontSize: 19, fontWeight: 600, color: "#E2E8F0", whiteSpace: "nowrap" }}>
              {copy.volHint}
            </div>
          </div>

          <div style={{ display: "flex", alignItems: "center", gap: 18 }}>
            <div
              style={{
                padding: "8px 16px",
                borderRadius: 12,
                background: volPress > 0.1 ? "#34D399" : "rgba(255,255,255,0.1)",
                color: volPress > 0.1 ? "#0B0D12" : "#F8FAFC",
                fontFamily: FONT_MONO,
                fontSize: 19,
                fontWeight: 800,
                transform: `scale(${1 + volPress * 0.08})`,
                whiteSpace: "nowrap",
              }}
            >
              VOL + / −
            </div>
            <div style={{ flex: 1 }}>
              <div style={{ display: "flex", justifyContent: "space-between", marginBottom: 8 }}>
                <span style={{ fontFamily: fontMain, fontSize: 17, fontWeight: 600, color: "#CBD5E1" }}>
                  {copy.volLabel}
                </span>
                <span style={{ fontFamily: FONT_MONO, fontSize: 20, fontWeight: 800, color: "#6EE7B7" }}>
                  {volPct}%
                </span>
              </div>
              <div style={{ width: "100%", height: 10, borderRadius: 999, background: "rgba(255,255,255,0.12)", overflow: "hidden" }}>
                <div
                  style={{
                    width: `${volPct}%`,
                    height: "100%",
                    borderRadius: 999,
                    background: "linear-gradient(90deg, #3B82F6 0%, #34D399 100%)",
                  }}
                />
              </div>
            </div>
          </div>
        </div>
      </div>

      {/* Right Stage: Real QuickCastPanel Phone + Animated Hardware Volume Rocker */}
      <div
        style={{
          position: "absolute",
          left: 1120,
          top: 112,
          width: 680,
          height: 900,
          display: "flex",
          flexDirection: "column",
          alignItems: "center",
          justifyContent: "center",
          opacity: phoneIn,
          transform: `translateY(${(1 - phoneIn) * 60 + floatY}px)`,
          zIndex: 18,
        }}
      >
        {/* Subtle glowing halo behind floating panel */}
        <div
          style={{
            position: "absolute",
            width: 520,
            height: 520,
            borderRadius: 999,
            background: `radial-gradient(circle, rgba(76,141,255,${0.26 * cardGlow}) 0%, rgba(229,109,72,${0.14 * cardGlow}) 45%, rgba(0,0,0,0) 72%)`,
            filter: "blur(20px)",
          }}
        />

        <div style={{ position: "relative", perspective: 2000 }}>
          <div
            style={{
              transform: `rotateY(${lerp(-10, -4, panelPop)}deg) scale(${0.92 + panelPop * 0.08})`,
              transformStyle: "preserve-3d",
              position: "relative",
            }}
          >
            <StagePhone src={uiImg(locale, "09-dark.png")} w={388} />

            {/* Highlighted Hardware Volume Rocker indicator on the right edge of the phone */}
            <div
              style={{
                position: "absolute",
                right: -13,
                top: 235,
                width: 10,
                height: 112,
                borderRadius: "0 6px 6px 0",
                background: volPress > 0.1 ? "#34D399" : "rgba(148,163,184,0.55)",
                boxShadow: volPress > 0.1 ? "0 0 22px rgba(52,211,153,0.95)" : "none",
                transform: `translateX(${volPress * -3}px)`,
              }}
            />

            {/* Floating callout badge next to hardware volume button when pressed */}
            {volBadgePop > 0.01 && (
              <div
                style={{
                  position: "absolute",
                  right: -188,
                  top: 256,
                  opacity: volBadgePop,
                  transform: `translateX(${(1 - volBadgePop) * 18}px) scale(${1 + volPress * 0.06})`,
                  padding: "10px 18px",
                  borderRadius: 16,
                  background: "rgba(15,23,42,0.94)",
                  border: "2px solid rgba(52,211,153,0.9)",
                  boxShadow: "0 14px 36px rgba(0,0,0,0.55)",
                  display: "flex",
                  alignItems: "center",
                  gap: 10,
                  whiteSpace: "nowrap",
                }}
              >
                <span style={{ fontFamily: FONT_MONO, fontSize: 18, fontWeight: 800, color: "#6EE7B7" }}>
                  VOL +
                </span>
                <span style={{ fontFamily: FONT_MONO, fontSize: 20, fontWeight: 800, color: "#FFFFFF" }}>
                  {volPct}%
                </span>
              </div>
            )}
          </div>
        </div>

        {/* Bottom context caption under the phone */}
        <div
          style={{
            marginTop: 22,
            padding: "9px 22px",
            borderRadius: 999,
            background: "rgba(21,26,37,0.88)",
            border: "1.5px solid rgba(255,255,255,0.16)",
            fontFamily: fontMain,
            fontSize: 19,
            fontWeight: 600,
            color: "#E2E8F0",
            opacity: panelPop,
            whiteSpace: "nowrap",
          }}
        >
          {copy.phoneTag}
        </div>
      </div>

      {/* Bottom Left Footer Pill */}
      <div
        style={{
          position: "absolute",
          left: 100,
          bottom: 42,
          padding: "10px 24px",
          borderRadius: 999,
          background: "rgba(21,26,37,0.86)",
          border: "1.5px solid rgba(255,255,255,0.15)",
          fontFamily: fontMain,
          fontSize: 20,
          fontWeight: 600,
          color: "#CBD5E1",
          opacity: prog(f, 55, 95, easeOut),
        }}
      >
        {copy.footerPill}
      </div>
    </AbsoluteFill>
  );
};
