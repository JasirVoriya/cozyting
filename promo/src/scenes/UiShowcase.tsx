import React from "react";
import { AbsoluteFill, useCurrentFrame, interpolateColors } from "remotion";
import { C, fontForLocale, uiImg, prog, easeInOut, easeOut, sceneFade, lerp, type PromoLocale } from "../theme";
import { Eyebrow, Reveal, StagePhone, TapRipple, UiSlice } from "../components";
import { getCopy } from "../i18n";

export const UiShowcase: React.FC<{ dur: number; locale: PromoLocale }> = ({ dur, locale }) => {
  const f = useCurrentFrame();
  const copy = getCopy(locale).uiShowcase;
  const fontMain = fontForLocale(locale);

  const theme = prog(f, 168, 240, easeInOut);

  const bg = interpolateColors(theme, [0, 1], [C.bg, "#E8ECF2"]);
  const ink = interpolateColors(theme, [0, 1], [C.ink, "#0F172A"]);
  const ink2 = interpolateColors(theme, [0, 1], ["#CBD5E1", "#334155"]);
  const pillBg = interpolateColors(theme, [0, 1], ["rgba(21,26,35,0.92)", "rgba(255,255,255,0.94)"]);
  const pillBorder = interpolateColors(theme, [0, 1], ["rgba(255,255,255,0.18)", "rgba(15,23,42,0.14)"]);

  const enter = (i: number) => prog(f, 6 + i * 10, 58 + i * 10, easeInOut);
  const float = (i: number) => Math.sin((f + i * 40) / 65) * 6;
  const orbit = lerp(-4, 4, f / dur);

  const phone = (i: number, dark: string, light: string) => {
    const pos = [-1, 0, 1][i];
    const e = enter(i === 1 ? 0 : i === 0 ? 1 : 2);
    const x = pos * 490;
    const ry = -pos * 16 + orbit;
    const z = pos === 0 ? 60 : -130;
    return (
      <div
        key={i}
        style={{
          position: "absolute",
          left: 960 - 185 + x,
          top: 278,
          transform: `translateY(${(1 - e) * 420 + float(i)}px) translateZ(${z}px) rotateY(${ry}deg)`,
          opacity: e,
        }}
      >
        <StagePhone src={uiImg(locale, dark)} src2={uiImg(locale, light)} wipe2={theme} w={348} />
      </div>
    );
  };

  const cap = (text: string, a: number, b: number, eyebrow: string) => (
    <div
      style={{
        position: "absolute",
        top: 52,
        width: "100%",
        display: "flex",
        flexDirection: "column",
        alignItems: "center",
        gap: 10,
      }}
    >
      <Eyebrow text={eyebrow} start={a} out={b} />
      <Reveal
        text={text}
        start={a + 6}
        out={b}
        size={copy.headlineSize}
        weight={700}
        font={fontMain}
        stagger={locale === "en" ? 1.0 : 1.8}
        color={ink}
      />
    </div>
  );

  const themeCardIn = prog(f, 118, 152, easeOut);

  return (
    <AbsoluteFill style={{ background: bg, opacity: sceneFade(f, dur, 8, 18), overflow: "hidden" }}>
      <AbsoluteFill
        style={{
          background: `radial-gradient(ellipse 52% 45% at 50% 70%, rgba(76,141,255,${lerp(0.18, 0.22, theme)}) 0%, rgba(76,141,255,0) 70%), radial-gradient(ellipse 30% 30% at 30% 60%, rgba(255,92,108,0.11) 0%, rgba(255,92,108,0) 70%)`,
        }}
      />
      <AbsoluteFill style={{ perspective: 2200, perspectiveOrigin: "50% 40%" }}>
        <AbsoluteFill style={{ transformStyle: "preserve-3d" }}>
          {phone(0, "03-dark.png", "03-light.png")}
          {phone(2, "02-dark.png", "02-light.png")}
          {phone(1, "01-dark.png", "01-light.png")}
        </AbsoluteFill>
      </AbsoluteFill>

      {/* Enlarged Real Floating Theme Switcher Slice */}
      {themeCardIn > 0.001 && (
        <div
          style={{
            position: "absolute",
            left: 960 - (586 * 0.84) / 2,
            top: 174,
            opacity: themeCardIn,
            transform: `translateY(${(1 - themeCardIn) * 18}px)`,
            zIndex: 30,
          }}
        >
          <UiSlice
            src={uiImg(locale, "05-dark.png")}
            src2={uiImg(locale, "05-light.png")}
            mix={theme}
            rect={[46, 224, 586, 68]}
            radius={22}
            scale={0.84}
            elevation={1}
            accentBorder="rgba(37,99,235,0.82)"
          >
            <TapRipple x={293} y={34} start={162} scale={0.84} color="#2563EB" />
          </UiSlice>
        </div>
      )}

      <AbsoluteFill style={{ background: `linear-gradient(180deg, rgba(0,0,0,0) 74%, ${bg} 98%)` }} />

      {cap(copy.headline1, 4, 152, copy.eyebrow1)}
      {cap(copy.headline2, 168, 355, copy.eyebrow2)}

      {/* 3 floating screen badges at the bottom */}
      <div
        style={{
          position: "absolute",
          bottom: 38,
          width: "100%",
          display: "flex",
          justifyContent: "center",
          gap: 110,
          opacity: prog(f, 45, 85),
        }}
      >
        {copy.badges.map((lb, idx) => (
          <div
            key={idx}
            style={{
              padding: "12px 26px",
              borderRadius: 999,
              background: pillBg,
              border: `1.5px solid ${pillBorder}`,
              boxShadow: "0 14px 36px rgba(0,0,0,0.22)",
              fontFamily: fontMain,
              fontSize: 22,
              fontWeight: 600,
              letterSpacing: locale === "en" ? 0.2 : 1,
              color: ink2,
              minWidth: 310,
              textAlign: "center",
              whiteSpace: "nowrap",
            }}
          >
            {lb}
          </div>
        ))}
      </div>
    </AbsoluteFill>
  );
};
