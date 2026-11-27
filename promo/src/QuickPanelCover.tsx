import React from "react";
import { AbsoluteFill } from "remotion";
import { C, FONT_EN, FONT_MONO, fontForLocale, uiImg, type PromoLocale } from "./theme";
import { LogoMark, StagePhone } from "./components";
import { getCopy } from "./i18n";
import type { CoverAspect } from "./Cover";

export interface QuickPanelCoverProps {
  locale?: PromoLocale;
  aspect?: CoverAspect;
}

const HouseGlyph: React.FC<{ size?: number; color?: string }> = ({ size = 26, color = "#FFFFFF" }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none">
    <path d="M4.2 11.2L12 5.2L19.8 11.2" stroke={color} strokeWidth={2.2} strokeLinecap="round" strokeLinejoin="round" />
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

/**
 * Dedicated Quick Panel feature video cover/thumbnail in 3 platform ratios:
 * - 16:9 (1920x1080) landscape (YouTube / Bilibili / Official Website)
 * - 4:3  (1600x1200) classic feed (Bilibili feed / WeChat)
 * - 3:4  (1200x1600) vertical portrait (Xiaohongshu / Douyin / Channels)
 */
export const QuickPanelCover: React.FC<QuickPanelCoverProps> = ({ locale = "zh", aspect = "16x9" }) => {
  const intro = getCopy(locale).intro;
  const qp = getCopy(locale).quickPanel;
  const copy = getCopy(locale).quickPanelVideo;
  const fontMain = fontForLocale(locale);
  const pillColors = [C.coral, C.blue, C.emerald] as const;

  if (aspect === "3x4") {
    const width = 1200;
    const height = 1600;
    const logoSize = 112;
    const phoneW = 486;
    const bez = phoneW * 0.032;
    const phoneLeft = (width - (phoneW + bez * 2)) / 2;
    const headlineSize = locale === "en" ? 64 : locale === "ja" ? 62 : 76;

    return (
      <AbsoluteFill style={{ background: C.bg, overflow: "hidden", width, height }}>
        <AbsoluteFill
          style={{
            background:
              "radial-gradient(circle at 50% 18%, rgba(229,109,72,0.18) 0%, rgba(255,209,102,0.11) 20%, rgba(229,109,72,0) 46%), radial-gradient(circle at 50% 74%, rgba(76,141,255,0.22) 0%, rgba(52,211,153,0.08) 30%, rgba(46,108,246,0) 56%)",
          }}
        />

        <svg width={width} height={height} style={{ position: "absolute", inset: 0, pointerEvents: "none" }}>
          <circle cx={width / 2} cy={220} r={240} fill="none" stroke="rgba(255,209,102,0.08)" strokeWidth={1.5} />
          <circle cx={width / 2} cy={220} r={390} fill="none" stroke="rgba(229,109,72,0.08)" strokeWidth={1.4} />
        </svg>

        {/* Top Centered Brand + New Feature Badge + Big Headline + Quick Tile Pill */}
        <div
          style={{
            position: "absolute",
            left: 64,
            right: 64,
            top: 74,
            display: "flex",
            flexDirection: "column",
            alignItems: "center",
            textAlign: "center",
          }}
        >
          {/* Compact Brand + NEW Badge row */}
          <div style={{ display: "flex", alignItems: "center", gap: 20 }}>
            <LogoMark size={logoSize} rings={1} core={1} play={1} arc={1} pulse={0.3} />
            <div style={{ display: "flex", flexDirection: "column", alignItems: "flex-start", gap: 8 }}>
              <div
                style={{
                  padding: "6px 18px",
                  borderRadius: 999,
                  background: "rgba(229,109,72,0.20)",
                  border: "1.5px solid rgba(229,109,72,0.68)",
                  fontFamily: fontMain,
                  fontSize: 20,
                  fontWeight: 700,
                  color: "#FFA687",
                  letterSpacing: 1,
                }}
              >
                {copy.coverBadge}
              </div>
              <div style={{ display: "flex", alignItems: "baseline", gap: 14 }}>
                <span
                  style={{
                    fontFamily: intro.brandSecondary ? fontMain : FONT_EN,
                    fontSize: 54,
                    fontWeight: 800,
                    color: "#FFFFFF",
                    lineHeight: 1.05,
                  }}
                >
                  {intro.brandPrimary}
                </span>
                {intro.brandSecondary ? (
                  <span style={{ fontFamily: FONT_EN, fontSize: 30, fontWeight: 600, color: C.ink2 }}>
                    {intro.brandSecondary}
                  </span>
                ) : null}
              </div>
            </div>
          </div>

          {/* Big Scannable 2-Line Headline */}
          <div
            style={{
              marginTop: 34,
              fontFamily: fontMain,
              fontSize: headlineSize,
              fontWeight: 800,
              color: "#FFFFFF",
              lineHeight: 1.18,
              letterSpacing: locale === "en" ? -1 : 1.5,
            }}
          >
            <div>{copy.coverHeadline3x4[0]}</div>
            <div style={{ color: "#FFE6C7" }}>{copy.coverHeadline3x4[1]}</div>
          </div>

          {/* Concrete Quick Settings Tile + Notification Chip */}
          <div style={{ marginTop: 34, display: "flex", alignItems: "center", justifyContent: "center", gap: 18 }}>
            <div
              style={{
                height: 76,
                borderRadius: 22,
                background: "linear-gradient(135deg, #E56D48 0%, #D95938 100%)",
                boxShadow: "0 14px 32px rgba(229,109,72,0.40)",
                padding: "0 24px",
                display: "flex",
                alignItems: "center",
                gap: 14,
              }}
            >
              <div
                style={{
                  width: 42,
                  height: 42,
                  borderRadius: 999,
                  background: "rgba(255,255,255,0.20)",
                  display: "flex",
                  alignItems: "center",
                  justifyContent: "center",
                }}
              >
                <HouseGlyph size={24} color="#FFFFFF" />
              </div>
              <div style={{ textAlign: "left" }}>
                <div style={{ fontFamily: fontMain, fontSize: 21, fontWeight: 700, color: "#FFFFFF", lineHeight: 1.15 }}>
                  {qp.tileTitle}
                </div>
                <div style={{ fontFamily: fontMain, fontSize: 15, fontWeight: 600, color: "rgba(255,255,255,0.9)", marginTop: 2 }}>
                  {qp.tileSub}
                </div>
              </div>
            </div>

            <div
              style={{
                height: 76,
                borderRadius: 22,
                background: "rgba(21,28,44,0.92)",
                border: "1.5px solid rgba(76,141,255,0.68)",
                padding: "0 24px",
                display: "flex",
                alignItems: "center",
                gap: 12,
                fontFamily: fontMain,
                fontSize: 21,
                fontWeight: 700,
                color: "#E2E8F0",
                whiteSpace: "nowrap",
              }}
            >
              <span
                style={{
                  width: 11,
                  height: 11,
                  borderRadius: 999,
                  background: C.blue,
                  boxShadow: "0 0 10px rgba(76,141,255,0.9)",
                }}
              />
              {copy.coverPills[1]}
            </div>
          </div>
        </div>

        {/* Bottom Centered Hero Floating Quick Panel Phone + Hardware Volume Callout */}
        <div style={{ position: "absolute", left: phoneLeft, top: 618, perspective: 1800 }}>
          <div style={{ position: "relative", transform: "rotateX(3deg)", transformStyle: "preserve-3d" }}>
            <StagePhone src={uiImg(locale, "09-dark.png")} w={phoneW} />
            <div
              style={{
                position: "absolute",
                right: -13,
                top: 290,
                width: 12,
                height: 132,
                borderRadius: "0 6px 6px 0",
                background: C.emerald,
                boxShadow: "0 0 24px rgba(52,211,153,0.95)",
              }}
            />
            <div
              style={{
                position: "absolute",
                right: -190,
                top: 316,
                padding: "11px 20px",
                borderRadius: 16,
                background: "rgba(15,23,42,0.94)",
                border: "2px solid rgba(52,211,153,0.92)",
                boxShadow: "0 16px 36px rgba(0,0,0,0.55)",
                display: "flex",
                alignItems: "center",
                gap: 10,
              }}
            >
              <span style={{ fontFamily: FONT_MONO, fontSize: 19, fontWeight: 800, color: "#6EE7B7" }}>VOL +</span>
              <span style={{ fontFamily: FONT_MONO, fontSize: 21, fontWeight: 800, color: "#FFFFFF" }}>68%</span>
            </div>
          </div>
        </div>

        <AbsoluteFill
          style={{
            pointerEvents: "none",
            background: "radial-gradient(ellipse 84% 80% at 50% 48%, rgba(0,0,0,0) 58%, rgba(0,0,0,0.46) 100%)",
          }}
        />
      </AbsoluteFill>
    );
  }

  // 16:9 (1920x1080) & 4:3 (1600x1200)
  const is4x3 = aspect === "4x3";
  const width = is4x3 ? 1600 : 1920;
  const height = is4x3 ? 1200 : 1080;

  const logoSize = is4x3 ? 116 : 126;
  const leftX = is4x3 ? 82 : 110;
  const leftTop = is4x3 ? 136 : 112;
  const leftMaxW = is4x3 ? 820 : 1010;
  const headlineSize = is4x3
    ? locale === "en"
      ? 64
      : locale === "ja"
        ? 62
        : 76
    : locale === "en"
      ? 74
      : locale === "ja"
        ? 68
        : 84;

  const phoneW = is4x3 ? 416 : 426;
  const phoneX = is4x3 ? 954 : 1218;
  const phoneY = is4x3 ? 110 : 66;

  return (
    <AbsoluteFill style={{ background: C.bg, overflow: "hidden", width, height }}>
      <AbsoluteFill
        style={{
          background: is4x3
            ? "radial-gradient(circle at 22% 30%, rgba(229,109,72,0.18) 0%, rgba(255,209,102,0.11) 20%, rgba(229,109,72,0) 46%), radial-gradient(circle at 78% 54%, rgba(76,141,255,0.20) 0%, rgba(52,211,153,0.08) 28%, rgba(46,108,246,0) 52%)"
            : "radial-gradient(circle at 20% 34%, rgba(229,109,72,0.18) 0%, rgba(255,209,102,0.11) 18%, rgba(229,109,72,0) 44%), radial-gradient(circle at 74% 52%, rgba(76,141,255,0.20) 0%, rgba(52,211,153,0.08) 26%, rgba(46,108,246,0) 50%)",
        }}
      />

      <svg width={width} height={height} style={{ position: "absolute", inset: 0, pointerEvents: "none" }}>
        <circle cx={leftX + logoSize * 0.5} cy={leftTop + logoSize * 0.5} r={220} fill="none" stroke="rgba(255,209,102,0.08)" strokeWidth={1.5} />
        <circle cx={leftX + logoSize * 0.5} cy={leftTop + logoSize * 0.5} r={370} fill="none" stroke="rgba(229,109,72,0.08)" strokeWidth={1.4} />
      </svg>

      {/* Left Column */}
      <div style={{ position: "absolute", left: leftX, top: leftTop, maxWidth: leftMaxW }}>
        {/* Brand + New Feature Badge */}
        <div style={{ display: "flex", alignItems: "center", gap: 22 }}>
          <LogoMark size={logoSize} rings={1} core={1} play={1} arc={1} pulse={0.35} />
          <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
            <div
              style={{
                alignSelf: "flex-start",
                padding: "6px 18px",
                borderRadius: 999,
                background: "rgba(229,109,72,0.20)",
                border: "1.5px solid rgba(229,109,72,0.72)",
                fontFamily: fontMain,
                fontSize: is4x3 ? 20 : 21,
                fontWeight: 700,
                color: "#FFA687",
                letterSpacing: 1,
              }}
            >
              {copy.coverBadge}
            </div>
            <div style={{ display: "flex", alignItems: "baseline", gap: 16 }}>
              <span
                style={{
                  fontFamily: intro.brandSecondary ? fontMain : FONT_EN,
                  fontSize: is4x3 ? 56 : 62,
                  fontWeight: 800,
                  color: "#FFFFFF",
                  lineHeight: 1.05,
                }}
              >
                {intro.brandPrimary}
              </span>
              {intro.brandSecondary ? (
                <span style={{ fontFamily: FONT_EN, fontSize: is4x3 ? 30 : 34, fontWeight: 600, color: C.ink2 }}>
                  {intro.brandSecondary}
                </span>
              ) : null}
            </div>
          </div>
        </div>

        {/* Big 2-Line Semantic Headline (zero orphan-word wrap across zh/en/ja) */}
        <div
          style={{
            marginTop: is4x3 ? 44 : 38,
            fontFamily: fontMain,
            fontSize: headlineSize,
            fontWeight: 800,
            color: "#FFFFFF",
            lineHeight: 1.15,
            letterSpacing: locale === "en" ? -1.2 : 1.5,
          }}
        >
          <div>{copy.coverHeadline3x4[0]}</div>
          <div style={{ color: "#FFE6C7" }}>{copy.coverHeadline3x4[1]}</div>
        </div>

        {/* Concise Subtitle */}
        <div
          style={{
            marginTop: is4x3 ? 22 : 18,
            fontFamily: fontMain,
            fontSize: is4x3 ? 22 : 24,
            fontWeight: 450,
            color: "#B8C3D4",
            lineHeight: 1.45,
            maxWidth: is4x3 ? 780 : 960,
          }}
        >
          {copy.coverSubtitle}
        </div>

        {/* Concrete Quick Settings Tile Preview */}
        <div style={{ marginTop: is4x3 ? 46 : 40, display: "flex", alignItems: "center", gap: 18 }}>
          <div
            style={{
              height: 88,
              borderRadius: 24,
              background: "linear-gradient(135deg, #E56D48 0%, #D95938 100%)",
              boxShadow: "0 16px 36px rgba(229,109,72,0.40)",
              padding: "0 26px",
              display: "flex",
              alignItems: "center",
              gap: 16,
            }}
          >
            <div
              style={{
                width: 48,
                height: 48,
                borderRadius: 999,
                background: "rgba(255,255,255,0.20)",
                display: "flex",
                alignItems: "center",
                justifyContent: "center",
              }}
            >
              <HouseGlyph size={28} color="#FFFFFF" />
            </div>
            <div>
              <div style={{ fontFamily: fontMain, fontSize: 23, fontWeight: 700, color: "#FFFFFF" }}>{qp.tileTitle}</div>
              <div style={{ fontFamily: fontMain, fontSize: 16, fontWeight: 600, color: "rgba(255,255,255,0.9)", marginTop: 2 }}>
                {qp.tileSub}
              </div>
            </div>
          </div>

          <div
            style={{
              height: 88,
              borderRadius: 24,
              background: "rgba(18,24,38,0.92)",
              border: "1.5px solid rgba(76,141,255,0.68)",
              padding: "0 26px",
              display: "flex",
              alignItems: "center",
              gap: 14,
            }}
          >
            <div
              style={{
                width: 44,
                height: 44,
                borderRadius: 14,
                background: "rgba(76,141,255,0.22)",
                display: "flex",
                alignItems: "center",
                justifyContent: "center",
              }}
            >
              <HouseGlyph size={24} color="#93C5FD" />
            </div>
            <div>
              <div style={{ fontFamily: fontMain, fontSize: 20, fontWeight: 700, color: "#FFFFFF" }}>{qp.notifAction}</div>
              <div style={{ fontFamily: fontMain, fontSize: 15, fontWeight: 500, color: "#CBD5E1", marginTop: 2 }}>
                {copy.coverPills[1]}
              </div>
            </div>
          </div>
        </div>

        {/* 3 Clean Capability Pills */}
        <div style={{ marginTop: is4x3 ? 42 : 34, display: "flex", flexWrap: "wrap", gap: 14 }}>
          {copy.coverPills.map((label, i) => {
            const accent = pillColors[i];
            return (
              <div
                key={i}
                style={{
                  padding: "11px 22px",
                  borderRadius: 999,
                  background: "rgba(21,26,36,0.90)",
                  border: "1px solid rgba(255,255,255,0.16)",
                  fontFamily: fontMain,
                  fontSize: 20,
                  fontWeight: 600,
                  color: C.ink,
                  display: "flex",
                  alignItems: "center",
                  gap: 10,
                  whiteSpace: "nowrap",
                }}
              >
                <span
                  style={{
                    width: 10,
                    height: 10,
                    borderRadius: 999,
                    background: accent,
                    boxShadow: `0 0 10px ${accent}`,
                  }}
                />
                {label}
              </div>
            );
          })}
        </div>
      </div>

      {/* Right Column: Hero Real Floating Quick Panel Phone + Hardware Volume Rocker */}
      <div style={{ position: "absolute", left: phoneX, top: phoneY, perspective: 1800 }}>
        <div style={{ position: "relative", transform: `rotateY(${is4x3 ? -8 : -9}deg) rotateX(3deg)`, transformStyle: "preserve-3d" }}>
          <StagePhone src={uiImg(locale, "09-dark.png")} w={phoneW} />
          <div
            style={{
              position: "absolute",
              right: -13,
              top: 256,
              width: 11,
              height: 120,
              borderRadius: "0 6px 6px 0",
              background: C.emerald,
              boxShadow: "0 0 24px rgba(52,211,153,0.95)",
            }}
          />
          <div
            style={{
              position: "absolute",
              right: is4x3 ? -174 : -186,
              top: is4x3 ? 272 : 278,
              padding: "10px 18px",
              borderRadius: 16,
              background: "rgba(15,23,42,0.95)",
              border: "2px solid rgba(52,211,153,0.92)",
              boxShadow: "0 16px 38px rgba(0,0,0,0.58)",
              display: "flex",
              alignItems: "center",
              gap: 9,
              whiteSpace: "nowrap",
            }}
          >
            <span style={{ fontFamily: FONT_MONO, fontSize: 18, fontWeight: 800, color: "#6EE7B7" }}>VOL +</span>
            <span style={{ fontFamily: FONT_MONO, fontSize: 20, fontWeight: 800, color: "#FFFFFF" }}>68%</span>
          </div>
        </div>
      </div>

      <AbsoluteFill
        style={{
          pointerEvents: "none",
          background: "radial-gradient(ellipse 80% 78% at 50% 48%, rgba(0,0,0,0) 58%, rgba(0,0,0,0.48) 100%)",
        }}
      />
    </AbsoluteFill>
  );
};
