import React from "react";
import { AbsoluteFill, useCurrentFrame } from "remotion";
import { C, FONT_CN, FONT_EN, prog, easeInOut, easeOut, sceneFade, lerp } from "../theme";
import { Eyebrow, Reveal, Ripple, Speaker, StagePhone, UiSlice } from "../components";

/** Real music apps cycling automatically in Act 1: 网易云音乐 -> QQ 音乐 -> 汽水音乐 */
const MUSIC_APPS = [
  {
    id: "netease",
    name: "网易云音乐",
    sub: "自动识别：允许",
    track: "夜航星 (Night Voyager)",
    color: "#F43F5E",
    iconBg: "linear-gradient(135deg, #E11D48, #FB7185)",
    badgeGlyph: "☁",
    cardY: 342,
  },
  {
    id: "qqmusic",
    name: "QQ 音乐",
    sub: "自动识别：允许",
    track: "晴天 · 臻品母带",
    color: "#22C55E",
    iconBg: "linear-gradient(135deg, #16A34A, #FACC15)",
    badgeGlyph: "♪",
    cardY: 540,
  },
  {
    id: "soda",
    name: "汽水音乐",
    sub: "自动识别：允许",
    track: "晚风告白 · 每日推荐",
    color: "#22D3EE",
    iconBg: "linear-gradient(135deg, #06B6D4, #84CC16)",
    badgeGlyph: "♫",
    cardY: 738,
  },
] as const;

/**
 * Scene 03 (10s = 600f) — Two Concrete, User-Intuitive Acts:
 *   Act 1 (0–300f):   Auto-Detect & Auto-Switch across 3 Real Music Apps (网易云音乐 -> QQ 音乐 -> 汽水音乐)
 *   Act 2 (300–600f): Side-by-Side Real-Life Split ("📱 手机内播放" vs "🔊 客厅音箱播放")
 */
