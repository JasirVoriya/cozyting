import React from "react";
import { AbsoluteFill, useCurrentFrame } from "remotion";
import { C, FONT_CN, FONT_EN, FONT_MONO, prog, easeInOut, easeOut, BEAT, lerp } from "../theme";
import { GitHubIcon, LogoMark, MouseCursor, Reveal, Ripple, StagePhone } from "../components";

/**
 * Scene 08 (8s = 480f) — Product Hero Freeze Frame (NEVER fades to black!)
 *   - Left: Dual-theme real hero phones
 *   - Right: Brand logo, MiaoCast name, tagline, 4 user-facing feature pills
 *   - Bottom-Right: Clean, prominent GitHub Search Bar with GitHub Icon + Repo Name ONLY + Search Button + Mouse Pointer Click!
 *   - Final frame holds 100% sharp & bright forever!
 */
export const Outro: React.FC<{ dur: number }> = () => {
  const f = useCurrentFrame();
  const size = 216;
  const rings = prog(f, 0, 48, easeInOut);
  const core = prog(f, 4, 32, easeOut);
  const play = prog(f, 18, 42);
  const arc = prog(f, 22, 56, easeInOut);
  const pulse = Math.max(0, Math.sin(((f - 58) / BEAT) * Math.PI)) * prog(f, 58, 86) * (1 - prog(f, 280, 340));
  const phonesIn = prog(f, 8, 68, easeOut);

  // Prominent GitHub Search Box animation
  const barIn = prog(f, 115, 155, easeOut);

  // Mouse pointer glides from lower-right directly onto the "搜索" button inside the GitHub search bar (center ~ x=1698, y=814)
  const cursorMove = prog(f, 175, 232, easeInOut);
  const cursorX = lerp(1835, 1698, cursorMove);
  const cursorY = lerp(1025, 816, cursorMove);
  const hovering = prog(f, 218, 236, easeOut);

  // Click happens at f=244..264, then holds cleanly in highlighted state on the final frame!
  const clickDown = prog(f, 244, 252, easeOut);
  const clickUp = prog(f, 252, 266, easeOut);
  const pressing = Math.max(0, clickDown - clickUp);
  const rippleT = f >= 246 ? Math.min(1, (f - 246) / 42) : 0;

  const tags = ["Android 10+", "最多 63 台同频", "刷视频不打断听歌", "纯局域网 · 免费开源"];

  return (
    <AbsoluteFill style={{ background: C.bg }}>
      <AbsoluteFill
        style={{
          background: `radial-gradient(circle at 28% 54%, rgba(46,108,246,${0.20 * phonesIn}) 0%, rgba(46,108,246,0) 42%), radial-gradient(circle at 69% 38%, rgba(255,92,108,${0.12 * core}) 0%, rgba(255,92,108,0) 38%)`,
        }}
      />
      <svg width="1920" height="1080" style={{ position: "absolute", inset: 0 }}>
        <Ripple cx={1315} cy={245} t={f / 70} maxR={620} color={C.coral} width={2} />
        <Ripple cx={1315} cy={245} t={(f - 12) / 80} maxR={700} color={C.ink} width={1} />
      </svg>

      {/* NO END FADE-OUT! Everything stays crisp on screen until the final frame. */}
      <AbsoluteFill>
        {/* Left Side: Dual-Theme Real Hero Phones (01-light in back, 01-dark in front) */}
        <div
          style={{
            position: "absolute",
            left: 115,
            top: 105,
            perspective: 2000,
            opacity: phonesIn,
          }}
        >
          {/* Light Mode Phone (back right) */}
          <div
            style={{
              position: "absolute",
              left: 255,
              top: 35,
              transform: `translateY(${(1 - phonesIn) * 110}px) rotateY(-14deg) rotateX(4deg) scale(0.92)`,
              transformStyle: "preserve-3d",
            }}
          >
            <StagePhone src="ui/01-light.png" w={355} />
          </div>

          {/* Dark Mode Phone (front left, with elevated speaker card) */}
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
              src="ui/01-dark.png"
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

        {/* Right Side: Brand Mark, Wordmark, Tagline, Feature Pills, and Clean GitHub Search Box */}
        <div
          style={{
            position: "absolute",
            left: 1315 - size / 2 + ((256 - 226) / 512) * size,
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
          <Reveal text="妙投" start={28} size={104} weight={700} stagger={4} tracking={6} />
          <Reveal text="MiaoCast" start={40} size={56} weight={600} font={FONT_EN} color="#CBD5E1" stagger={2} />
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
            text="任何安卓手机，一键投向全屋小米音箱。"
            start={62}
            size={36}
            weight={600}
            color="#FFFFFF"
            stagger={1.2}
            tracking={1.5}
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
          {tags.map((t, i) => {
            const a = prog(f, 92 + i * 6, 128 + i * 6);
            return (
              <div
                key={t}
                style={{
                  padding: "11px 24px",
                  borderRadius: 999,
                  border: "1.5px solid rgba(255,255,255,0.20)",
                  background: "rgba(255,255,255,0.06)",
                  fontFamily: FONT_CN,
                  fontSize: 23,
                  fontWeight: 600,
                  color: "#F1F5F9",
                  opacity: a,
                  transform: `translateY(${(1 - a) * 14}px)`,
                }}
              >
                {t}
              </div>
            );
          })}
        </div>

        {/* CLEAN GITHUB SEARCH BAR: [GitHub Icon] + [🔍 Repo Name ONLY] + [搜索] + Mouse Pointer */}
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
              border: `2.5px solid ${hovering > 0.25 ? "#FF7A87" : "rgba(255,92,108,0.82)"}`,
              boxShadow: `0 26px 68px rgba(0,0,0,0.60), 0 0 ${36 + hovering * 28}px rgba(255,92,108,${0.25 + hovering * 0.20})`,
              display: "flex",
              alignItems: "center",
              justifyContent: "space-between",
              padding: "0 16px 0 28px",
            }}
          >
            {/* Left: GitHub Octocat Icon + Magnifier + Repo Name ONLY */}
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
                  fontSize: 31,
                  fontWeight: 700,
                  color: "#FFFFFF",
                  letterSpacing: 0.2,
                  whiteSpace: "nowrap",
                }}
              >
                JasirVoriya/miaocast-releases
              </div>
            </div>

            {/* Right: Search Button */}
            <div
              style={{
                height: 76,
                padding: "0 42px",
                borderRadius: 999,
                background: hovering > 0.25 ? "#FF6B7A" : C.coral,
                boxShadow: `0 10px 30px rgba(255,92,108,${0.44 + hovering * 0.26})`,
                display: "flex",
                alignItems: "center",
                justifyContent: "center",
                fontFamily: FONT_CN,
                fontSize: 28,
                fontWeight: 700,
                color: "#FFFFFF",
                letterSpacing: 3,
                whiteSpace: "nowrap",
                flexShrink: 0,
                transform: `scale(${1 + hovering * 0.035 - pressing * 0.07})`,
              }}
            >
              搜索
            </div>
          </div>
        </div>

        {/* Crisp Vector Mouse Pointer Gliding & Clicking "搜索" */}
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
