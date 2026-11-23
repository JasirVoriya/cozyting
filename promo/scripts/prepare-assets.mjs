// Crops the dark / light halves of the official 3-language UI screenshots into 678x1510 phone PNGs.
import { execFileSync } from "node:child_process";
import { readdirSync, mkdirSync, copyFileSync, existsSync } from "node:fs";
import path from "node:path";

const ROOT = new URL("../../", import.meta.url).pathname;
const SHOTS_ROOT = path.join(ROOT, "docs/assets/screenshots");
const PUBLIC_ROOT = new URL("../public/", import.meta.url).pathname;

const LOCALE_DIRS = [
  { locale: "zh", srcDir: SHOTS_ROOT },
  { locale: "en", srcDir: path.join(SHOTS_ROOT, "en") },
  { locale: "ja", srcDir: path.join(SHOTS_ROOT, "ja") },
];

function probeSize(filePath) {
  const out = execFileSync(
    "ffprobe",
    ["-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height", "-of", "csv=s=x:p=0", filePath],
    { encoding: "utf8" }
  ).trim();
  const [w, h] = out.split("x").map(Number);
  return { w, h };
}

for (const { locale, srcDir } of LOCALE_DIRS) {
  if (!existsSync(srcDir)) {
    throw new Error(`Missing screenshot directory for locale "${locale}": ${srcDir}`);
  }
  const dstDir = path.join(PUBLIC_ROOT, "ui", locale);
  mkdirSync(dstDir, { recursive: true });

  const files = readdirSync(srcDir)
    .filter((f) => f.endsWith(".png") && !f.startsWith("._"))
    .sort();

  for (const f of files) {
    const srcFile = path.join(srcDir, f);
    const id = f.slice(0, 2);
    const { w, h } = probeSize(srcFile);

    // High-DPI side-by-side export (2608x2841: pad=48, single=1236x2745, gap=40)
    // vs legacy export (1440x1568: pad=29, single=678x1510, gap=26)
    const crops =
      w >= 2000 && h >= 2000
        ? [
            ["dark", "crop=1236:2745:48:48,scale=678:1510:flags=lanczos"],
            ["light", "crop=1236:2745:1324:48,scale=678:1510:flags=lanczos"],
          ]
        : [
            ["dark", "crop=678:1510:29:29"],
            ["light", "crop=678:1510:733:29"],
          ];

    for (const [theme, vf] of crops) {
      execFileSync("ffmpeg", [
        "-y",
        "-loglevel",
        "error",
        "-i",
        srcFile,
        "-vf",
        vf,
        path.join(dstDir, `${id}-${theme}.png`),
      ]);
    }
  }
  console.log(`assets ready [${locale}]: ${files.length} screens -> ${dstDir}`);
}

copyFileSync(path.join(ROOT, "docs/assets/logo.svg"), path.join(PUBLIC_ROOT, "logo.svg"));
console.log("all promo assets ready");
