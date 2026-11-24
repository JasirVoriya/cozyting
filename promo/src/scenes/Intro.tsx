import React from "react";
import { AbsoluteFill, useCurrentFrame } from "remotion";
import {
  C,
  FONT_EN,
  FONT_MONO,
  fontForLocale,
  uiImg,
  prog,
  easeInOut,
  easeOut,
  BEAT,
  lerp,
  type PromoLocale,
} from "../theme";
import { LogoMark, Ripple, Speaker, StagePhone, UiSlice } from "../components";
import { getCopy } from "../i18n";

const CUT_LEN = 45; // 0.75s @ 60fps
const HOOK_END = CUT_LEN * 4; // 180 frames = 3.00s
const INTRO_DUR = 360; // 6.00s (3 bars @ 120 BPM)

export const Intro: React.FC<{ locale: PromoLocale }> = ({ locale }) => {
  const f = useCurrentFrame();
  const fullCopy = getCopy(locale);
  const copy = fullCopy.intro;
  const fontMain = fontForLocale(locale);

  // ==================== PART 1: 0.00s – 3.00s (4 RAPID 0.75s HOOK CUTS) ====================
  if (f < HOOK_END) {
    const cutIdx = Math.min(3, Math.floor(f / CUT_LEN)) as 0 | 1 | 2 | 3;
    const lf = f - cutIdx * CUT_LEN; // local frame 0..44 inside current cut
    const snap = prog(lf, 0, 9, easeOut); // 0.15s crisp snap-in, zero sluggish char stagger
    const flash = 1 - prog(lf, 0, 6, easeOut);
    const camScale = lerp(0.992, 1.024, lf / CUT_LEN);
    const cutCopy = copy.hookCuts[cutIdx];

    const cutAccent =
      cutIdx === 0 ? C.coral : cutIdx === 1 ? "#22D3EE" : cutIdx === 2 ? "#38BDF8" : C.coral;

    return (
      <AbsoluteFill style={{ background: C.bg, overflow: "hidden" }}>
        {/* Dynamic ambient glow per cut */}
        <AbsoluteFill
          style={{
            background:
              cutIdx === 0
                ? "radial-gradient(circle at 22% 58%, rgba(76,141,255,0.18) 0%, rgba(76,141,255,0) 42%), radial-gradient(circle at 80% 58%, rgba(255,92,108,0.22) 0%, rgba(255,92,108,0) 42%)"
                : cutIdx === 1
                ? "radial-gradient(circle at 26% 62%, rgba(244,63,94,0.18) 0%, rgba(244,63,94,0) 42%), radial-gradient(circle at 75% 58%, rgba(34,211,238,0.18) 0%, rgba(34,211,238,0) 44%)"
                : cutIdx === 2
                ? "radial-gradient(circle at 24% 60%, rgba(56,189,248,0.20) 0%, rgba(56,189,248,0) 44%), radial-gradient(circle at 76% 60%, rgba(255,92,108,0.22) 0%, rgba(255,92,108,0) 44%)"
                : "radial-gradient(circle at 26% 60%, rgba(255,92,108,0.20) 0%, rgba(255,92,108,0) 44%), radial-gradient(circle at 76% 58%, rgba(56,189,248,0.18) 0%, rgba(56,189,248,0) 44%)",
          }}
        />

        {/* Top 4-segment kinetic progress rail */}
        <div
          style={{
            position: "absolute",
            left: 100,
            right: 100,
            top: 42,
            display: "flex",
            gap: 14,
            zIndex: 30,
          }}
        >
          {[0, 1, 2, 3].map((idx) => {
            const fill = idx < cutIdx ? 1 : idx === cutIdx ? (lf + 1) / CUT_LEN : 0;
            return (
              <div
                key={idx}
                style={{
                  flex: 1,
                  height: 5,
                  borderRadius: 99,
                  background: "rgba(255,255,255,0.12)",
                  overflow: "hidden",
                }}
              >
                <div
                  style={{
                    width: `${fill * 100}%`,
                    height: "100%",
                    borderRadius: 99,
                    background: idx === cutIdx ? C.coral : "rgba(255,255,255,0.72)",
                    boxShadow: idx === cutIdx ? "0 0 14px rgba(255,92,108,0.75)" : "none",
                  }}
                />
              </div>
            );
          })}
        </div>

        {/* Instant 0.15s Snap Header */}
        <div
          style={{
            position: "absolute",
            left: 100,
            right: 100,
            top: 74,
            zIndex: 25,
            opacity: snap,
            transform: `translateY(${(1 - snap) * 24}px) scale(${lerp(0.97, 1, snap)})`,
            transformOrigin: "left top",
            filter: `blur(${(1 - snap) * 4}px)`,
          }}
        >
          <div style={{ display: "flex", alignItems: "center", gap: 14 }}>
            <div style={{ width: 34, height: 3.5, borderRadius: 3, background: cutAccent }} />
            <div
              style={{
                fontFamily: locale === "en" ? FONT_EN : fontMain,
                fontSize: 22,
                fontWeight: 700,
                letterSpacing: locale === "en" ? 3.2 : 2.2,
                color: cutAccent,
              }}
            >
              {cutCopy.eyebrow}
            </div>
          </div>
          <div
            style={{
              marginTop: 14,
              fontFamily: fontMain,
              fontSize: cutCopy.headlineSize,
              fontWeight: 800,
              color: "#FFFFFF",
              letterSpacing: locale === "en" ? -0.6 : 1,
              lineHeight: 1.16,
              whiteSpace: "nowrap",
              textShadow: "0 10px 36px rgba(0,0,0,0.55)",
            }}
          >
            {cutCopy.headline}
          </div>
        </div>

        {/* Visual Hero Stage (subtle kinetic push across each 0.75s cut) */}
        <div
          style={{
            position: "absolute",
            inset: 0,
            transform: `scale(${camScale})`,
            transformOrigin: "center 60%",
          }}
        >
          {/* ---------- CUT 1: HUAWEI / SAMSUNG / OPPO / PIXEL -> XIAOAI SPEAKER ---------- */}
          {cutIdx === 0 && (
            <HookCutBrands
              lf={lf}
              locale={locale}
              fontMain={fontMain}
              cut={copy.hookCuts[0]}
            />
          )}

          {/* ---------- CUT 2: NETEASE / QQ MUSIC / SODA AUTO-SWITCH ---------- */}
          {cutIdx === 1 && (
            <HookCutAutoSwitch
              lf={lf}
              locale={locale}
              fontMain={fontMain}
              cut={copy.hookCuts[1]}
              apps={fullCopy.oneApp.apps}
            />
          )}

          {/* ---------- CUT 3: LEFT SHORT VIDEO x RIGHT LIVING ROOM SPEAKER ---------- */}
          {cutIdx === 2 && (
            <HookCutDualChannel
              lf={lf}
              f={f}
              locale={locale}
              fontMain={fontMain}
              cut={copy.hookCuts[2]}
              oneApp={fullCopy.oneApp}
            />
          )}

          {/* ---------- CUT 4: 4-ROOM PHASE LOCK + 150ms LIVE HOT-SWITCH ---------- */}
          {cutIdx === 3 && (
            <HookCutMultiRoomLatency
              lf={lf}
              f={f}
              locale={locale}
              fontMain={fontMain}
              cut={copy.hookCuts[3]}
              multiRoom={fullCopy.multiRoom}
            />
          )}
        </div>

        {/* Crisp 6-frame cut-edge kinetic flash */}
        {flash > 0.01 && (
          <AbsoluteFill
            style={{
              pointerEvents: "none",
              background: `radial-gradient(circle at 50% 50%, rgba(255,255,255,${0.12 * flash}) 0%, rgba(255,92,108,${0.08 * flash}) 45%, rgba(0,0,0,0) 78%)`,
            }}
          />
        )}
      </AbsoluteFill>
    );
  }

  // ==================== PART 2: 3.00s – 6.00s (SNAP BRAND LOCKUP + COVER FRAME) ====================
  const bf = f - HOOK_END; // 0..179
  const snapBrand = prog(bf, 0, 12, easeOut);
  const snapTagline = prog(bf, 5, 17, easeOut);
  const snapSub = prog(bf, 9, 21, easeOut);
  const phoneSnap = prog(bf, 2, 22, easeOut);
  const popHeader = prog(bf, 18, 36, easeOut);
  const popPlayer = prog(bf, 24, 44, easeOut);
  const dropFlash = 1 - prog(bf, 0, 10, easeOut);
  const out = prog(bf, INTRO_DUR - HOOK_END - 18, INTRO_DUR - HOOK_END, easeInOut);

  const beatPulse = Math.max(0, Math.sin((bf / BEAT) * Math.PI)) * prog(bf, 0, 14);
  const floatY = Math.sin(bf / 36) * 5;
  const playProgress = prog(bf, 12, 172, (t) => t);
  const sec = 34 + Math.floor(playProgress * 4);
  const pillColors = ["#34D399", C.blue, C.coral] as const;
  const logoSize = 188;

  return (
    <AbsoluteFill style={{ background: C.bg, overflow: "hidden" }}>
      {/* Dual ambient glow: coral behind brand, album blue behind real hero phone */}
      <AbsoluteFill
        style={{
          background: `radial-gradient(circle at 24% 46%, rgba(255,92,108,${0.14 * snapBrand}) 0%, rgba(255,92,108,0) 38%), radial-gradient(circle at 73% 54%, rgba(46,108,246,${0.24 * phoneSnap}) 0%, rgba(46,108,246,0) 44%)`,
        }}
      />

      {/* Crisp radial drop ripples on the 3.0s brand lockup beat */}
      <svg width="1920" height="1080" style={{ position: "absolute", inset: 0, opacity: 1 - out }}>
        <Ripple cx={202} cy={329} t={bf / 42} maxR={480} color={C.coral} width={2.4} />
        <Ripple cx={202} cy={329} t={(bf - 8) / 48} maxR={560} color={C.ink} width={1.2} />
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
        {/* Logo + Brand Name Row */}
        <div
          style={{
            display: "flex",
            alignItems: "center",
            gap: 28,
            opacity: snapBrand,
            transform: `translateY(${(1 - snapBrand) * 22}px) scale(${lerp(0.92, 1, snapBrand)})`,
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
            transform: `translateY(${(1 - snapTagline) * 18}px)`,
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
            const p = prog(bf, 14 + i * 5, 28 + i * 5, easeOut);
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
            transform: `translateY(${(1 - phoneSnap) * 90 + floatY}px) rotateY(${lerp(-16, -11, phoneSnap)}deg) rotateX(${lerp(8, 4, phoneSnap)}deg)`,
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

      {/* Subtle impact snap flash on 3.00s landing beat */}
      {dropFlash > 0.01 && (
        <AbsoluteFill
          style={{
            pointerEvents: "none",
            background: `radial-gradient(circle at 24% 38%, rgba(255,92,108,${0.22 * dropFlash}) 0%, rgba(255,255,255,${0.10 * dropFlash}) 28%, rgba(0,0,0,0) 68%)`,
          }}
        />
      )}
    </AbsoluteFill>
  );
};

// ============================================================================
// HOOK CUT 1: HUAWEI / SAMSUNG / OPPO / PIXEL -> XIAOAI SPEAKER
// ============================================================================
const HookCutBrands: React.FC<{
  lf: number;
  locale: PromoLocale;
  fontMain: string;
  cut: ReturnType<typeof getCopy>["intro"]["hookCuts"][0];
}> = ({ lf, locale, fontMain, cut }) => {
  const beam = prog(lf, 3, 22, easeOut);
  const pulseT = (lf % 22) / 22;

  return (
    <div style={{ position: "absolute", inset: 0 }}>
      {/* Connecting SVG beams from left brand grid -> center phone -> right speaker */}
      <svg width="1920" height="1080" style={{ position: "absolute", inset: 0 }}>
        {[0, 1].map((row) => {
          const y0 = 420 + row * 195;
          const y1 = 540 + (row === 0 ? -36 : 36);
          return (
            <g key={row}>
              <path
                d={`M 680 ${y0} C 760 ${y0}, 790 ${y1}, 865 ${y1}`}
                fill="none"
                stroke="rgba(255,92,108,0.55)"
                strokeWidth={3.5}
                strokeDasharray="10 8"
                strokeDashoffset={-lf * 2.2}
                opacity={beam}
              />
              <circle
                cx={lerp(680, 865, pulseT)}
                cy={lerp(y0, y1, pulseT)}
                r={7}
                fill={C.coral}
                opacity={beam}
              />
            </g>
          );
        })}
        {/* Phone -> Speaker main thick glowing beam */}
        <path
          d="M 1140 570 L 1365 570"
          fill="none"
          stroke={C.coral}
          strokeWidth={5}
          strokeLinecap="round"
          strokeDasharray="14 10"
          strokeDashoffset={-lf * 3.5}
          opacity={beam}
        />
        <circle cx={lerp(1140, 1365, pulseT)} cy={570} r={9} fill="#FFFFFF" opacity={beam} />
        <circle cx={lerp(1140, 1365, (pulseT + 0.5) % 1)} cy={570} r={8} fill={C.coral} opacity={beam} />
      </svg>

      {/* Left 2x2 Brand Cards */}
      <div
        style={{
          position: "absolute",
          left: 100,
          top: 342,
          width: 580,
          display: "grid",
          gridTemplateColumns: "1fr 1fr",
          gap: 24,
        }}
      >
        {cut.brands.map((b, i) => {
          const p = prog(lf, i * 2, 11 + i * 2, easeOut);
          return (
            <div
              key={b.name}
              style={{
                height: 170,
                borderRadius: 28,
                padding: "26px 28px",
                background: "rgba(21,27,39,0.96)",
                border: "2px solid rgba(255,92,108,0.5)",
                boxShadow: "0 22px 48px rgba(0,0,0,0.48), 0 0 28px rgba(255,92,108,0.14)",
                display: "flex",
                flexDirection: "column",
                justifyContent: "space-between",
                opacity: p,
                transform: `translateX(${(1 - p) * -28}px) scale(${lerp(0.94, 1, p)})`,
              }}
            >
              <div style={{ display: "flex", alignItems: "center", gap: 14 }}>
                <div
                  style={{
                    width: 14,
                    height: 14,
                    borderRadius: 99,
                    background: C.coral,
                    boxShadow: "0 0 14px rgba(255,92,108,0.8)",
                  }}
                />
                <div
                  style={{
                    fontFamily: locale === "zh" ? fontMain : FONT_EN,
                    fontSize: 38,
                    fontWeight: 800,
                    color: "#FFFFFF",
                  }}
                >
                  {b.name}
                </div>
              </div>
              <div
                style={{
                  fontFamily: FONT_EN,
                  fontSize: 22,
                  fontWeight: 600,
                  color: "#94A3B8",
                  letterSpacing: 1,
                }}
              >
                {b.sub}
              </div>
            </div>
          );
        })}
      </div>

      {/* Center Real Phone + MiPlay Bridge Pill */}
      <div
        style={{
          position: "absolute",
          left: 862,
          top: 298,
          display: "flex",
          flexDirection: "column",
          alignItems: "center",
          opacity: prog(lf, 2, 12, easeOut),
          transform: `translateY(${(1 - prog(lf, 2, 12, easeOut)) * 34}px)`,
        }}
      >
        <StagePhone src={uiImg(locale, "01-dark.png")} w={252} />
        <div
          style={{
            marginTop: 18,
            padding: "9px 22px",
            borderRadius: 999,
            background: "rgba(255,92,108,0.18)",
            border: "1.5px solid rgba(255,92,108,0.65)",
            fontFamily: fontMain,
            fontSize: 21,
            fontWeight: 700,
            color: "#FFD4D9",
            whiteSpace: "nowrap",
          }}
        >
          {cut.bridgeLabel}
        </div>
      </div>

      {/* Right Hero Xiaomi / XiaoAi Speaker Card */}
      <div
        style={{
          position: "absolute",
          right: 100,
          top: 342,
          width: 450,
          height: 454,
          borderRadius: 34,
          background: "linear-gradient(155deg, rgba(30,22,30,0.96) 0%, rgba(16,20,29,0.96) 100%)",
          border: "2.5px solid rgba(255,92,108,0.7)",
          boxShadow: "0 30px 70px rgba(0,0,0,0.55), 0 0 55px rgba(255,92,108,0.22)",
          display: "flex",
          flexDirection: "column",
          alignItems: "center",
          justifyContent: "center",
          opacity: prog(lf, 3, 13, easeOut),
          transform: `scale(${lerp(0.92, 1, prog(lf, 3, 13, easeOut))})`,
        }}
      >
        <svg width="280" height="250" viewBox="-150 -135 300 270" style={{ overflow: "visible" }}>
          <Speaker x={0} y={0} s={1.85} active={1} />
        </svg>
        <div
          style={{
            marginTop: 12,
            fontFamily: fontMain,
            fontSize: 38,
            fontWeight: 800,
            color: "#FFFFFF",
          }}
        >
          {cut.speakerTitle}
        </div>
        <div
          style={{
            marginTop: 16,
            padding: "10px 24px",
            borderRadius: 999,
            background: "rgba(52,211,153,0.16)",
            border: "1.5px solid rgba(52,211,153,0.55)",
            fontFamily: fontMain,
            fontSize: 22,
            fontWeight: 700,
            color: "#6EE7B7",
            whiteSpace: "nowrap",
          }}
        >
          {cut.speakerBadge}
        </div>
      </div>
    </div>
  );
};

// ============================================================================
// HOOK CUT 2: NETEASE / QQ MUSIC / SODA AUTO-SWITCH + MUSIC-ONLY ISOLATION
// ============================================================================
const HookCutAutoSwitch: React.FC<{
  lf: number;
  locale: PromoLocale;
  fontMain: string;
  cut: ReturnType<typeof getCopy>["intro"]["hookCuts"][1];
  apps: ReturnType<typeof getCopy>["oneApp"]["apps"];
}> = ({ lf, locale, fontMain, cut, apps }) => {
  // Rapidly hand off across the 3 music apps within the 45-frame cut
  const activeIdx = lf < 14 ? 0 : lf < 29 ? 1 : 2;
  const activeApp = apps[activeIdx];
  const streamY = 398 + activeIdx * 176;
  const dotT = (lf % 15) / 15;
  const eqBars = [0.7, 1.0, 0.65, 0.9, 0.55, 0.82].map(
    (b, i) => 0.3 + b * (0.4 + 0.6 * Math.abs(Math.sin(lf * 0.28 + i * 1.1)))
  );

  return (
    <div style={{ position: "absolute", inset: 0 }}>
      {/* Glowing stream from active music app card to right-hand real player */}
      <svg width="1920" height="1080" style={{ position: "absolute", inset: 0 }}>
        <path
          d={`M 730 ${streamY} C 835 ${streamY}, 845 550, 965 550`}
          fill="none"
          stroke={activeApp.color}
          strokeWidth={5}
          strokeDasharray="12 9"
          strokeDashoffset={-lf * 3.8}
        />
        <circle cx={lerp(730, 965, dotT)} cy={lerp(streamY, 550, dotT)} r={9} fill="#FFFFFF" />
        <circle cx={lerp(730, 965, (dotT + 0.5) % 1)} cy={lerp(streamY, 550, (dotT + 0.5) % 1)} r={8} fill={activeApp.color} />
      </svg>

      {/* Left Stack: 3 Iconic Music App Cards */}
      <div
        style={{
          position: "absolute",
          left: 100,
          top: 324,
          width: 610,
          display: "flex",
          flexDirection: "column",
          gap: 24,
        }}
      >
        {apps.map((app, idx) => {
          const isActive = idx === activeIdx;
          const p = prog(lf, idx * 2, 10 + idx * 2, easeOut);
          return (
            <div
              key={app.id}
              style={{
                height: 152,
                borderRadius: 28,
                padding: "22px 26px",
                background: isActive ? "rgba(255,255,255,0.97)" : "rgba(20,25,36,0.92)",
                border: `2.5px solid ${isActive ? app.color : "rgba(255,255,255,0.16)"}`,
                boxShadow: isActive
                  ? `0 24px 60px rgba(0,0,0,0.58), 0 0 38px ${app.color}55`
                  : "0 14px 30px rgba(0,0,0,0.38)",
                transform: `translateX(${isActive ? 28 : 0}px) scale(${isActive ? 1.035 : 0.98})`,
                opacity: p * (isActive ? 1 : 0.72),
                display: "flex",
                alignItems: "center",
                justifyContent: "space-between",
                gap: 18,
              }}
            >
              <div style={{ display: "flex", alignItems: "center", gap: 18, minWidth: 0 }}>
                <div
                  style={{
                    width: 68,
                    height: 68,
                    borderRadius: 20,
                    background: app.iconBg,
                    display: "flex",
                    alignItems: "center",
                    justifyContent: "center",
                    fontSize: 34,
                    color: "#FFFFFF",
                    fontWeight: 800,
                    flexShrink: 0,
                  }}
                >
                  {app.badgeGlyph}
                </div>
                <div style={{ minWidth: 0 }}>
                  <div
                    style={{
                      fontFamily: fontMain,
                      fontSize: 32,
                      fontWeight: 800,
                      color: isActive ? "#0F172A" : "#F8FAFC",
                      whiteSpace: "nowrap",
                    }}
                  >
                    {app.name}
                  </div>
                  <div
                    style={{
                      marginTop: 6,
                      fontFamily: fontMain,
                      fontSize: 21,
                      fontWeight: 500,
                      color: isActive ? "#475569" : "#94A3B8",
                      whiteSpace: "nowrap",
                    }}
                  >
                    {app.track}
                  </div>
                </div>
              </div>
              {isActive && (
                <div
                  style={{
                    padding: "8px 16px",
                    borderRadius: 999,
                    background: app.color,
                    color: "#FFFFFF",
                    fontFamily: FONT_MONO,
                    fontSize: 18,
                    fontWeight: 800,
                    whiteSpace: "nowrap",
                    flexShrink: 0,
                  }}
                >
                  LIVE
                </div>
              )}
            </div>
          );
        })}
      </div>

      {/* Right Stage: Floating Switch Pill + Real Album Player Slice + Isolation Badge */}
      <div
        style={{
          position: "absolute",
          right: 100,
          top: 324,
          width: 850,
          display: "flex",
          flexDirection: "column",
          alignItems: "flex-start",
          gap: 24,
          opacity: prog(lf, 2, 11, easeOut),
          transform: `translateX(${(1 - prog(lf, 2, 11, easeOut)) * 28}px)`,
        }}
      >
        <div style={{ display: "flex", alignItems: "center", gap: 16, flexWrap: "wrap" }}>
          <div
            style={{
              padding: "11px 24px",
              borderRadius: 999,
              background: `${activeApp.color}28`,
              border: `2px solid ${activeApp.color}`,
              fontFamily: fontMain,
              fontSize: 24,
              fontWeight: 800,
              color: "#FFFFFF",
              boxShadow: `0 0 28px ${activeApp.color}44`,
            }}
          >
            {cut.switchBadge}
          </div>
          <div
            style={{
              padding: "11px 22px",
              borderRadius: 999,
              background: "rgba(20,26,38,0.92)",
              border: "1.5px solid rgba(255,255,255,0.2)",
              fontFamily: fontMain,
              fontSize: 22,
              fontWeight: 600,
              color: "#E2E8F0",
            }}
          >
            {cut.speakerStatus}
          </div>
        </div>

        {/* Real cropped Album Player Card from official localized screenshot */}
        <div
          style={{
            padding: "24px 24px",
            borderRadius: 34,
            background: "rgba(18,24,35,0.94)",
            border: `2px solid ${activeApp.color}88`,
            boxShadow: `0 30px 70px rgba(0,0,0,0.6), 0 0 42px ${activeApp.color}22`,
            display: "flex",
            alignItems: "center",
            justifyContent: "space-between",
            gap: 24,
            width: "100%",
          }}
        >
          <UiSlice
            src={uiImg(locale, "01-dark.png")}
            rect={[32, 102, 614, 332]}
            radius={26}
            scale={0.98}
            elevation={0.9}
            accentBorder={`${activeApp.color}AA`}
            style={{ flexShrink: 0 }}
          />
          <div style={{ display: "flex", alignItems: "flex-end", gap: 9, height: 150, paddingRight: 10, flexShrink: 0 }}>
            {eqBars.map((h, i) => (
              <div
                key={i}
                style={{
                  width: 14,
                  height: Math.round(h * 145),
                  borderRadius: 8,
                  background: `linear-gradient(180deg, #FFFFFF 0%, ${activeApp.color} 100%)`,
                  boxShadow: `0 0 14px ${activeApp.color}88`,
                }}
              />
            ))}
          </div>
        </div>

        {/* Bottom Isolation Callout */}
        <div
          style={{
            width: "100%",
            padding: "16px 26px",
            borderRadius: 22,
            background: "rgba(52,211,153,0.12)",
            border: "1.5px solid rgba(52,211,153,0.48)",
            fontFamily: fontMain,
            fontSize: 24,
            fontWeight: 700,
            color: "#6EE7B7",
          }}
        >
          🔒 {cut.isolateBadge}
        </div>
      </div>
    </div>
  );
};

// ============================================================================
// HOOK CUT 3: LEFT PHONE SHORT VIDEO + VOICE x RIGHT LIVING ROOM SPEAKER
// ============================================================================
const HookCutDualChannel: React.FC<{
  lf: number;
  f: number;
  locale: PromoLocale;
  fontMain: string;
  cut: ReturnType<typeof getCopy>["intro"]["hookCuts"][2];
  oneApp: ReturnType<typeof getCopy>["oneApp"];
}> = ({ lf, f, locale, fontMain, cut, oneApp }) => {
  const enter = prog(lf, 0, 10, easeOut);
  const voicePop = prog(lf, 8, 20, easeOut);
  const vid = oneApp.shortVideos[0];
  const eqBars = [0.65, 1.0, 0.78, 0.94, 0.6, 0.88, 0.72].map(
    (b, i) => 0.3 + b * (0.45 + 0.55 * Math.abs(Math.sin(f * 0.25 + i * 1.1)))
  );

  return (
    <div
      style={{
        position: "absolute",
        left: 100,
        right: 100,
        top: 320,
        height: 660,
        display: "flex",
        alignItems: "stretch",
        justifyContent: "space-between",
        gap: 36,
      }}
    >
      {/* LEFT PANEL: Phone Short Video + Voice Message (Local Only) */}
      <div
        style={{
          flex: 1,
          borderRadius: 34,
          padding: 30,
          background: "linear-gradient(155deg, rgba(16,32,52,0.95) 0%, rgba(14,19,29,0.96) 100%)",
          border: "2.5px solid rgba(56,189,248,0.62)",
          boxShadow: "0 28px 68px rgba(0,0,0,0.55), 0 0 42px rgba(56,189,248,0.16)",
          display: "flex",
          flexDirection: "column",
          justifyContent: "space-between",
          opacity: enter,
          transform: `translateX(${(1 - enter) * -32}px)`,
        }}
      >
        <div
          style={{
            padding: "10px 22px",
            borderRadius: 999,
            background: "rgba(56,189,248,0.18)",
            border: "1.5px solid rgba(56,189,248,0.55)",
            fontFamily: fontMain,
            fontSize: 25,
            fontWeight: 800,
            color: "#7DD3FC",
            alignSelf: "flex-start",
          }}
        >
          {cut.leftBadge}
        </div>

        {/* Short video + Voice card showcase */}
        <div style={{ display: "flex", alignItems: "center", gap: 26, marginTop: 18, flex: 1 }}>
          {/* Vertical Short Video Frame */}
          <div
            style={{
              width: 296,
              height: 445,
              borderRadius: 32,
              background: vid.bg,
              border: "3px solid rgba(255,255,255,0.22)",
              padding: 16,
              display: "flex",
              flexDirection: "column",
              justifyContent: "space-between",
              position: "relative",
              flexShrink: 0,
              boxShadow: "0 20px 44px rgba(0,0,0,0.5)",
            }}
          >
            <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: 8 }}>
              <span
                style={{
                  padding: "5px 10px",
                  borderRadius: 999,
                  background: "rgba(0,0,0,0.48)",
                  fontFamily: fontMain,
                  fontSize: 14,
                  fontWeight: 700,
                  color: "#FFFFFF",
                  whiteSpace: "nowrap",
                }}
              >
                {vid.tag}
              </span>
              <span
                style={{
                  fontSize: 15,
                  color: "#7DD3FC",
                  fontWeight: 700,
                  fontFamily: fontMain,
                  whiteSpace: "nowrap",
                }}
              >
                {oneApp.phoneSpeakerChip}
              </span>
            </div>
            <div style={{ textAlign: "center", fontSize: 96, lineHeight: 1 }}>{vid.emoji}</div>
            <div>
              <div style={{ fontFamily: fontMain, fontSize: 20, fontWeight: 700, color: "#FFFFFF" }}>
                {vid.title}
              </div>
              <div
                style={{
                  marginTop: 8,
                  display: "flex",
                  justifyContent: "space-between",
                  fontFamily: FONT_MONO,
                  fontSize: 17,
                  color: "#CBD5E1",
                }}
              >
                <span>♥ {vid.likes}</span>
                <span>▶ 00:12</span>
              </div>
            </div>
          </div>

          {/* Right side of left panel: Voice Chat Banner + Local Phone Output Info */}
          <div style={{ flex: 1, display: "flex", flexDirection: "column", gap: 22 }}>
            <div
              style={{
                padding: "22px 24px",
                borderRadius: 24,
                background: "rgba(34,197,94,0.18)",
                border: "2px solid rgba(34,197,94,0.65)",
                transform: `scale(${lerp(0.9, 1, voicePop)})`,
                opacity: voicePop,
              }}
            >
              <div style={{ fontFamily: fontMain, fontSize: 28, fontWeight: 800, color: "#FFFFFF" }}>
                💬 {oneApp.voiceBannerTitle}
              </div>
              <div style={{ marginTop: 8, fontFamily: fontMain, fontSize: 20, fontWeight: 600, color: "#86EFAC" }}>
                {oneApp.voiceBannerSub}
              </div>
            </div>

            <div
              style={{
                padding: "22px 24px",
                borderRadius: 24,
                background: "rgba(255,255,255,0.05)",
                border: "1.5px solid rgba(255,255,255,0.14)",
              }}
            >
              <div style={{ fontFamily: fontMain, fontSize: 28, fontWeight: 800, color: "#FFFFFF", lineHeight: 1.3 }}>
                {oneApp.phoneCopyTitleLines[0]}
                <br />
                {oneApp.phoneCopyTitleLines[1]}
              </div>
              <div style={{ marginTop: 12, fontFamily: fontMain, fontSize: 20, color: "#94A3B8", lineHeight: 1.45 }}>
                {oneApp.phoneCopyBodyLines.join(locale === "en" ? " " : "")}
              </div>
            </div>
          </div>
        </div>
      </div>

      {/* CENTER SHIELD DIVIDER */}
      <div
        style={{
          width: 148,
          display: "flex",
          flexDirection: "column",
          alignItems: "center",
          justifyContent: "center",
          position: "relative",
          flexShrink: 0,
        }}
      >
        <div
          style={{
            position: "absolute",
            top: 20,
            bottom: 20,
            width: 3,
            background: "linear-gradient(180deg, rgba(56,189,248,0) 0%, rgba(255,255,255,0.5) 50%, rgba(255,92,108,0) 100%)",
          }}
        />
        <div
          style={{
            position: "relative",
            zIndex: 5,
            padding: "18px 20px",
            borderRadius: 26,
            background: "#121722",
            border: "2.5px solid rgba(255,255,255,0.4)",
            boxShadow: "0 18px 46px rgba(0,0,0,0.7)",
            textAlign: "center",
          }}
        >
          <div style={{ fontSize: 34 }}>🛡️</div>
          <div
            style={{
              marginTop: 8,
              fontFamily: fontMain,
              fontSize: locale === "en" ? 20 : 24,
              fontWeight: 800,
              color: "#FFFFFF",
              whiteSpace: "nowrap",
            }}
          >
            {cut.dividerLabel}
          </div>
        </div>
      </div>

      {/* RIGHT PANEL: Living Room Speaker Playing Music Nonstop */}
      <div
        style={{
          flex: 1,
          borderRadius: 34,
          padding: 30,
          background: "linear-gradient(155deg, rgba(44,20,30,0.95) 0%, rgba(15,19,28,0.96) 100%)",
          border: "2.5px solid rgba(255,92,108,0.72)",
          boxShadow: "0 28px 68px rgba(0,0,0,0.55), 0 0 46px rgba(255,92,108,0.22)",
          display: "flex",
          flexDirection: "column",
          justifyContent: "space-between",
          opacity: enter,
          transform: `translateX(${(1 - enter) * 32}px)`,
        }}
      >
        <div
          style={{
            padding: "10px 22px",
            borderRadius: 999,
            background: "rgba(255,92,108,0.20)",
            border: "1.5px solid rgba(255,92,108,0.68)",
            fontFamily: fontMain,
            fontSize: 25,
            fontWeight: 800,
            color: "#FDA4AF",
            alignSelf: "flex-start",
          }}
        >
          {cut.rightBadge}
        </div>

        {/* Real Album Player Slice + Pulsing Speaker */}
        <div style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 22 }}>
          <UiSlice
            src={uiImg(locale, "01-dark.png")}
            rect={[32, 102, 614, 332]}
            radius={26}
            scale={1.06}
            elevation={1}
            accentBorder="rgba(255,92,108,0.75)"
          />
          <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", width: "100%", padding: "0 12px" }}>
            <div style={{ display: "flex", alignItems: "flex-end", gap: 10, height: 74 }}>
              {eqBars.map((h, i) => (
                <div
                  key={i}
                  style={{
                    width: 14,
                    height: Math.round(h * 72),
                    borderRadius: 8,
                    background: "linear-gradient(180deg, #FFFFFF 0%, #FF5C6C 100%)",
                    boxShadow: "0 0 12px rgba(255,92,108,0.7)",
                  }}
                />
              ))}
            </div>
            <div
              style={{
                padding: "12px 24px",
                borderRadius: 999,
                background: "rgba(255,92,108,0.16)",
                border: "1.5px solid rgba(255,92,108,0.58)",
                fontFamily: fontMain,
                fontSize: 23,
                fontWeight: 700,
                color: "#FFFFFF",
                whiteSpace: "nowrap",
              }}
            >
              {cut.speakerNowPlaying}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};

