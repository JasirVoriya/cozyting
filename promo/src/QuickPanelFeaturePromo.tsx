import React from "react";
import { AbsoluteFill, Audio, Sequence, interpolate, interpolateColors, staticFile, useCurrentFrame } from "remotion";
import {
  BEAT,
  C,
  FONT_EN,
  FONT_MONO,
  fontForLocale,
  uiImg,
  prog,
  easeInOut,
  easeOut,
  sceneFade,
  lerp,
  type PromoLocale,
} from "./theme";
import { Eyebrow, Finish, GitHubIcon, LogoMark, MouseCursor, Reveal, Ripple, StagePhone, TapRipple } from "./components";
import { getCopy } from "./i18n";

export const QUICK_PANEL_TOTAL_FRAMES = 24 * 60; // 1440 frames = 24s (12 bars @ 120 BPM)

const HouseGlyph: React.FC<{ size?: number; color?: string }> = ({ size = 28, color = "#FFFFFF" }) => (
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

/** Act 1 (0-3s / 180f): Relatable everyday pain-point hook — zero feature spoilers */
const HookAct: React.FC<{ dur: number; locale: PromoLocale }> = ({ dur, locale }) => {
  const f = useCurrentFrame();
  const copy = getCopy(locale).quickPanelVideo;
  const fontMain = fontForLocale(locale);
  const stepsIn = prog(f, 18, 62, easeOut);
  const slash = prog(f, 82, 126, easeInOut);

  return (
    <AbsoluteFill style={{ background: C.bg, opacity: sceneFade(f, dur, 8, 12), overflow: "hidden" }}>
      <AbsoluteFill
        style={{
          background:
            "radial-gradient(ellipse 62% 54% at 50% 44%, rgba(229,109,72,0.16) 0%, rgba(76,141,255,0.08) 42%, rgba(0,0,0,0) 74%)",
        }}
      />

      <div
        style={{
          position: "absolute",
          inset: 0,
          display: "flex",
          flexDirection: "column",
          alignItems: "center",
          justifyContent: "center",
          padding: "0 110px",
          textAlign: "center",
        }}
      >
        <Eyebrow text={copy.hookEyebrow} start={2} />

        <div style={{ marginTop: 22 }}>
          <Reveal
            text={copy.hookQuestion}
            start={8}
            size={copy.hookQuestionSize}
            weight={800}
            font={fontMain}
            stagger={locale === "en" ? 0.65 : 1.1}
          />
        </div>

        <div
          style={{
            marginTop: 22,
            fontFamily: fontMain,
            fontSize: 28,
            fontWeight: 450,
            color: "#94A3B8",
            opacity: prog(f, 24, 58, easeOut),
            maxWidth: 1180,
          }}
        >
          {copy.hookSub}
        </div>

        {/* Tedious old 4-step detour struck through with coral slash */}
        <div
          style={{
            marginTop: 64,
            position: "relative",
            display: "flex",
            alignItems: "center",
            gap: 20,
            opacity: stepsIn,
            transform: `translateY(${(1 - stepsIn) * 24}px)`,
          }}
        >
          {copy.hookOldSteps.map((step, i) => (
            <React.Fragment key={i}>
              <div
                style={{
                  padding: "18px 30px",
                  borderRadius: 20,
                  background: "rgba(255,255,255,0.04)",
                  border: "1.5px solid rgba(255,255,255,0.12)",
                  fontFamily: fontMain,
                  fontSize: 24,
                  fontWeight: 600,
                  color: slash > 0.35 ? "#64748B" : "#CBD5E1",
                  whiteSpace: "nowrap",
                }}
              >
                {step}
              </div>
              {i < copy.hookOldSteps.length - 1 && (
                <span style={{ fontFamily: FONT_EN, fontSize: 26, color: "#475569", fontWeight: 700 }}>→</span>
              )}
            </React.Fragment>
          ))}

          {/* Coral diagonal strike-through */}
          <svg
            width="100%"
            height="100%"
            viewBox="0 0 1200 100"
            preserveAspectRatio="none"
            style={{ position: "absolute", inset: 0, overflow: "visible", pointerEvents: "none" }}
          >
            <line
              x1={-16}
              y1={56}
              x2={1216}
              y2={44}
              stroke={C.coral}
              strokeWidth={7}
              strokeLinecap="round"
              pathLength={1}
              strokeDasharray="1 1"
              strokeDashoffset={1 - slash}
            />
          </svg>
        </div>
      </div>
    </AbsoluteFill>
  );
};

/** Act 2 (3-8s / 300f): Dual system entry — Quick Settings Tile + Notification tap */
const EntryAct: React.FC<{ dur: number; locale: PromoLocale }> = ({ dur, locale }) => {
  const f = useCurrentFrame();
  const copy = getCopy(locale).quickPanelVideo;
  const qp = getCopy(locale).quickPanel;
  const fontMain = fontForLocale(locale);

  const card1In = prog(f, 8, 44, easeOut);
  const card2In = prog(f, 68, 108, easeOut);
  const phoneIn = prog(f, 18, 66, easeOut);
  const popProgress = prog(f, 198, 252, easeOut);

  return (
    <AbsoluteFill style={{ background: C.bg, opacity: sceneFade(f, dur, 10, 12), overflow: "hidden" }}>
      <AbsoluteFill
        style={{
          background:
            "radial-gradient(circle at 26% 52%, rgba(229,109,72,0.18) 0%, rgba(229,109,72,0) 45%), radial-gradient(circle at 75% 56%, rgba(76,141,255,0.20) 0%, rgba(76,141,255,0) 44%)",
        }}
      />

      <div style={{ position: "absolute", left: 100, top: 58, zIndex: 20 }}>
        <Eyebrow text={copy.entryEyebrow} start={4} />
        <div style={{ marginTop: 12 }}>
          <Reveal
            text={copy.entryHeadline}
            start={10}
            size={copy.entryHeadlineSize}
            weight={700}
            font={fontMain}
            stagger={locale === "en" ? 0.8 : 1.4}
          />
        </div>
        <div
          style={{
            marginTop: 12,
            fontFamily: fontMain,
            fontSize: 25,
            fontWeight: 450,
            color: "#CBD5E1",
            opacity: prog(f, 28, 60, easeOut),
            maxWidth: 960,
          }}
        >
          {copy.entrySub}
        </div>
      </div>

      {/* Left: Two big interactive entry cards */}
      <div
        style={{
          position: "absolute",
          left: 100,
          top: 280,
          width: 830,
          display: "flex",
          flexDirection: "column",
          gap: 34,
          zIndex: 20,
        }}
      >
        {/* Entry 1: Control Center Quick Settings Tile */}
        <div
          style={{
            opacity: card1In,
            transform: `translateX(${(1 - card1In) * -40}px) scale(${0.96 + card1In * 0.04})`,
            padding: "26px 28px",
            borderRadius: 30,
            background: "rgba(17,22,34,0.94)",
            border: "2px solid rgba(229,109,72,0.85)",
            boxShadow: "0 28px 68px rgba(0,0,0,0.55), 0 0 38px rgba(229,109,72,0.18)",
          }}
        >
          <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: 18, gap: 12 }}>
            <div
              style={{
                padding: "6px 16px",
                borderRadius: 999,
                background: "rgba(229,109,72,0.22)",
                fontFamily: fontMain,
                fontSize: 20,
                fontWeight: 700,
                color: "#FCA588",
                whiteSpace: "nowrap",
              }}
            >
              {qp.tileBadge}
            </div>
            <div style={{ fontFamily: fontMain, fontSize: 20, fontWeight: 600, color: "#E2E8F0", whiteSpace: "nowrap" }}>
              {qp.tileHint}
            </div>
          </div>

          <div style={{ display: "flex", alignItems: "center", gap: 18 }}>
            <div
              style={{
                position: "relative",
                flex: "0 0 360px",
                height: 92,
                borderRadius: 26,
                background: "linear-gradient(135deg, #E56D48 0%, #D95938 100%)",
                boxShadow: "0 14px 32px rgba(229,109,72,0.42)",
                padding: "0 24px",
                display: "flex",
                alignItems: "center",
                gap: 18,
              }}
            >
              <div
                style={{
                  width: 52,
                  height: 52,
                  borderRadius: 999,
                  background: "rgba(255,255,255,0.20)",
                  display: "flex",
                  alignItems: "center",
                  justifyContent: "center",
                  flexShrink: 0,
                }}
              >
                <HouseGlyph size={30} color="#FFFFFF" />
              </div>
              <div>
                <div style={{ fontFamily: fontMain, fontSize: 24, fontWeight: 700, color: "#FFFFFF" }}>{qp.tileTitle}</div>
                <div style={{ fontFamily: fontMain, fontSize: 17, fontWeight: 600, color: "rgba(255,255,255,0.9)", marginTop: 3 }}>
                  {qp.tileSub}
                </div>
              </div>
              {/* Tap ripple synced to 4.3s (f=78 inside Act 2) */}
              <TapRipple x={180} y={46} start={78} color="#FFFFFF" />
            </div>

            <div
              style={{
                flex: 1,
                height: 92,
                borderRadius: 26,
                background: "rgba(255,255,255,0.05)",
                border: "1px solid rgba(255,255,255,0.09)",
                padding: "0 20px",
                display: "flex",
                alignItems: "center",
                gap: 14,
              }}
            >
              <div
                style={{
                  width: 12,
                  height: 12,
                  borderRadius: 999,
                  background: C.emerald,
                  boxShadow: "0 0 12px rgba(52,211,153,0.9)",
                  flexShrink: 0,
                }}
              />
              <div style={{ fontFamily: fontMain, fontSize: 19, fontWeight: 600, color: "#CBD5E1", lineHeight: 1.35 }}>
                {copy.tileAddBadge}
              </div>
            </div>
          </div>
        </div>

        {/* Entry 2: Ongoing Notification Bar */}
        <div
          style={{
            opacity: card2In,
            transform: `translateX(${(1 - card2In) * -40}px) scale(${0.96 + card2In * 0.04})`,
            padding: "26px 28px",
            borderRadius: 30,
            background: "rgba(17,22,34,0.94)",
            border: "2px solid rgba(76,141,255,0.85)",
            boxShadow: "0 28px 68px rgba(0,0,0,0.55), 0 0 38px rgba(76,141,255,0.18)",
          }}
        >
          <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: 18, gap: 12 }}>
            <div
              style={{
                padding: "6px 16px",
                borderRadius: 999,
                background: "rgba(76,141,255,0.22)",
                fontFamily: fontMain,
                fontSize: 20,
                fontWeight: 700,
                color: "#93C5FD",
                whiteSpace: "nowrap",
              }}
            >
              {qp.notifBadge}
            </div>
            <div style={{ fontFamily: fontMain, fontSize: 20, fontWeight: 600, color: "#E2E8F0", whiteSpace: "nowrap" }}>
              {qp.notifHint}
            </div>
          </div>

          <div
            style={{
              position: "relative",
              borderRadius: 22,
              background: "rgba(255,255,255,0.06)",
              border: "1px solid rgba(255,255,255,0.13)",
              padding: "20px 22px",
              display: "flex",
              alignItems: "center",
              justifyContent: "space-between",
              gap: 16,
            }}
          >
            <div style={{ display: "flex", alignItems: "center", gap: 16 }}>
              <div
                style={{
                  width: 48,
                  height: 48,
                  borderRadius: 15,
                  background: "rgba(76,141,255,0.24)",
                  border: "1px solid rgba(76,141,255,0.55)",
                  display: "flex",
                  alignItems: "center",
                  justifyContent: "center",
                  flexShrink: 0,
                }}
              >
                <HouseGlyph size={26} color="#93C5FD" />
              </div>
              <div>
                <div style={{ fontFamily: fontMain, fontSize: 22, fontWeight: 700, color: "#FFFFFF" }}>{qp.notifTitle}</div>
                <div style={{ fontFamily: fontMain, fontSize: 17, fontWeight: 500, color: "#CBD5E1", marginTop: 4 }}>{qp.notifSub}</div>
              </div>
            </div>
            <div
              style={{
                padding: "10px 20px",
                borderRadius: 999,
                background: "rgba(76,141,255,0.28)",
                border: "1.5px solid rgba(147,197,253,0.7)",
                fontFamily: fontMain,
                fontSize: 18,
                fontWeight: 700,
                color: "#FFFFFF",
                whiteSpace: "nowrap",
              }}
            >
              {qp.notifAction}
            </div>
            {/* Tap ripple synced to 6.2s (f=192 inside Act 2) */}
            <TapRipple x={380} y={46} start={192} color="#60A5FA" />
          </div>
        </div>
      </div>

      {/* Right: Phone smoothly revealing floating QuickCastPanel */}
      <div
        style={{
          position: "absolute",
          left: 1185,
          top: 112,
          opacity: phoneIn,
          transform: `translateY(${(1 - phoneIn) * 60}px) scale(${0.94 + popProgress * 0.06})`,
          perspective: 1800,
        }}
      >
        <div style={{ transform: `rotateY(${lerp(-14, -7, popProgress)}deg)`, transformStyle: "preserve-3d" }}>
          <StagePhone src={uiImg(locale, "09-dark.png")} w={406} dim={lerp(0.35, 0, popProgress)} />
        </div>
      </div>
    </AbsoluteFill>
  );
};

