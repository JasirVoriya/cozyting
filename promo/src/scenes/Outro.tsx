import React from "react";
import { AbsoluteFill, useCurrentFrame } from "remotion";
import { C, FONT_EN, FONT_MONO, fontForLocale, uiImg, prog, easeInOut, easeOut, BEAT, lerp, type PromoLocale } from "../theme";
import { GitHubIcon, LogoMark, MouseCursor, Reveal, Ripple, StagePhone } from "../components";
import { getCopy } from "../i18n";

export const Outro: React.FC<{ dur: number; locale: PromoLocale }> = ({ locale }) => {
  const f = useCurrentFrame();
  const copy = getCopy(locale).outro;
  const fontMain = fontForLocale(locale);
  const size = 216;
  const rings = prog(f, 0, 48, easeInOut);
  const core = prog(f, 4, 32, easeOut);
  const play = prog(f, 18, 42);
  const arc = prog(f, 22, 56, easeInOut);
  const pulse = Math.max(0, Math.sin(((f - 58) / BEAT) * Math.PI)) * prog(f, 58, 86) * (1 - prog(f, 280, 340));
  const phonesIn = prog(f, 8, 68, easeOut);

  const barIn = prog(f, 115, 155, easeOut);

  const cursorMove = prog(f, 175, 232, easeInOut);
  const cursorX = lerp(1835, 1692, cursorMove);
  const cursorY = lerp(1025, 816, cursorMove);
  const hovering = prog(f, 218, 236, easeOut);

  const clickDown = prog(f, 244, 252, easeOut);
  const clickUp = prog(f, 252, 266, easeOut);
  const pressing = Math.max(0, clickDown - clickUp);
  const rippleT = f >= 246 ? Math.min(1, (f - 246) / 42) : 0;

  return (
    <AbsoluteFill style={{ background: C.bg }}>
      <AbsoluteFill
        style={{
          background: `radial-gradient(circle at 28% 54%, rgba(76,141,255,${0.14 * phonesIn}) 0%, rgba(46,108,246,0) 42%), radial-gradient(circle at 69% 32%, rgba(255,209,102,${0.15 * core}) 0%, rgba(229,109,72,${0.13 * core}) 20%, rgba(229,109,72,0) 44%)`,
        }}
      />
      <svg width="1920" height="1080" style={{ position: "absolute", inset: 0 }}>
        <Ripple cx={1315} cy={245} t={f / 70} maxR={620} color={C.coral} width={2} />
        <Ripple cx={1315} cy={245} t={(f - 12) / 80} maxR={700} color={C.warmGold} width={1.3} />
      </svg>

      <AbsoluteFill>
        {/* Left Side: Dual-Theme Real Hero Phones */}
        <div
          style={{
            position: "absolute",
            left: 115,
            top: 105,
            perspective: 2000,
            opacity: phonesIn,
          }}
        >
          <div
            style={{
              position: "absolute",
              left: 255,
              top: 35,
              transform: `translateY(${(1 - phonesIn) * 110}px) rotateY(-14deg) rotateX(4deg) scale(0.92)`,
              transformStyle: "preserve-3d",
            }}
          >
            <StagePhone src={uiImg(locale, "01-light.png")} w={355} />
          </div>

          <div
            style={{
              position: "absolute",
              left: 0,
              top: 0,
              transform: `translateY(${(1 - phonesIn) * 85}px) rotateY(12deg) rotateX(3deg)`,
              transformStyle: "preserve-3d",
            }}
          >
            <StagePhone
              src={uiImg(locale, "01-dark.png")}
              w={365}
              dim={0.28}
              slices={[
                {
                  id: "outro-spk",
                  rect: [48, 792, 582, 150],
                  radius: 26,
                  elevate: prog(f, 40, 88, easeOut),
                  dx: 24,
                  dy: -6,
                  popScale: 1.12,
                  accentBorder: "rgba(76,141,255,0.85)",
                },
              ]}
            />
          </div>
        </div>

        {/* Right Side: Brand Mark, Wordmark, Tagline, Feature Pills, GitHub Search Box */}
        <div
          style={{
            position: "absolute",
            left: 1315 - size / 2,
            top: 235 - size / 2,
          }}
        >
          <LogoMark size={size} rings={rings} core={core} play={play} arc={arc} pulse={pulse} />
        </div>

        <div
          style={{
            position: "absolute",
            left: 830,
            top: 382,
            width: 970,
            display: "flex",
            justifyContent: "center",
            alignItems: "baseline",
            gap: 24,
          }}
        >
          <Reveal
            text={copy.brandPrimary}
            start={28}
            size={copy.brandSecondary ? 104 : 96}
            weight={700}
            font={copy.brandSecondary ? fontMain : FONT_EN}
            stagger={copy.brandSecondary ? 4 : 2.2}
            tracking={copy.brandSecondary ? 6 : 2}
          />
          {copy.brandSecondary ? (
            <Reveal text={copy.brandSecondary} start={40} size={56} weight={600} font={FONT_EN} color="#CBD5E1" stagger={2} />
          ) : null}
        </div>

        <div
          style={{
            position: "absolute",
            left: 830,
            top: 532,
            width: 970,
            display: "flex",
            justifyContent: "center",
          }}
        >
          <Reveal
            text={copy.tagline}
            start={62}
            size={copy.taglineSize}
            weight={600}
            font={fontMain}
            color="#FFFFFF"
            stagger={locale === "en" ? 0.8 : 1.2}
            tracking={locale === "en" ? 0.2 : 1.2}
          />
        </div>

        {/* 4 User-Friendly Feature Pills */}
        <div
          style={{
            position: "absolute",
            left: 810,
            top: 632,
            width: 1010,
            display: "flex",
            justifyContent: "center",
            flexWrap: "wrap",
            gap: 14,
          }}
        >
          {copy.tags.map((t, i) => {
            const a = prog(f, 92 + i * 6, 128 + i * 6);
            return (
              <div
                key={t}
                style={{
                  padding: "11px 22px",
                  borderRadius: 999,
                  border: "1.5px solid rgba(255,255,255,0.20)",
                  background: "rgba(255,255,255,0.06)",
                  fontFamily: fontMain,
                  fontSize: 22,
                  fontWeight: 600,
                  color: "#F1F5F9",
                  opacity: a,
                  transform: `translateY(${(1 - a) * 14}px)`,
                  whiteSpace: "nowrap",
                }}
              >
                {t}
              </div>
            );
          })}
        </div>

        {/* CLEAN GITHUB SEARCH BAR */}
        <div
          style={{
            position: "absolute",
            left: 830,
            top: 760,
            width: 970,
            display: "flex",
            justifyContent: "center",
            opacity: barIn,
            transform: `translateY(${(1 - barIn) * 24}px)`,
          }}
        >
          <div
            style={{
              width: 960,
              height: 108,
              borderRadius: 999,
              background: "rgba(20,25,36,0.95)",
              border: `2.5px solid ${hovering > 0.25 ? "#F08462" : "rgba(229,109,72,0.82)"}`,
              boxShadow: `0 26px 68px rgba(0,0,0,0.60), 0 0 ${36 + hovering * 28}px rgba(229,109,72,${0.25 + hovering * 0.20})`,
              display: "flex",
              alignItems: "center",
              justifyContent: "space-between",
              padding: "0 16px 0 28px",
            }}
          >
            <div style={{ display: "flex", alignItems: "center", gap: 20 }}>
              <div
                style={{
                  width: 60,
                  height: 60,
                  borderRadius: 999,
                  background: "rgba(255,255,255,0.12)",
                  border: "1.5px solid rgba(255,255,255,0.24)",
                  display: "flex",
                  alignItems: "center",
                  justifyContent: "center",
                  flexShrink: 0,
                }}
              >
                <GitHubIcon size={36} color="#FFFFFF" />
              </div>

              <svg
                width="30"
                height="30"
                viewBox="0 0 24 24"
                fill="none"
                stroke="#94A3B8"
                strokeWidth="2.4"
                strokeLinecap="round"
                style={{ flexShrink: 0 }}
              >
                <circle cx="11" cy="11" r="7.5" />
                <line x1="16.5" y1="16.5" x2="21.5" y2="21.5" />
              </svg>

              <div
                style={{
                  fontFamily: FONT_MONO,
                  fontSize: 30,
                  fontWeight: 700,
                  color: "#FFFFFF",
                  letterSpacing: 0.2,
                  whiteSpace: "nowrap",
                }}
              >
                JasirVoriya/cozyting
              </div>
            </div>

            <div
              style={{
                height: 76,
                minWidth: 164,
                padding: "0 38px",
                borderRadius: 999,
                background: hovering > 0.25 ? "#F08462" : C.coral,
                boxShadow: `0 10px 30px rgba(229,109,72,${0.44 + hovering * 0.26})`,
                display: "flex",
                alignItems: "center",
                justifyContent: "center",
                fontFamily: fontMain,
                fontSize: 27,
                fontWeight: 700,
                color: "#FFFFFF",
                letterSpacing: locale === "en" ? 0.5 : 3,
                whiteSpace: "nowrap",
                flexShrink: 0,
                transform: `scale(${1 + hovering * 0.035 - pressing * 0.07})`,
              }}
            >
              {copy.searchButton}
            </div>
          </div>
        </div>

        <MouseCursor
          x={cursorX}
          y={cursorY}
          opacity={prog(f, 170, 192)}
          pressing={pressing}
          rippleT={rippleT}
          rippleColor="#FFFFFF"
          size={52}
        />
      </AbsoluteFill>
    </AbsoluteFill>
  );
};
