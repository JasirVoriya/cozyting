import React from "react";
import { AbsoluteFill, useCurrentFrame } from "remotion";
import { C, FONT_EN, FONT_MONO, fontForLocale, prog, easeInOut, easeOut, sceneFade, lerp, type PromoLocale } from "../theme";
import { GitHubIcon, LogoMark, MouseCursor } from "../components";
import { getCopy } from "../i18n";

const QUERY = "CozyTing";

export const Search: React.FC<{ dur: number; locale: PromoLocale }> = ({ dur, locale }) => {
  const f = useCurrentFrame();
  const copy = getCopy(locale).search;
  const fontMain = fontForLocale(locale);

  const barIn = prog(f, 8, 42, easeOut);
  const typedCount = Math.min(QUERY.length, Math.max(0, Math.floor((f - 44) / 8)));
  const typedText = QUERY.slice(0, typedCount);
  const caretOn = f < 132 && Math.floor(f / 14) % 2 === 0;

  const suggestIn = prog(f, 108, 138, easeOut);

  const cursorIn = prog(f, 106, 158, easeInOut);
  const cursorX = lerp(1640, 1418, cursorIn);
  const cursorY = lerp(760, 460, cursorIn);
  const hovering = prog(f, 146, 162, easeOut);

  const clickDown = prog(f, 166, 174, easeOut);
  const clickUp = prog(f, 174, 186, easeOut);
  const pressing = Math.max(0, clickDown - clickUp);
  const rippleT = f >= 168 ? Math.min(1, (f - 168) / 36) : 0;

  const zoomOut = prog(f, 198, dur, easeInOut);

  return (
    <AbsoluteFill style={{ background: C.bg, opacity: sceneFade(f, dur, 10, 16), overflow: "hidden" }}>
      <AbsoluteFill
        style={{
          background:
            "radial-gradient(circle at 50% 46%, rgba(229,109,72,0.16) 0%, rgba(229,109,72,0) 46%), radial-gradient(circle at 30% 62%, rgba(76,141,255,0.14) 0%, rgba(76,141,255,0) 42%)",
        }}
      />

      <AbsoluteFill
        style={{
          transform: `scale(${lerp(1, 1.09, zoomOut)})`,
          filter: zoomOut > 0.01 ? `blur(${zoomOut * 8}px)` : undefined,
        }}
      >
        {/* Top clean prompt */}
        <div
          style={{
            position: "absolute",
            top: 235,
            width: "100%",
            textAlign: "center",
            opacity: barIn,
            transform: `translateY(${(1 - barIn) * 20}px)`,
          }}
        >
          <div style={{ display: "inline-flex", alignItems: "center", gap: 16 }}>
            <GitHubIcon size={40} color="#FFFFFF" />
            <span style={{ fontFamily: fontMain, fontSize: 42, fontWeight: 700, color: "#FFFFFF", letterSpacing: locale === "en" ? 0.5 : 2 }}>
              {copy.prompt}
            </span>
          </div>
        </div>

        {/* Main Hero Search Bar */}
        <div
          style={{
            position: "absolute",
            left: 360,
            top: 400,
            width: 1200,
            height: 112,
            borderRadius: 999,
            background: "rgba(22,28,40,0.94)",
            border: `2.5px solid ${typedCount > 0 ? "rgba(229,109,72,0.78)" : "rgba(255,255,255,0.22)"}`,
            boxShadow: `0 34px 90px rgba(0,0,0,0.62), 0 0 ${32 + hovering * 24}px rgba(229,109,72,${0.18 + hovering * 0.14})`,
            display: "flex",
            alignItems: "center",
            padding: "0 16px 0 42px",
            justifyContent: "space-between",
            opacity: barIn,
            transform: `scale(${lerp(0.92, 1, barIn)})`,
          }}
        >
          <div style={{ display: "flex", alignItems: "center", gap: 26 }}>
            <svg width="38" height="38" viewBox="0 0 24 24" fill="none" stroke="#94A3B8" strokeWidth="2.4" strokeLinecap="round">
              <circle cx="11" cy="11" r="7.5" />
              <line x1="16.5" y1="16.5" x2="21.5" y2="21.5" />
            </svg>
            <div
              style={{
                display: "flex",
                alignItems: "center",
                fontFamily: typedCount > 0 ? FONT_EN : fontMain,
                fontSize: 44,
                fontWeight: 700,
                color: typedCount > 0 ? "#FFFFFF" : "#64748B",
                letterSpacing: 1,
              }}
            >
              {typedCount > 0 ? typedText : copy.placeholder}
              {caretOn && (
                <span
                  style={{
                    display: "inline-block",
                    width: 4,
                    height: 46,
                    marginLeft: 5,
                    borderRadius: 2,
                    background: C.coral,
                  }}
                />
              )}
            </div>
          </div>

          <div
            style={{
              height: 80,
              minWidth: 180,
              padding: "0 44px",
              borderRadius: 999,
              background: hovering > 0.3 ? "#FF6B7A" : C.coral,
              boxShadow: `0 12px 34px rgba(229,109,72,${0.42 + hovering * 0.28})`,
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              gap: 12,
              fontFamily: fontMain,
              fontSize: 30,
              fontWeight: 700,
              color: "#FFFFFF",
              letterSpacing: locale === "en" ? 0.5 : 3,
              transform: `scale(${1 + hovering * 0.035 - pressing * 0.07})`,
            }}
          >
            {copy.button}
          </div>
        </div>

        {/* Instant Autocomplete Result Dropdown Preview */}
        {suggestIn > 0.001 && (
          <div
            style={{
              position: "absolute",
              left: 400,
              top: 540,
              width: 1120,
              padding: "28px 36px",
              borderRadius: 28,
              background: "rgba(18,23,34,0.95)",
              border: "1.5px solid rgba(255,255,255,0.16)",
              boxShadow: "0 30px 80px rgba(0,0,0,0.58)",
              display: "flex",
              alignItems: "center",
              justifyContent: "space-between",
              opacity: suggestIn,
              transform: `translateY(${(1 - suggestIn) * -18}px)`,
            }}
          >
            <div style={{ display: "flex", alignItems: "center", gap: 24 }}>
              <div style={{ width: 64, height: 64, display: "flex", alignItems: "center", justifyContent: "center", flexShrink: 0 }}>
                <LogoMark size={64} rings={1} core={1} play={1} arc={1} />
              </div>
              <div>
                <div style={{ display: "flex", alignItems: "center", gap: 14 }}>
                  <span style={{ fontFamily: FONT_MONO, fontSize: 30, fontWeight: 700, color: "#FFFFFF" }}>
                    JasirVoriya / cozyting-releases
                  </span>
                  <span
                    style={{
                      padding: "4px 14px",
                      borderRadius: 999,
                      background: "rgba(52,211,153,0.18)",
                      border: "1px solid rgba(52,211,153,0.55)",
                      fontFamily: fontMain,
                      fontSize: 18,
                      fontWeight: 600,
                      color: "#6EE7B7",
                      whiteSpace: "nowrap",
                    }}
                  >
                    {copy.officialBadge}
                  </span>
                </div>
                <div style={{ marginTop: 8, fontFamily: fontMain, fontSize: 22, color: "#CBD5E1" }}>
                  {copy.repoDesc}
                </div>
              </div>
            </div>
            <div style={{ fontFamily: FONT_EN, fontSize: 28, fontWeight: 700, color: C.coral }}>↵</div>
          </div>
        )}

        <MouseCursor
          x={cursorX}
          y={cursorY}
          opacity={prog(f, 102, 122)}
          pressing={pressing}
          rippleT={rippleT}
          rippleColor="#FFFFFF"
          size={54}
        />
      </AbsoluteFill>
    </AbsoluteFill>
  );
};