/** Act 3 (8-14s / 360f): 1:1 Frosted Card Close-Up with 3 sequential focus slices */
const FocusAct: React.FC<{ dur: number; locale: PromoLocale }> = ({ dur, locale }) => {
  const f = useCurrentFrame();
  const copy = getCopy(locale).quickPanelVideo;
  const fontMain = fontForLocale(locale);

  const phoneIn = prog(f, 0, 38, easeOut);
  // 3 sequential spotlight windows inside 360f:
  // 0..120: Now Playing + top-right CozyTing home icon
  // 120..240: Master volume slider
  // 240..360: Multi-room speakers + independent volume sliders
  const s1 = prog(f, 14, 42, easeOut) * (1 - prog(f, 114, 136, easeInOut));
  const s2 = prog(f, 122, 150, easeOut) * (1 - prog(f, 226, 248, easeInOut));
  const s3 = prog(f, 234, 262, easeOut);
  const activeIdx = f < 122 ? 0 : f < 234 ? 1 : 2;
  const accents = [C.coral, C.blue, C.emerald] as const;

  return (
    <AbsoluteFill style={{ background: C.bg, opacity: sceneFade(f, dur, 8, 12), overflow: "hidden" }}>
      <AbsoluteFill
        style={{
          background:
            "radial-gradient(circle at 28% 54%, rgba(76,141,255,0.22) 0%, rgba(76,141,255,0) 46%), radial-gradient(circle at 78% 52%, rgba(229,109,72,0.14) 0%, rgba(229,109,72,0) 44%)",
        }}
      />

      {/* Left: Hero Phone with sequential UiSlice pop-outs */}
      <div
        style={{
          position: "absolute",
          left: 170,
          top: 86,
          opacity: phoneIn,
          transform: `translateY(${(1 - phoneIn) * 44}px)`,
          perspective: 2000,
          zIndex: 20,
        }}
      >
        <div style={{ transform: "rotateY(9deg)", transformStyle: "preserve-3d" }}>
          <StagePhone
            src={uiImg(locale, "09-dark.png")}
            w={416}
            dim={0.34}
            slices={[
              {
                id: "qp-now-playing",
                rect: [42, 344, 594, 240],
                radius: 24,
                elevate: s1,
                dx: 42,
                dy: -8,
                popScale: 1.15,
                accentBorder: "rgba(229,109,72,0.92)",
              },
              {
                id: "qp-master-vol",
                rect: [44, 580, 590, 72],
                radius: 18,
                elevate: s2,
                dx: 46,
                dy: 0,
                popScale: 1.18,
                accentBorder: "rgba(76,141,255,0.95)",
              },
              {
                id: "qp-speakers",
                rect: [44, 780, 590, 274],
                radius: 24,
                elevate: s3,
                dx: 42,
                dy: 8,
                popScale: 1.15,
                accentBorder: "rgba(52,211,153,0.95)",
              },
            ]}
          />
        </div>
      </div>

      {/* Right: Headline + 3 interactive highlight cards */}
      <div style={{ position: "absolute", left: 790, top: 82, width: 1020, zIndex: 20 }}>
        <Eyebrow text={copy.focusEyebrow} start={2} />
        <div style={{ marginTop: 14 }}>
          <Reveal
            text={copy.focusHeadline}
            start={8}
            size={copy.focusHeadlineSize}
            weight={700}
            font={fontMain}
            stagger={locale === "en" ? 0.8 : 1.4}
          />
        </div>

        <div style={{ marginTop: 48, display: "flex", flexDirection: "column", gap: 24 }}>
          {copy.focusCards.map((card, i) => {
            const isActive = activeIdx === i;
            const enter = prog(f, 16 + i * 16, 52 + i * 16, easeOut);
            const accent = accents[i];
            return (
              <div
                key={i}
                style={{
                  opacity: enter * (isActive ? 1 : 0.48),
                  transform: `translateX(${(1 - enter) * 32 + (isActive ? 10 : 0)}px) scale(${isActive ? 1.02 : 0.98})`,
                  padding: "26px 32px",
                  borderRadius: 28,
                  background: isActive ? "rgba(21,28,44,0.96)" : "rgba(17,22,34,0.72)",
                  border: `2px solid ${isActive ? accent : "rgba(255,255,255,0.10)"}`,
                  boxShadow: isActive ? `0 24px 64px rgba(0,0,0,0.52), 0 0 34px ${accent}33` : "none",
                }}
              >
                <div style={{ display: "flex", alignItems: "center", gap: 14 }}>
                  <span
                    style={{
                      padding: "5px 15px",
                      borderRadius: 999,
                      background: `${accent}2b`,
                      color: accent,
                      fontFamily: fontMain,
                      fontSize: 19,
                      fontWeight: 700,
                      whiteSpace: "nowrap",
                    }}
                  >
                    {card.badge}
                  </span>
                  <span style={{ fontFamily: fontMain, fontSize: 27, fontWeight: 700, color: "#FFFFFF" }}>
                    {card.title}
                  </span>
                </div>
                <div style={{ marginTop: 12, fontFamily: fontMain, fontSize: 21, fontWeight: 450, color: "#CBD5E1" }}>
                  {card.desc}
                </div>
              </div>
            );
          })}
        </div>
      </div>
    </AbsoluteFill>
  );
};

