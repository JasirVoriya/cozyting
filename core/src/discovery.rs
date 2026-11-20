//! mDNS 设备发现（发送端浏览局域网服务）。
//!
//! 协议规范：
//! - 服务类型：`_mi-connect._udp.local`（SRV 端口 56666）与 `_lyra-mdns._udp.local`（SRV 端口 5353）；
//! - 查询：PTR，QU 位置 1（`qclass=0x8001`）；
//! - 控制通道端口固定为 **TCP 8899**；
//! - `_mi-connect` TXT 常见键：`name` / `apps=[5]` / `sec=2` / `appsData` / `flags` / `dev` / `idHash` / `version` / `mac`。

use std::collections::BTreeMap;
use std::net::Ipv4Addr;

pub const MDNS_ADDR: Ipv4Addr = Ipv4Addr::new(224, 0, 0, 251);
pub const MDNS_PORT: u16 = 5353;
pub const SERVICE_MI_CONNECT: &str = "_mi-connect._udp.local";
pub const SERVICE_LYRA: &str = "_lyra-mdns._udp.local";
pub const CONTROL_PORT: u16 = 8899;

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Speaker {
    /// mDNS 实例全名（稳定身份优先用 TXT/appsData 中的 device id，IP 会因 DHCP 变化）
    pub instance: String,
    pub friendly_name: String,
    pub ipv4: Option<Ipv4Addr>,
    pub srv_port: Option<u16>,
    pub txt: BTreeMap<String, String>,
}

impl Speaker {
    pub fn display_name(&self) -> String {
        self.txt.get("name").cloned().filter(|s| !s.is_empty()).unwrap_or_else(|| self.friendly_name.clone())
    }
    /// 型号：SRV 目标主机名（如 LX06.local / L17A-1915.local）的前缀。
    pub fn model(&self) -> Option<String> {
        self.txt.get("model").cloned().or_else(|| self.txt.get("_host").map(|h| h.split(['.', '-']).next().unwrap_or("").to_string()))
    }
    /// 是否声明了 MiPlay 应用 5（apps=[5]）。
    pub fn advertises_miplay(&self) -> bool {
        self.txt.get("apps").map(|v| v.contains('5')).unwrap_or(false)
    }
    /// 发现来源服务类型（`_mi-connect._udp.local` 或 `_lyra-mdns._udp.local`）。
    pub fn service_type(&self) -> &str {
        self.txt.get("_service").map(String::as_str).unwrap_or("")
    }
    /// TXT `apps` 原始字段（如 `[5]`）；缺失为空串。
    pub fn apps_raw(&self) -> &str {
        self.txt.get("apps").map(String::as_str).unwrap_or("")
    }
    pub fn is_mi_connect(&self) -> bool {
        self.service_type() == SERVICE_MI_CONNECT || self.instance.ends_with(&format!(".{SERVICE_MI_CONNECT}"))
    }
    /// JNI 发现行格式：`ip\tname\tmodel\tadvertises_miplay\tservice\tapps`
    pub fn format_discover_line(&self, ip: Ipv4Addr) -> String {
        format!(
            "{ip}\t{}\t{}\t{}\t{}\t{}",
            self.display_name(),
            self.model().unwrap_or_default(),
            self.advertises_miplay() as u8,
            self.service_type(),
            self.apps_raw()
        )
    }
}

/// 同一 IP 收到多条 mDNS 记录时，优先保留声明 `apps=[5]` / `_mi-connect` / 有明确 `name` 的记录。
pub fn merge_speaker(existing: &mut Speaker, incoming: Speaker) {
    let rank = |s: &Speaker| (
        s.advertises_miplay(),
        s.is_mi_connect(),
        s.txt.get("name").map(|v| !v.is_empty()).unwrap_or(false),
    );
    if rank(&incoming) > rank(existing) {
        *existing = incoming;
    }
}

/// 构造 PTR 查询（QU）。
pub fn build_ptr_query(service: &str) -> Vec<u8> {
    let mut p = vec![0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0];
    for label in service.trim_end_matches('.').split('.') {
        p.push(label.len() as u8);
        p.extend_from_slice(label.as_bytes());
    }
    p.push(0);
    p.extend_from_slice(&[0, 12, 0x80, 1]);
    p
}

fn read_u16(p: &[u8], o: usize) -> Option<u16> {
    Some(u16::from_be_bytes([*p.get(o)?, *p.get(o + 1)?]))
}

