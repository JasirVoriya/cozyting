//! MiPlay 发送端命令行（调试用；默认静音）。
//!
//! 状态调查请用 **`inspect`**（认证后仅 GET_*，结束 TCP shutdown，**不**发协议 CLOSE）。
//! 旧 `probe` / `devinfo` / `channel` 结束会发 `CLOSE(0x0002)`，不能称为无副作用只读。

use miaocast_core::control::SourceIdentity;
use miaocast_core::session::{self, CastConfig, CastSession, SilenceSource};
use std::net::Ipv4Addr;
use std::time::{Duration, Instant};

fn arg(args: &[String], k: &str) -> Option<String> {
    args.iter().position(|a| a == k).and_then(|i| args.get(i + 1).cloned())
}

fn print_help() {
    eprintln!(
        "usage:\n\
         \tmiplay-cli inspect IP [--auth legacy]\n\
         \t  显式只查询：legacy 挑战应答后仅 GET_DEVICE_INFO / GET_MIRROR_MODE / GET_VOLUME /\n\
         \t  GET_MEDIA_INFO / GET_STATE / GET_CHANNEL；结束 TCP shutdown，不发 CLOSE/Open/\n\
         \t  SetPlaySource/SetChannel/SetVolume/SetLocalDeviceInfo。media.status=\n\
         \t  not_received|empty_payload|parse_error|ok；仅 ok 时 source_name 为\n\
         \t  absent|empty|nonempty，否则 source_name=null（从不用 \"\"）。设备\n\
         \t  mirror/state/volume 在顶层专用 GET；GET_CHANNEL 用 get_channel_received。\n\
         \tmiplay-cli discover [--local IP] [--secs N]\n\
         \tmiplay-cli probe IP          # 引导后发协议 CLOSE — 非无副作用只查询\n\
         \tmiplay-cli mute IP [--volume 0]\n\
         \tmiplay-cli cast IP[,IP...] [--auth auto|legacy|safety] [--secs N] [--delay-ms auto|N] [--ref single_room|multi_room]\n\
         \tmiplay-cli help"
    );
}

