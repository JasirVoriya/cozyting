//! 跨端 App 更新裁决与跨版本区间日志聚合引擎（纯逻辑、零网络 I/O、零新增依赖）。
//!
//! 供 Android JNI (`ffi.rs`) 与 iOS C ABI (`ffi_c.rs` / `miplay_core.h`) 共享同一套：
//! - SemVer 2.0 版本归一化与比较（自动剥离前导 `v` 与 `-release`/`-acceptance`/`-debug` 构建后缀）；
//! - `update.json`（GitHub Pages 静态清单）与 GitHub Releases REST API 双格式自动解析；
//! - `stable` / `prerelease` 通道过滤、平台资产挑选（排除未签名/调试包）与最低系统版本门禁；
//! - `(current_version, target_version]` 半开区间跨版本差异日志聚合（正式版升级自动跳过已转正 RC）。

use std::cmp::Ordering;
use std::collections::HashSet;

const MAX_JSON_DEPTH: usize = 32;
const MAX_CHANGELOG_ENTRIES: usize = 10;
const FALLBACK_EMPTY_NOTES: &str = "本版本包含功能改进与稳定性修复，可前往 Release 页面查看完整变更记录。";

// ---------------------------------------------------------------------------
// SemVer 2.0 解析与比较
// ---------------------------------------------------------------------------

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum PreIdent {
    Num(u64),
    Alpha(String),
}

impl Ord for PreIdent {
    fn cmp(&self, other: &Self) -> Ordering {
        match (self, other) {
            (Self::Num(a), Self::Num(b)) => a.cmp(b),
            (Self::Num(_), Self::Alpha(_)) => Ordering::Less,
            (Self::Alpha(_), Self::Num(_)) => Ordering::Greater,
            (Self::Alpha(a), Self::Alpha(b)) => a.cmp(b),
        }
    }
}

impl PartialOrd for PreIdent {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SemVer {
    pub major: u64,
    pub minor: u64,
    pub patch: u64,
    pub pre: Vec<PreIdent>,
}

impl SemVer {
    pub fn is_prerelease(&self) -> bool {
        !self.pre.is_empty()
    }

    pub fn base_triple(&self) -> (u64, u64, u64) {
        (self.major, self.minor, self.patch)
    }

    pub fn normalized_string(&self) -> String {
        let base = format!("{}.{}.{}", self.major, self.minor, self.patch);
        if self.pre.is_empty() {
            base
        } else {
            let pre_str: Vec<String> = self
                .pre
                .iter()
                .map(|p| match p {
                    PreIdent::Num(n) => n.to_string(),
                    PreIdent::Alpha(s) => s.clone(),
                })
                .collect();
            format!("{}-{}", base, pre_str.join("."))
        }
    }
}

impl Ord for SemVer {
    fn cmp(&self, other: &Self) -> Ordering {
        match self.base_triple().cmp(&other.base_triple()) {
            Ordering::Equal => {}
            ord => return ord,
        }
        match (self.pre.is_empty(), other.pre.is_empty()) {
            (true, true) => Ordering::Equal,
            (true, false) => Ordering::Greater,
            (false, true) => Ordering::Less,
            (false, false) => {
                for (a, b) in self.pre.iter().zip(other.pre.iter()) {
                    match a.cmp(b) {
                        Ordering::Equal => continue,
                        ord => return ord,
                    }
                }
                self.pre.len().cmp(&other.pre.len())
            }
        }
    }
}

impl PartialOrd for SemVer {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}

fn is_build_flavor_suffix(token: &str) -> bool {
    matches!(
        token.to_ascii_lowercase().as_str(),
        "release" | "acceptance" | "debug" | "standard"
    )
}

/// 解析版本号：自动剥离前导 `v`/`V`、`+build` 元数据与 Gradle 追加的 `-release`/`-acceptance`/`-debug` 后缀。
pub fn parse_semver(raw: &str) -> Option<SemVer> {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return None;
    }
    let no_v = trimmed
        .strip_prefix('v')
        .or_else(|| trimmed.strip_prefix('V'))
        .unwrap_or(trimmed);
    let no_build = no_v.split('+').next().unwrap_or(no_v).trim();
    if no_build.is_empty() {
        return None;
    }

    let (core_part, pre_raw) = match no_build.find('-') {
        Some(idx) => (&no_build[..idx], Some(&no_build[idx + 1..])),
        None => (no_build, None),
    };

    let nums: Vec<&str> = core_part.split('.').collect();
    if nums.is_empty() || nums.len() > 3 {
        return None;
    }
    let major = nums[0].parse::<u64>().ok()?;
    let minor = if nums.len() >= 2 {
        nums[1].parse::<u64>().ok()?
    } else {
        0
    };
    let patch = if nums.len() >= 3 {
        nums[2].parse::<u64>().ok()?
    } else {
        0
    };

    let mut pre = Vec::new();
    if let Some(pr) = pre_raw {
        // 剥离尾部连续的构建/渠道后缀（例如 "rc.6-acceptance-release" -> "rc.6"；"release" -> ""）
        let mut dash_parts: Vec<&str> = pr.split('-').filter(|s| !s.is_empty()).collect();
        while let Some(&last) = dash_parts.last() {
            if is_build_flavor_suffix(last) {
                dash_parts.pop();
            } else {
                break;
            }
        }
        for part in dash_parts {
            for dot_seg in part.split('.').filter(|s| !s.is_empty()) {
                if dot_seg.bytes().all(|b| b.is_ascii_digit()) {
                    if let Ok(n) = dot_seg.parse::<u64>() {
                        pre.push(PreIdent::Num(n));
                    } else {
                        pre.push(PreIdent::Alpha(dot_seg.to_ascii_lowercase()));
                    }
                } else {
                    pre.push(PreIdent::Alpha(dot_seg.to_ascii_lowercase()));
                }
            }
        }
    }