/// 读取（可能压缩的）域名，返回 (name, 下一个偏移)。
fn read_name(p: &[u8], start: usize) -> Option<(String, usize)> {
    let mut labels = Vec::new();
    let mut cur = start;
    let mut resume = None;
    for _ in 0..64 {
        let len = *p.get(cur)? as usize;
        cur += 1;
        if len == 0 {
            return Some((labels.join("."), resume.unwrap_or(cur)));
        }
        if len & 0xc0 == 0xc0 {
            let ptr = ((len & 0x3f) << 8) | *p.get(cur)? as usize;
            cur += 1;
            resume.get_or_insert(cur);
            cur = ptr;
        } else {
            labels.push(String::from_utf8_lossy(p.get(cur..cur + len)?).into_owned());
            cur += len;
        }
    }
    None
}

fn parse_txt(p: &[u8]) -> BTreeMap<String, String> {
    let mut out = BTreeMap::new();
    let mut o = 0;
    while o < p.len() {
        let n = p[o] as usize;
        o += 1;
        let Some(s) = p.get(o..o + n) else { break };
        let s = String::from_utf8_lossy(s);
        match s.split_once('=') {
            Some((k, v)) => out.insert(k.to_string(), v.to_string()),
            None => out.insert(s.to_string(), String::new()),
        };
        o += n;
    }
    out
}

/// 解析一个 mDNS 响应包中属于 `service` 的实例。`src` 为 UDP 源地址（缺 A 记录时回退）。
pub fn parse_response(packet: &[u8], service: &str, src: Option<Ipv4Addr>) -> Vec<Speaker> {
    let mut out: BTreeMap<String, Speaker> = BTreeMap::new();
    let mut addrs: BTreeMap<String, Ipv4Addr> = BTreeMap::new();
    let mut srv_target: BTreeMap<String, String> = BTreeMap::new();
    let Some(qd) = read_u16(packet, 4) else { return vec![] };
    let rr = [6, 8, 10].iter().filter_map(|o| read_u16(packet, *o)).map(|v| v as usize).sum::<usize>();
    let mut o = 12;
    for _ in 0..qd {
        let Some((_, n)) = read_name(packet, o) else { return vec![] };
        o = n + 4;
    }
    let suffix = format!(".{service}");
    for _ in 0..rr {
        let Some((owner, n)) = read_name(packet, o) else { break };
        o = n;
        let (Some(t), Some(len)) = (read_u16(packet, o), read_u16(packet, o + 8)) else { break };
        o += 10;
        let end = o + len as usize;
        let Some(rdata) = packet.get(o..end) else { break };
        match t {
            1 if rdata.len() == 4 => {
                addrs.insert(owner.clone(), Ipv4Addr::new(rdata[0], rdata[1], rdata[2], rdata[3]));
            }
            12 if owner.eq_ignore_ascii_case(service) => {
                if let Some((inst, _)) = read_name(packet, o) {
                    let e = out.entry(inst.clone()).or_default();
                    e.instance = inst;
                }
            }
            16 if owner.ends_with(&suffix) => {
                let e = out.entry(owner.clone()).or_default();
                e.instance = owner.clone();
                e.txt = parse_txt(rdata);
            }
            33 if owner.ends_with(&suffix) && rdata.len() >= 6 => {
                let e = out.entry(owner.clone()).or_default();
                e.instance = owner.clone();
                e.srv_port = read_u16(rdata, 4);
                if let Some((target, _)) = read_name(packet, o + 6) {
                    srv_target.insert(owner.clone(), target);
                }
            }
            _ => {}
        }
        o = end;
    }
    out.into_values()
        .filter(|s| s.instance.ends_with(&suffix))
        .map(|mut s| {
            s.txt.insert("_service".into(), service.to_string());
            s.ipv4 = srv_target.get(&s.instance).and_then(|t| addrs.get(t)).copied().or(src);
            if let Some(t) = srv_target.get(&s.instance) { s.txt.insert("_host".into(), t.clone()); }
            s.friendly_name = s
                .txt
                .get("name")
                .filter(|v| !v.is_empty())
                .cloned()
                .unwrap_or_else(|| s.instance.trim_end_matches(&suffix).to_string());
            s
        })
        .collect()
}