fn main() {
    env_logger::Builder::from_env(env_logger::Env::default().default_filter_or("info"))
        .format_timestamp_millis()
        .init();
    let args: Vec<String> = std::env::args().collect();
    let cmd = args.get(1).map(String::as_str).unwrap_or("help");
    let mut id = SourceIdentity::default();
    if let Some(n) = arg(&args, "--name") {
        id.source_name = n;
    }
    if let Some(r) = arg(&args, "--ref") {
        id.ref_function = r;
    }
    match cmd {
        "help" | "-h" | "--help" => print_help(),
        "inspect" => {
            let ip: Ipv4Addr = args.get(2).expect("inspect IP").parse().expect("ip");
            let auth = arg(&args, "--auth").unwrap_or_else(|| "legacy".into());
            if auth != "legacy" {
                eprintln!("inspect: only --auth legacy is implemented (got {auth})");
                std::process::exit(2);
            }
            match session::inspect(ip) {
                Ok(r) => {
                    println!("{}", r.to_json());
                    eprintln!(
                        "inspect ok ended_with={} tx={:?}",
                        r.ended_with,
                        r.tx_commands.iter().map(|c| format!("{c:#06x}")).collect::<Vec<_>>()
                    );
                }
                Err(e) => {
                    eprintln!("inspect failed: {e}");
                    std::process::exit(1);
                }
            }
        }
        "discover" => {
            let local: Ipv4Addr = arg(&args, "--local")
                .and_then(|s| s.parse().ok())
                .unwrap_or(Ipv4Addr::UNSPECIFIED);
            let secs: u64 = arg(&args, "--secs").and_then(|s| s.parse().ok()).unwrap_or(3);
            match miaocast_core::discovery::browse(local, Duration::from_secs(secs)) {
                Ok(list) => {
                    for s in list {
                        println!("{s:?}");
                    }
                }
                Err(e) => eprintln!("discover failed: {e}"),
            }
        }
        "probe" => {
            eprintln!("warning: probe sends protocol CLOSE(0x0002) after bootstrap; use `inspect` for query-without-CLOSE");
            let ip: Ipv4Addr = args[2].parse().expect("ip");
            match session::probe(ip, &id) {
                Ok(s) => println!("{}", s.to_json()),
                Err(e) => {
                    eprintln!("probe failed: {e}");
                    std::process::exit(1);
                }
            }
        }
        "devinfo" => {
            eprintln!("warning: legacy devinfo ends with protocol CLOSE; prefer `inspect`");
            let ip: Ipv4Addr = args[2].parse().expect("ip");
            let mode = miaocast_core::session::AuthMode::parse(
                &arg(&args, "--auth").unwrap_or_else(|| "legacy".into()),
            );
            match session::connect_bootstrapped(ip, &id, mode) {
                Ok((ctl, route)) => {
                    let p = ctl.status.device_info.clone().unwrap_or_default();
                    let kv = miaocast_core::frame::decode_kv(&p);
                    let shown: Vec<_> = kv
                        .iter()
                        .filter(|(k, _)| {
                            !matches!(
                                k.as_str(),
                                "accountId"
                                    | "sn"
                                    | "house_Id"
                                    | "room_Id"
                                    | "bluetoothMac"
                                    | "deviceId"
                                    | "mac"
                                    | "wifiMac"
                                    | "ip"
                                    | "did"
                                    | "miotDid"
                            )
                        })
                        .collect();
                    println!(
                        "DEVINFO {ip} route={route} len={} channel={:?} fields={} {:?}",
                        p.len(),
                        miaocast_core::frame::device_info_channel(&p),
                        kv.len(),
                        shown
                    );
                    let _ = ctl.runtime_writer().close();
                }
                Err(e) => {
                    eprintln!("devinfo failed: {e}");
                    std::process::exit(1);
                }
            }
        }
        "safety" => {
            let ip: Ipv4Addr = args[2].parse().expect("ip");
            let ver = arg(&args, "--version")
                .unwrap_or_else(|| miaocast_core::control::SAFETY_NATIVE_VERSION.to_string());
            let post: Vec<u32> = arg(&args, "--post-auth")
                .map(|v| {
                    v.split(',')
                        .filter_map(|x| {
                            if x == "observe" {
                                return Some(0);
                            }
                            let (plain, h) = match x.strip_prefix('p') {
                                Some(h) => (0x1_0000u32, h),
                                None => (0, x),
                            };
                            u16::from_str_radix(h.trim_start_matches("0x"), 16)
                                .ok()
                                .filter(|c| *c == 0x1e || *c == 0x1a)
                                .map(|c| c as u32 | plain)
                        })
                        .collect()
                })
                .unwrap_or_default();
            match session::safety_probe_ex(ip, &ver, &post) {
                Ok(r) => println!("{{\"ip\":\"{ip}\",\"version_sent\":{:?},\"report\":{}}}", ver, r.to_json()),
                Err(e) => {
                    eprintln!("safety failed: {e}");
                    std::process::exit(1);
                }
            }
        }
        "channel" => {
            eprintln!("warning: channel query uses command_then_close (protocol CLOSE); prefer `inspect`");
            let ip: Ipv4Addr = args[2].parse().expect("ip");
            if let Some(v) = arg(&args, "--set") {
                if !args.iter().any(|a| a == "--i-know-this-changes-the-speaker") {
                    eprintln!("refusing: setChannel changes speaker config");
                    std::process::exit(2);
                }
                println!("SET_CHANNEL {:?}", session::set_channel(ip, &id, v.parse().expect("0|1|2")));
            } else {
                match session::get_channel(ip, &id) {
                    Ok(p) => println!("{{\"ip\":\"{ip}\",\"get_channel_ack_payload\":{:?}}}", p),
                    Err(e) => {
                        eprintln!("channel failed: {e}");
                        std::process::exit(1);
                    }
                }
            }
        }
        "mute" => {
            let ip: Ipv4Addr = args[2].parse().expect("ip");
            let v: u8 = arg(&args, "--volume").and_then(|s| s.parse().ok()).unwrap_or(0);
            match session::premute(ip, &id, v) {
                Ok((orig, ack)) => println!("{{\"ip\":\"{ip}\",\"volume_original\":{orig:?},\"volume_ack\":{ack:?}}}"),
                Err(e) => {
                    eprintln!("mute failed: {e}");
                    std::process::exit(1);
                }
            }
        }
        "cast" => {
            let ips: Vec<Ipv4Addr> = args[2].split(',').map(|s| s.parse().expect("ip")).collect();
            let secs: u64 = arg(&args, "--secs").and_then(|s| s.parse().ok()).unwrap_or(20);
            let (delay_us, freq) = match arg(&args, "--delay-ms").as_deref() {
                None | Some("auto") => miaocast_core::netinfo::auto_playback_delay_us(),
                Some(v) => (v.parse::<i64>().expect("delay-ms") * 1000, None),
            };
            println!("DELAY {delay_us} us (wifi freq {freq:?})");
            let vap: u8 = arg(&args, "--volume-after-play")
                .and_then(|s| s.parse().ok())
                .unwrap_or(0);
            if id.ref_function.is_empty() || arg(&args, "--ref").is_none() {
                id.ref_function = if ips.len() > 1 {
                    "multi_room".into()
                } else {
                    "single_room".into()
                };
            }
            let cfg = CastConfig {
                identity: id,
                playback_delay_us: delay_us,
                premute: if args.iter().any(|a| a == "--no-premute") {
                    None
                } else {
                    Some(0)
                },
                volume_after_play: Some(vap),
                auth: miaocast_core::session::AuthMode::parse(
                    &arg(&args, "--auth").unwrap_or_default(),
                ),
                ..Default::default()
            };
            let src = SilenceSource { remaining: None };
            let (s, initial) = match CastSession::start(&ips, Box::new(src), cfg) {
                Ok(x) => x,
                Err(e) => {
                    eprintln!("cast failed: {e}");
                    std::process::exit(1);
                }
            };
            for st in &initial {
                println!("INITIAL {}", st.to_json());
            }
            let at = |k: &str| arg(&args, k).and_then(|s| s.parse::<u64>().ok());
            let mut events: Vec<(u64, &str)> = vec![];
            if let Some(t) = at("--add-at") {
                events.push((t, "add"));
            }
            if let Some(t) = at("--pause-at") {
                events.push((t, "pause"));
            }
            if let Some(t) = at("--resume-at") {
                events.push((t, "resume"));
            }
            if let Some(t) = at("--drop-at") {
                events.push((t, "drop"));
            }
            if let Some(t) = at("--switch-at") {
                events.push((t, "switch"));
            }
            if let Some(t) = at("--stall-at") {
                events.push((t, "stall"));
            }
            if let Some(t) = at("--blackhole-at") {
                events.push((t, "blackhole"));
            }
            events.sort();
            let t0 = Instant::now();
            let mut next_stats = 2u64;
            while t0.elapsed() < Duration::from_secs(secs) {
                std::thread::sleep(Duration::from_millis(100));
                let el = t0.elapsed().as_secs();
                while let Some(&(t, ev)) = events.first() {
                    if el < t {
                        break;
                    }
                    events.remove(0);
                    match ev {
                        "add" => {
                            let ip: Ipv4Addr = arg(&args, "--add").expect("--add IP").parse().expect("ip");
                            println!(
                                "EVENT add {ip} -> {}",
                                s.add_receiver(ip)
                                    .map(|x| x.to_json())
                                    .unwrap_or_else(|e| format!("ERR {e}"))
                            );
                        }
                        "pause" => println!("EVENT pause -> {:?}", s.pause()),
                        "resume" => println!("EVENT resume -> {:?}", s.resume()),
                        "switch" => println!("EVENT switch_source -> {:?}", s.switch_source()),
                        "blackhole" => {
                            let ip: Ipv4Addr =
                                arg(&args, "--blackhole").expect("--blackhole IP").parse().expect("ip");
                            let secs: u64 = arg(&args, "--blackhole-secs")
                                .and_then(|v| v.parse().ok())
                                .unwrap_or(20);
                            println!("EVENT blackhole {ip} {secs}s -> {}", s.debug_blackhole(ip, secs));
                        }
                        "stall" => {
                            let ip: Ipv4Addr =
                                arg(&args, "--stall").expect("--stall IP").parse().expect("ip");
                            println!("EVENT stall-rx {ip} -> {}", s.debug_stall_rx(ip));
                        }
                        "drop" => {
                            let ip: Ipv4Addr =
                                arg(&args, "--drop").expect("--drop IP").parse().expect("ip");
                            println!("EVENT drop {ip} -> {}", s.debug_drop_connection(ip));
                        }
                        _ => {}
                    }
                }
                if el >= next_stats {
                    next_stats += 2;
                    println!("STATS {}", s.stats_json());
                    if !s.is_running() {
                        break;
                    }
                }
            }
            for (ip, r) in s.set_volume(None, 0) {
                println!("SETVOL0 {ip} -> {r:?}");
            }
            s.stop();
            println!("FINAL {}", s.stats_json());
        }
        _ => {
            print_help();
            std::process::exit(2);
        }
    }
}