    Some(SemVer {
        major,
        minor,
        patch,
        pre,
    })
}

/// 比较两个版本号字符串：`-1` (a < b), `0` (a == b), `1` (a > b), `-2` (非法格式)。
pub fn compare_versions(a: &str, b: &str) -> i32 {
    match (parse_semver(a), parse_semver(b)) {
        (Some(va), Some(vb)) => match va.cmp(&vb) {
            Ordering::Less => -1,
            Ordering::Equal => 0,
            Ordering::Greater => 1,
        },
        _ => -2,
    }
}

/// 比较点分系统版本（如 `"29"` vs `"29"`，或 iOS `"16.4"` vs `"16.0"`）：返回 `current >= min`。
fn os_version_meets_min(current_os: &str, min_os: &str) -> bool {
    let min_t = min_os.trim();
    let cur_t = current_os.trim();
    if min_t.is_empty() || cur_t.is_empty() {
        return true;
    }
    let parse_parts = |s: &str| -> Option<Vec<u64>> {
        let parts: Option<Vec<u64>> = s.split('.').map(|p| p.trim().parse::<u64>().ok()).collect();
        parts.filter(|v| !v.is_empty())
    };
    let (Some(c), Some(m)) = (parse_parts(cur_t), parse_parts(min_t)) else {
        return true;
    };
    let max_len = c.len().max(m.len());
    for i in 0..max_len {
        let cv = *c.get(i).unwrap_or(&0);
        let mv = *m.get(i).unwrap_or(&0);
        match cv.cmp(&mv) {
            Ordering::Greater => return true,
            Ordering::Less => return false,
            Ordering::Equal => continue,
        }
    }
    true
}

// ---------------------------------------------------------------------------
// 轻量零依赖 JSON 解析器（有界递归深度 + 完整 Unicode 转义）
// ---------------------------------------------------------------------------

#[derive(Clone, Debug)]
enum JsonVal {
    Null,
    Bool(bool),
    Num(f64),
    Int(i64),
    Str(String),
    Arr(Vec<JsonVal>),
    Obj(Vec<(String, JsonVal)>),
}

impl JsonVal {
    fn as_obj(&self) -> Option<&[(String, JsonVal)]> {
        match self {
            Self::Obj(v) => Some(v),
            _ => None,
        }
    }

    fn as_arr(&self) -> Option<&[JsonVal]> {
        match self {
            Self::Arr(v) => Some(v),
            _ => None,
        }
    }

    fn get(&self, key: &str) -> Option<&JsonVal> {
        self.as_obj()?
            .iter()
            .find(|(k, _)| k == key)
            .map(|(_, v)| v)
    }

    fn as_str(&self) -> Option<&str> {
        match self {
            Self::Str(s) => Some(s.as_str()),
            _ => None,
        }
    }

    fn as_bool(&self) -> Option<bool> {
        match self {
            Self::Bool(b) => Some(*b),
            _ => None,
        }
    }

    fn as_i64(&self) -> Option<i64> {
        match self {
            Self::Int(n) => Some(*n),
            Self::Num(f) if f.is_finite() => Some(*f as i64),
            Self::Str(s) => s.trim().parse::<i64>().ok(),
            _ => None,
        }
    }

    fn str_field(&self, key: &str) -> String {
        self.get(key)
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string()
    }

    fn bool_field(&self, key: &str) -> Option<bool> {
        self.get(key).and_then(|v| v.as_bool())
    }

    fn i64_field(&self, key: &str) -> Option<i64> {
        self.get(key).and_then(|v| v.as_i64())
    }
}

struct JsonParser<'a> {
    bytes: &'a [u8],
    pos: usize,
}

impl<'a> JsonParser<'a> {
    fn new(input: &'a str) -> Self {
        Self {
            bytes: input.as_bytes(),
            pos: 0,
        }
    }

    fn skip_ws(&mut self) {
        while self.pos < self.bytes.len() {
            match self.bytes[self.pos] {
                b' ' | b'\t' | b'\r' | b'\n' => self.pos += 1,
                _ => break,
            }
        }
    }

    fn peek(&self) -> Option<u8> {
        self.bytes.get(self.pos).copied()
    }

    fn parse_value(&mut self, depth: usize) -> Option<JsonVal> {
        if depth > MAX_JSON_DEPTH {
            return None;
        }
        self.skip_ws();
        match self.peek()? {
            b'{' => self.parse_obj(depth + 1),
            b'[' => self.parse_arr(depth + 1),
            b'"' => self.parse_str().map(JsonVal::Str),
            b't' => self.consume_lit(b"true").map(|_| JsonVal::Bool(true)),
            b'f' => self.consume_lit(b"false").map(|_| JsonVal::Bool(false)),
            b'n' => self.consume_lit(b"null").map(|_| JsonVal::Null),
            b'-' | b'0'..=b'9' => self.parse_num(),
            _ => None,
        }
    }

    fn consume_lit(&mut self, lit: &[u8]) -> Option<()> {
        if self.bytes.get(self.pos..self.pos + lit.len()) == Some(lit) {
            self.pos += lit.len();
            Some(())
        } else {
            None
        }
    }

