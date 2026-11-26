#!/usr/bin/env python3
"""从 GitHub Releases 聚合生成静态更新清单 `update.json`（schema_version = 1）。

输出供 Android / iOS 共享 L1 Rust 核心 `cozyting_update_evaluate_json` 消费：
- `channels.stable`：最新正式版及其已签名 Android APK + SHA-256 + iOS 信息；
- `channels.prerelease`：最新最高 SemVer 版本（含预发布 RC 与正式版）资产信息；
- `releases[]`：按 SemVer 倒序保存最近 30 个版本的精简变更日志，支持客户端跨版本区间聚合。
"""

from __future__ import annotations

import argparse
import datetime
import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple


BUILD_SUFFIXES = {"release", "acceptance", "debug", "standard"}
MAX_RELEASES_IN_MANIFEST = 30
HTML_COMMENT_RE = re.compile(r"<!--.*?-->", re.DOTALL)


def parse_semver(raw: str) -> Optional[Tuple[int, int, int, int, Tuple[Tuple[int, Any], ...]]]:
    """返回可比较元组：(major, minor, patch, is_stable_int, pre_idents)。"""
    s = (raw or "").strip()
    if not s:
        return None
    if s.startswith(("v", "V")):
        s = s[1:]
    s = s.split("+", 1)[0].strip()
    if not s:
        return None
    if "-" in s:
        core_part, pre_raw = s.split("-", 1)
    else:
        core_part, pre_raw = s, ""

    nums = core_part.split(".")
    if not (1 <= len(nums) <= 3):
        return None
    try:
        major = int(nums[0])
        minor = int(nums[1]) if len(nums) >= 2 else 0
        patch = int(nums[2]) if len(nums) >= 3 else 0
    except ValueError:
        return None

    pre_idents: List[Tuple[int, Any]] = []
    if pre_raw:
        dash_parts = [p for p in pre_raw.split("-") if p]
        while dash_parts and dash_parts[-1].lower() in BUILD_SUFFIXES:
            dash_parts.pop()
        for part in dash_parts:
            for seg in (x for x in part.split(".") if x):
                if seg.isdigit():
                    pre_idents.append((0, int(seg)))
                else:
                    pre_idents.append((1, seg.lower()))

    is_stable_int = 1 if not pre_idents else 0
    return (major, minor, patch, is_stable_int, tuple(pre_idents))


def normalized_version_str(raw: str) -> str:
    parsed = parse_semver(raw)
    if not parsed:
        return raw.lstrip("vV").strip()
    major, minor, patch, is_stable, pre_idents = parsed
    base = f"{major}.{minor}.{patch}"
    if is_stable:
        return base
    return f"{base}-" + ".".join(str(v) for _, v in pre_idents)


def clean_notes(raw: str) -> str:
    text = HTML_COMMENT_RE.sub("", raw or "").replace("\r\n", "\n")
    lines: List[str] = []
    blank_run = 0
    for line in text.splitlines():
        t = line.strip()
        if t.startswith(("**Full Changelog**:", "Full Changelog:")):
            continue
        if not t:
            blank_run += 1
            if blank_run <= 1 and lines:
                lines.append("")
        else:
            blank_run = 0
            lines.append(line.rstrip())
    while lines and not lines[-1]:
        lines.pop()
    return "\n".join(lines)


def parse_sha256sums(text: str) -> Dict[str, str]:
    mapping: Dict[str, str] = {}
    for line in (text or "").replace("\r\n", "\n").splitlines():
        parts = line.strip().split()
        if len(parts) >= 2:
            digest = parts[0].lower()
            filename = parts[-1].lstrip("*")
            if len(digest) == 64 and all(c in "0123456789abcdef" for c in digest):
                mapping[filename] = digest
    return mapping


def http_get_text(url: str, token: str = "") -> str:
    headers = {
        "Accept": "application/vnd.github+json",
        "User-Agent": "CozyTing-Update-Manifest-Generator",
    }
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=20) as resp:
        return resp.read().decode("utf-8", errors="replace")