/** Act 4 (14-19s / 300f): Hardware Volume Keys + Dark-to-Light Frosted Glass Wipe */
const VolumeThemeAct: React.FC<{ dur: number; locale: PromoLocale }> = ({ dur, locale }) => {
  const f = useCurrentFrame();
  const copy = getCopy(locale).quickPanelVideo;
  const qp = getCopy(locale).quickPanel;
  const fontMain = fontForLocale(locale);

  // Hardware Volume+ presses at 15.1s (f=66) and 15.7s (f=102)
  const press1 = prog(f, 62, 72, easeOut) * (1 - prog(f, 74, 88, easeInOut));
  const press2 = prog(f, 98, 108, easeOut) * (1 - prog(f, 110, 124, easeInOut));
  const volPress = Math.max(press1, press2);
  const volPct = Math.round(58 + 5 * prog(f, 64, 82, easeOut) + 5 * prog(f, 100, 118, easeOut));

  // Theme wipe from Dark to Light starting at 17.0s (f=180..236)
  const theme = prog(f, 178, 238, easeInOut);
  const bg = interpolateColors(theme, [0, 1], [C.bg, "#E9EEF5"]);
  const ink = interpolateColors(theme, [0, 1], [C.ink, "#0F172A"]);
  const cardBg = interpolateColors(theme, [0, 1], ["rgba(17,22,34,0.92)", "rgba(255,255,255,0.92)"]);
  const cardBorder = interpolateColors(theme, [0, 1], ["rgba(52,211,153,0.75)", "rgba(15,23,42,0.14)"]);
  const subInk = interpolateColors(theme, [0, 1], ["#CBD5E1", "#475569"]);

  return (
    <AbsoluteFill style={{ background: bg, opacity: sceneFade(f, dur, 8, 12), overflow: "hidden" }}>
      <AbsoluteFill
        style={{
          background: `radial-gradient(circle at 68% 55%, rgba(52,211,153,${lerp(0.18, 0.10, theme)}) 0%, rgba(52,211,153,0) 45%), radial-gradient(circle at 30% 50%, rgba(76,141,255,${lerp(0.14, 0.16, theme)}) 0%, rgba(76,141,255,0) 45%)`,
        }}
      />

      {/* Top Header swaps cleanly from Hardware Volume Keys -> Frosted Dual Themes */}
      <div style={{ position: "absolute", left: 100, top: 64, zIndex: 20 }}>
        <Eyebrow text={copy.volThemeEyebrow1} start={2} out={162} color={C.emerald} />
        <div style={{ marginTop: 12 }}>
          <Reveal
            text={copy.volThemeHeadline1}
            start={8}
            out={162}
            size={copy.volThemeHeadlineSize}
            weight={700}
            font={fontMain}
            color={ink}
            stagger={locale === "en" ? 0.75 : 1.3}
          />
        </div>
      </div>

      <div style={{ position: "absolute", left: 100, top: 64, zIndex: 20 }}>
        <Eyebrow text={copy.volThemeEyebrow2} start={174} color={C.coral} />
        <div style={{ marginTop: 12 }}>
          <Reveal
            text={copy.volThemeHeadline2}
            start={180}
            size={copy.volThemeHeadlineSize}
            weight={700}
            font={fontMain}
            color={ink}
            stagger={locale === "en" ? 0.75 : 1.3}
          />
        </div>
      </div>

      {/* Left: Hardware Volume Card + Theme Indicator */}
      <div
        style={{
          position: "absolute",
          left: 100,
          top: 310,
          width: 780,
          display: "flex",
          flexDirection: "column",
          gap: 30,
          zIndex: 20,
        }}
      >
        <div
          style={{
            padding: "32px 34px",
            borderRadius: 32,
            background: cardBg,
            border: `2px solid ${cardBorder}`,
            boxShadow: `0 28px 70px rgba(0,0,0,${lerp(0.5, 0.12, theme)})`,
          }}
        >
          <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: 22 }}>
            <div
              style={{
                padding: "9px 20px",
                borderRadius: 14,
                background: volPress > 0.1 ? C.emerald : "rgba(52,211,153,0.18)",
                color: volPress > 0.1 ? "#0B0D12" : theme > 0.5 ? "#047857" : "#6EE7B7",
                fontFamily: FONT_MONO,
                fontSize: 22,
                fontWeight: 800,
                transform: `scale(${1 + volPress * 0.08})`,
              }}
            >
              VOL + / −
            </div>
            <div style={{ fontFamily: FONT_MONO, fontSize: 42, fontWeight: 800, color: theme > 0.5 ? "#0F8A5F" : "#6EE7B7" }}>
              {volPct}%
            </div>
          </div>

          <div style={{ fontFamily: fontMain, fontSize: 22, fontWeight: 600, color: ink, marginBottom: 14 }}>{qp.volLabel}</div>
          <div
            style={{
              width: "100%",
              height: 16,
              borderRadius: 999,
              background: theme > 0.5 ? "rgba(15,23,42,0.10)" : "rgba(255,255,255,0.12)",
              overflow: "hidden",
            }}
          >
            <div
              style={{
                width: `${volPct}%`,
                height: "100%",
                borderRadius: 999,
                background: "linear-gradient(90deg, #3B82F6 0%, #34D399 100%)",
              }}
            />
          </div>

          <div style={{ marginTop: 20, fontFamily: fontMain, fontSize: 20, fontWeight: 500, color: subInk }}>
            {copy.volSafeNote}
          </div>
        </div>

        {/* Dark / Light Frosted Theme Pill */}
        <div
          style={{
            display: "inline-flex",
            alignSelf: "flex-start",
            padding: 8,
            borderRadius: 999,
            background: cardBg,
            border: `1.5px solid ${theme > 0.5 ? "rgba(15,23,42,0.12)" : "rgba(255,255,255,0.16)"}`,
            gap: 8,
          }}
        >
          <div
            style={{
              padding: "11px 26px",
              borderRadius: 999,
              background: theme < 0.5 ? C.coral : "transparent",
              color: theme < 0.5 ? "#FFFFFF" : subInk,
              fontFamily: fontMain,
              fontSize: 21,
              fontWeight: 700,
            }}
          >
            {copy.themeDarkLabel}
          </div>
          <div
            style={{
              padding: "11px 26px",
              borderRadius: 999,
              background: theme >= 0.5 ? C.coral : "transparent",
              color: theme >= 0.5 ? "#FFFFFF" : subInk,
              fontFamily: fontMain,
              fontSize: 21,
              fontWeight: 700,
            }}
          >
            {copy.themeLightLabel}
          </div>
        </div>
      </div>

      {/* Right: Phone with live Hardware Volume Rocker + Dark->Light wipe */}
      <div
        style={{
          position: "absolute",
          left: 1138,
          top: 136,
          perspective: 2000,
          zIndex: 18,
        }}
      >
        <div style={{ position: "relative", transform: "rotateY(-6deg)", transformStyle: "preserve-3d" }}>
          <StagePhone src={uiImg(locale, "09-dark.png")} src2={uiImg(locale, "09-light.png")} wipe2={theme} w={406} />

          <div
            style={{
              position: "absolute",
              right: -13,
              top: 245,
              width: 11,
              height: 118,
              borderRadius: "0 6px 6px 0",
              background: volPress > 0.1 ? C.emerald : "rgba(148,163,184,0.62)",
              boxShadow: volPress > 0.1 ? "0 0 24px rgba(52,211,153,0.95)" : "none",
              transform: `translateX(${volPress * -3}px)`,
            }}
          />

          <div
            style={{
              position: "absolute",
              right: -195,
              top: 268,
              opacity: 1 - prog(f, 165, 190, easeOut),
              transform: `scale(${1 + volPress * 0.08})`,
              padding: "11px 20px",
              borderRadius: 16,
              background: "rgba(15,23,42,0.94)",
              border: "2px solid rgba(52,211,153,0.92)",
              boxShadow: "0 16px 38px rgba(0,0,0,0.52)",
              display: "flex",
              alignItems: "center",
              gap: 10,
              whiteSpace: "nowrap",
            }}
          >
            <span style={{ fontFamily: FONT_MONO, fontSize: 19, fontWeight: 800, color: "#6EE7B7" }}>VOL +</span>
            <span style={{ fontFamily: FONT_MONO, fontSize: 22, fontWeight: 800, color: "#FFFFFF" }}>{volPct}%</span>
          </div>
        </div>
      </div>
    </AbsoluteFill>
  );
};