    fn parse_obj(&mut self, depth: usize) -> Option<JsonVal> {
        self.pos += 1; // '{'
        self.skip_ws();
        let mut entries = Vec::new();
        if self.peek() == Some(b'}') {
            self.pos += 1;
            return Some(JsonVal::Obj(entries));
        }
        loop {
            self.skip_ws();
            if self.peek() != Some(b'"') {
                return None;
            }
            let key = self.parse_str()?;
            self.skip_ws();
            if self.peek() != Some(b':') {
                return None;
            }
            self.pos += 1;
            let val = self.parse_value(depth)?;
            entries.push((key, val));
            self.skip_ws();
            match self.peek()? {
                b',' => {
                    self.pos += 1;
                }
                b'}' => {
                    self.pos += 1;
                    return Some(JsonVal::Obj(entries));
                }
                _ => return None,
            }
        }
    }

    fn parse_arr(&mut self, depth: usize) -> Option<JsonVal> {
        self.pos += 1; // '['
        self.skip_ws();
        let mut items = Vec::new();
        if self.peek() == Some(b']') {
            self.pos += 1;
            return Some(JsonVal::Arr(items));
        }
        loop {
            let val = self.parse_value(depth)?;
            items.push(val);
            self.skip_ws();
            match self.peek()? {
                b',' => {
                    self.pos += 1;
                }
                b']' => {
                    self.pos += 1;
                    return Some(JsonVal::Arr(items));
                }
                _ => return None,
            }
        }
    }

    fn parse_hex4(&mut self) -> Option<u16> {
        let slice = self.bytes.get(self.pos..self.pos + 4)?;
        let s = std::str::from_utf8(slice).ok()?;
        let v = u16::from_str_radix(s, 16).ok()?;
        self.pos += 4;
        Some(v)
    }

    fn parse_str(&mut self) -> Option<String> {
        self.pos += 1; // '"'
        let mut out = String::new();
        let mut chunk_start = self.pos;
        while self.pos < self.bytes.len() {
            let b = self.bytes[self.pos];
            if b == b'"' {
                if self.pos > chunk_start {
                    let seg = std::str::from_utf8(&self.bytes[chunk_start..self.pos]).ok()?;
                    out.push_str(seg);
                }
                self.pos += 1;
                return Some(out);
            }
            if b == b'\\' {
                if self.pos > chunk_start {
                    let seg = std::str::from_utf8(&self.bytes[chunk_start..self.pos]).ok()?;
                    out.push_str(seg);
                }
                self.pos += 1;
                let esc = *self.bytes.get(self.pos)?;
                self.pos += 1;
                match esc {
                    b'"' => out.push('"'),
                    b'\\' => out.push('\\'),
                    b'/' => out.push('/'),
                    b'b' => out.push('\u{0008}'),
                    b'f' => out.push('\u{000C}'),
                    b'n' => out.push('\n'),
                    b'r' => out.push('\r'),
                    b't' => out.push('\t'),
                    b'u' => {
                        let code = self.parse_hex4()?;
                        if (0xD800..=0xDBFF).contains(&code) {
                            // UTF-16 代理对（如 emoji）
                            if self.bytes.get(self.pos..self.pos + 2) == Some(b"\\u") {
                                self.pos += 2;
                                let low = self.parse_hex4()?;
                                if (0xDC00..=0xDFFF).contains(&low) {
                                    let combined = 0x10000
                                        + (((code as u32 - 0xD800) << 10) | (low as u32 - 0xDC00));
                                    if let Some(ch) = char::from_u32(combined) {
                                        out.push(ch);
                                    }
                                }
                            }
                        } else if let Some(ch) = char::from_u32(code as u32) {
                            out.push(ch);
                        }
                    }
                    other => out.push(other as char),
                }
                chunk_start = self.pos;
            } else {
                self.pos += 1;
            }
        }
        None
    }

    fn parse_num(&mut self) -> Option<JsonVal> {
        let start = self.pos;
        if self.peek() == Some(b'-') {
            self.pos += 1;
        }
        let mut is_float = false;
        while let Some(b) = self.peek() {
            match b {
                b'0'..=b'9' => self.pos += 1,
                b'.' | b'e' | b'E' | b'+' | b'-' => {
                    is_float = true;
                    self.pos += 1;
                }
                _ => break,
            }
        }
        let s = std::str::from_utf8(&self.bytes[start..self.pos]).ok()?;
        if !is_float {
            if let Ok(n) = s.parse::<i64>() {
                return Some(JsonVal::Int(n));
            }
        }
        s.parse::<f64>().ok().map(JsonVal::Num)
    }
}

fn parse_json(raw: &str) -> Option<JsonVal> {
    let mut p = JsonParser::new(raw);
    let val = p.parse_value(0)?;
    p.skip_ws();
    if p.pos == p.bytes.len() {
        Some(val)
    } else {
        // 容忍尾部空白后仍返回有效 JSON
        Some(val)
    }
}

fn json_escape(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 8);
    for ch in s.chars() {
        match ch {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => {
                out.push_str(&format!("\\u{:04x}", c as u32));
            }
            c => out.push(c),
        }
    }
    out
}

// ---------------------------------------------------------------------------
// 更新日志清洗与内部 Release 模型
// ---------------------------------------------------------------------------

/// 清洗 GitHub 自动生成的 HTML 注释与纯空壳 Full Changelog 行，保留可读正文。
pub fn clean_release_notes(raw: &str) -> String {
    // 1. 移除 <!-- ... --> HTML 注释
    let mut no_comments = String::with_capacity(raw.len());
    let mut rest = raw;
    while let Some(start) = rest.find("<!--") {
        no_comments.push_str(&rest[..start]);
        let after = &rest[start + 4..];
        if let Some(end) = after.find("-->") {
            rest = &after[end + 3..];
        } else {
            rest = "";
            break;
        }
    }
    no_comments.push_str(rest);

    // 2. 归一化换行并过滤无实质信息的纯 compare 链接行
    let normalized = no_comments.replace("\r\n", "\n");
    let mut lines: Vec<&str> = Vec::new();
    let mut blank_run = 0usize;
    for raw_line in normalized.lines() {
        let trimmed = raw_line.trim();
        if trimmed.starts_with("**Full Changelog**:") || trimmed.starts_with("Full Changelog:") {
            continue;
        }
        if trimmed.is_empty() {
            blank_run += 1;
            if blank_run <= 1 && !lines.is_empty() {
                lines.push("");
            }
        } else {
            blank_run = 0;
            lines.push(trimmed);
        }
    }
    while lines.last() == Some(&"") {
        lines.pop();
    }
    lines.join("\n")
}

