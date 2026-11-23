import React from "react";
import { AbsoluteFill, Audio, Sequence, staticFile, interpolate } from "remotion";
import { T, TOTAL_FRAMES } from "./theme";
import { Finish } from "./components";
import { Intro } from "./scenes/Intro";
import { AnyPhone } from "./scenes/AnyPhone";
import { OneApp } from "./scenes/OneApp";
import { MultiRoom } from "./scenes/MultiRoom";
import { Latency } from "./scenes/Latency";
import { Lan } from "./scenes/Lan";
import { UiShowcase } from "./scenes/UiShowcase";
import { Outro } from "./scenes/Outro";

const S: React.FC<{ r: readonly [number, number]; children: (dur: number) => React.ReactNode }> = ({ r, children }) => (
  <Sequence from={r[0]} durationInFrames={r[1] - r[0]}>
    {children(r[1] - r[0])}
  </Sequence>
);

export const Promo: React.FC = () => (
  <AbsoluteFill style={{ background: "#0B0D12" }}>
    <Audio src={staticFile("music.wav")} volume={(f) => interpolate(f, [TOTAL_FRAMES - 30, TOTAL_FRAMES], [1, 0], { extrapolateLeft: "clamp" })} />
    <S r={T.intro}>{() => <Intro />}</S>
    <S r={T.anyPhone}>{(d) => <AnyPhone dur={d} />}</S>
    <S r={T.oneApp}>{(d) => <OneApp dur={d} />}</S>
    <S r={T.multiRoom}>{(d) => <MultiRoom dur={d} />}</S>
    <S r={T.latency}>{(d) => <Latency dur={d} />}</S>
    <S r={T.lan}>{(d) => <Lan dur={d} />}</S>
    <S r={T.ui}>{(d) => <UiShowcase dur={d} />}</S>
    <S r={T.outro}>{(d) => <Outro dur={d} />}</S>
    <Finish />
  </AbsoluteFill>
);
