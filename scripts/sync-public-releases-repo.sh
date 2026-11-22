#!/usr/bin/env bash
# 将私有主仓的公开介绍文档、更新日志、交互式架构浏览器与 GitHub Pages/更新清单工作流
# 安全同步至公开分发仓（JasirVoriya/miaocast-releases）。
# 严禁同步源码目录（core/、android/、cli/）与任何私有文件（.local/、keystore.properties 等）。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TARGET_REPO="${1:-JasirVoriya/miaocast-releases}"
STAGE_DIR="${STAGE_DIR:-/tmp/miaocast-releases-stage}"

rm -rf "$STAGE_DIR"
mkdir -p "$STAGE_DIR"

echo "[sync] Cloning or initializing target repo: $TARGET_REPO -> $STAGE_DIR"
if gh repo view "$TARGET_REPO" >/dev/null 2>&1; then
  git clone "https://github.com/${TARGET_REPO}.git" "$STAGE_DIR"
else
  echo "[sync] Target repo $TARGET_REPO does not exist yet; create it first with gh repo create." >&2
  exit 1
fi

cd "$STAGE_DIR"
# 确保在 main 分支上（兼容全新空仓库）
if git rev-parse --verify HEAD >/dev/null 2>&1; then
  git checkout -B main
else
  git checkout -b main
fi

# 清理旧跟踪内容（保留 .git）
find "$STAGE_DIR" -mindepth 1 -maxdepth 1 ! -name '.git' -exec rm -rf {} +

# 1. 复制公开顶层文档（严禁复制任何源码或私有文件）
for f in README.md README_EN.md CHANGELOG.md SECURITY.md CONTRIBUTING.md LICENSE; do
  if [[ -f "$ROOT/$f" ]]; then
    cp "$ROOT/$f" "$STAGE_DIR/$f"
  fi
done

# 2. 复制 docs/（含 architecture.html、assets/ 与全部公开设计/兼容文档，过滤 .DS_Store 与 AppleDouble）
mkdir -p "$STAGE_DIR/docs"
rsync -a --exclude='.DS_Store' --exclude='._*' "$ROOT/docs/" "$STAGE_DIR/docs/"

# 3. 复制静态 update.json 生成脚本
mkdir -p "$STAGE_DIR/scripts"
cp "$ROOT/scripts/generate-update-manifest.py" "$STAGE_DIR/scripts/generate-update-manifest.py"
chmod +x "$STAGE_DIR/scripts/generate-update-manifest.py"

# 4. 替换公开仓 README 中指向私有仓 CI 徽章的链接（避免公开页面出现 404 断图）
python3 - "$STAGE_DIR" "$TARGET_REPO" <<'PY'
import sys
from pathlib import Path

stage = Path(sys.argv[1])
target_repo = sys.argv[2]
owner, repo = target_repo.split("/", 1)
pages_url = f"https://{owner.lower()}.github.io/{repo}/"

for fname in ("README.md", "README_EN.md"):
    p = stage / fname
    if not p.is_file():
        continue
    text = p.read_text(encoding="utf-8")
    # 将指向私仓 CI 的徽章替换为公开仓 Pages 状态徽章
    for old_ci in (
        '<a href="https://github.com/JasirVoriya/miaocast/actions/workflows/ci.yml"><img alt="CI" src="https://github.com/JasirVoriya/miaocast/actions/workflows/ci.yml/badge.svg"></a>',
        '<a href="https://github.com/JasirVoriya/miplay-sender/actions/workflows/ci.yml"><img alt="CI" src="https://github.com/JasirVoriya/miplay-sender/actions/workflows/ci.yml/badge.svg"></a>',
    ):
        text = text.replace(
            old_ci,
            f'<a href="https://github.com/{target_repo}/actions/workflows/pages.yml"><img alt="Pages & Update Feed" src="https://github.com/{target_repo}/actions/workflows/pages.yml/badge.svg"></a>',
        )
    # 兜底修正可能残留的旧私仓 Pages / Releases 链接
    text = text.replace("https://jasirvoriya.github.io/miplay-sender/", pages_url)
    text = text.replace("https://jasirvoriya.github.io/miaocast/", pages_url)
    text = text.replace("https://github.com/JasirVoriya/miplay-sender/releases", f"https://github.com/{target_repo}/releases")
    text = text.replace("https://github.com/JasirVoriya/miaocast/releases", f"https://github.com/{target_repo}/releases")
    p.write_text(text, encoding="utf-8")
PY

# 5. 生成公开仓专属 GitHub Pages 工作流（同时发布交互式架构浏览器与 update.json 更新清单）
mkdir -p "$STAGE_DIR/.github/workflows"
cat > "$STAGE_DIR/.github/workflows/pages.yml" <<'YAML'
name: Deploy Pages & Update Manifest

on:
  push:
    branches: [main]
  release:
    types: [published, edited, deleted, prereleased, released]
  workflow_dispatch:

permissions:
  contents: read
  pages: write
  id-token: write

concurrency:
  group: pages
  cancel-in-progress: true

jobs:
  deploy:
    name: Build & Deploy Showcase Pages + update.json
    runs-on: ubuntu-latest
    environment:
      name: github-pages
      url: ${{ steps.deployment.outputs.page_url }}
    steps:
      - name: Checkout repository
        uses: actions/checkout@v4

      - name: Configure GitHub Pages
        uses: actions/configure-pages@v5

      - name: Assemble static site (Architecture Explorer + assets)
        run: |
          mkdir -p _site/assets
          cp docs/architecture.html _site/architecture.html
          cp docs/architecture.html _site/index.html
          cp -r docs/assets/* _site/assets/
          touch _site/.nojekyll

      - name: Generate App Update Manifest (update.json)
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        run: |
          python3 scripts/generate-update-manifest.py \
            --repo "${{ github.repository }}" \
            --out _site/update.json
          head -n 40 _site/update.json

      - name: Upload Pages artifact
        uses: actions/upload-pages-artifact@v3
        with:
          path: _site

      - name: Deploy to GitHub Pages
        id: deployment
        uses: actions/deploy-pages@v4
YAML

# 6. 生成 .gitignore
cat > "$STAGE_DIR/.gitignore" <<'EOF'
.DS_Store
._*
_site/
__pycache__/
EOF

echo "[sync] Staged files summary:"
git status -s
echo "[sync] Ready in $STAGE_DIR (review and commit/push)."