// ============================================================================
// HOOK CUT 4: 4-ROOM PHASE LOCK (UP TO 63) + 150ms LIVE HOT-SWITCH
// ============================================================================
const HookCutMultiRoomLatency: React.FC<{
  lf: number;
  f: number;
  locale: PromoLocale;
  fontMain: string;
  cut: ReturnType<typeof getCopy>["intro"]["hookCuts"][3];
  multiRoom: ReturnType<typeof getCopy>["multiRoom"];
}> = ({ lf, f, locale, fontMain, cut, multiRoom }) => {
  const enter = prog(lf, 0, 10, easeOut);
  const count = Math.round(lerp(4, 63, prog(lf, 0, 22, easeOut)));
  const ultraTier = multiRoom.tiers[3];

  // Phase-locked waveform path generator
  const makeWave = (phase: number, yBase: number) => {
    const pts: string[] = [];
    for (let x = 0; x <= 680; x += 14) {
      const y = yBase + Math.sin(x * 0.036 - f * 0.42 + phase) * 16;
      pts.push(`${x === 0 ? "M" : "L"}${x} ${y.toFixed(1)}`);
    }
    return pts.join(" ");
  };

  return (
    <div
      style={{
        position: "absolute",
        left: 100,
        right: 100,
        top: 320,
        height: 660,
        display: "flex",
        gap: 40,
      }}
    >
      {/* LEFT PANEL: 4-Room Floorplan Locked in Phase + 63 Counter */}
      <div
        style={{
          flex: 1,
          borderRadius: 34,
          padding: 30,
          background: "rgba(18,23,34,0.95)",
          border: "2.5px solid rgba(255,92,108,0.62)",
          boxShadow: "0 28px 68px rgba(0,0,0,0.55), 0 0 42px rgba(255,92,108,0.16)",
          display: "flex",
          flexDirection: "column",
          justifyContent: "space-between",
          opacity: enter,
          transform: `translateY(${(1 - enter) * 26}px)`,
        }}
      >
        {/* Top Hero Stat: Up to 63 synced */}
        <div style={{ display: "flex", alignItems: "baseline", justifyContent: "space-between" }}>
          <div style={{ display: "flex", alignItems: "baseline", gap: 14 }}>
            <span style={{ fontFamily: fontMain, fontSize: 30, fontWeight: 700, color: C.ink2 }}>
              {multiRoom.countPrefix}
            </span>
            <span
              style={{
                fontFamily: FONT_MONO,
                fontSize: 82,
                fontWeight: 900,
                color: C.coral,
                lineHeight: 1,
                textShadow: "0 0 30px rgba(255,92,108,0.5)",
              }}
            >
              {count}
            </span>
            <span style={{ fontFamily: fontMain, fontSize: 30, fontWeight: 700, color: "#FFFFFF" }}>
              {multiRoom.countSuffix}
            </span>
          </div>
          <div
            style={{
              padding: "9px 20px",
              borderRadius: 999,
              background: "rgba(52,211,153,0.16)",
              border: "1.5px solid rgba(52,211,153,0.55)",
              fontFamily: fontMain,
              fontSize: 20,
              fontWeight: 700,
              color: "#6EE7B7",
              whiteSpace: "nowrap",
            }}
          >
            {cut.syncBadge}
          </div>
        </div>

        {/* 4-Room Floorplan SVG */}
        <div style={{ display: "flex", justifyContent: "center", alignItems: "center", flex: 1 }}>
          <svg width="720" height="470" viewBox="-20 -10 640 430">
            {multiRoom.rooms.map((rm) => (
              <g key={rm.name}>
                <rect
                  x={rm.x + 6}
                  y={rm.y + 6}
                  width={rm.w - 12}
                  height={rm.h - 12}
                  rx={18}
                  fill="rgba(255,92,108,0.09)"
                  stroke="rgba(255,92,108,0.58)"
                  strokeWidth={2.5}
                />
                <text
                  x={rm.x + 24}
                  y={rm.y + 38}
                  fontFamily={fontMain}
                  fontSize={22}
                  fontWeight={700}
                  fill="#E2E8F0"
                >
                  {rm.name}
                </text>
                <Speaker x={rm.spkX} y={rm.spkY + 8} s={0.95} active={1} />
              </g>
            ))}
          </svg>
        </div>
      </div>

      {/* RIGHT PANEL: 800ms -> 150ms Hot-Switch Hero + Real 150ms Tier Slice + Phase-Locked Waveforms */}
      <div
        style={{
          flex: 1,
          borderRadius: 34,
          padding: 30,
          background: "rgba(18,23,34,0.95)",
          border: "2.5px solid rgba(56,189,248,0.62)",
          boxShadow: "0 28px 68px rgba(0,0,0,0.55), 0 0 42px rgba(56,189,248,0.16)",
          display: "flex",
          flexDirection: "column",
          justifyContent: "space-between",
          opacity: enter,
          transform: `translateY(${(1 - enter) * 26}px)`,
        }}
      >
        {/* Top Kinetic Latency Drop */}
        <div>
          <div style={{ display: "flex", alignItems: "center", gap: 22 }}>
            <span
              style={{
                fontFamily: FONT_MONO,
                fontSize: 44,
                fontWeight: 700,
                color: "#64748B",
                textDecoration: "line-through",
              }}
            >
              {cut.latencyFrom}
            </span>
            <span style={{ fontFamily: FONT_EN, fontSize: 42, fontWeight: 800, color: "#38BDF8" }}>→</span>
            <span
              style={{
                fontFamily: FONT_MONO,
                fontSize: 84,
                fontWeight: 900,
                color: C.coral,
                lineHeight: 1,
                textShadow: "0 0 32px rgba(255,92,108,0.55)",
              }}
            >
              {cut.latencyTo}
            </span>
          </div>
          <div
            style={{
              marginTop: 14,
              display: "inline-block",
              padding: "9px 22px",
              borderRadius: 999,
              background: "rgba(255,92,108,0.18)",
              border: "1.5px solid rgba(255,92,108,0.65)",
              fontFamily: fontMain,
              fontSize: 22,
              fontWeight: 700,
              color: "#FFD4D9",
            }}
          >
            {cut.latencyTag}
          </div>
        </div>

        {/* Real 150ms Tier Slice from localized 02-dark.png */}
        <div style={{ display: "flex", justifyContent: "center" }}>
          <UiSlice
            src={uiImg(locale, "02-dark.png")}
            rect={ultraTier.rect}
            radius={22}
            scale={1.26}
            elevation={1}
            accentBorder="rgba(255,92,108,0.9)"
          />
        </div>

        {/* Synchronized Phase-Locked Waveforms */}
        <div
          style={{
            padding: "16px 22px",
            borderRadius: 22,
            background: "rgba(11,14,21,0.88)",
            border: "1.5px solid rgba(255,255,255,0.14)",
          }}
        >
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 8 }}>
            <span style={{ fontFamily: fontMain, fontSize: 19, fontWeight: 700, color: "#CBD5E1" }}>
              {multiRoom.waveRooms.join(" · ")}
            </span>
            <span style={{ fontFamily: fontMain, fontSize: 18, fontWeight: 700, color: "#34D399" }}>
              {cut.waveLockBadge}
            </span>
          </div>
          <svg width="100%" height="96" viewBox="0 0 680 96" preserveAspectRatio="none">
            <path d={makeWave(0, 22)} fill="none" stroke={C.coral} strokeWidth={3.5} />
            <path d={makeWave(0, 48)} fill="none" stroke="#38BDF8" strokeWidth={3.5} />
            <path d={makeWave(0, 74)} fill="none" stroke="#34D399" strokeWidth={3.5} />
          </svg>
        </div>
      </div>
    </div>
  );
};