#[derive(Clone, Debug)]
struct AssetChoice {
    name: String,
    download_url: String,
    size_bytes: i64,
    sha256: String,
    sha256sums_url: String,
    min_os: String,
    installable: bool,
}

impl Default for AssetChoice {
    fn default() -> Self {
        Self {
            name: String::new(),
            download_url: String::new(),
            size_bytes: 0,
            sha256: String::new(),
            sha256sums_url: String::new(),
            min_os: String::new(),
            installable: false,
        }
    }
}

#[derive(Clone, Debug)]
struct ParsedRelease {
    tag: String,
    semver: SemVer,
    prerelease: bool,
    published_at: String,
    release_url: String,
    notes: String,
    asset: AssetChoice,
}

fn is_valid_https_url(url: &str) -> bool {
    let u = url.trim();
    u.starts_with("https://") && u.len() > "https://".len()
}

fn pick_android_asset_from_manifest(android_obj: &JsonVal, abi: &str) -> AssetChoice {
    let min_os = android_obj
        .i64_field("min_sdk")
        .map(|n| n.to_string())
        .unwrap_or_default();
    let wanted_abi = if abi.trim().is_empty() {
        "arm64-v8a"
    } else {
        abi.trim()
    };

    if let Some(assets) = android_obj.get("assets").and_then(|v| v.as_arr()) {
        for a in assets {
            let signed = a.bool_field("signed").unwrap_or(true);
            let name = a.str_field("name");
            let lower = name.to_ascii_lowercase();
            if !signed || lower.contains("-unsigned") || lower.contains("-debug") {
                continue;
            }
            let asset_abi = a.str_field("abi");
            if !asset_abi.is_empty() && !asset_abi.eq_ignore_ascii_case(wanted_abi) {
                continue;
            }
            let url = a.str_field("download_url");
            if !is_valid_https_url(&url) {
                continue;
            }
            let sha = a.str_field("sha256").trim().to_ascii_lowercase();
            let valid_sha = sha.len() == 64 && sha.bytes().all(|b| b.is_ascii_hexdigit());
            return AssetChoice {
                name,
                download_url: url,
                size_bytes: a.i64_field("size_bytes").unwrap_or(0).max(0),
                sha256: if valid_sha { sha } else { String::new() },
                sha256sums_url: String::new(),
                min_os,
                installable: true,
            };
        }
    }

    AssetChoice {
        min_os,
        ..AssetChoice::default()
    }
}

fn pick_ios_asset_from_manifest(ios_obj: &JsonVal, fallback_release_url: &str) -> AssetChoice {
    let min_os = ios_obj.str_field("min_os");
    let app_store = ios_obj.str_field("app_store_url");
    let rel = ios_obj.str_field("release_url");
    let download_url = if is_valid_https_url(&app_store) {
        app_store
    } else if is_valid_https_url(&rel) {
        rel
    } else {
        fallback_release_url.to_string()
    };
    AssetChoice {
        name: String::new(),
        download_url,
        size_bytes: 0,
        sha256: String::new(),
        sha256sums_url: String::new(),
        min_os,
        installable: false,
    }
}

fn pick_asset_from_gh_release(rel: &JsonVal, platform: &str, abi: &str, release_url: &str) -> AssetChoice {
    if platform.eq_ignore_ascii_case("ios") {
        return AssetChoice {
            download_url: release_url.to_string(),
            min_os: "16.0".to_string(),
            installable: false,
            ..AssetChoice::default()
        };
    }

    let wanted_abi = if abi.trim().is_empty() {
        "arm64-v8a"
    } else {
        abi.trim()
    }
    .to_ascii_lowercase();

    let mut sha256sums_url = String::new();
    let mut chosen: Option<AssetChoice> = None;

    if let Some(assets) = rel.get("assets").and_then(|v| v.as_arr()) {
        for a in assets {
            let name = a.str_field("name");
            let url = a.str_field("browser_download_url");
            if !is_valid_https_url(&url) {
                continue;
            }
            if name.eq_ignore_ascii_case("SHA256SUMS.txt") {
                sha256sums_url = url;
                continue;
            }
            let lower = name.to_ascii_lowercase();
            if !lower.ends_with(".apk") {
                continue;
            }
            if lower.contains("-unsigned") || lower.contains("-debug") || lower.contains("acceptance") {
                continue;
            }
            let abi_match = lower.contains(&wanted_abi)
                || (!lower.contains("armeabi") && !lower.contains("x86"));
            if !abi_match {
                continue;
            }
            chosen = Some(AssetChoice {
                name,
                download_url: url,
                size_bytes: a.i64_field("size").unwrap_or(0).max(0),
                sha256: String::new(),
                sha256sums_url: String::new(),
                min_os: "29".to_string(),
                installable: true,
            });
        }
    }

    match chosen {
        Some(mut c) => {
            c.sha256sums_url = sha256sums_url;
            c
        }
        None => AssetChoice {
            sha256sums_url,
            min_os: "29".to_string(),
            ..AssetChoice::default()
        },
    }
}

