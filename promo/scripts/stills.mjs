// Renders review stills for given frames: node scripts/stills.mjs 250 700 1200 ...
import { bundle } from "@remotion/bundler";
import { renderStill, selectComposition } from "@remotion/renderer";
import path from "node:path";
import { mkdirSync } from "node:fs";

const frames = process.argv.slice(2).map(Number);
const outDir = path.resolve("stills");
mkdirSync(outDir, { recursive: true });
const serveUrl = await bundle({ entryPoint: path.resolve("src/index.tsx") });
const composition = await selectComposition({ serveUrl, id: "MiaoCastPromo" });
for (const frame of frames) {
  const output = path.join(outDir, `f${String(frame).padStart(4, "0")}.jpg`);
  await renderStill({ composition, serveUrl, frame, output, imageFormat: "jpeg", jpegQuality: 80, scale: 0.5 });
  console.log(output);
}
