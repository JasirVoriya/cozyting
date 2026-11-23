import React from "react";
import { AbsoluteFill, useCurrentFrame } from "remotion";
import { C, FONT_CN, FONT_EN, FONT_MONO, prog, easeInOut, easeOut, BEAT } from "../theme";
import { LogoMark, Reveal, Ripple, StagePhone } from "../components";

export const Outro: React.FC<{ dur: number }> = ({ dur }) => {
  const f = useCurrentFrame();
  const size = 230;
  const rings = prog(f, 0, 50, easeInOut);
  const core = prog(f, 4, 34, easeOut);
  const play = prog(f, 20, 44);
  const arc = prog(f, 24, 60, easeInOut);
  const pulse =
    Math.max(0, Math.sin(((f - 60) / BEAT) * Math.PI)) * prog(f, 60, 90) * (1 - prog(f, 300, 360));
  const phonesIn = prog(f, 10, 75, easeOut);
  const end = prog(f, dur - 70, dur - 10, easeInOut);

  const tags = ["Android 10+", "最多 63 台音箱同频", "单 App 音频隔离", "纯局域网 · AGPL-3.0"];

  return (
    <AbsoluteFill style={{ background: C.bg }}>
      <AbsoluteFill
        style={{
          background: `radial-gradient(circle at 30% 54%, rgba(46,108,246,${0.18 * phonesIn}) 0%, rgba(46,108,246,0) 42%), radial-gradient(circle at 68% 38%, rgba(255,92,108,${0.10 * core}) 0%, rgba(255,92,108,0) 36%)`,
        }}
      />
      <svg width="1920" height="1080" style={{ position: "absolute", inset: 0 }}>
        <Ripple cx={1300} cy={295} t={f / 70} maxR={650} color={C.coral} width={2} />
        <Ripple cx={1300} cy={295} t={(f - 12) / 80} maxR={720} color={C.ink} width={1} />
      </svg>

      <AbsoluteFill style={{ opacity: 1 - end, filter: `blur(${end * 6}px)` }}>
        {/* Left Side: Dual-Theme Real Hero Phones (01-light in back, 01-dark in front) */}
        <div
          style={{
            position: "absolute",
            left: 140,
            top: 110,
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
              transform: `translateY(${(1 - phonesIn) * 120}px) rotateY(-14deg) rotateX(4deg) scale(0.92)`,
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
              transform: `translateY(${(1 - phonesIn) * 90}px) rotateY(12deg) rotateX(3deg)`,
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
                  elevate: prog(f, 45, 95, easeOut),
                  dx: 22,
                  dy: -6,
                  popScale: 1.09,
                  accentBorder: "rgba(76,141,255,0.75)",
                },
              ]}
            />
          </div>
        </div>

        {/* Right Side: Brand Mark, Wordmark, Tagline, Feature Pills, and GitHub URL */}
        <div
          style={{
            position: "absolute",
            left: 1300 - size / 2 + ((256 - 226) / 512) * size,
            top: 280 - size / 2,
          }}
        >
          <LogoMark size={size} rings={rings} core={core} play={play} arc={arc} pulse={pulse} />
        </div>

        <div
          style={{
            position: "absolute",
            left: 880,
            top: 435,
            width: 840,
            display: "flex",
            justifyContent: "center",
            alignItems: "baseline",
            gap: 24,
          }}
        >
          <Reveal text="妙投" start={34} size={104} weight={700} stagger={5} tracking={6} />
          <Reveal text="MiaoCast" start={46} size={54} weight={600} font={FONT_EN} color={C.ink2} stagger={2} />
        </div>

        <div
          style={{
            position: "absolute",
            left: 880,
            top: 590,
            width: 840,
            display: "flex",
            justifyContent: "center",
          }}
        >
          <Reveal
            text="任何安卓手机，一键投向全屋小米音箱。"
            start={72}
            size={32}
            weight={500}
            color={C.ink}
            stagger={1.3}
            tracking={2}
          />
        </div>

        <div
          style={{
            position: "absolute",
            left: 840,
            top: 690,
            width: 920,
            display: "flex",
            justifyContent: "center",
            flexWrap: "wrap",
            gap: 14,
          }}
        >
          {tags.map((t, i) => {
            const a = prog(f, 108 + i * 6, 148 + i * 6);
            return (
              <div
                key={t}
                style={{
                  padding: "10px 22px",
                  borderRadius: 999,
                  border: `1px solid ${C.line2}`,
                  background: "rgba(255,255,255,0.04)",
                  fontFamily: FONT_CN,
                  fontSize: 20,
                  color: C.ink,
                  opacity: a,
                  transform: `translateY(${(1 - a) * 16}px)`,
                }}
              >
                {t}
              </div>
            );
          })}
        </div>

        <div
          style={{
            position: "absolute",
            left: 880,
            top: 810,
            width: 840,
            textAlign: "center",
            fontFamily: FONT_MONO,
            fontSize: 24,
            letterSpacing: 1,
            color: C.coral,
            opacity: prog(f, 155, 195),
          }}
        >
          github.com/JasirVoriya/miaocast
        </div>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};
