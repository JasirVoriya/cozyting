import React from "react";
import { AbsoluteFill, useCurrentFrame } from "remotion";
import { C, FONT_EN, fontForLocale, uiImg, prog, easeInOut, easeOut, BEAT, lerp, type PromoLocale } from "../theme";
import { LogoMark, Reveal, Ripple, StagePhone } from "../components";
import { getCopy } from "../i18n";

export const Intro: React.FC<{ locale: PromoLocale }> = ({ locale }) => {
  const f = useCurrentFrame();
  const copy = getCopy(locale).intro;
  const fontMain = fontForLocale(locale);
  const size = 440;

  const core = prog(f, 16, 62, easeOut);
  const rings = prog(f, 70, 155, easeInOut);
  const play = prog(f, 110, 150);
  const arc = prog(f, 125, 185, easeInOut);
  const pulse = Math.max(0, Math.sin(((f - 180) / BEAT) * Math.PI)) * prog(f, 180, 220);

  // At f=180..255, logo + title glide to the left column while the real 3D phone rises on the right
  const split = prog(f, 185, 255, easeInOut);
  const logoScale = lerp(1, 0.46, split);
  const logoX = lerp(960, 255, split);
  const logoY = lerp(540, 285, split);

  const phoneIn = prog(f, 200, 275, easeOut);
  const popHeader = prog(f, 275, 320, easeOut);
  const popPlayer = prog(f, 305, 355, easeOut);
  const floatY = Math.sin(f / 45) * 6;

  // Live playback progress animation inside the real player card
  const playProgress = prog(f, 220, 470, (t) => t);
  const sec = 34 + Math.floor(playProgress * 6);

  const out = prog(f, 444, 480, easeInOut);
  const pillColors = ["#34D399", C.blue, C.coral] as const;

  return (
    <AbsoluteFill style={{ background: C.bg }}>
      {/* dual ambient glow: coral behind brand, album blue behind real phone */}
      <AbsoluteFill
        style={{
          background: `radial-gradient(circle at ${lerp(50, 24, split)}% 46%, rgba(255,92,108,${0.11 * core}) 0%, rgba(255,92,108,0) 36%), radial-gradient(circle at 73% 54%, rgba(46,108,246,${0.22 * phoneIn}) 0%, rgba(46,108,246,0) 42%)`,
        }}
      />

      {/* opening sound ripples */}
      <svg width="1920" height="1080" style={{ position: "absolute", inset: 0, opacity: 1 - split * 0.7 }}>
        {[0, 1, 2, 3].map((k) => (
          <Ripple key={k} cx={logoX} cy={logoY} t={(f - 28 - k * BEAT) / 90} maxR={500} color={C.ink} width={1.2} />
        ))}
      </svg>

      {/* LogoMark */}
      <div
        style={{
          position: "absolute",
          left: logoX - size / 2 + ((256 - 226) / 512) * size,
          top: logoY - size / 2,
          transform: `scale(${logoScale * (1 + out * 0.15)})`,
          filter: `blur(${out * 12}px)`,
          opacity: 1 - out,
        }}
      >
        <LogoMark size={size} rings={rings} core={core} play={play} arc={arc} pulse={pulse} />
      </div>

      {/* Left Column: Brand Wordmark & Value Proposition */}
      <div
        style={{
          position: "absolute",
          left: 380,
          top: 205,
          opacity: 1 - out,
          filter: `blur(${out * 10}px)`,
        }}
      >
        <div style={{ display: "flex", alignItems: "baseline", gap: 24 }}>
          <Reveal
            text={copy.brandPrimary}
            start={215}
            size={copy.brandSecondary ? 118 : 104}
            weight={700}
            font={copy.brandSecondary ? fontMain : FONT_EN}
            stagger={copy.brandSecondary ? 5 : 2.5}
            dur={38}
            tracking={copy.brandSecondary ? 6 : 2}
          />
          {copy.brandSecondary ? (
            <Reveal
              text={copy.brandSecondary}
              start={228}
              size={58}
              weight={600}
              font={FONT_EN}
              color={C.ink2}
              stagger={2}
              tracking={1}
            />
          ) : null}
        </div>
        <div style={{ marginTop: 34, marginLeft: -210 }}>
          <Reveal
            text={copy.headline}
            start={255}
            size={copy.headlineSize}
            weight={600}
            font={fontMain}
            color={C.ink}
            stagger={locale === "en" ? 0.9 : 1.5}
            tracking={locale === "en" ? 0.2 : 1.5}
          />
        </div>
        <div style={{ marginTop: 18, marginLeft: -210 }}>
          <Reveal
            text={copy.subtitle}
            start={285}
            size={copy.subtitleSize}
            weight={400}
            font={fontMain}
            color={C.ink2}
            stagger={0.7}
            tracking={locale === "en" ? 0.2 : 1}
          />
        </div>

        {/* 3 live status callout pills linked to the real screenshot on the right */}
        <div style={{ marginTop: 48, marginLeft: -210, display: "flex", flexWrap: "wrap", gap: 14, maxWidth: 960 }}>
          {copy.pills.map((label, i) => {
            const p = prog(f, 315 + i * 15, 347 + i * 15);
            const accent = pillColors[i];
            return (
              <div
                key={i}
                style={{
                  padding: "11px 22px",
                  borderRadius: 999,
                  background: "rgba(21,26,35,0.85)",
                  border: "1px solid rgba(255,255,255,0.14)",
                  fontFamily: fontMain,
                  fontSize: 20,
                  fontWeight: 500,
                  color: C.ink,
                  opacity: p,
                  transform: `translateY(${(1 - p) * 18}px)`,
                  boxShadow: "0 14px 34px rgba(0,0,0,0.35)",
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

      {/* Right Column: Real Hero Phone (01-dark.png) with Elevated Real Top Bar & Album Player Card */}
      <div
        style={{
          position: "absolute",
          left: 1215,
          top: 90,
          perspective: 1800,
          opacity: phoneIn * (1 - out),
          filter: `blur(${(1 - phoneIn) * 12 + out * 10}px)`,
        }}
      >
        <div
          style={{
            transform: `translateY(${(1 - phoneIn) * 140 + floatY}px) rotateY(${lerp(-18, -11, phoneIn)}deg) rotateX(${lerp(10, 4, phoneIn)}deg)`,
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
              left: -135,
              top: 195,
              padding: "8px 16px",
              borderRadius: 14,
              background: "rgba(15,20,30,0.92)",
              border: "1px solid rgba(76,141,255,0.45)",
              fontFamily: FONT_EN,
              fontSize: 17,
              fontWeight: 600,
              color: "#EAF0F7",
              opacity: popPlayer,
              transform: `translateX(${(1 - popPlayer) * 24}px)`,
              boxShadow: "0 16px 36px rgba(0,0,0,0.45)",
            }}
          >
            01:{sec < 10 ? `0${sec}` : sec} / 04:08 · 48kHz
          </div>
        </div>
      </div>
    </AbsoluteFill>
  );
};
