// Crops the dark / light halves of the official UI screenshots into phone-screen sized PNGs.
import { execFileSync } from "node:child_process";
import { readdirSync, mkdirSync, copyFileSync } from "node:fs";
const src = new URL("../../docs/assets/screenshots/", import.meta.url).pathname;
const dst = new URL("../public/ui/", import.meta.url).pathname;
mkdirSync(dst, { recursive: true });
for (const f of readdirSync(src).filter((f) => f.endsWith(".png") && !f.startsWith("._"))) {
  const id = f.slice(0, 2);
  for (const [name, x] of [["dark", 29], ["light", 733]]) {
    execFileSync("ffmpeg", ["-y", "-loglevel", "error", "-i", src + f, "-vf", `crop=678:1510:${x}:29`, `${dst}${id}-${name}.png`]);
  }
}
copyFileSync(new URL("../../docs/assets/logo.svg", import.meta.url).pathname, new URL("../public/logo.svg", import.meta.url).pathname);
console.log("assets ready");
