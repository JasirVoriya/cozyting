import React from "react";
import { AbsoluteFill, useCurrentFrame } from "remotion";
import { C, FONT_EN, fontForLocale, uiImg, prog, easeInOut, easeOut, sceneFade, lerp, type PromoLocale } from "../theme";
import { Eyebrow, Reveal, Ripple, Speaker, StagePhone, UiSlice } from "../components";
import { getCopy } from "../i18n";

export const OneApp: React.FC<{ dur: number; locale: PromoLocale }> = ({ dur, locale }) => {
  const f = useCurrentFrame();
  const copy = getCopy(locale).oneApp;
  const fontMain = fontForLocale(locale);
  const apps = copy.apps;

  const act2 = prog(f, 295, 332, easeInOut);
  const act1Op = 1 - act2;

  const enter1 = prog(f, 6, 46, easeOut);
  const sw1 = prog(f, 98, 130, easeInOut);
  const sw2 = prog(f, 196, 228, easeInOut);

  const activeIdx = sw2 > 0.5 ? 2 : sw1 > 0.5 ? 1 : 0;
  const activeApp = apps[activeIdx];
  const streamStartY = lerp(
    lerp(apps[0].cardY + 74, apps[1].cardY + 74, sw1),
    apps[2].cardY + 74,
    sw2
  );
  const switchPulse =
    activeIdx === 1
      ? prog(f, 100, 126, easeOut) * (1 - prog(f, 165, 190, easeInOut))
      : activeIdx === 2
      ? prog(f, 198, 224, easeOut) * (1 - prog(f, 265, 290, easeInOut))
      : 0;

  const f2 = Math.max(0, f - 305);
  const act2Enter = prog(f, 308, 346, easeOut);
  const swipeProg = prog(f2, 85, 125, easeInOut);
  const wechatPop = prog(f2, 140, 175, easeOut);
  const beatBars = [0.65, 1.0, 0.78, 0.92, 0.55].map(
    (base, i) => 0.35 + base * (0.45 + 0.55 * Math.abs(Math.sin(f * 0.16 + i * 1.1)))
  );

  return (
    <AbsoluteFill style={{ background: C.bg, opacity: sceneFade(f, dur, 10, 16), overflow: "hidden" }}>
      <AbsoluteFill
        style={{
          background:
            "radial-gradient(circle at 26% 56%, rgba(56,189,248,0.16) 0%, rgba(56,189,248,0) 42%), radial-gradient(circle at 78% 54%, rgba(229,109,72,0.18) 0%, rgba(229,109,72,0) 42%)",
        }}
      />

      {/* ==================== ACT 1: 3 REAL MUSIC APPS AUTO-DETECT & AUTO-SWITCH ==================== */}
      {act1Op > 0.001 && (
        <AbsoluteFill style={{ opacity: act1Op, transform: `scale(${lerp(1, 0.96, act2)})` }}>
          {/* Header */}
          <div style={{ position: "absolute", left: 100, top: 60, zIndex: 20 }}>
            <Eyebrow text={copy.act1Eyebrow} start={4} />
            <div style={{ marginTop: 14 }}>
              <Reveal
                text={copy.act1Headline}
                start={10}
                size={copy.act1HeadlineSize}
                weight={700}
                font={fontMain}
                stagger={locale === "en" ? 0.9 : 1.6}
              />
            </div>
            <div style={{ marginTop: 10 }}>
              <Reveal
                text={copy.act1Subtitle}
                start={32}
                size={copy.act1SubtitleSize}
                weight={450}
                font={fontMain}
                color="#CBD5E1"
                stagger={0.4}
              />
            </div>
          </div>

          {/* Left Background Context: Dimmed Real 03-dark Phone */}
          <div style={{ position: "absolute", left: 95, top: 235, perspective: 1800, opacity: enter1 }}>
            <div style={{ transform: "rotateY(8deg) rotateX(2deg)", transformStyle: "preserve-3d" }}>
              <StagePhone src={uiImg(locale, "03-dark.png")} w={390} dim={0.82} />
            </div>
          </div>

          {/* Left Foreground: 3 Crystal-Clear Real Music App Cards */}
          <div style={{ position: "absolute", left: 175, top: 342, width: 530, zIndex: 20 }}>
            {apps.map((app, idx) => {
              const isActive = idx === activeIdx;
              const lift = isActive ? 1 : 0;
              return (
                <div
                  key={app.id}
                  style={{
                    position: "absolute",
                    left: 0,
                    top: idx * 198,
                    width: 525,
                    height: 156,
                    borderRadius: 28,
                    padding: "20px 24px",
                    background: isActive ? "rgba(255,255,255,0.97)" : "rgba(20,25,36,0.92)",
                    border: `2.5px solid ${isActive ? app.color : "rgba(255,255,255,0.16)"}`,
                    boxShadow: isActive
                      ? `0 28px 68px rgba(0,0,0,0.62), 0 0 38px ${app.color}45`
                      : "0 14px 34px rgba(0,0,0,0.42)",
                    transform: `translateX(${lift * 34}px) scale(${1 + lift * 0.05})`,
                    opacity: enter1 * (isActive ? 1 : 0.72),
                    display: "flex",
                    flexDirection: "column",
                    justifyContent: "space-between",
                  }}
                >
                  {/* Top Row: App Icon + Name + Active Pill */}
                  <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: 12 }}>
                    <div style={{ display: "flex", alignItems: "center", gap: 16, minWidth: 0 }}>
                      <div
                        style={{
                          width: 56,
                          height: 56,
                          borderRadius: 16,
                          background: app.iconBg,
                          display: "flex",
                          alignItems: "center",
                          justifyContent: "center",
                          fontSize: 30,
                          fontWeight: 800,
                          color: "#FFFFFF",
                          boxShadow: `0 8px 20px ${app.color}44`,
                          flexShrink: 0,
                        }}
                      >
                        {app.badgeGlyph}
                      </div>
                      <div style={{ minWidth: 0 }}>
                        <div
                          style={{
                            fontFamily: fontMain,
                            fontSize: 28,
                            fontWeight: 800,
                            color: isActive ? "#0F172A" : "#FFFFFF",
                            whiteSpace: "nowrap",
                          }}
                        >
                          {app.name}
                        </div>
                        <div
                          style={{
                            fontFamily: fontMain,
                            fontSize: 17,
                            fontWeight: 500,
                            color: isActive ? "#475569" : "#94A3B8",
                            whiteSpace: "nowrap",
                          }}
                        >
                          {app.track}
                        </div>
                      </div>
                    </div>

                    <div
                      style={{
                        padding: "7px 14px",
                        borderRadius: 999,
                        background: isActive ? app.color : "rgba(255,255,255,0.08)",
                        fontFamily: fontMain,
                        fontSize: 17,
                        fontWeight: 700,
                        color: isActive ? "#FFFFFF" : "#94A3B8",
                        whiteSpace: "nowrap",
                        flexShrink: 0,
                      }}
                    >
                      {isActive ? (idx === 0 ? copy.statusCasting : copy.statusSwitched) : copy.statusStandby}
                    </div>
                  </div>

                  {/* Bottom Row: Real-style Blue Toggle */}
                  <div
                    style={{
                      display: "flex",
                      alignItems: "center",
                      justifyContent: "space-between",
                      paddingTop: 10,
                      borderTop: `1px solid ${isActive ? "rgba(15,23,42,0.10)" : "rgba(255,255,255,0.08)"}`,
                    }}
                  >
                    <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
                      <div
                        style={{
                          width: 44,
                          height: 24,
                          borderRadius: 12,
                          background: "#2563EB",
                          position: "relative",
                        }}
                      >
                        <div
                          style={{
                            position: "absolute",
                            right: 3,
                            top: 3,
                            width: 18,
                            height: 18,
                            borderRadius: 9,
                            background: "#FFFFFF",
                          }}
                        />
                      </div>
                      <span
                        style={{
                          fontFamily: fontMain,
                          fontSize: 19,
                          fontWeight: 700,
                          color: isActive ? "#2563EB" : "#60A5FA",
                        }}
                      >
                        {app.sub}
                      </span>
                    </div>
                    <div
                      style={{
                        width: 24,
                        height: 24,
                        borderRadius: 12,
                        background: isActive ? "#2563EB" : "transparent",
                        border: isActive ? "none" : "2px solid rgba(255,255,255,0.28)",
                        display: "flex",
                        alignItems: "center",
                        justifyContent: "center",
                        color: "#FFFFFF",
                        fontSize: 14,
                        fontWeight: 800,
                      }}
                    >
                      {isActive ? "✓" : ""}
                    </div>
                  </div>
                </div>
              );
            })}
          </div>

          {/* Center Curved Stream Gliding Dynamically */}
          <svg width="1920" height="1080" style={{ position: "absolute", inset: 0, pointerEvents: "none" }}>
            {(() => {
              const endY = 585;
              const d = `M 750 ${streamStartY} C 940 ${streamStartY}, 1020 ${endY}, 1265 ${endY}`;
              return (
                <g opacity={enter1}>
                  <path d={d} fill="none" stroke={activeApp.color} strokeWidth={13} opacity={0.18} strokeLinecap="round" />
                  <path
                    d={d}
                    fill="none"
                    stroke={activeApp.color}
                    strokeWidth={4.8}
                    strokeDasharray="12 12"
                    strokeDashoffset={-f * 3.4}
                    strokeLinecap="round"
                  />
                  <circle cx={750} cy={streamStartY} r={10} fill={activeApp.color} />
                  <circle cx={1265} cy={endY} r={10} fill={activeApp.color} />
                </g>
              );
            })()}
            <Ripple cx={1540} cy={510} t={(f - 115) / 50} maxR={260} color="#22C55E" width={4} />
            <Ripple cx={1540} cy={510} t={(f - 213) / 50} maxR={260} color="#22D3EE" width={4} />
          </svg>

          {/* Center Clean Auto-Switch Callout Pill */}
          <div
            style={{
              position: "absolute",
              left: 745,
              top: 395,
              width: 500,
              display: "flex",
              flexDirection: "column",
              alignItems: "center",
            }}
          >
            <div
              style={{
                padding: "16px 30px",
                borderRadius: 999,
                background: `${activeApp.color}28`,
                border: `2px solid ${activeApp.color}`,
                boxShadow: `0 18px 46px rgba(0,0,0,0.48), 0 0 36px ${activeApp.color}38`,
                fontFamily: fontMain,
                fontSize: 26,
                fontWeight: 700,
                color: "#FFFFFF",
                whiteSpace: "nowrap",
                transform: `scale(${1 + switchPulse * 0.08})`,
              }}
            >
              {activeIdx === 0 ? copy.pillAutoFollow(activeApp.name) : copy.pillSwitchedTo(activeApp.name)}
            </div>
          </div>
          <div
            style={{
              position: "absolute",
              left: 740,
              top: 855,
              width: 510,
              textAlign: "center",
              fontFamily: fontMain,
              fontSize: 23,
              fontWeight: 500,
              color: "#CBD5E1",
              opacity: enter1,
            }}
          >
            {copy.act1Footnote}
          </div>

          {/* Right: Prominent Speaker Card */}
          <div
            style={{
              position: "absolute",
              left: 1270,
              top: 325,
              width: 545,
              height: 520,
              borderRadius: 36,
              background: "rgba(20,25,36,0.92)",
              border: `2.5px solid ${activeApp.color}99`,
              boxShadow: `0 36px 90px rgba(0,0,0,0.55), 0 0 54px ${activeApp.color}26`,
              display: "flex",
              flexDirection: "column",
              alignItems: "center",
              justifyContent: "center",
              gap: 24,
              padding: 36,
              opacity: enter1,
            }}
          >
            <svg width="210" height="190" style={{ overflow: "visible" }}>
              <Speaker x={105} y={95} s={2.4} active={1} />
            </svg>

            <div
              style={{
                padding: "11px 28px",
                borderRadius: 999,
                background: `${activeApp.color}28`,
                border: `2px solid ${activeApp.color}`,
                fontFamily: fontMain,
                fontSize: 27,
                fontWeight: 800,
                color: "#FFFFFF",
                whiteSpace: "nowrap",
              }}
            >
              {copy.speakerCastingPrefix}
              {activeApp.name}
            </div>
            <div style={{ fontFamily: fontMain, fontSize: 24, color: "#CBD5E1", fontWeight: 500 }}>
              {activeApp.track}
            </div>
          </div>
        </AbsoluteFill>
      )}

      {/* ==================== ACT 2: REAL-LIFE SPLIT ==================== */}
      {act2 > 0.001 && (
        <AbsoluteFill style={{ opacity: act2, transform: `scale(${lerp(1.04, 1, act2)})` }}>
          {/* Header */}
          <div style={{ position: "absolute", left: 100, top: 60, zIndex: 20 }}>
            <Eyebrow text={copy.act2Eyebrow} start={302} />
            <div style={{ marginTop: 14 }}>
              <Reveal
                text={copy.act2Headline}
                start={308}
                size={copy.act2HeadlineSize}
                weight={700}
                font={fontMain}
                stagger={locale === "en" ? 0.9 : 1.6}
              />
            </div>
            <div style={{ marginTop: 10 }}>
              <Reveal
                text={copy.act2Subtitle}
                start={332}
                size={copy.act2SubtitleSize}
                weight={450}
                font={fontMain}
                color="#CBD5E1"
                stagger={0.4}
              />
            </div>
          </div>

          {/* LEFT PANEL: Phone Playback */}
          <div
            style={{
              position: "absolute",
              left: 100,
              top: 255,
              width: 755,
              height: 755,
              borderRadius: 38,
              background: "rgba(21,26,37,0.88)",
              border: "2px solid rgba(96,165,250,0.52)",
              boxShadow: "0 36px 85px rgba(0,0,0,0.52)",
              display: "flex",
              flexDirection: "column",
              justifyContent: "space-between",
              padding: "32px 40px 36px 40px",
              opacity: act2Enter,
            }}
          >
            <div style={{ width: "100%", display: "flex", justifyContent: "space-between", alignItems: "center", gap: 12 }}>
              <div
                style={{
                  padding: "8px 20px",
                  borderRadius: 999,
                  background: "rgba(96,165,250,0.22)",
                  border: "1.5px solid rgba(96,165,250,0.72)",
                  fontFamily: fontMain,
                  fontSize: 22,
                  fontWeight: 700,
                  color: "#93C5FD",
                  whiteSpace: "nowrap",
                }}
              >
                {copy.phoneHeaderLeft}
              </div>
              <div
                style={{
                  padding: "8px 18px",
                  borderRadius: 999,
                  background: "rgba(56,189,248,0.16)",
                  border: "1.5px solid rgba(56,189,248,0.55)",
                  fontFamily: fontMain,
                  fontSize: 19,
                  fontWeight: 700,
                  color: "#7DD3FC",
                  whiteSpace: "nowrap",
                }}
              >
                {copy.phoneHeaderRight}
              </div>
            </div>

            <div style={{ display: "flex", alignItems: "center", gap: 34, flex: 1, marginTop: 22 }}>
              <div
                style={{
                  width: 306,
                  height: 604,
                  borderRadius: 44,
                  padding: 10,
                  background: "linear-gradient(145deg, #3A4252, #151922)",
                  boxShadow: "0 28px 60px rgba(0,0,0,0.65), 0 0 0 1.5px rgba(255,255,255,0.16) inset",
                  flexShrink: 0,
                }}
              >
                <div
                  style={{
                    width: "100%",
                    height: "100%",
                    borderRadius: 34,
                    overflow: "hidden",
                    position: "relative",
                    background: "#0D111A",
                  }}
                >
                  <div
                    style={{
                      position: "absolute",
                      left: 0,
                      top: -swipeProg * 584,
                      width: "100%",
                      height: 1168,
                    }}
                  >
                    {copy.shortVideos.map((v, idx) => (
                      <div
                        key={idx}
                        style={{
                          width: "100%",
                          height: 584,
                          background: v.bg,
                          position: "relative",
                          display: "flex",
                          flexDirection: "column",
                          justifyContent: "space-between",
                          padding: 20,
                        }}
                      >
                        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: 6 }}>
                          <div
                            style={{
                              padding: "4px 10px",
                              borderRadius: 999,
                              background: "rgba(0,0,0,0.45)",
                              fontFamily: fontMain,
                              fontSize: 14,
                              fontWeight: 600,
                              color: "#FFFFFF",
                              whiteSpace: "nowrap",
                            }}
                          >
                            {v.tag}
                          </div>
                          <div
                            style={{
                              padding: "4px 10px",
                              borderRadius: 999,
                              background: "rgba(96,165,250,0.28)",
                              border: "1px solid rgba(96,165,250,0.7)",
                              fontFamily: fontMain,
                              fontSize: 13,
                              fontWeight: 700,
                              color: "#93C5FD",
                              whiteSpace: "nowrap",
                            }}
                          >
                            {copy.phoneSpeakerChip}
                          </div>
                        </div>

                        <div style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 12 }}>
                          <div
                            style={{
                              width: 110,
                              height: 110,
                              borderRadius: 999,
                              background: "rgba(255,255,255,0.14)",
                              border: "2px solid rgba(255,255,255,0.32)",
                              display: "flex",
                              alignItems: "center",
                              justifyContent: "center",
                              fontSize: 50,
                            }}
                          >
                            {v.emoji}
                          </div>
                          <div
                            style={{
                              padding: "6px 14px",
                              borderRadius: 999,
                              background: "rgba(0,0,0,0.45)",
                              fontFamily: fontMain,
                              fontSize: 14,
                              color: "#E2E8F0",
                              whiteSpace: "nowrap",
                            }}
                          >
                            {copy.shortVideoPlaying}
                          </div>
                        </div>

                        <div
                          style={{
                            position: "absolute",
                            right: 14,
                            bottom: 104,
                            display: "flex",
                            flexDirection: "column",
                            alignItems: "center",
                            gap: 16,
                            fontFamily: FONT_EN,
                            fontSize: 13,
                            color: "#FFFFFF",
                            fontWeight: 600,
                          }}
                        >
                          <div style={{ textAlign: "center" }}>
                            <div style={{ fontSize: 26, color: C.coral }}>♥</div>
                            <div>{v.likes}</div>
                          </div>
                          <div style={{ textAlign: "center" }}>
                            <div style={{ fontSize: 24 }}>💬</div>
                            <div>2.1k</div>
                          </div>
                          <div style={{ textAlign: "center" }}>
                            <div style={{ fontSize: 24 }}>↗</div>
                            <div style={{ fontFamily: fontMain }}>{copy.shareLabel}</div>
                          </div>
                        </div>

                        <div style={{ paddingRight: 42 }}>
                          <div style={{ fontFamily: fontMain, fontSize: 16, fontWeight: 700, color: "#FFFFFF", whiteSpace: "nowrap" }}>
                            {v.title}
                          </div>
                          <div
                            style={{
                              marginTop: 8,
                              height: 4,
                              borderRadius: 2,
                              background: "rgba(255,255,255,0.22)",
                              overflow: "hidden",
                            }}
                          >
                            <div style={{ width: `${35 + ((f * 0.4) % 55)}%`, height: "100%", background: "#FFFFFF" }} />
                          </div>
                        </div>
                      </div>
                    ))}
                  </div>

                  {/* Popping Voice Notification Banner */}
                  <div
                    style={{
                      position: "absolute",
                      left: 12,
                      right: 12,
                      top: 60,
                      padding: "10px 12px",
                      borderRadius: 16,
                      background: "rgba(15,23,42,0.95)",
                      border: "1.5px solid rgba(52,211,153,0.7)",
                      boxShadow: "0 14px 32px rgba(0,0,0,0.55)",
                      display: "flex",
                      alignItems: "center",
                      gap: 10,
                      opacity: wechatPop,
                      transform: `translateY(${(1 - wechatPop) * -28}px)`,
                    }}
                  >
                    <div
                      style={{
                        width: 32,
                        height: 32,
                        borderRadius: 9,
                        background: "#22C55E",
                        display: "flex",
                        alignItems: "center",
                        justifyContent: "center",
                        fontSize: 17,
                        flexShrink: 0,
                      }}
                    >
                      💬
                    </div>
                    <div>
                      <div style={{ fontFamily: fontMain, fontSize: 14, fontWeight: 700, color: "#FFFFFF" }}>
                        {copy.voiceBannerTitle}
                      </div>
                      <div style={{ fontFamily: fontMain, fontSize: 12, color: "#86EFAC" }}>{copy.voiceBannerSub}</div>
                    </div>
                  </div>
                </div>
              </div>

              <div style={{ display: "flex", flexDirection: "column", gap: 22 }}>
                <div
                  style={{
                    fontFamily: fontMain,
                    fontSize: locale === "zh" ? 40 : 34,
                    fontWeight: 700,
                    color: "#FFFFFF",
                    lineHeight: 1.28,
                  }}
                >
                  {copy.phoneCopyTitleLines[0]}
                  <br />
                  {copy.phoneCopyTitleLines[1]}
                </div>
                <div style={{ fontFamily: fontMain, fontSize: locale === "zh" ? 25 : 23, color: "#CBD5E1", lineHeight: 1.58 }}>
                  {copy.phoneCopyBodyLines[0]}
                  <br />
                  {copy.phoneCopyBodyLines[1]}
                  <br />
                  {copy.phoneCopyBodyLines[2]}
                </div>
              </div>
            </div>
          </div>

          {/* CENTER DIVIDER BADGE */}
          <div
            style={{
              position: "absolute",
              left: 850,
              top: 560,
              width: 220,
              display: "flex",
              flexDirection: "column",
              alignItems: "center",
              zIndex: 25,
            }}
          >
            <div
              style={{
                padding: "16px 24px",
                borderRadius: 24,
                background: "rgba(11,13,18,0.96)",
                border: "2px solid rgba(229,109,72,0.75)",
                boxShadow: "0 18px 46px rgba(0,0,0,0.6), 0 0 32px rgba(229,109,72,0.25)",
                textAlign: "center",
              }}
            >
              <div style={{ fontFamily: fontMain, fontSize: 26, fontWeight: 800, color: C.coral, whiteSpace: "nowrap" }}>
                {copy.dividerTitle}
              </div>
              <div style={{ marginTop: 4, fontFamily: fontMain, fontSize: 17, fontWeight: 600, color: "#E2E8F0", whiteSpace: "nowrap" }}>
                {copy.dividerSub}
              </div>
            </div>
          </div>

          {/* RIGHT PANEL: Speaker Playback */}
          <div
            style={{
              position: "absolute",
              left: 1065,
              top: 255,
              width: 755,
              height: 755,
              borderRadius: 38,
              background: "rgba(21,26,37,0.88)",
              border: "2px solid rgba(229,109,72,0.58)",
              boxShadow: "0 36px 85px rgba(0,0,0,0.52), 0 0 60px rgba(229,109,72,0.12)",
              display: "flex",
              flexDirection: "column",
              alignItems: "center",
              justifyContent: "space-between",
              padding: "32px 40px 36px 40px",
              opacity: act2Enter,
            }}
          >
            <div style={{ width: "100%", display: "flex", justifyContent: "space-between", alignItems: "center", gap: 12 }}>
              <div
                style={{
                  padding: "8px 20px",
                  borderRadius: 999,
                  background: "rgba(229,109,72,0.20)",
                  border: "1.5px solid rgba(229,109,72,0.68)",
                  fontFamily: fontMain,
                  fontSize: 22,
                  fontWeight: 700,
                  color: "#FF8A95",
                  whiteSpace: "nowrap",
                }}
              >
                {copy.speakerHeaderLeft}
              </div>
              <div
                style={{
                  padding: "8px 18px",
                  borderRadius: 999,
                  background: "rgba(52,211,153,0.18)",
                  border: "1.5px solid rgba(52,211,153,0.6)",
                  fontFamily: fontMain,
                  fontSize: 19,
                  fontWeight: 700,
                  color: "#6EE7B7",
                  whiteSpace: "nowrap",
                }}
              >
                {copy.speakerHeaderRight}
              </div>
            </div>

            <div style={{ position: "relative", width: 260, height: 220, display: "flex", alignItems: "center", justifyContent: "center" }}>
              <svg width="260" height="220" style={{ overflow: "visible" }}>
                <Speaker x={130} y={115} s={2.6} active={1} />
              </svg>
              <div
                style={{
                  position: "absolute",
                  right: 10,
                  top: 8 + Math.sin(f * 0.1) * 10,
                  fontSize: 42,
                  color: C.coral,
                  fontWeight: 700,
                }}
              >
                ♫
              </div>
              <div
                style={{
                  position: "absolute",
                  left: 12,
                  top: 28 + Math.cos(f * 0.11) * 10,
                  fontSize: 34,
                  color: "#FF8A95",
                  fontWeight: 700,
                }}
              >
                ♪
              </div>
            </div>

            <div style={{ width: "100%", display: "flex", flexDirection: "column", alignItems: "center", gap: 18 }}>
              <UiSlice
                src={uiImg(locale, "01-dark.png")}
                rect={[32, 102, 614, 240]}
                radius={26}
                scale={0.86}
                elevation={0.9}
                accentBorder="rgba(229,109,72,0.65)"
              />

              <div style={{ display: "flex", alignItems: "center", gap: 18, marginTop: 4 }}>
                <div style={{ display: "flex", alignItems: "flex-end", gap: 6, height: 34, flexShrink: 0 }}>
                  {beatBars.map((h, i) => (
                    <div
                      key={i}
                      style={{
                        width: 7,
                        height: `${Math.round(h * 34)}px`,
                        borderRadius: 4,
                        background: C.coral,
                      }}
                    />
                  ))}
                </div>
                <div
                  style={{
                    fontFamily: fontMain,
                    fontSize: locale === "zh" ? 34 : 28,
                    fontWeight: 700,
                    color: "#FFFFFF",
                    whiteSpace: "nowrap",
                  }}
                >
                  {copy.speakerBottomLine}
                </div>
              </div>
            </div>
          </div>
        </AbsoluteFill>
      )}
    </AbsoluteFill>
  );
};