def fetch_releases_api(repo: str, token: str = "") -> List[Dict[str, Any]]:
    url = f"https://api.github.com/repos/{repo}/releases?per_page={MAX_RELEASES_IN_MANIFEST}"
    data = json.loads(http_get_text(url, token=token))
    if not isinstance(data, list):
        raise RuntimeError(f"Unexpected GitHub API response for {repo}")
    return data


def extract_android_assets(
    release: Dict[str, Any],
    sha256_by_name: Dict[str, str],
) -> List[Dict[str, Any]]:
    out: List[Dict[str, Any]] = []
    for asset in release.get("assets") or []:
        name = str(asset.get("name") or "")
        lower = name.lower()
        if not lower.endswith(".apk"):
            continue
        if "-unsigned" in lower or "-debug" in lower or "acceptance" in lower:
            continue
        url = str(asset.get("browser_download_url") or "")
        if not url.startswith("https://"):
            continue
        abi = "arm64-v8a"
        for candidate_abi in ("arm64-v8a", "armeabi-v7a", "x86_64", "x86"):
            if candidate_abi in lower:
                abi = candidate_abi
                break
        out.append(
            {
                "abi": abi,
                "name": name,
                "download_url": url,
                "size_bytes": int(asset.get("size") or 0),
                "sha256": sha256_by_name.get(name, ""),
                "signed": True,
            }
        )
    return out


def extract_macos_assets(
    release: Dict[str, Any],
    sha256_by_name: Dict[str, str],
) -> List[Dict[str, Any]]:
    out: List[Dict[str, Any]] = []
    for asset in release.get("assets") or []:
        name = str(asset.get("name") or "")
        lower = name.lower()
        if not lower.endswith(".dmg"):
            continue
        if "-unsigned" in lower or "-debug" in lower:
            continue
        url = str(asset.get("browser_download_url") or "")
        if not url.startswith("https://"):
            continue
        if "universal" in lower:
            arch = "universal"
        elif "x86_64" in lower or "x64" in lower:
            arch = "x86_64"
        else:
            arch = "arm64"
        out.append(
            {
                "arch": arch,
                "name": name,
                "download_url": url,
                "size_bytes": int(asset.get("size") or 0),
                "sha256": sha256_by_name.get(name, ""),
                "signed": True,
            }
        )
    return out


def fetch_release_sha256_map(
    release: Dict[str, Any],
    token: str = "",
    offline_sha_map: Optional[Dict[str, str]] = None,
) -> Dict[str, str]:
    if offline_sha_map is not None:
        return offline_sha_map
    for asset in release.get("assets") or []:
        name = str(asset.get("name") or "")
        if name.lower() == "sha256sums.txt":
            url = str(asset.get("browser_download_url") or "")
            if url.startswith("https://"):
                try:
                    return parse_sha256sums(http_get_text(url, token=token))
                except (urllib.error.URLError, TimeoutError, OSError) as exc:
                    sys.stderr.write(f"[warn] failed to fetch {url}: {exc}\n")
    return {}


def build_channel_entry(
    release: Dict[str, Any],
    sha_map: Dict[str, str],
    min_android_sdk: int = 29,
    min_macos: str = "14.2",
    min_ios: str = "16.0",
    ios_app_store_url: str = "",
) -> Dict[str, Any]:
    tag = str(release.get("tag_name") or "")
    rel_url = str(release.get("html_url") or "")
    return {
        "tag": tag,
        "version": normalized_version_str(tag),
        "prerelease": bool(release.get("prerelease")),
        "published_at": str(release.get("published_at") or ""),
        "release_url": rel_url,
        "notes": clean_notes(str(release.get("body") or "")),
        "android": {
            "min_sdk": min_android_sdk,
            "assets": extract_android_assets(release, sha_map),
        },
        "macos": {
            "min_os": min_macos,
            "release_url": rel_url,
            "assets": extract_macos_assets(release, sha_map),
        },
        "ios": {
            "min_os": min_ios,
            "release_url": rel_url,
            "app_store_url": ios_app_store_url,
        },
    }


