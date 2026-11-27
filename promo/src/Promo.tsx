import React from "react";
import { AbsoluteFill, Audio, Sequence, staticFile, interpolate } from "remotion";
import { T, TOTAL_FRAMES, type PromoLocale } from "./theme";
import { Finish } from "./components";
import { Intro } from "./scenes/Intro";
import { AnyPhone } from "./scenes/AnyPhone";
import { OneApp } from "./scenes/OneApp";
import { MultiRoomLatency } from "./scenes/MultiRoomLatency";
import { Lan } from "./scenes/Lan";
import { QuickPanel } from "./scenes/QuickPanel";
import { UiShowcase } from "./scenes/UiShowcase";
import { Search } from "./scenes/Search";
import { Outro } from "./scenes/Outro";

const S: React.FC<{ r: readonly [number, number]; children: (dur: number) => React.ReactNode }> = ({ r, children }) => (
  <Sequence from={r[0]} durationInFrames={r[1] - r[0]}>
    {children(r[1] - r[0])}
  </Sequence>
);

export const Promo: React.FC<{ locale?: PromoLocale }> = ({ locale = "zh" }) => (
  <AbsoluteFill style={{ background: "#0B0D12" }}>
    <Audio
      src={staticFile("music.wav")}
      volume={(f) => interpolate(f, [TOTAL_FRAMES - 45, TOTAL_FRAMES], [1, 0], { extrapolateLeft: "clamp" })}
    />
    <S r={T.intro}>{() => <Intro locale={locale} />}</S>
    <S r={T.anyPhone}>{(d) => <AnyPhone dur={d} locale={locale} />}</S>
    <S r={T.oneApp}>{(d) => <OneApp dur={d} locale={locale} />}</S>
    <S r={T.multiRoomLatency}>{(d) => <MultiRoomLatency dur={d} locale={locale} />}</S>
    <S r={T.lan}>{(d) => <Lan dur={d} locale={locale} />}</S>
    <S r={T.quickPanel}>{(d) => <QuickPanel dur={d} locale={locale} />}</S>
    <S r={T.ui}>{(d) => <UiShowcase dur={d} locale={locale} />}</S>
    <S r={T.search}>{(d) => <Search dur={d} locale={locale} />}</S>
    <S r={T.outro}>{(d) => <Outro dur={d} locale={locale} />}</S>
    <Finish />
  </AbsoluteFill>
);