fn parse_manifest_channel_entry(entry: &JsonVal, platform: &str, abi: &str) -> Option<ParsedRelease> {
    let tag = entry.str_field("tag");
    let ver_raw = {
        let v = entry.str_field("version");
        if v.is_empty() {
            tag.clone()
        } else {
            v
        }
    };
    let semver = parse_semver(&ver_raw).or_else(|| parse_semver(&tag))?;
    let prerelease = entry
        .bool_field("prerelease")
        .unwrap_or_else(|| semver.is_prerelease());
    let release_url = entry.str_field("release_url");
    let published_at = entry.str_field("published_at");
    let notes = clean_release_notes(&entry.str_field("notes"));

    let asset = if platform.eq_ignore_ascii_case("ios") {
        entry
            .get("ios")
            .map(|ios| pick_ios_asset_from_manifest(ios, &release_url))
            .unwrap_or_else(|| AssetChoice {
                download_url: release_url.clone(),
                ..AssetChoice::default()
            })
    } else {
        entry
            .get("android")
            .map(|and| pick_android_asset_from_manifest(and, abi))
            .unwrap_or_default()
    };

    Some(ParsedRelease {
        tag: if tag.is_empty() {
            format!("v{}", semver.normalized_string())
        } else {
            tag
        },
        semver,
        prerelease,
        published_at,
        release_url,
        notes,
        asset,
    })
}

fn parse_gh_release_item(item: &JsonVal, platform: &str, abi: &str) -> Option<ParsedRelease> {
    if item.bool_field("draft").unwrap_or(false) {
        return None;
    }
    let tag = item.str_field("tag_name");
    let semver = parse_semver(&tag)?;
    let prerelease = item
        .bool_field("prerelease")
        .unwrap_or_else(|| semver.is_prerelease());
    let published_at = item.str_field("published_at");
    let release_url = item.str_field("html_url");
    let notes = clean_release_notes(&item.str_field("body"));
    let asset = pick_asset_from_gh_release(item, platform, abi, &release_url);
    Some(ParsedRelease {
        tag,
        semver,
        prerelease,
        published_at,
        release_url,
        notes,
        asset,
    })
}

fn parse_feed(raw_json: &str, platform: &str, abi: &str) -> Result<Vec<ParsedRelease>, String> {
    let root = parse_json(raw_json).ok_or_else(|| "更新响应 JSON 格式非法".to_string())?;
    let mut list: Vec<ParsedRelease> = Vec::new();

    if root.get("schema_version").is_some() || root.get("channels").is_some() {
        // 1. 先解析 channels.stable / channels.prerelease（携带完整资产与 SHA-256）
        if let Some(channels) = root.get("channels") {
            for key in ["stable", "prerelease"] {
                if let Some(ch_obj) = channels.get(key) {
                    if let Some(r) = parse_manifest_channel_entry(ch_obj, platform, abi) {
                        list.push(r);
                    }
                }
            }
        }
        // 2. 再合并 releases[] 历史列表（用于跨版本区间更新日志聚合）
        if let Some(releases) = root.get("releases").and_then(|v| v.as_arr()) {
            for item in releases {
                if let Some(r) = parse_manifest_channel_entry(item, platform, abi) {
                    if let Some(existing) = list.iter_mut().find(|e| e.semver == r.semver) {
                        if existing.notes.is_empty() && !r.notes.is_empty() {
                            existing.notes = r.notes;
                        }
                        if existing.published_at.is_empty() && !r.published_at.is_empty() {
                            existing.published_at = r.published_at;
                        }
                        if existing.release_url.is_empty() && !r.release_url.is_empty() {
                            existing.release_url = r.release_url;
                        }
                    } else {
                        list.push(r);
                    }
                }
            }
        }
    } else if let Some(arr) = root.as_arr() {
        // GitHub Releases REST API 列表
        for item in arr {
            if let Some(r) = parse_gh_release_item(item, platform, abi) {
                list.push(r);
            }
        }
    } else if root.get("tag_name").is_some() {
        // 单个 GitHub Release 对象
        if let Some(r) = parse_gh_release_item(&root, platform, abi) {
            list.push(r);
        }
    } else if let Some(msg) = root.get("message").and_then(|v| v.as_str()) {
        return Err(format!("远端返回错误：{}", msg));
    } else {
        return Err("无法识别的更新响应结构".to_string());
    }

    // 去重并按 SemVer 从高到低排序
    list.sort_by(|a, b| b.semver.cmp(&a.semver));
    let mut deduped: Vec<ParsedRelease> = Vec::with_capacity(list.len());
    for item in list {
        if let Some(existing) = deduped.iter_mut().find(|e| e.semver == item.semver) {
            if !existing.asset.installable && item.asset.installable {
                existing.asset = item.asset;
            }
            if existing.notes.is_empty() && !item.notes.is_empty() {
                existing.notes = item.notes;
            }
        } else {
            deduped.push(item);
        }
    }
    Ok(deduped)
}

fn format_date_short(iso: &str) -> &str {
    if iso.len() >= 10 {
        &iso[..10]
    } else {
        iso
    }
}

