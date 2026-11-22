#!/usr/bin/env python3
"""从 CHANGELOG.md 或 git 提交历史自动提取当前版本的 Release Notes。

优先级：
1. 仓库根目录 CHANGELOG.md 中与当前 tag 精确匹配的小节（如 `## [0.9.7]` 或 `## [0.9.7-rc.6]`）；
2. 若无匹配（例如快速迭代的预发布 RC），自动抓取上一个相邻 tag 到当前提交的 git log，
   按 Conventional Commits 分类整理为面向用户的清晰条目（自动过滤发版杂项提交）。
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path
from typing import List, Optional, Tuple


def normalize_tag_version(tag: str) -> str:
    t = tag.strip()
    if t.startswith(("v", "V")):
        t = t[1:]
    return t


def extract_from_changelog(changelog_text: str, tag: str) -> Optional[str]:
    target = normalize_tag_version(tag)
    if not target:
        return None
    header_re = re.compile(r"^##\s+\[?v?([^\]\s]+)\]?(?:\s+-\s+.*)?\s*$")
    lines = changelog_text.replace("\r\n", "\n").splitlines()
    collecting = False
    collected: List[str] = []
    for line in lines:
        m = header_re.match(line.strip())
        if m:
            if collecting:
                break
            if m.group(1).strip().lower() == target.lower():
                collecting = True
            continue
        if collecting:
            if line.strip() == "---":
                continue
            collected.append(line)
    body = "\n".join(collected).strip()
    return body or None


def run_git(args: List[str], cwd: Optional[Path] = None) -> Tuple[int, str]:
    proc = subprocess.run(
        ["git", *args],
        cwd=str(cwd) if cwd else None,
        capture_output=True,
        text=True,
        check=False,
    )
    return proc.returncode, proc.stdout.strip()


def find_previous_tag(tag: str, cwd: Optional[Path] = None) -> Optional[str]:
    # 若 tag 已存在于 git，用 <tag>^ 查找上一个可达 tag；否则从 HEAD 查最近 tag
    code, _ = run_git(["rev-parse", "--verify", f"refs/tags/{tag}"], cwd=cwd)
    ref = f"{tag}^" if code == 0 else "HEAD"
    code, prev = run_git(["describe", "--tags", "--abbrev=0", ref], cwd=cwd)
    if code == 0 and prev and prev != tag:
        return prev
    return None


def collect_commits(tag: str, cwd: Optional[Path] = None) -> Tuple[Optional[str], List[str]]:
    prev = find_previous_tag(tag, cwd=cwd)
    code, _ = run_git(["rev-parse", "--verify", f"refs/tags/{tag}"], cwd=cwd)
    end_ref = tag if code == 0 else "HEAD"
    rev_range = f"{prev}..{end_ref}" if prev else end_ref
    code, out = run_git(["log", rev_range, "--no-merges", "--pretty=format:%s"], cwd=cwd)
    if code != 0 or not out:
        return prev, []
    return prev, [line.strip() for line in out.splitlines() if line.strip()]


CONVENTIONAL_RE = re.compile(r"^([a-zA-Z]+)(?:\(([^)]+)\))?(!)?:\s*(.+)$")


def summarize_commits(subjects: List[str]) -> Optional[str]:
    buckets = {
        "feat": [],
        "fix": [],
        "perf": [],
        "other": [],
    }
    for subj in subjects:
        lower = subj.lower()
        if lower.startswith(("chore(release):", "release:", "merge ")):
            continue
        m = CONVENTIONAL_RE.match(subj)
        if m:
            kind = m.group(1).lower()
            scope = (m.group(2) or "").strip()
            desc = m.group(4).strip()
            item = f"- **{scope}**: {desc}" if scope else f"- {desc}"
            if kind == "feat":
                buckets["feat"].append(item)
            elif kind == "fix":
                buckets["fix"].append(item)
            elif kind in ("perf", "refactor", "style"):
                buckets["perf"].append(item)
            else:
                buckets["other"].append(item)
        else:
            buckets["other"].append(f"- {subj}")

    sections: List[str] = []
    mapping = [
        ("feat", "### ✨ 新功能"),
        ("fix", "### 🐛 问题修复"),
        ("perf", "### ⚡ 性能与体验优化"),
        ("other", "### 🔧 其他改进"),
    ]
    for key, title in mapping:
        if buckets[key]:
            sections.append(title + "\n" + "\n".join(buckets[key]))
    return "\n\n".join(sections).strip() or None


def build_release_notes(tag: str, changelog_path: Path, repo_dir: Optional[Path] = None) -> str:
    if changelog_path.is_file():
        text = changelog_path.read_text(encoding="utf-8")
        from_changelog = extract_from_changelog(text, tag)
        if from_changelog:
            return from_changelog

    _, subjects = collect_commits(tag, cwd=repo_dir)
    from_git = summarize_commits(subjects)
    if from_git:
        return from_git

    return "- 本版本包含功能改进与稳定性修复。"


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="提取当前版本 Release Notes")
    parser.add_argument("--tag", required=True, help="目标发布 tag，例如 v0.9.7 或 v0.9.7-rc.6")
    parser.add_argument("--changelog", default="CHANGELOG.md", help="CHANGELOG.md 路径")
    parser.add_argument("--repo-dir", default=".", help="Git 仓库根目录")
    parser.add_argument("--out", default="", help="输出文件路径（留空输出到 stdout）")
    args = parser.parse_args(argv)

    notes = build_release_notes(
        tag=args.tag,
        changelog_path=Path(args.changelog),
        repo_dir=Path(args.repo_dir),
    )
    if args.out:
        out_path = Path(args.out)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(notes + "\n", encoding="utf-8")
    else:
        sys.stdout.write(notes + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
