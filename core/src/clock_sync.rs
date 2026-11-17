//! UDP 计时器服务端：**手机是时钟主**，音箱为客户端。
//!
//! 包格式 40 字节，小端（已确认两端：MiPlayForWindows `MiPlayRtsp.kt:251-269`；FusionPlay `media.rs:705-857`）：
//! ```text
//! [0..8)   客户端发送时刻 (client 本地 µs)
//! [16..24) 服务端时间戳 A (µs)      ← Windows 写“收到时刻”，FusionPlay 读作 server_send
//! [24..32) 服务端时间戳 B (µs)      ← Windows 写“发出时刻”，FusionPlay 读作 server_receive
//! [32..36) 序号 u32 (客户端填，服务端原样回)
//! ```
//! 两份实现对 16/24 的标签相反，但 FusionPlay 的 offset 公式对二者对称，处理时间极短时结果一致；
//! 官方音箱/手机的真实语义 = 待抓包确认（方案 §9 C4）。这里采用 Windows 已真机验证的写法。
//! 客户端节奏（FusionPlay 模拟音箱的实现，非官方确认）：每轮 10 个样本间隔 10ms，约每 5s 一轮，RTT>250ms 丢弃，线性拟合频偏。

use crate::clock::now_us;
use std::net::{Ipv4Addr, SocketAddrV4, UdpSocket};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

pub const TIMER_PACKET_LEN: usize = 40;

pub fn fill_response(pkt: &mut [u8; TIMER_PACKET_LEN], recv_us: i64, send_us: i64) {
    pkt[16..24].copy_from_slice(&recv_us.to_le_bytes());
    pkt[24..32].copy_from_slice(&send_us.to_le_bytes());
}

pub struct TimerServer {
    pub socket: UdpSocket,
    stop: Arc<AtomicBool>,
    pub requests: Arc<std::sync::atomic::AtomicU64>,
    /// 调试“网络黑洞”：now_us() 小于该值时收到请求也不回（模拟丢包）。
    pub mute_until: Arc<std::sync::atomic::AtomicI64>,
}

impl TimerServer {
    pub fn bind(local: Ipv4Addr) -> crate::Result<Self> {
        let socket = UdpSocket::bind(SocketAddrV4::new(local, 0))?;
        socket.set_read_timeout(Some(std::time::Duration::from_millis(500)))?;
        Ok(Self { socket, stop: Arc::new(AtomicBool::new(false)), requests: Default::default(), mute_until: Default::default() })
    }

    pub fn port(&self) -> u16 {
        self.socket.local_addr().map(|a| a.port()).unwrap_or(0)
    }

    pub fn stop_handle(&self) -> Arc<AtomicBool> {
        self.stop.clone()
    }

    /// 一个计时器服务可以服务组内所有音箱（同一时钟主）；Windows/Android 实现为每台一个 socket，二者皆可。
    pub fn run(self, allowed: Vec<Ipv4Addr>) {
        let mut buf = [0u8; 64];
        while !self.stop.load(Ordering::Relaxed) {
            let Ok((n, from)) = self.socket.recv_from(&mut buf) else { continue };
            let recv = now_us();
            let std::net::SocketAddr::V4(f) = from else { continue };
            if n != TIMER_PACKET_LEN || (!allowed.is_empty() && !allowed.contains(f.ip())) {
                continue;
            }
            if recv < self.mute_until.load(Ordering::Relaxed) { continue }
            let mut pkt = [0u8; TIMER_PACKET_LEN];
            pkt.copy_from_slice(&buf[..TIMER_PACKET_LEN]);
            fill_response(&mut pkt, recv, now_us());
            let _ = self.socket.send_to(&pkt, from);
            self.requests.fetch_add(1, Ordering::Relaxed);
        }
    }
}

/// 客户端侧 offset 计算（用于自测/模拟音箱）：与 FusionPlay decode_timer_sample 等价。
pub fn client_offset(pkt: &[u8; TIMER_PACKET_LEN], client_send: i64, client_recv: i64) -> (i64, i64) {
    let a = i64::from_le_bytes(pkt[16..24].try_into().unwrap());
    let b = i64::from_le_bytes(pkt[24..32].try_into().unwrap());
    let offset = ((a - client_send) + (b - client_recv)) / 2;
    let rtt = (client_recv - client_send) - (b - a).abs();
    (offset, rtt)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn offset_symmetric() {
        let mut p = [0u8; 40];
        // 客户端时钟比服务端慢 1000µs，单程 100µs
        fill_response(&mut p, 5_100 + 1_000, 5_110 + 1_000);
        let (off, rtt) = client_offset(&p, 5_000, 5_210);
        assert!((off - 1_000).abs() <= 1, "{off}");
        assert_eq!(rtt, 200);
    }
}
