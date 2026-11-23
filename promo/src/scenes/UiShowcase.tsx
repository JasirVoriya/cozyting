import React from "react";
import { AbsoluteFill, useCurrentFrame, interpolateColors } from "remotion";
import { C, FONT_CN, prog, easeInOut, easeOut, sceneFade, lerp } from "../theme";
import { Eyebrow, Reveal, StagePhone, TapRipple, UiSlice } from "../components";

export const UiShowcase: React.FC<{ dur: number }> = ({ dur }) => {
  const f = useCurrentFrame();

  // At f=205, finger taps "浅色" on the real floating Theme Switcher card cut from 05-dark/05-light -> radial wipe to Light Theme!
  const theme = prog(f, 212, 280, easeInOut);
  // At f=385, side phones transition to 06-light (About & Update) and 08-light (Cross-Version Update Changelog Dialog)
  const swap = prog(f, 385, 430, easeInOut);
  const popUpdateDialog = prog(f, 425, 468, easeOut);

  const bg = interpolateColors(theme, [0, 1], [C.bg, "#E8ECF2"]);
  const ink = interpolateColors(theme, [0, 1], [C.ink, "#0F172A"]);
  const ink2 = interpolateColors(theme, [0, 1], [C.ink2, "#475569"]);
  const pillBg = interpolateColors(theme, [0, 1], ["rgba(21,26,35,0.88)", "rgba(255,255,255,0.92)"]);
  const pillBorder = interpolateColors(theme, [0, 1], ["rgba(255,255,255,0.14)", "rgba(15,23,42,0.12)"]);

  const enter = (i: number) => prog(f, 6 + i * 10, 66 + i * 10, easeInOut);
  const float = (i: number) => Math.sin((f + i * 40) / 70) * 7;
  const orbit = lerp(-4, 4, f / dur);

  const phone = (i: number, dark: string, light: string, dark2?: string, light2?: string, isRight?: boolean) => {
    const pos = [-1, 0, 1][i];
    const e = enter(i === 1 ? 0 : i === 0 ? 1 : 2);
    const x = pos * 485;
    const ry = -pos * 18 + orbit;
    const z = pos === 0 ? 55 : -140;
    return (
      <div
        key={i}
        style={{
          position: "absolute",
          left: 960 - 185 + x,
          top: 262,
          transform: `translateY(${(1 - e) * 460 + float(i)}px) translateZ(${z}px) rotateY(${ry}deg)`,
          opacity: e,
        }}
      >
        <StagePhone src={`ui/${dark}`} src2={`ui/${light}`} wipe2={theme} w={345} />
        {dark2 && light2 && (
          <div style={{ position: "absolute", inset: 0, opacity: swap }}>
            <StagePhone
              src={`ui/${dark2}`}
              src2={`ui/${light2}`}
              wipe2={theme}
              w={345}
              dim={isRight ? popUpdateDialog * 0.42 : 0}
              slices={
                isRight
                  ? [
                      {
                        id: "update-dialog-card",
                        src: theme > 0.5 ? `ui/${light2}` : `ui/${dark2}`,
                        rect: [32, 442, 614, 610],
                        radius: 34,
                        elevate: popUpdateDialog,
                        dx: -24,
                        dy: -16,
                        popScale: 1.12,
                        accentBorder: "rgba(37,99,235,0.75)",
                      },
                    ]
                  : []
              }
            />
          </div>
        )}
      </div>
    );
  };

  const cap = (text: string, a: number, b: number, eyebrow: string) => (
    <div
      style={{
        position: "absolute",
        top: 62,
        width: "100%",
        display: "flex",
        flexDirection: "column",
        alignItems: "center",
        gap: 12,
      }}
    >
      <Eyebrow text={eyebrow} start={a} out={b} />
      <Reveal text={text} start={a + 8} out={b} size={48} weight={650} stagger={2} color={ink} />
    </div>
  );

  // Floating real "主题模式 (跟随系统 / 浅色 / 深色)" card slice cut from 05-dark / 05-light
  const themeCardIn = prog(f, 165, 200, easeOut) * (1 - prog(f, 355, 385, easeInOut));

  const labels =
    swap < 0.5
      ? ["常用音源 · 自动识别", "多音箱同步 · 独立音量", "4 档延迟 · 实时热切"]
      : ["关于与更新 · 可一键关闭", "多音箱同步 · 独立音量", "跨版本日志 · SHA-256 校验"];

  return (
    <AbsoluteFill style={{ background: bg, opacity: sceneFade(f, dur, 6, 18), overflow: "hidden" }}>
      <AbsoluteFill
        style={{
          background: `radial-gradient(ellipse 52% 45% at 50% 70%, rgba(76,141,255,${lerp(0.16, 0.2, theme)}) 0%, rgba(76,141,255,0) 70%), radial-gradient(ellipse 30% 30% at 30% 60%, rgba(255,92,108,0.10) 0%, rgba(255,92,108,0) 70%)`,
        }}
      />
      <AbsoluteFill style={{ perspective: 2200, perspectiveOrigin: "50% 40%" }}>
        <AbsoluteFill style={{ transformStyle: "preserve-3d" }}>
          {phone(0, "03-dark.png", "03-light.png", "06-dark.png", "06-light.png", false)}
          {phone(2, "02-dark.png", "02-light.png", "08-dark.png", "08-light.png", true)}
          {phone(1, "01-dark.png", "01-light.png")}
        </AbsoluteFill>
      </AbsoluteFill>

      {/* Real floating Theme Switcher Slice cut directly from 05-dark.png / 05-light.png */}
      {themeCardIn > 0.001 && (
        <div
          style={{
            position: "absolute",
            left: 960 - (586 * 0.5) / 2,
            top: 158,
            opacity: themeCardIn,
            transform: `translateY(${(1 - themeCardIn) * 18}px)`,
            zIndex: 30,
          }}
        >
          <UiSlice
            src="ui/05-dark.png"
            src2="ui/05-light.png"
            mix={theme}
            rect={[46, 200, 586, 94]}
            radius={22}
            scale={0.5}
            elevation={1}
            accentBorder="rgba(37,99,235,0.65)"
          >
            <TapRipple x={293} y={56} start={204} scale={0.5} color="#2563EB" />
          </UiSlice>
        </div>
      )}

      {/* bottom fade so phones sink into the floor */}
      <AbsoluteFill style={{ background: `linear-gradient(180deg, rgba(0,0,0,0) 74%, ${bg} 98%)` }} />
      {cap("专辑氛围主界面，信息克制一目了然。", 6, 195, "Real Android UI")}
      {cap("深色 · 浅色 · 跟随系统，一键丝滑切换", 215, 372, "Adaptive Dual Themes")}
      {cap("跨版本更新日志聚合 · SHA-256 与签名校验", 392, 580, "Transparent & Auditable")}

      {/* 3 floating screen badges at the bottom */}
      <div
        style={{
          position: "absolute",
          bottom: 40,
          width: "100%",
          display: "flex",
          justifyContent: "center",
          gap: 185,
          opacity: prog(f, 60, 100),
        }}
      >
        {labels.map((lb, idx) => (
          <div
            key={idx}
            style={{
              padding: "10px 24px",
              borderRadius: 999,
              background: pillBg,
              border: `1px solid ${pillBorder}`,
              boxShadow: "0 12px 32px rgba(0,0,0,0.18)",
              fontFamily: FONT_CN,
              fontSize: 20,
              fontWeight: 500,
              letterSpacing: 1.5,
              color: ink2,
              minWidth: 265,
              textAlign: "center",
            }}
          >
            {lb}
          </div>
        ))}
      </div>
    </AbsoluteFill>
  );
};