/** Act 5 (19-24s / 300f): Permanent Crisp End-Frame Lockup (never fades to black) */
const EndLockupAct: React.FC<{ dur: number; locale: PromoLocale }> = ({ locale }) => {
  const f = useCurrentFrame();
  const outro = getCopy(locale).outro;
  const copy = getCopy(locale).quickPanelVideo;
  const fontMain = fontForLocale(locale);

  const size = 204;
  const rings = prog(f, 0, 38, easeInOut);
  const core = prog(f, 4, 28, easeOut);
  const play = prog(f, 14, 36);
  const arc = prog(f, 18, 46, easeInOut);
  const pulse = Math.max(0, Math.sin(((f - 48) / BEAT) * Math.PI)) * prog(f, 48, 72) * (1 - prog(f, 195, 235));
  const phonesIn = prog(f, 6, 54, easeOut);

  const barIn = prog(f, 52, 86, easeOut);
  const cursorMove = prog(f, 95, 142, easeInOut);
  const cursorX = lerp(1835, 1692, cursorMove);
  const cursorY = lerp(1020, 824, cursorMove);
  const hovering = prog(f, 130, 146, easeOut);
  const clickDown = prog(f, 152, 159, easeOut);
  const clickUp = prog(f, 159, 172, easeOut);
  const pressing = Math.max(0, clickDown - clickUp);
  const rippleT = f >= 154 ? Math.min(1, (f - 154) / 38) : 0;

  return (
    <AbsoluteFill style={{ background: C.bg }}>
      <AbsoluteFill
        style={{
          background: `radial-gradient(circle at 28% 54%, rgba(76,141,255,${0.16 * phonesIn}) 0%, rgba(46,108,246,0) 44%), radial-gradient(circle at 69% 32%, rgba(255,209,102,${0.15 * core}) 0%, rgba(229,109,72,${0.14 * core}) 20%, rgba(229,109,72,0) 46%)`,
        }}
      />
      <svg width="1920" height="1080" style={{ position: "absolute", inset: 0 }}>
        <Ripple cx={1315} cy={235} t={f / 70} maxR={600} color={C.coral} width={2} />
        <Ripple cx={1315} cy={235} t={(f - 12) / 80} maxR={680} color={C.warmGold} width={1.3} />
      </svg>

      {/* Left: Dual-Theme Frosted Quick Panel Phones */}
      <div style={{ position: "absolute", left: 112, top: 108, perspective: 2000, opacity: phonesIn }}>
        <div
          style={{
            position: "absolute",
            left: 256,
            top: 34,
            transform: `translateY(${(1 - phonesIn) * 95}px) rotateY(-13deg) rotateX(4deg) scale(0.92)`,
            transformStyle: "preserve-3d",
          }}
        >
          <StagePhone src={uiImg(locale, "09-light.png")} w={354} />
        </div>
        <div
          style={{
            position: "absolute",
            left: 0,
            top: 0,
            transform: `translateY(${(1 - phonesIn) * 75}px) rotateY(11deg) rotateX(3deg)`,
            transformStyle: "preserve-3d",
          }}
        >
          <StagePhone src={uiImg(locale, "09-dark.png")} w={364} />
        </div>
      </div>

      {/* Right: Brand Mark + Title + Quick Panel Feature Tags + GitHub Search CTA */}
      <div style={{ position: "absolute", left: 1315 - size / 2, top: 222 - size / 2 }}>
        <LogoMark size={size} rings={rings} core={core} play={play} arc={arc} pulse={pulse} />
      </div>

      <div
        style={{
          position: "absolute",
          left: 830,
          top: 362,
          width: 970,
          display: "flex",
          justifyContent: "center",
          alignItems: "baseline",
          gap: 24,
        }}
      >
        <Reveal
          text={outro.brandPrimary}
          start={18}
          size={outro.brandSecondary ? 92 : 84}
          weight={800}
          font={outro.brandSecondary ? fontMain : FONT_EN}
          stagger={outro.brandSecondary ? 3.5 : 1.8}
          tracking={outro.brandSecondary ? 5 : 1.5}
          color="#FFFFFF"
        />
        {outro.brandSecondary ? (
          <Reveal text={outro.brandSecondary} start={26} size={42} weight={600} font={FONT_EN} stagger={1.2} color={C.ink2} />
        ) : null}
      </div>

      <div style={{ position: "absolute", left: 830, top: 486, width: 970, textAlign: "center" }}>
        <Reveal text={copy.outroSubtitle} start={26} size={34} weight={600} font={fontMain} color="#E2E8F0" stagger={0.8} />
      </div>

      <div
        style={{
          position: "absolute",
          left: 830,
          top: 562,
          width: 970,
          display: "flex",
          justifyContent: "center",
          gap: 14,
          flexWrap: "wrap",
          opacity: prog(f, 38, 68, easeOut),
        }}
      >
        {copy.outroTags.map((tag, i) => (
          <div
            key={i}
            style={{
              padding: "10px 22px",
              borderRadius: 999,
              background: "rgba(21,26,37,0.88)",
              border: "1.5px solid rgba(255,255,255,0.16)",
              fontFamily: fontMain,
              fontSize: 20,
              fontWeight: 600,
              color: "#E2E8F0",
              whiteSpace: "nowrap",
            }}
          >
            {tag}
          </div>
        ))}
      </div>

      {/* GitHub Search Box CTA */}
      <div
        style={{
          position: "absolute",
          left: 878,
          top: 760,
          width: 874,
          height: 112,
          borderRadius: 56,
          background: "rgba(18,22,31,0.96)",
          border: `2px solid ${hovering > 0.4 ? "rgba(229,109,72,0.88)" : "rgba(255,255,255,0.20)"}`,
          boxShadow: "0 28px 72px rgba(0,0,0,0.58)",
          display: "flex",
          alignItems: "center",
          padding: "0 18px 0 32px",
          gap: 20,
          opacity: barIn,
          transform: `translateY(${(1 - barIn) * 24}px)`,
        }}
      >
        <GitHubIcon size={44} color="#FFFFFF" />
        <div style={{ flex: 1, fontFamily: FONT_MONO, fontSize: 30, fontWeight: 700, color: "#FFFFFF" }}>
          JasirVoriya/cozyting
        </div>
        <div
          style={{
            position: "relative",
            padding: "16px 34px",
            borderRadius: 999,
            background: C.coral,
            color: "#FFFFFF",
            fontFamily: fontMain,
            fontSize: 24,
            fontWeight: 700,
            transform: `scale(${1 - pressing * 0.06})`,
            boxShadow: "0 10px 26px rgba(229,109,72,0.42)",
          }}
        >
          {outro.searchButton}
          {rippleT > 0 && rippleT < 1 && (
            <div
              style={{
                position: "absolute",
                inset: -14 * rippleT,
                borderRadius: 999,
                border: "3px solid rgba(255,255,255,0.85)",
                opacity: 1 - rippleT,
              }}
            />
          )}
        </div>
      </div>

      {f >= 90 && <MouseCursor x={cursorX} y={cursorY} pressing={pressing} />}
    </AbsoluteFill>
  );
};

export const QuickPanelFeaturePromo: React.FC<{ locale?: PromoLocale }> = ({ locale = "zh" }) => (
  <AbsoluteFill style={{ background: C.bg }}>
    <Audio
      src={staticFile("quick-panel-music.wav")}
      volume={(f) => interpolate(f, [QUICK_PANEL_TOTAL_FRAMES - 36, QUICK_PANEL_TOTAL_FRAMES], [1, 0], { extrapolateLeft: "clamp" })}
    />
    <Sequence from={0} durationInFrames={180}>
      <HookAct dur={180} locale={locale} />
    </Sequence>
    <Sequence from={180} durationInFrames={300}>
      <EntryAct dur={300} locale={locale} />
    </Sequence>
    <Sequence from={480} durationInFrames={360}>
      <FocusAct dur={360} locale={locale} />
    </Sequence>
    <Sequence from={840} durationInFrames={300}>
      <VolumeThemeAct dur={300} locale={locale} />
    </Sequence>
    <Sequence from={1140} durationInFrames={300}>
      <EndLockupAct dur={300} locale={locale} />
    </Sequence>
    <Finish />
  </AbsoluteFill>
);