def generate_manifest(
    repo: str,
    raw_releases: List[Dict[str, Any]],
    token: str = "",
    offline_sha_by_tag: Optional[Dict[str, Dict[str, str]]] = None,
    min_android_sdk: int = 29,
    min_macos: str = "14.2",
    min_ios: str = "16.0",
    ios_app_store_url: str = "",
) -> Dict[str, Any]:
    valid: List[Tuple[Tuple[int, int, int, int, Tuple[Tuple[int, Any], ...]], Dict[str, Any]]] = []
    for rel in raw_releases:
        if rel.get("draft"):
            continue
        tag = str(rel.get("tag_name") or "")
        sv = parse_semver(tag)
        if sv is None:
            continue
        valid.append((sv, rel))

    # 按 SemVer 倒序（最新在前）
    valid.sort(key=lambda pair: pair[0], reverse=True)
    ordered = [r for _, r in valid[:MAX_RELEASES_IN_MANIFEST]]

    latest_stable = next((r for r in ordered if not r.get("prerelease")), None)
    latest_prerelease = ordered[0] if ordered else None

    channels: Dict[str, Any] = {}
    sha_cache: Dict[str, Dict[str, str]] = {}

    def get_sha(rel: Dict[str, Any]) -> Dict[str, str]:
        tag = str(rel.get("tag_name") or "")
        if tag not in sha_cache:
            offline = offline_sha_by_tag.get(tag) if offline_sha_by_tag is not None else None
            sha_cache[tag] = fetch_release_sha256_map(rel, token=token, offline_sha_map=offline)
        return sha_cache[tag]

    if latest_stable is not None:
        channels["stable"] = build_channel_entry(
            latest_stable,
            get_sha(latest_stable),
            min_android_sdk=min_android_sdk,
            min_macos=min_macos,
            min_ios=min_ios,
            ios_app_store_url=ios_app_store_url,
        )
    if latest_prerelease is not None:
        channels["prerelease"] = build_channel_entry(
            latest_prerelease,
            get_sha(latest_prerelease),
            min_android_sdk=min_android_sdk,
            min_macos=min_macos,
            min_ios=min_ios,
            ios_app_store_url=ios_app_store_url,
        )

    history = [
        {
            "tag": str(r.get("tag_name") or ""),
            "version": normalized_version_str(str(r.get("tag_name") or "")),
            "prerelease": bool(r.get("prerelease")),
            "published_at": str(r.get("published_at") or ""),
            "release_url": str(r.get("html_url") or ""),
            "notes": clean_notes(str(r.get("body") or "")),
        }
        for r in ordered
    ]

    now_iso = (
        datetime.datetime.now(datetime.timezone.utc)
        .replace(microsecond=0)
        .isoformat()
        .replace("+00:00", "Z")
    )
    return {
        "schema_version": 1,
        "generated_at": now_iso,
        "repo": repo,
        "channels": channels,
        "releases": history,
    }


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="生成窝爱听（CozyTing）静态更新清单 update.json")
    parser.add_argument("--repo", required=True, help="公开 Release 仓库，例如 JasirVoriya/cozyting")
    parser.add_argument("--out", required=True, help="输出 update.json 路径")
    parser.add_argument("--input-json", default="", help="可选：离线测试用 Releases JSON 文件路径")
    parser.add_argument("--min-android-sdk", type=int, default=29)
    parser.add_argument("--min-macos", default="14.2")
    parser.add_argument("--min-ios", default="16.0")
    parser.add_argument("--ios-app-store-url", default="")
    args = parser.parse_args(argv)

    token = os.environ.get("GITHUB_TOKEN", "").strip()
    if args.input_json:
        raw_releases = json.loads(Path(args.input_json).read_text(encoding="utf-8"))
        offline_sha: Optional[Dict[str, Dict[str, str]]] = {}
    else:
        raw_releases = fetch_releases_api(args.repo, token=token)
        offline_sha = None

    manifest = generate_manifest(
        repo=args.repo,
        raw_releases=raw_releases,
        token=token,
        offline_sha_by_tag=offline_sha,
        min_android_sdk=args.min_android_sdk,
        min_macos=args.min_macos,
        min_ios=args.min_ios,
        ios_app_store_url=args.ios_app_store_url,
    )
    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
