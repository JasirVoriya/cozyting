//! MiPlay 发送端命令行原型（只用于调试；默认全程静音）。
//!
//! miplay-cli discover [--local IP] [--secs N]
//! miplay-cli probe IP
//! miplay-cli mute IP [--volume 0]
//! miplay-cli safety IP [--post-auth 1e,1a|observe]   （只做 SafetyAuth 互验 + 可选只读查询）
//! miplay-cli cast IP[,IP...] [--auth auto|legacy|safety] [--secs 20] [--delay-ms auto|N] [--ref single_room|multi_room] [--no-premute]
//!                [--volume-after-play 0] [--add IP --add-at S] [--pause-at S --resume-at S] [--drop IP --drop-at S] [--switch-at S] [--stall IP --stall-at S] [--blackhole IP --blackhole-at S --blackhole-secs N]
//!     音源固定为 AAC 静音帧；推流前先 premute 到 0，PLAY 后（含中途加入/重连的音箱）再设一次 volume-after-play（默认 0）。
//!     --delay-ms 缺省 auto：按 Wi-Fi 频段（5 GHz 800 ms，其他 1000 ms）。

use miplay_sender_core::control::SourceIdentity;
use miplay_sender_core::session::{self, CastConfig, CastSession, SilenceSource};
use std::net::Ipv4Addr;
use std::time::{Duration, Instant};

fn arg(args: &[String], k: &str) -> Option<String> {
    args.iter().position(|a| a == k).and_then(|i| args.get(i + 1).cloned())
}