/// 阻塞式浏览：在指定本地 IPv4 接口上发两轮查询并收集 `timeout` 时长。
/// Android 上调用前必须持有 `WifiManager.MulticastLock`（Kotlin 侧负责）。
pub fn browse(local: Ipv4Addr, timeout: std::time::Duration) -> crate::Result<Vec<Speaker>> {
    use std::net::{SocketAddrV4, UdpSocket};
    let sock = UdpSocket::bind(SocketAddrV4::new(local, 0))?;
    sock.set_read_timeout(Some(std::time::Duration::from_millis(300)))?;
    for _ in 0..2 {
        for svc in [SERVICE_MI_CONNECT, SERVICE_LYRA] {
            sock.send_to(&build_ptr_query(svc), SocketAddrV4::new(MDNS_ADDR, MDNS_PORT))?;
        }
        std::thread::sleep(std::time::Duration::from_millis(160));
    }
    let deadline = std::time::Instant::now() + timeout;
    let mut found: BTreeMap<Ipv4Addr, Speaker> = BTreeMap::new();
    let mut buf = vec![0u8; 16 * 1024];
    while std::time::Instant::now() < deadline {
        match sock.recv_from(&mut buf) {
            Ok((n, std::net::SocketAddr::V4(from))) => {
                for svc in [SERVICE_MI_CONNECT, SERVICE_LYRA] {
                    for s in parse_response(&buf[..n], svc, Some(*from.ip())) {
                        if let Some(ip) = s.ipv4 {
                            found
                                .entry(ip)
                                .and_modify(|e| merge_speaker(e, s.clone()))
                                .or_insert(s);
                        }
                    }
                }
            }
            Ok(_) => {}
            Err(e) if matches!(e.kind(), std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut) => {}
            Err(e) => return Err(e.into()),
        }
    }
    Ok(found.into_values().collect())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn query_shape() {
        let q = build_ptr_query(SERVICE_MI_CONNECT);
        assert_eq!(&q[12..24], b"\x0b_mi-connect");
        assert_eq!(&q[q.len() - 4..], &[0, 12, 0x80, 1]);
    }

    #[test]
    fn merge_speaker_prefers_mi_connect_over_lyra() {
        let ip: Ipv4Addr = "192.168.31.34".parse().unwrap();
        let mut lyra = Speaker {
            instance: "B5A83339._lyra-mdns._udp.local".into(),
            friendly_name: "B5A83339".into(),
            ipv4: Some(ip),
            srv_port: Some(5353),
            txt: BTreeMap::from([("_service".into(), SERVICE_LYRA.into()), ("_host".into(), "B5A83339.local".into())]),
        };
        let mi = Speaker {
            instance: "小爱音箱-1905._mi-connect._udp.local".into(),
            friendly_name: "小爱音箱-1905".into(),
            ipv4: Some(ip),
            srv_port: Some(56666),
            txt: BTreeMap::from([
                ("_service".into(), SERVICE_MI_CONNECT.into()),
                ("_host".into(), "L17A-1915.local".into()),
                ("apps".into(), "[5]".into()),
                ("name".into(), "小爱音箱-1905".into()),
            ]),
        };
        merge_speaker(&mut lyra, mi.clone());
        assert!(lyra.advertises_miplay());
        assert_eq!(lyra.display_name(), "小爱音箱-1905");
        assert_eq!(lyra.service_type(), SERVICE_MI_CONNECT);

        let mut mi_first = mi;
        let lyra_late = Speaker {
            instance: "B5A83339._lyra-mdns._udp.local".into(),
            friendly_name: "B5A83339".into(),
            ipv4: Some(ip),
            srv_port: Some(5353),
            txt: BTreeMap::from([("_service".into(), SERVICE_LYRA.into())]),
        };
        merge_speaker(&mut mi_first, lyra_late);
        assert!(mi_first.advertises_miplay());
        assert_eq!(mi_first.display_name(), "小爱音箱-1905");
    }

    #[test]
    fn format_discover_line_includes_service_and_apps() {
        let ip: Ipv4Addr = "192.168.31.182".parse().unwrap();
        let lyra = Speaker {
            instance: "B5A83339._lyra-mdns._udp.local".into(),
            friendly_name: "B5A83339".into(),
            ipv4: Some(ip),
            srv_port: Some(5353),
            txt: BTreeMap::from([
                ("_service".into(), SERVICE_LYRA.into()),
                ("_host".into(), "B5A83339.local".into()),
            ]),
        };
        assert_eq!(
            lyra.format_discover_line(ip),
            "192.168.31.182\tB5A83339\tB5A83339\t0\t_lyra-mdns._udp.local\t"
        );
    }
}
