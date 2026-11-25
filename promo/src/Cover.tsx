import React from "react";
import { AbsoluteFill } from "remotion";
import { C, FONT_EN, fontForLocale, uiImg, type PromoLocale } from "./theme";
import { LogoMark, StagePhone } from "./components";
import { getCopy } from "./i18n";

export type CoverAspect = "16x9" | "4x3" | "3x4";

export interface CoverProps {
  locale?: PromoLocale;
  aspect?: CoverAspect;
}

/**
 * Dedicated promo video cover/thumbnail:
 * - 16:9 (1920x1080) landscape
 * - 4:3  (1600x1200) classic feed
 * - 3:4  (1200x1600) vertical portrait
 * Brand-minimalist warm-lamp dark aesthetic aligned with CozyTing's promo film.
 */
export const Cover: React.FC<CoverProps> = ({ locale = "zh", aspect = "16x9" }) => {
  const copy = getCopy(locale).intro;
  const fontMain = fontForLocale(locale);
  const pillColors = [C.emerald, C.blue, C.coral] as const;

  if (aspect === "3x4") {
    const width = 1200;
    const height = 1600;
    const logoSize = 152;
    const phoneW = 504;
    const bez = phoneW * 0.032;
    const phoneLeft = (width - (phoneW + bez * 2)) / 2;
    const headlineSize = locale === "en" ? 42 : locale === "ja" ? 45 : 48;

    return (
      <AbsoluteFill style={{ background: C.bg, overflow: "hidden", width, height }}>
        {/* Vertical ambient glow: warm lamp behind top brand, soft blue-amber aura behind hero phone */}
        <AbsoluteFill
          style={{
            background:
              "radial-gradient(circle at 50% 17%, rgba(255,209,102,0.16) 0%, rgba(229,109,72,0.13) 18%, rgba(229,109,72,0) 44%), radial-gradient(circle at 50% 72%, rgba(76,141,255,0.18) 0%, rgba(255,209,102,0.06) 28%, rgba(46,108,246,0) 52%)",
          }}
        />

        {/* Restrained concentric acoustic rings behind top brand header */}
        <svg width={width} height={height} style={{ position: "absolute", inset: 0, pointerEvents: "none" }}>
          <circle cx={width / 2} cy={210} r={220} fill="none" stroke="rgba(255,209,102,0.085)" strokeWidth={1.6} />
          <circle cx={width / 2} cy={210} r={360} fill="none" stroke="rgba(229,109,72,0.085)" strokeWidth={1.4} />
          <circle cx={width / 2} cy={210} r={510} fill="none" stroke="rgba(255,255,255,0.045)" strokeWidth={1.2} />
        </svg>

        {/* Top Centered Brand Block */}
        <div
          style={{
            position: "absolute",
            left: 72,
            right: 72,
            top: 96,
            display: "flex",
            flexDirection: "column",
            alignItems: "center",
            textAlign: "center",
          }}
        >
          {/* Logo + Wordmark */}
          <div style={{ display: "flex", alignItems: "center", justifyContent: "center", gap: 26 }}>
            <div style={{ width: logoSize, height: logoSize, flexShrink: 0 }}>
              <LogoMark size={logoSize} rings={1} core={1} play={1} arc={1} pulse={0.35} />
            </div>
            <div style={{ display: "flex", alignItems: "baseline", gap: 18 }}>
              <div
                style={{
                  fontFamily: copy.brandSecondary ? fontMain : FONT_EN,
                  fontSize: copy.brandSecondary ? 102 : 94,
                  fontWeight: 800,
                  color: "#FFFFFF",
                  letterSpacing: copy.brandSecondary ? 5 : 1.5,
                  lineHeight: 1.05,
                }}
              >
                {copy.brandPrimary}
              </div>
              {copy.brandSecondary ? (
                <div
                  style={{
                    fontFamily: FONT_EN,
                    fontSize: 44,
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

          {/* Official Brand Slogan */}
          <div
            style={{
              marginTop: 42,
              maxWidth: 1020,
              fontFamily: fontMain,
              fontSize: headlineSize,
              fontWeight: 700,
              color: "#F6F7F9",
              letterSpacing: locale === "en" ? -0.3 : 1.2,
              lineHeight: 1.26,
            }}
          >
            {copy.headline}
          </div>

          {/* Concise Subtitle */}
          <div
            style={{
              marginTop: 18,
              maxWidth: 920,
              fontFamily: fontMain,
              fontSize: 26,
              fontWeight: 450,
              color: "#B0BAC9",
              letterSpacing: locale === "en" ? 0.2 : 0.8,
              lineHeight: 1.45,
            }}
          >
            {copy.subtitle}
          </div>

          {/* 3 Clean Capability Pills */}
          <div
            style={{
              marginTop: 38,
              display: "flex",
              flexWrap: "wrap",
              justifyContent: "center",
              gap: 14,
              maxWidth: 1040,
            }}
          >
            {copy.pills.map((label, i) => {
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
                    fontSize: 21,
                    fontWeight: 500,
                    color: C.ink,
                    boxShadow: "0 16px 36px rgba(0,0,0,0.42)",
                    whiteSpace: "nowrap",
                    display: "flex",
                    alignItems: "center",
                    gap: 10,
                  }}
                >
                  <span
                    style={{
                      width: 10,
                      height: 10,
                      borderRadius: 999,
                      background: accent,
                      boxShadow: `0 0 10px ${accent}`,
                      display: "inline-block",
                      flexShrink: 0,
                    }}
                  />
                  {label}
                </div>
              );
            })}
          </div>
        </div>

        {/* Bottom Centered Hero Real Phone UI (01-dark.png) */}
        <div
          style={{
            position: "absolute",
            left: phoneLeft,
            top: 632,
            perspective: 1800,
          }}
        >
          <div
            style={{
              transform: "rotateX(4deg)",
              transformStyle: "preserve-3d",
            }}
          >
            <StagePhone
              src={uiImg(locale, "01-dark.png")}
              w={phoneW}
              dim={0.4}
              slices={[
                {
                  id: "top-bar",
                  rect: [16, 14, 646, 72],
                  radius: 22,
                  elevate: 1,
                  dx: 0,
                  dy: -6,
                  popScale: 1.05,
                  accentBorder: "rgba(52,211,153,0.55)",
                },
                {
                  id: "album-player",
                  rect: [32, 102, 614, 332],
                  radius: 28,
                  elevate: 1,
                  dx: 0,
                  dy: 6,
                  popScale: 1.07,
                  accentBorder: "rgba(76,141,255,0.55)",
                  overlay: (
                    <div
                      style={{
                        position: "absolute",
                        left: 22 * (phoneW / 678),
                        top: (314 - 102) * (phoneW / 678),
                        width: 248 * (phoneW / 678),
                        height: 8 * (phoneW / 678),
                        borderRadius: 99,
                        background: "#FFFFFF",
                        boxShadow: "0 0 12px rgba(255,255,255,0.8)",
                      }}
                    />
                  ),
                },
              ]}
            />
          </div>
        </div>

        {/* Subtle static vignette finish */}
        <AbsoluteFill
          style={{
            pointerEvents: "none",
            background:
              "radial-gradient(ellipse 82% 80% at 50% 48%, rgba(0,0,0,0) 58%, rgba(0,0,0,0.48) 100%)",
          }}
        />
      </AbsoluteFill>
    );
  }

  const is4x3 = aspect === "4x3";
  const width = is4x3 ? 1600 : 1920;
  const height = is4x3 ? 1200 : 1080;

  // Aspect-tuned layout metrics
  const logoSize = is4x3 ? 182 : 204;
  const leftX = is4x3 ? 88 : 112;
  const leftTop = is4x3 ? 292 : 272;
  const leftMaxW = is4x3 ? 830 : 980;

  const brandPrimarySize = is4x3
    ? copy.brandSecondary
      ? 112
      : 100
    : copy.brandSecondary
      ? 124
      : 112;
  const brandSecondarySize = is4x3 ? 42 : 54;

  const headlineSize = is4x3
    ? locale === "en"
      ? 42
      : 48
    : locale === "en"
      ? 44
      : 50;

  const subtitleSize = is4x3 ? 26 : 28;
  const pillFontSize = is4x3 ? 21 : 22;

  const phoneX = is4x3 ? 988 : 1218;
  const phoneY = is4x3 ? 86 : 74;
  const phoneW = is4x3 ? 448 : 446;

  const ringCx = leftX + logoSize * 0.44;
  const ringCy = leftTop + logoSize * 0.5;

  return (
    <AbsoluteFill style={{ background: C.bg, overflow: "hidden", width, height }}>
      {/* Warm lamp amber + coral ambient glow behind brand, soft blue-amber aura behind hero phone */}
      <AbsoluteFill
        style={{
          background: is4x3
            ? `radial-gradient(circle at 22% 34%, rgba(255,209,102,0.16) 0%, rgba(229,109,72,0.13) 20%, rgba(229,109,72,0) 46%), radial-gradient(circle at 78% 52%, rgba(76,141,255,0.17) 0%, rgba(255,209,102,0.06) 26%, rgba(46,108,246,0) 50%)`
            : `radial-gradient(circle at 20% 38%, rgba(255,209,102,0.16) 0%, rgba(229,109,72,0.13) 18%, rgba(229,109,72,0) 44%), radial-gradient(circle at 74% 52%, rgba(76,141,255,0.17) 0%, rgba(255,209,102,0.06) 24%, rgba(46,108,246,0) 48%)`,
        }}
      />

      {/* Restrained geometric acoustic rings radiating from the cozy-house mark */}
      <svg width={width} height={height} style={{ position: "absolute", inset: 0, pointerEvents: "none" }}>
        <circle cx={ringCx} cy={ringCy} r={is4x3 ? 210 : 230} fill="none" stroke="rgba(255,209,102,0.09)" strokeWidth={1.6} />
        <circle cx={ringCx} cy={ringCy} r={is4x3 ? 340 : 375} fill="none" stroke="rgba(229,109,72,0.09)" strokeWidth={1.4} />
        <circle cx={ringCx} cy={ringCy} r={is4x3 ? 490 : 535} fill="none" stroke="rgba(255,255,255,0.045)" strokeWidth={1.2} />
      </svg>

      {/* Left Column: Logo + Brand Wordmark + Official Slogan + Subtitle + 3 Status Pills */}
      <div
        style={{
          position: "absolute",
          left: leftX,
          top: leftTop,
          maxWidth: leftMaxW,
        }}
      >
        {/* Brand Lockup */}
        <div
          style={{
            display: "flex",
            alignItems: "center",
            gap: is4x3 ? 26 : 30,
          }}
        >
          <div style={{ width: logoSize, height: logoSize, flexShrink: 0, marginLeft: -8 }}>
            <LogoMark size={logoSize} rings={1} core={1} play={1} arc={1} pulse={0.35} />
          </div>

          {is4x3 && copy.brandSecondary ? (
            <div style={{ display: "flex", flexDirection: "column", justifyContent: "center", gap: 6 }}>
              <div
                style={{
                  fontFamily: fontMain,
                  fontSize: brandPrimarySize,
                  fontWeight: 800,
                  color: "#FFFFFF",
                  letterSpacing: 6,
                  lineHeight: 1.04,
                }}
              >
                {copy.brandPrimary}
              </div>
              <div
                style={{
                  fontFamily: FONT_EN,
                  fontSize: brandSecondarySize,
                  fontWeight: 600,
                  color: C.ink2,
                  letterSpacing: 1.5,
                  lineHeight: 1.1,
                }}
              >
                {copy.brandSecondary}
              </div>
            </div>
          ) : (
            <div style={{ display: "flex", alignItems: "baseline", gap: 24 }}>
              <div
                style={{
                  fontFamily: copy.brandSecondary ? fontMain : FONT_EN,
                  fontSize: brandPrimarySize,
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
                    fontSize: brandSecondarySize,
                    fontWeight: 600,
                    color: C.ink2,
                    letterSpacing: 1,
                  }}
                >
                  {copy.brandSecondary}
                </div>
              ) : null}
            </div>
          )}
        </div>

        {/* Official Brand Slogan */}
        <div
          style={{
            marginTop: is4x3 ? 52 : 44,
            fontFamily: fontMain,
            fontSize: headlineSize,
            fontWeight: 700,
            color: "#F6F7F9",
            letterSpacing: locale === "en" ? -0.3 : 1.2,
            lineHeight: 1.26,
          }}
        >
          {copy.headline}
        </div>

        {/* Concise Product Positioning Subtitle */}
        <div
          style={{
            marginTop: is4x3 ? 24 : 20,
            maxWidth: is4x3 ? 760 : 940,
            fontFamily: fontMain,
            fontSize: subtitleSize,
            fontWeight: 450,
            color: "#B0BAC9",
            letterSpacing: locale === "en" ? 0.2 : 0.8,
            lineHeight: 1.48,
          }}
        >
          {copy.subtitle}
        </div>

        {/* 3 Clean Capability Pills */}
        <div
          style={{
            marginTop: is4x3 ? 54 : 50,
            display: "flex",
            flexWrap: "wrap",
            gap: is4x3 ? 14 : 16,
            maxWidth: leftMaxW,
          }}
        >
          {copy.pills.map((label, i) => {
            const accent = pillColors[i];
            return (
              <div
                key={i}
                style={{
                  padding: is4x3 ? "13px 24px" : "13px 26px",
                  borderRadius: 999,
                  background: "rgba(21,26,36,0.90)",
                  border: "1px solid rgba(255,255,255,0.16)",
                  fontFamily: fontMain,
                  fontSize: pillFontSize,
                  fontWeight: 500,
                  color: C.ink,
                  boxShadow: "0 16px 36px rgba(0,0,0,0.42)",
                  whiteSpace: "nowrap",
                  display: "flex",
                  alignItems: "center",
                  gap: 10,
                }}
              >
                <span
                  style={{
                    width: 10,
                    height: 10,
                    borderRadius: 999,
                    background: accent,
                    boxShadow: `0 0 10px ${accent}`,
                    display: "inline-block",
                    flexShrink: 0,
                  }}
                />
                {label}
              </div>
            );
          })}
        </div>
      </div>

      {/* Right Column: Hero Real Phone UI (01-dark.png) with Elevated Top Bar & Album Player Card */}
      <div
        style={{
          position: "absolute",
          left: phoneX,
          top: phoneY,
          perspective: 1800,
        }}
      >
        <div
          style={{
            transform: `rotateY(${is4x3 ? -10 : -11}deg) rotateX(4deg)`,
            transformStyle: "preserve-3d",
          }}
        >
          <StagePhone
            src={uiImg(locale, "01-dark.png")}
            w={phoneW}
            dim={0.42}
            slices={[
              {
                id: "top-bar",
                rect: [16, 14, 646, 72],
                radius: 22,
                elevate: 1,
                dx: -16,
                dy: -6,
                popScale: 1.07,
                accentBorder: "rgba(52,211,153,0.55)",
              },
              {
                id: "album-player",
                rect: [32, 102, 614, 332],
                radius: 28,
                elevate: 1,
                dx: is4x3 ? -22 : -26,
                dy: 4,
                popScale: 1.09,
                accentBorder: "rgba(76,141,255,0.55)",
                overlay: (
                  <div
                    style={{
                      position: "absolute",
                      left: 22 * (phoneW / 678),
                      top: (314 - 102) * (phoneW / 678),
                      width: 248 * (phoneW / 678),
                      height: 8 * (phoneW / 678),
                      borderRadius: 99,
                      background: "#FFFFFF",
                      boxShadow: "0 0 12px rgba(255,255,255,0.8)",
                    }}
                  />
                ),
              },
            ]}
          />
        </div>
      </div>

      {/* Subtle static vignette finish */}
      <AbsoluteFill
        style={{
          pointerEvents: "none",
          background:
            "radial-gradient(ellipse 80% 78% at 50% 48%, rgba(0,0,0,0) 58%, rgba(0,0,0,0.48) 100%)",
        }}
      />
    </AbsoluteFill>
  );
};