fn main() {
    env_logger::Builder::from_env(env_logger::Env::default().default_filter_or("info"))
        .format_timestamp_millis()
        .init();
    let args: Vec<String> = std::env::args().collect();
    let cmd = args.get(1).map(String::as_str).unwrap_or("help");
    let mut id = SourceIdentity::default();
    if let Some(n) = arg(&args, "--name") { id.source_name = n; }
    if let Some(r) = arg(&args, "--ref") { id.ref_function = r; }
    match cmd {
        "discover" => {
            let local: Ipv4Addr = arg(&args, "--local").and_then(|s| s.parse().ok()).unwrap_or(Ipv4Addr::UNSPECIFIED);
            let secs: u64 = arg(&args, "--secs").and_then(|s| s.parse().ok()).unwrap_or(3);
            match miplay_sender_core::discovery::browse(local, Duration::from_secs(secs)) {
                Ok(list) => for s in list { println!("{s:?}"); },
                Err(e) => eprintln!("discover failed: {e}"),
            }
        }
        "probe" => {
            let ip: Ipv4Addr = args[2].parse().expect("ip");
            match session::probe(ip, &id) {
                Ok(s) => println!("{}", s.to_json()),
                Err(e) => { eprintln!("probe failed: {e}"); std::process::exit(1) }
            }
        }
        "devinfo" => {
            // 只读：引导时的 GetDeviceInfo 应答原始载荷
            let ip: Ipv4Addr = args[2].parse().expect("ip");
            let mode = miplay_sender_core::session::AuthMode::parse(&arg(&args, "--auth").unwrap_or("legacy".into()));
            match session::connect_bootstrapped(ip, &id, mode) {
                Ok((ctl, route)) => {
                    let p = ctl.status.device_info.clone().unwrap_or_default();
                    let kv = miplay_sender_core::frame::decode_kv(&p);
                    // 隐私字段不打印
                    let shown: Vec<_> = kv.iter().filter(|(k, _)| !matches!(k.as_str(), "accountId" | "sn" | "house_Id" | "room_Id" | "bluetoothMac" | "deviceId" | "mac" | "wifiMac" | "ip" | "did" | "miotDid")).collect();
                    println!("DEVINFO {ip} route={route} len={} channel={:?} fields={} {:?}", p.len(), miplay_sender_core::frame::device_info_channel(&p), kv.len(), shown);
                    let _ = hex_dump;
                    let _ = ctl.runtime_writer().close();
                }
                Err(e) => { eprintln!("devinfo failed: {e}"); std::process::exit(1) }
            }
        }
        "safety" => {
            // 只做 SafetyAuth 握手（0x0036/0x0029/0x1400 → 0x1401 → 加密 0x1402/0x1403 互验），不推流、不改设置。
            let ip: Ipv4Addr = args[2].parse().expect("ip");
            let ver = arg(&args, "--version").unwrap_or(miplay_sender_core::control::SAFETY_NATIVE_VERSION.to_string());
            // --post-auth 1e,1a,p1e,observe：互验后发只读空载荷命令（只允许 GetDeviceInfo 0x1e / 心跳 0x1a；前缀 p = 明文），observe = 只观察
            let post: Vec<u32> = arg(&args, "--post-auth").map(|v| v.split(',').filter_map(|x| {
                if x == "observe" { return Some(0) }
                let (plain, h) = match x.strip_prefix('p') { Some(h) => (0x1_0000u32, h), None => (0, x) };
                u16::from_str_radix(h.trim_start_matches("0x"), 16).ok().filter(|c| *c == 0x1e || *c == 0x1a).map(|c| c as u32 | plain)
            }).collect()).unwrap_or_default();
            match session::safety_probe_ex(ip, &ver, &post) {
                Ok(r) => println!("{{\"ip\":\"{ip}\",\"version_sent\":{:?},\"report\":{}}}", ver, r.to_json()),
                Err(e) => { eprintln!("safety failed: {e}"); std::process::exit(1) }
            }
        }
        "channel" => {
            // 只读：GetChannel(0x18)。设置需显式 --set N --i-know-this-changes-the-speaker（本项目未执行）。
            let ip: Ipv4Addr = args[2].parse().expect("ip");
            if let Some(v) = arg(&args, "--set") {
                if !args.iter().any(|a| a == "--i-know-this-changes-the-speaker") { eprintln!("refusing: setChannel changes speaker config"); std::process::exit(2) }
                println!("SET_CHANNEL {:?}", session::set_channel(ip, &id, v.parse().expect("0|1|2")));
            } else {
                match session::get_channel(ip, &id) {
                    Ok(p) => println!("{{\"ip\":\"{ip}\",\"get_channel_ack_payload\":{:?}}}", p),
                    Err(e) => { eprintln!("channel failed: {e}"); std::process::exit(1) }
                }
            }
        }
        "mute" => {
            let ip: Ipv4Addr = args[2].parse().expect("ip");
            let v: u8 = arg(&args, "--volume").and_then(|s| s.parse().ok()).unwrap_or(0);
            match session::premute(ip, &id, v) {
                Ok((orig, ack)) => println!("{{\"ip\":\"{ip}\",\"volume_original\":{orig:?},\"volume_ack\":{ack:?}}}"),
                Err(e) => { eprintln!("mute failed: {e}"); std::process::exit(1) }
            }
        }
        "cast" => {
            let ips: Vec<Ipv4Addr> = args[2].split(',').map(|s| s.parse().expect("ip")).collect();
            let secs: u64 = arg(&args, "--secs").and_then(|s| s.parse().ok()).unwrap_or(20);
            let (delay_us, freq) = match arg(&args, "--delay-ms").as_deref() {
                None | Some("auto") => miplay_sender_core::netinfo::auto_playback_delay_us(),
                Some(v) => (v.parse::<i64>().expect("delay-ms") * 1000, None),
            };
            println!("DELAY {delay_us} us (wifi freq {freq:?})");
            let vap: u8 = arg(&args, "--volume-after-play").and_then(|s| s.parse().ok()).unwrap_or(0);
            if id.ref_function.is_empty() || arg(&args, "--ref").is_none() {
                id.ref_function = if ips.len() > 1 { "multi_room".into() } else { "single_room".into() };
            }
            let cfg = CastConfig {
                identity: id,
                playback_delay_us: delay_us,
                premute: if args.iter().any(|a| a == "--no-premute") { None } else { Some(0) },
                volume_after_play: Some(vap),
                auth: miplay_sender_core::session::AuthMode::parse(&arg(&args, "--auth").unwrap_or_default()),
                ..Default::default()
            };
            let src = SilenceSource { remaining: None };
            let (s, initial) = match CastSession::start(&ips, Box::new(src), cfg) {
                Ok(x) => x,
                Err(e) => { eprintln!("cast failed: {e}"); std::process::exit(1) }
            };
            for st in &initial { println!("INITIAL {}", st.to_json()); }
            let at = |k: &str| arg(&args, k).and_then(|s| s.parse::<u64>().ok());
            let mut events: Vec<(u64, &str)> = vec![];
            if let Some(t) = at("--add-at") { events.push((t, "add")) }
            if let Some(t) = at("--pause-at") { events.push((t, "pause")) }
            if let Some(t) = at("--resume-at") { events.push((t, "resume")) }
            if let Some(t) = at("--drop-at") { events.push((t, "drop")) }
            if let Some(t) = at("--switch-at") { events.push((t, "switch")) }
            if let Some(t) = at("--stall-at") { events.push((t, "stall")) }
            if let Some(t) = at("--blackhole-at") { events.push((t, "blackhole")) }
            events.sort();
            let t0 = Instant::now();
            let mut next_stats = 2u64;
            while t0.elapsed() < Duration::from_secs(secs) {
                std::thread::sleep(Duration::from_millis(100));
                let el = t0.elapsed().as_secs();
                while let Some(&(t, ev)) = events.first() {
                    if el < t { break }
                    events.remove(0);
                    match ev {
                        "add" => { let ip: Ipv4Addr = arg(&args, "--add").expect("--add IP").parse().expect("ip");
                                   println!("EVENT add {ip} -> {}", s.add_receiver(ip).map(|x| x.to_json()).unwrap_or_else(|e| format!("ERR {e}"))); }
                        "pause" => println!("EVENT pause -> {:?}", s.pause()),
                        "resume" => println!("EVENT resume -> {:?}", s.resume()),
                        "switch" => println!("EVENT switch_source -> {:?}", s.switch_source()),
                        "blackhole" => { let ip: Ipv4Addr = arg(&args, "--blackhole").expect("--blackhole IP").parse().expect("ip");
                                         let secs: u64 = arg(&args, "--blackhole-secs").and_then(|v| v.parse().ok()).unwrap_or(20);
                                         println!("EVENT blackhole {ip} {secs}s -> {}", s.debug_blackhole(ip, secs)); }
                        "stall" => { let ip: Ipv4Addr = arg(&args, "--stall").expect("--stall IP").parse().expect("ip");
                                     println!("EVENT stall-rx {ip} -> {}", s.debug_stall_rx(ip)); }
                        "drop" => { let ip: Ipv4Addr = arg(&args, "--drop").expect("--drop IP").parse().expect("ip");
                                    println!("EVENT drop {ip} -> {}", s.debug_drop_connection(ip)); }
                        _ => {}
                    }
                }
                if el >= next_stats { next_stats += 2; println!("STATS {}", s.stats_json()); if !s.is_running() { break } }
            }
            for (ip, r) in s.set_volume(None, 0) { println!("SETVOL0 {ip} -> {r:?}"); }
            s.stop();
            println!("FINAL {}", s.stats_json());
        }
        _ => eprintln!("usage: miplay-cli discover|probe IP|mute IP|cast IP[,IP] [--secs N] [--delay-ms N] [--ref multi_room]"),
    }
}

fn hex_dump(p: &[u8]) -> String { p.iter().map(|b| format!("{b:02x}")).collect() }
