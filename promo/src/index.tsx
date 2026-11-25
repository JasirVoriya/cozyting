import React from "react";
import { Composition, Still, registerRoot } from "remotion";
import { Promo } from "./Promo";
import { Cover } from "./Cover";
import { FPS, TOTAL_FRAMES } from "./theme";

const Root: React.FC = () => (
  <>
    <Composition
      id="CozyTingPromo"
      component={Promo}
      defaultProps={{ locale: "zh" as const }}
      durationInFrames={TOTAL_FRAMES}
      fps={FPS}
      width={1920}
      height={1080}
    />
    <Composition
      id="CozyTingPromoEn"
      component={Promo}
      defaultProps={{ locale: "en" as const }}
      durationInFrames={TOTAL_FRAMES}
      fps={FPS}
      width={1920}
      height={1080}
    />
    <Composition
      id="CozyTingPromoJa"
      component={Promo}
      defaultProps={{ locale: "ja" as const }}
      durationInFrames={TOTAL_FRAMES}
      fps={FPS}
      width={1920}
      height={1080}
    />
    <Still
      id="CozyTingCover16x9"
      component={Cover}
      defaultProps={{ locale: "zh" as const, aspect: "16x9" as const }}
      width={1920}
      height={1080}
    />
    <Still
      id="CozyTingCover4x3"
      component={Cover}
      defaultProps={{ locale: "zh" as const, aspect: "4x3" as const }}
      width={1600}
      height={1200}
    />
    <Still
      id="CozyTingCover3x4"
      component={Cover}
      defaultProps={{ locale: "zh" as const, aspect: "3x4" as const }}
      width={1200}
      height={1600}
    />
    <Still
      id="CozyTingCover16x9En"
      component={Cover}
      defaultProps={{ locale: "en" as const, aspect: "16x9" as const }}
      width={1920}
      height={1080}
    />
    <Still
      id="CozyTingCover4x3En"
      component={Cover}
      defaultProps={{ locale: "en" as const, aspect: "4x3" as const }}
      width={1600}
      height={1200}
    />
    <Still
      id="CozyTingCover3x4En"
      component={Cover}
      defaultProps={{ locale: "en" as const, aspect: "3x4" as const }}
      width={1200}
      height={1600}
    />
    <Still
      id="CozyTingCover16x9Ja"
      component={Cover}
      defaultProps={{ locale: "ja" as const, aspect: "16x9" as const }}
      width={1920}
      height={1080}
    />
    <Still
      id="CozyTingCover4x3Ja"
      component={Cover}
      defaultProps={{ locale: "ja" as const, aspect: "4x3" as const }}
      width={1600}
      height={1200}
    />
    <Still
      id="CozyTingCover3x4Ja"
      component={Cover}
      defaultProps={{ locale: "ja" as const, aspect: "3x4" as const }}
      width={1200}
      height={1600}
    />
  </>
);

registerRoot(Root);
