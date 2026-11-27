// Renders dedicated Quick Panel feature promo covers in 16:9 (1920x1080), 4:3 (1600x1200), and 3:4 (1200x1600):
//   node scripts/covers-quick-panel.mjs --locale zh
//   node scripts/covers-quick-panel.mjs --locale all
//   node scripts/covers-quick-panel.mjs --locale all --format jpeg
import { bundle } from "@remotion/bundler";
import { renderStill, selectComposition } from "@remotion/renderer";
import path from "node:path";
import { mkdirSync } from "node:fs";

const COVER_MAP = {
  zh: [
    { id: "QuickPanelCover16x9", aspect: "16x9" },
    { id: "QuickPanelCover4x3", aspect: "4x3" },
    { id: "QuickPanelCover3x4", aspect: "3x4" },
  ],
  en: [
    { id: "QuickPanelCover16x9En", aspect: "16x9" },
    { id: "QuickPanelCover4x3En", aspect: "4x3" },
    { id: "QuickPanelCover3x4En", aspect: "3x4" },
  ],
  ja: [
    { id: "QuickPanelCover16x9Ja", aspect: "16x9" },
    { id: "QuickPanelCover4x3Ja", aspect: "4x3" },
    { id: "QuickPanelCover3x4Ja", aspect: "3x4" },
  ],
};

const args = process.argv.slice(2);
let localeArg = "zh";
let format = "png";
for (let i = 0; i < args.length; i++) {
  if (args[i] === "--locale" && args[i + 1]) {
    localeArg = args[++i];
  } else if (args[i] === "--format" && args[i + 1]) {
    format = args[++i];
  }
}

const locales = localeArg === "all" ? ["zh", "en", "ja"] : [localeArg];
for (const loc of locales) {
  if (!COVER_MAP[loc]) {
    throw new Error(`Unsupported locale "${loc}", expected zh | en | ja | all`);
  }
}
if (format !== "png" && format !== "jpeg") {
  throw new Error(`Unsupported format "${format}", expected png | jpeg`);
}

const outDir = path.resolve("out", "covers", "quick-panel");
mkdirSync(outDir, { recursive: true });
const serveUrl = await bundle({ entryPoint: path.resolve("src/index.tsx") });

for (const loc of locales) {
  for (const item of COVER_MAP[loc]) {
    const composition = await selectComposition({ serveUrl, id: item.id });
    const ext = format === "jpeg" ? "jpg" : "png";
    const output = path.join(outDir, `cozyting-quick-panel-cover-${loc}-${item.aspect}.${ext}`);
    await renderStill({
      composition,
      serveUrl,
      output,
      imageFormat: format,
      ...(format === "jpeg" ? { jpegQuality: 92 } : {}),
    });
    console.log(output);
  }
}