/// 跨版本区间更新日志聚合：
/// - 仅保留严格位于 `(current, target]` 区间内的版本（倒序：最新在上）；
/// - 若某个 `(major, minor, patch)` 的正式版已经出现在该区间内，自动跳过同主版本号的预发 RC，
///   避免跨正式版升级时重复刷屏或出现仅在 RC 间存在过的中间修复；
/// - 若目标版本本身是未转正的 RC，则保留该主版本大于 current 且 <= target 的全部 RC 增量，以及之前遗漏的正式版。
fn collect_range_changelog<'a>(
    releases: &'a [ParsedRelease],
    current: &SemVer,
    target: &SemVer,
) -> (Vec<&'a ParsedRelease>, usize, bool) {
    let in_range: Vec<&ParsedRelease> = releases
        .iter()
        .filter(|r| r.semver > *current && r.semver <= *target)
        .collect();

    let stable_triples_in_range: HashSet<(u64, u64, u64)> = in_range
        .iter()
        .filter(|r| !r.prerelease)
        .map(|r| r.semver.base_triple())
        .collect();

    let mut filtered: Vec<&ParsedRelease> = in_range
        .into_iter()
        .filter(|r| !r.prerelease || !stable_triples_in_range.contains(&r.semver.base_triple()))
        .collect();

    filtered.sort_by(|a, b| b.semver.cmp(&a.semver));
    let total = filtered.len();
    let truncated = total > MAX_CHANGELOG_ENTRIES;
    if truncated {
        filtered.truncate(MAX_CHANGELOG_ENTRIES);
    }
    (filtered, total, truncated)
}

fn render_combined_notes(entries: &[&ParsedRelease]) -> String {
    if entries.is_empty() {
        return FALLBACK_EMPTY_NOTES.to_string();
    }
    if entries.len() == 1 {
        let n = entries[0].notes.trim();
        return if n.is_empty() {
            FALLBACK_EMPTY_NOTES.to_string()
        } else {
            n.to_string()
        };
    }
    let mut blocks = Vec::with_capacity(entries.len());
    for e in entries {
        let date = format_date_short(&e.published_at);
        let header = if date.is_empty() {
            e.tag.clone()
        } else {
            format!("{} ({})", e.tag, date)
        };
        let body = if e.notes.trim().is_empty() {
            FALLBACK_EMPTY_NOTES
        } else {
            e.notes.trim()
        };
        blocks.push(format!("{}\n{}", header, body));
    }
    blocks.join("\n\n")
}

fn error_decision_json(current_version: &str, msg: &str) -> String {
    format!(
        "{{\"status\":\"error\",\"current_version\":\"{}\",\"latest_tag\":\"\",\"latest_version\":\"\",\"is_prerelease\":false,\"published_at\":\"\",\"release_notes\":\"\",\"changelog_entries\":[],\"versions_behind\":0,\"changelog_truncated\":false,\"release_url\":\"\",\"asset_name\":\"\",\"download_url\":\"\",\"asset_size_bytes\":0,\"sha256\":\"\",\"sha256sums_url\":\"\",\"installable\":false,\"error\":\"{}\"}}",
        json_escape(current_version),
        json_escape(msg),
    )
}