export const OneApp: React.FC<{ dur: number }> = ({ dur }) => {
  const f = useCurrentFrame();

  // Transition between Act 1 (3 Real Music Apps Auto-Switch) and Act 2 (Phone Short-Video vs Speaker Music)
  const act2 = prog(f, 295, 332, easeInOut);
  const act1Op = 1 - act2;

  // Act 1 timings: 0..100 NetEase -> 100..200 QQ Music -> 200..300 Soda Music
  const enter1 = prog(f, 6, 46, easeOut);
  const sw1 = prog(f, 98, 130, easeInOut); // 0 -> 1 (NetEase -> QQ Music)
  const sw2 = prog(f, 196, 228, easeInOut); // 0 -> 1 (QQ Music -> Soda Music)

  const activeIdx = sw2 > 0.5 ? 2 : sw1 > 0.5 ? 1 : 0;
  const activeApp = MUSIC_APPS[activeIdx];
  // Smooth continuous origin Y gliding from card 0 -> card 1 -> card 2
  const streamStartY = lerp(
    lerp(MUSIC_APPS[0].cardY + 74, MUSIC_APPS[1].cardY + 74, sw1),
    MUSIC_APPS[2].cardY + 74,
    sw2
  );
  const switchPulse =
    activeIdx === 1
      ? prog(f, 100, 126, easeOut) * (1 - prog(f, 165, 190, easeInOut))
      : activeIdx === 2
      ? prog(f, 198, 224, easeOut) * (1 - prog(f, 265, 290, easeInOut))
      : 0;

  // Act 2 timings (f >= 300)
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
          background: `radial-gradient(circle at 26% 56%, rgba(56,189,248,0.16) 0%, rgba(56,189,248,0) 42%), radial-gradient(circle at 78% 54%, rgba(255,92,108,0.18) 0%, rgba(255,92,108,0) 42%)`,
        }}
      />

      {/* ==================== ACT 1: 3 REAL MUSIC APPS AUTO-DETECT & AUTO-SWITCH ==================== */}
      {act1Op > 0.001 && (
        <AbsoluteFill style={{ opacity: act1Op, transform: `scale(${lerp(1, 0.96, act2)})` }}>
          {/* Header */}
          <div style={{ position: "absolute", left: 100, top: 60, zIndex: 20 }}>
            <Eyebrow text="Smart Source Auto-Follow" start={4} />
            <div style={{ marginTop: 14 }}>
              <Reveal text="换个音乐 App 听歌，自动识别无缝切换。" start={10} size={58} weight={700} stagger={1.6} />
            </div>
            <div style={{ marginTop: 10 }}>
              <Reveal
                text="网易云、QQ 音乐、汽水音乐开启「自动识别」：切到哪个播，音箱就自动跟过去。"
                start={32}
                size={27}
                weight={450}
                color="#CBD5E1"
                stagger={0.5}
              />
            </div>
          </div>

          {/* Left Background Context: Dimmed Real 03-dark Phone */}
          <div style={{ position: "absolute", left: 95, top: 235, perspective: 1800, opacity: enter1 }}>
            <div style={{ transform: "rotateY(8deg) rotateX(2deg)", transformStyle: "preserve-3d" }}>
              <StagePhone src="ui/03-dark.png" w={390} dim={0.82} />
            </div>
          </div>

          {/* Left Foreground: 3 Crystal-Clear Real Music App Cards (网易云音乐 / QQ 音乐 / 汽水音乐) */}
          <div style={{ position: "absolute", left: 175, top: 342, width: 530, zIndex: 20 }}>
            {MUSIC_APPS.map((app, idx) => {
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
                  <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
                    <div style={{ display: "flex", alignItems: "center", gap: 16 }}>
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
                        }}
                      >
                        {app.badgeGlyph}
                      </div>
                      <div>
                        <div
                          style={{
                            fontFamily: FONT_CN,
                            fontSize: 30,
                            fontWeight: 800,
                            color: isActive ? "#0F172A" : "#FFFFFF",
                          }}
                        >
                          {app.name}
                        </div>
                        <div
                          style={{
                            fontFamily: FONT_CN,
                            fontSize: 18,
                            fontWeight: 500,
                            color: isActive ? "#475569" : "#94A3B8",
                          }}
                        >
                          {app.track}
                        </div>
                      </div>
                    </div>

                    <div
                      style={{
                        padding: "7px 16px",
                        borderRadius: 999,
                        background: isActive ? app.color : "rgba(255,255,255,0.08)",
                        fontFamily: FONT_CN,
                        fontSize: 18,
                        fontWeight: 700,
                        color: isActive ? "#FFFFFF" : "#94A3B8",
                        whiteSpace: "nowrap",
                      }}
                    >
                      {isActive ? (idx === 0 ? "♫ 正在投送" : "⚡ 已自动切换") : "待命"}
                    </div>
                  </div>

                  {/* Bottom Row: Real-style Blue Toggle ("自动识别：允许") */}
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
                          fontFamily: FONT_CN,
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

          {/* Center Curved Stream Gliding Dynamically from Card 0 -> Card 1 -> Card 2 to the Speaker */}
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

          {/* Center Clean Auto-Switch Callout Pill (Positioned cleanly above the curve) */}
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
                padding: "16px 32px",
                borderRadius: 999,
                background: `${activeApp.color}28`,
                border: `2px solid ${activeApp.color}`,
                boxShadow: `0 18px 46px rgba(0,0,0,0.48), 0 0 36px ${activeApp.color}38`,
                fontFamily: FONT_CN,
                fontSize: 28,
                fontWeight: 700,
                color: "#FFFFFF",
                whiteSpace: "nowrap",
                transform: `scale(${1 + switchPulse * 0.08})`,
              }}
            >
              {activeIdx === 0
                ? "🎵 自动跟随：网易云音乐"
                : `⚡ 自动识别 · 切至 ${activeApp.name}`}
            </div>
          </div>
          <div
            style={{
              position: "absolute",
              left: 755,
              top: 855,
              width: 480,
              textAlign: "center",
              fontFamily: FONT_CN,
              fontSize: 25,
              fontWeight: 500,
              color: "#CBD5E1",
              opacity: enter1,
            }}
          >
            切歌换 App 全自动，免回妙投重选
          </div>

          {/* Right: Prominent Speaker Card Displaying Live Switched Music App */}
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
                fontFamily: FONT_CN,
                fontSize: 28,
                fontWeight: 800,
                color: "#FFFFFF",
                whiteSpace: "nowrap",
              }}
            >
              ♫ 正在投送：{activeApp.name}
            </div>
            <div style={{ fontFamily: FONT_CN, fontSize: 25, color: "#CBD5E1", fontWeight: 500 }}>
              {activeApp.track}
            </div>
          </div>
        </AbsoluteFill>
      )}

      {/* ==================== ACT 2: REAL-LIFE SPLIT — 📱 手机内播放 vs 🔊 客厅音箱播放 ==================== */}
      {act2 > 0.001 && (
        <AbsoluteFill style={{ opacity: act2, transform: `scale(${lerp(1.04, 1, act2)})` }}>
          {/* Header */}
          <div style={{ position: "absolute", left: 100, top: 60, zIndex: 20 }}>
            <Eyebrow text="Dual-Channel Isolation · Zero Interruption" start={302} />
            <div style={{ marginTop: 14 }}>
              <Reveal text="手机刷视频、回语音，音箱照常放歌。" start={308} size={62} weight={700} stagger={1.6} />
            </div>
            <div style={{ marginTop: 10 }}>
              <Reveal
                text="只投你选的音乐 App，短视频与微信语音全部留在手机本地，不断歌、不串音。"
                start={332}
                size={27}
                weight={450}
                color="#CBD5E1"
                stagger={0.5}
              />
            </div>
          </div>

          {/* LEFT PANEL: Symmetric Top Header ("📱 手机内播放" | "● 声音仅留本机") + Short-Video Phone */}
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
              padding: "32px 42px 36px 42px",
              opacity: act2Enter,
            }}
          >
            {/* Symmetric Panel Header Matching Right Box! */}
            <div style={{ width: "100%", display: "flex", justifyContent: "space-between", alignItems: "center" }}>
              <div
                style={{
                  padding: "8px 22px",
                  borderRadius: 999,
                  background: "rgba(96,165,250,0.22)",
                  border: "1.5px solid rgba(96,165,250,0.72)",
                  fontFamily: FONT_CN,
                  fontSize: 23,
                  fontWeight: 700,
                  color: "#93C5FD",
                }}
              >
                📱 手机内播放
              </div>
              <div
                style={{
                  padding: "8px 18px",
                  borderRadius: 999,
                  background: "rgba(56,189,248,0.16)",
                  border: "1.5px solid rgba(56,189,248,0.55)",
                  fontFamily: FONT_CN,
                  fontSize: 20,
                  fontWeight: 700,
                  color: "#7DD3FC",
                }}
              >
                ● 声音仅留本机
              </div>
            </div>

            {/* Body: Phone running Short Videos + Clean Right Copy */}
            <div style={{ display: "flex", alignItems: "center", gap: 36, flex: 1, marginTop: 22 }}>
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
                    {[
                      {
                        bg: "linear-gradient(160deg, #1E3A5F 0%, #2D1B4E 55%, #111827 100%)",
                        tag: "短视频推荐",
                        title: "@旅行日记 · 周末露营",
                        likes: "12.8w",
                        emoji: "🏕️",
                      },
                      {
                        bg: "linear-gradient(160deg, #4C1D3D 0%, #1F2937 55%, #0F172A 100%)",
                        tag: "美食探店",
                        title: "@街角小馆 · 深夜拉面",
                        likes: "8.4w",
                        emoji: "🍜",
                      },
                    ].map((v, idx) => (
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
                        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
                          <div
                            style={{
                              padding: "4px 12px",
                              borderRadius: 999,
                              background: "rgba(0,0,0,0.45)",
                              fontFamily: FONT_CN,
                              fontSize: 15,
                              fontWeight: 600,
                              color: "#FFFFFF",
                            }}
                          >
                            {v.tag}
                          </div>
                          <div
                            style={{
                              padding: "4px 11px",
                              borderRadius: 999,
                              background: "rgba(96,165,250,0.28)",
                              border: "1px solid rgba(96,165,250,0.7)",
                              fontFamily: FONT_CN,
                              fontSize: 14,
                              fontWeight: 700,
                              color: "#93C5FD",
                            }}
                          >
                            🔊 手机发声
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
                              padding: "6px 15px",
                              borderRadius: 999,
                              background: "rgba(0,0,0,0.45)",
                              fontFamily: FONT_CN,
                              fontSize: 15,
                              color: "#E2E8F0",
                            }}
                          >
                            ▶ 短视频外放中
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
                            <div>分享</div>
                          </div>
                        </div>

                        <div style={{ paddingRight: 42 }}>
                          <div style={{ fontFamily: FONT_CN, fontSize: 18, fontWeight: 700, color: "#FFFFFF", whiteSpace: "nowrap" }}>
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

                  {/* Popping WeChat Voice Notification Banner */}
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
                      }}
                    >
                      💬
                    </div>
                    <div>
                      <div style={{ fontFamily: FONT_CN, fontSize: 15, fontWeight: 700, color: "#FFFFFF" }}>
                        微信语音 6&quot;
                      </div>
                      <div style={{ fontFamily: FONT_CN, fontSize: 13, color: "#86EFAC" }}>仅手机本机收听</div>
                    </div>
                  </div>
                </div>
              </div>

              <div style={{ display: "flex", flexDirection: "column", gap: 22 }}>
                <div style={{ fontFamily: FONT_CN, fontSize: 40, fontWeight: 700, color: "#FFFFFF", lineHeight: 1.28 }}>
                  照常刷短视频
                  <br />
                  听微信语音
                </div>
                <div style={{ fontFamily: FONT_CN, fontSize: 25, color: "#CBD5E1", lineHeight: 1.58 }}>
                  声音只留在手机本地，
                  <br />
                  绝不外放到客厅音箱，
                  <br />
                  不尴尬、不社死。
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
                padding: "16px 26px",
                borderRadius: 24,
                background: "rgba(11,13,18,0.96)",
                border: "2px solid rgba(255,92,108,0.75)",
                boxShadow: "0 18px 46px rgba(0,0,0,0.6), 0 0 32px rgba(255,92,108,0.25)",
                textAlign: "center",
              }}
            >
              <div style={{ fontFamily: FONT_CN, fontSize: 28, fontWeight: 800, color: C.coral }}>互不打扰</div>
              <div style={{ marginTop: 4, fontFamily: FONT_CN, fontSize: 18, fontWeight: 600, color: "#E2E8F0" }}>
                双路独立发声
              </div>
            </div>
          </div>

          {/* RIGHT PANEL: Symmetric Top Header ("🔊 客厅音箱播放" | "● 音乐持续不断") */}
          <div
            style={{
              position: "absolute",
              left: 1065,
              top: 255,
              width: 755,
              height: 755,
              borderRadius: 38,
              background: "rgba(21,26,37,0.88)",
              border: "2px solid rgba(255,92,108,0.58)",
              boxShadow: "0 36px 85px rgba(0,0,0,0.52), 0 0 60px rgba(255,92,108,0.12)",
              display: "flex",
              flexDirection: "column",
              alignItems: "center",
              justifyContent: "space-between",
              padding: "32px 42px 36px 42px",
              opacity: act2Enter,
            }}
          >
            <div style={{ width: "100%", display: "flex", justifyContent: "space-between", alignItems: "center" }}>
              <div
                style={{
                  padding: "8px 22px",
                  borderRadius: 999,
                  background: "rgba(255,92,108,0.20)",
                  border: "1.5px solid rgba(255,92,108,0.68)",
                  fontFamily: FONT_CN,
                  fontSize: 23,
                  fontWeight: 700,
                  color: "#FF8A95",
                }}
              >
                🔊 客厅音箱播放
              </div>
              <div
                style={{
                  padding: "8px 18px",
                  borderRadius: 999,
                  background: "rgba(52,211,153,0.18)",
                  border: "1.5px solid rgba(52,211,153,0.6)",
                  fontFamily: FONT_CN,
                  fontSize: 20,
                  fontWeight: 700,
                  color: "#6EE7B7",
                }}
              >
                ● 音乐持续不断
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
                src="ui/01-dark.png"
                rect={[32, 102, 614, 240]}
                radius={26}
                scale={0.86}
                elevation={0.9}
                accentBorder="rgba(255,92,108,0.65)"
              />

              <div style={{ display: "flex", alignItems: "center", gap: 18, marginTop: 4 }}>
                <div style={{ display: "flex", alignItems: "flex-end", gap: 6, height: 34 }}>
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
                <div style={{ fontFamily: FONT_CN, fontSize: 34, fontWeight: 700, color: "#FFFFFF" }}>
                  音箱照常放歌 · 不断歌、不串音
                </div>
              </div>
            </div>
          </div>
        </AbsoluteFill>
      )}
    </AbsoluteFill>
  );
};
