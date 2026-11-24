// Renders review stills for given frames & locale:
//   node scripts/stills.mjs --locale en 380 650 1000 1320 1650 2050 2460 2850 3150 3680
import { bundle } from "@remotion/bundler";
import { renderStill, selectComposition } from "@remotion/renderer";
import path from "node:path";
import { mkdirSync } from "node:fs";

const COMP_MAP = {
  zh: "CozyTingPromo",
  en: "CozyTingPromoEn",
  ja: "CozyTingPromoJa",
};

const args = process.argv.slice(2);
let locale = "zh";
const frames = [];
for (let i = 0; i < args.length; i++) {
  if (args[i] === "--locale" && args[i + 1]) {
    locale = args[++i];
  } else {
    frames.push(Number(args[i]));
  }
}

const compId = COMP_MAP[locale];
if (!compId) {
  throw new Error(`Unsupported locale "${locale}", expected zh | en | ja`);
}

const outDir = path.resolve("stills", locale);
mkdirSync(outDir, { recursive: true });
const serveUrl = await bundle({ entryPoint: path.resolve("src/index.tsx") });
const composition = await selectComposition({ serveUrl, id: compId });
for (const frame of frames) {
  const output = path.join(outDir, `f${String(frame).padStart(4, "0")}.jpg`);
  await renderStill({ composition, serveUrl, frame, output, imageFormat: "jpeg", jpegQuality: 82, scale: 0.5 });
  console.log(output);
}