/// 跨端更新裁决统一入口（供 JNI `NativeBridge.evaluateUpdateJson` 与 C ABI `miplay_update_evaluate_json` 共用）。
pub fn evaluate_update_json(
    current_version: &str,
    channel: &str,
    platform: &str,
    abi: &str,
    os_version: &str,
    ignored_tag: &str,
    raw_response_json: &str,
) -> String {
    let Some(current_sv) = parse_semver(current_version) else {
        return error_decision_json(current_version, "当前版本号格式非法");
    };
    let current_norm = current_sv.normalized_string();

    let effective_channel = match channel.trim().to_ascii_lowercase().as_str() {
        "stable" => "stable",
        "prerelease" | "preview" | "rc" => "prerelease",
        _ => {
            if current_sv.is_prerelease() {
                "prerelease"
            } else {
                "stable"
            }
        }
    };

    let releases = match parse_feed(raw_response_json, platform, abi) {
        Ok(r) => r,
        Err(e) => return error_decision_json(&current_norm, &e),
    };

    let candidate = releases.iter().find(|r| {
        if effective_channel == "stable" {
            !r.prerelease
        } else {
            true
        }
    });

    let Some(target) = candidate else {
        return format!(
            "{{\"status\":\"up_to_date\",\"current_version\":\"{}\",\"latest_tag\":\"\",\"latest_version\":\"\",\"is_prerelease\":false,\"published_at\":\"\",\"release_notes\":\"\",\"changelog_entries\":[],\"versions_behind\":0,\"changelog_truncated\":false,\"release_url\":\"\",\"asset_name\":\"\",\"download_url\":\"\",\"asset_size_bytes\":0,\"sha256\":\"\",\"sha256sums_url\":\"\",\"installable\":false,\"error\":\"\"}}",
            json_escape(&current_norm),
        );
    };

    let target_norm = target.semver.normalized_string();
    let is_newer = target.semver > current_sv;

    let ignored_match = {
        let ig = ignored_tag.trim();
        if ig.is_empty() {
            false
        } else if ig.eq_ignore_ascii_case(&target.tag) {
            true
        } else {
            parse_semver(ig).map(|v| v == target.semver).unwrap_or(false)
        }
    };

    let os_supported = os_version_meets_min(os_version, &target.asset.min_os);

    let status = if !is_newer {
        "up_to_date"
    } else if ignored_match {
        "ignored"
    } else if !os_supported {
        "unsupported_os"
    } else {
        "update_available"
    };

    let (entries, versions_behind, truncated) = if is_newer {
        collect_range_changelog(&releases, &current_sv, &target.semver)
    } else {
        (Vec::new(), 0, false)
    };

    let combined_notes = if is_newer {
        render_combined_notes(&entries)
    } else {
        String::new()
    };

    let entries_json: Vec<String> = entries
        .iter()
        .map(|e| {
            let body = if e.notes.trim().is_empty() {
                FALLBACK_EMPTY_NOTES
            } else {
                e.notes.trim()
            };
            format!(
                "{{\"tag\":\"{}\",\"version\":\"{}\",\"prerelease\":{},\"published_at\":\"{}\",\"notes\":\"{}\"}}",
                json_escape(&e.tag),
                json_escape(&e.semver.normalized_string()),
                e.prerelease,
                json_escape(&e.published_at),
                json_escape(body),
            )
        })
        .collect();

    format!(
        "{{\"status\":\"{}\",\"current_version\":\"{}\",\"latest_tag\":\"{}\",\"latest_version\":\"{}\",\"is_prerelease\":{},\"published_at\":\"{}\",\"release_notes\":\"{}\",\"changelog_entries\":[{}],\"versions_behind\":{},\"changelog_truncated\":{},\"release_url\":\"{}\",\"asset_name\":\"{}\",\"download_url\":\"{}\",\"asset_size_bytes\":{},\"sha256\":\"{}\",\"sha256sums_url\":\"{}\",\"installable\":{},\"error\":\"\"}}",
        status,
        json_escape(&current_norm),
        json_escape(&target.tag),
        json_escape(&target_norm),
        target.prerelease,
        json_escape(&target.published_at),
        json_escape(&combined_notes),
        entries_json.join(","),
        versions_behind,
        truncated,
        json_escape(&target.release_url),
        json_escape(&target.asset.name),
        json_escape(&target.asset.download_url),
        target.asset.size_bytes,
        json_escape(&target.asset.sha256),
        json_escape(&target.asset.sha256sums_url),
        target.asset.installable,
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn semver_strips_gradle_build_suffixes_without_breaking_rc() {
        assert_eq!(compare_versions("0.9.7-rc.6-release", "v0.9.7-rc.6"), 0);
        assert_eq!(compare_versions("0.9.7-rc.6-acceptance", "v0.9.7-rc.6"), 0);
        assert_eq!(compare_versions("0.9.6-release", "v0.9.6"), 0);
        assert_eq!(compare_versions("0.9.6-acceptance-release", "0.9.6"), 0);
        assert_eq!(compare_versions("0.9.7-rc.2", "0.9.7-rc.10"), -1);
        assert_eq!(compare_versions("0.9.7-rc.6-release", "v0.9.7"), -1);
        assert_eq!(compare_versions("v0.9.7", "0.9.7-rc.6-release"), 1);
        assert_eq!(compare_versions("bad", "0.9.6"), -2);
    }

    #[test]
    fn range_changelog_single_step_vs_multi_version_and_skips_superseded_rcs() {
        let manifest = r#"{
          "schema_version": 1,
          "channels": {
            "stable": {
              "tag": "v0.9.7",
              "version": "0.9.7",
              "prerelease": false,
              "published_at": "2026-11-25T10:00:00Z",
              "release_url": "https://github.com/JasirVoriya/miaocast-releases/releases/tag/v0.9.7",
              "notes": "- 0.9.7 正式版汇总：无线调试无感采集与妙投品牌升级",
              "android": {
                "min_sdk": 29,
                "assets": [
                  {
                    "abi": "arm64-v8a",
                    "name": "miplay-sender-v0.9.7-arm64-v8a.apk",
                    "download_url": "https://github.com/JasirVoriya/miaocast-releases/releases/download/v0.9.7/miplay-sender-v0.9.7-arm64-v8a.apk",
                    "size_bytes": 6800000,
                    "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                    "signed": true
                  }
                ]
              }
            },
            "prerelease": {
              "tag": "v0.9.8-rc.2",
              "version": "0.9.8-rc.2",
              "prerelease": true,
              "published_at": "2026-11-26T10:00:00Z",
              "release_url": "https://github.com/JasirVoriya/miaocast-releases/releases/tag/v0.9.8-rc.2",
              "notes": "- rc2 增量修复",
              "android": {
                "min_sdk": 29,
                "assets": [
                  {
                    "abi": "arm64-v8a",
                    "name": "miplay-sender-v0.9.8-rc.2-arm64-v8a.apk",
                    "download_url": "https://github.com/JasirVoriya/miaocast-releases/releases/download/v0.9.8-rc.2/miplay-sender-v0.9.8-rc.2-arm64-v8a.apk",
                    "size_bytes": 6900000,
                    "sha256": "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210",
                    "signed": true
                  }
                ]
              }
            }
          },
          "releases": [
            { "tag": "v0.9.8-rc.2", "prerelease": true, "published_at": "2026-11-26T10:00:00Z", "notes": "- rc2 增量修复" },
            { "tag": "v0.9.8-rc.1", "prerelease": true, "published_at": "2026-11-25T20:00:00Z", "notes": "- rc1 新能力" },
            { "tag": "v0.9.7", "prerelease": false, "published_at": "2026-11-25T10:00:00Z", "notes": "- 0.9.7 正式版汇总：无线调试无感采集与妙投品牌升级" },
            { "tag": "v0.9.7-rc.6", "prerelease": true, "published_at": "2026-11-22T06:00:00Z", "notes": "- 0.9.7-rc.6 临时预览" },
            { "tag": "v0.9.6", "prerelease": false, "published_at": "2026-11-21T14:00:00Z", "notes": "- 0.9.6 miName 解析" },
            { "tag": "v0.9.5", "prerelease": false, "published_at": "2026-11-21T10:00:00Z", "notes": "- 0.9.5 常用音源管理" },
            { "tag": "v0.9.4", "prerelease": false, "published_at": "2026-11-20T10:00:00Z", "notes": "- 0.9.4 初始开源" }
          ]
        }"#;

        // 1) 相邻升级：0.9.6 -> 0.9.7（只展示 0.9.7，跳过 0.9.7-rc.6）
        let d_step = evaluate_update_json("0.9.6-release", "stable", "android", "arm64-v8a", "29", "", manifest);
        let p_step = parse_json(&d_step).unwrap();
        assert_eq!(p_step.str_field("status"), "update_available");
        assert_eq!(p_step.i64_field("versions_behind"), Some(1));
        let entries = p_step.get("changelog_entries").unwrap().as_arr().unwrap();
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0].str_field("tag"), "v0.9.7");

        // 2) 跨多正式版：0.9.4 -> 0.9.7（展示 0.9.7、0.9.6、0.9.5，自动跳过已转正的 0.9.7-rc.6）
        let d_jump = evaluate_update_json("0.9.4-release", "stable", "android", "arm64-v8a", "29", "", manifest);
        let p_jump = parse_json(&d_jump).unwrap();
        assert_eq!(p_jump.i64_field("versions_behind"), Some(3));
        let tags: Vec<String> = p_jump
            .get("changelog_entries")
            .unwrap()
            .as_arr()
            .unwrap()
            .iter()
            .map(|e| e.str_field("tag"))
            .collect();
        assert_eq!(tags, vec!["v0.9.7", "v0.9.6", "v0.9.5"]);

        // 3) 预览版追新：0.9.8-rc.1 -> 0.9.8-rc.2（只展示 rc.2）
        let d_rc = evaluate_update_json("0.9.8-rc.1-release", "prerelease", "android", "arm64-v8a", "34", "", manifest);
        let p_rc = parse_json(&d_rc).unwrap();
        let rc_tags: Vec<String> = p_rc
            .get("changelog_entries")
            .unwrap()
            .as_arr()
            .unwrap()
            .iter()
            .map(|e| e.str_field("tag"))
            .collect();
        assert_eq!(rc_tags, vec!["v0.9.8-rc.2"]);

        // 4) 旧正式版切预览版：0.9.5 -> 0.9.8-rc.2（含 0.9.8-rc.2, 0.9.8-rc.1, 0.9.7, 0.9.6；跳过已转正的 0.9.7-rc.6）
        let d_mix = evaluate_update_json("0.9.5", "prerelease", "android", "arm64-v8a", "34", "", manifest);
        let p_mix = parse_json(&d_mix).unwrap();
        let mix_tags: Vec<String> = p_mix
            .get("changelog_entries")
            .unwrap()
            .as_arr()
            .unwrap()
            .iter()
            .map(|e| e.str_field("tag"))
            .collect();
        assert_eq!(mix_tags, vec!["v0.9.8-rc.2", "v0.9.8-rc.1", "v0.9.7", "v0.9.6"]);
    }

    #[test]
    fn github_releases_api_fallback_rejects_unsigned_and_cleans_comments() {
        let gh_api = r#"[
          {
            "tag_name": "v0.9.8",
            "prerelease": false,
            "draft": false,
            "published_at": "2026-11-26T08:00:00Z",
            "html_url": "https://github.com/JasirVoriya/miaocast-releases/releases/tag/v0.9.8",
            "body": "<!-- auto comment -->\n\n- 新增应用内更新检测\n\n**Full Changelog**: https://github.com/JasirVoriya/miaocast-releases/compare/v0.9.7...v0.9.8",
            "assets": [
              {
                "name": "miaocast-v0.9.8-arm64-v8a-unsigned.apk",
                "browser_download_url": "https://github.com/JasirVoriya/miaocast-releases/releases/download/v0.9.8/miaocast-v0.9.8-arm64-v8a-unsigned.apk",
                "size": 6500000
              },
              {
                "name": "miaocast-v0.9.8-arm64-v8a.apk",
                "browser_download_url": "https://github.com/JasirVoriya/miaocast-releases/releases/download/v0.9.8/miaocast-v0.9.8-arm64-v8a.apk",
                "size": 6600000
              },
              {
                "name": "SHA256SUMS.txt",
                "browser_download_url": "https://github.com/JasirVoriya/miaocast-releases/releases/download/v0.9.8/SHA256SUMS.txt",
                "size": 512
              }
            ]
          }
        ]"#;

        let out = evaluate_update_json("0.9.7", "stable", "android", "arm64-v8a", "29", "", gh_api);
        let parsed = parse_json(&out).unwrap();
        assert_eq!(parsed.str_field("status"), "update_available");
        assert_eq!(parsed.str_field("asset_name"), "miaocast-v0.9.8-arm64-v8a.apk");
        assert_eq!(parsed.bool_field("installable"), Some(true));
        assert!(parsed.str_field("sha256sums_url").ends_with("SHA256SUMS.txt"));
        assert_eq!(parsed.str_field("release_notes"), "- 新增应用内更新检测");
    }

    #[test]
    fn ignored_tag_and_min_sdk_gate_work() {
        let manifest = r#"{
          "schema_version": 1,
          "channels": {
            "stable": {
              "tag": "v1.0.0",
              "prerelease": false,
              "android": {
                "min_sdk": 31,
                "assets": [
                  {
                    "abi": "arm64-v8a",
                    "name": "miplay-sender-v1.0.0-arm64-v8a.apk",
                    "download_url": "https://github.com/JasirVoriya/miaocast-releases/releases/download/v1.0.0/miplay-sender-v1.0.0-arm64-v8a.apk",
                    "size_bytes": 7000000,
                    "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    "signed": true
                  }
                ]
              }
            }
          }
        }"#;

        let ignored = evaluate_update_json("0.9.7", "stable", "android", "arm64-v8a", "34", "v1.0.0", manifest);
        assert_eq!(parse_json(&ignored).unwrap().str_field("status"), "ignored");

        let unsupported = evaluate_update_json("0.9.7", "stable", "android", "arm64-v8a", "29", "", manifest);
        assert_eq!(parse_json(&unsupported).unwrap().str_field("status"), "unsupported_os");

        let up_to_date = evaluate_update_json("1.0.0-release", "stable", "android", "arm64-v8a", "34", "", manifest);
        assert_eq!(parse_json(&up_to_date).unwrap().str_field("status"), "up_to_date");
    }
}
