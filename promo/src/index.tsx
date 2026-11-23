import React from "react";
import { Composition, registerRoot } from "remotion";
import { Promo } from "./Promo";
import { FPS, TOTAL_FRAMES } from "./theme";

const Root: React.FC = () => (
  <>
    <Composition
      id="MiaoCastPromo"
      component={Promo}
      defaultProps={{ locale: "zh" as const }}
      durationInFrames={TOTAL_FRAMES}
      fps={FPS}
      width={1920}
      height={1080}
    />
    <Composition
      id="MiaoCastPromoEn"
      component={Promo}
      defaultProps={{ locale: "en" as const }}
      durationInFrames={TOTAL_FRAMES}
      fps={FPS}
      width={1920}
      height={1080}
    />
    <Composition
      id="MiaoCastPromoJa"
      component={Promo}
      defaultProps={{ locale: "ja" as const }}
      durationInFrames={TOTAL_FRAMES}
      fps={FPS}
      width={1920}
      height={1080}
    />
  </>
);

registerRoot(Root);
