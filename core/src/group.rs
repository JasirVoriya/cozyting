//! 多音箱组会话：N 条单播会话 + 一个时钟主 + 共享 AAC/PTS/TIME_OFFSET。
//!
//! 组内每台音箱各自维护一条独立的 8899 控制连接与 RTSP/RTP 媒体流，但所有成员共享同一条 AAC PTS 时间线与同一个 `TIME_OFFSET`；
//! 广播器保留最近 47 个 AU 的历史缓冲窗口，供中途加入的成员先快速补齐缓冲再衔接实时流。

use crate::control::{ControlSession, RuntimeWriter, SourceIdentity};
use crate::media::{Packetizer, PacketizerState};
use crate::rtsp::SourceRtsp;
use crate::timeline::{OutputTimestamp, SharedTimeOffset, SharedTimeline};
use crate::{Error, Result};
use std::collections::{HashMap, VecDeque};
use std::io::{BufReader, Write};
use std::net::{Ipv4Addr, TcpListener, TcpStream};
use std::sync::mpsc::{sync_channel, Receiver, SyncSender, TrySendError};
use std::sync::{Arc, Mutex};
use std::time::Duration;

pub const MAX_MEMBERS: usize = 63; // 单组最大接收端数量上限
pub const HISTORY_AUS: usize = 47;
pub const STARTUP_RUNWAY_AUS: usize = 14;

/// 声道路由：立体声对（ref_function=stereo）时左右分别编码，同路由成员共享同一 AAC。
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum ChannelRoute {
    Stereo,
    LeftOnly,
    RightOnly,
}

#[derive(Clone)]
pub struct SharedAu {
    pub index: u64,
    pub ts: OutputTimestamp,
    pub aac_by_route: Arc<HashMap<ChannelRoute, Vec<u8>>>,
}

/// 发布/订阅：保留 47 个 AU 历史，晚加入成员先补发历史，再接实时流。
#[derive(Default)]
pub struct Broadcaster {
    history: VecDeque<SharedAu>,
    subscribers: Vec<(String, SyncSender<SharedAu>)>,
}

impl Broadcaster {
    pub fn subscribe(&mut self, member_id: &str) -> Receiver<SharedAu> {
        let (tx, rx) = sync_channel(HISTORY_AUS + 64);
        for au in &self.history {
            let _ = tx.try_send(au.clone());
        }
        self.subscribers.push((member_id.to_string(), tx));
        rx
    }

    pub fn unsubscribe(&mut self, member_id: &str) {
        self.subscribers.retain(|(id, _)| id != member_id);
    }

    /// 某成员队列满（网络阻塞）时不阻塞其他成员：记录并移除该慢速成员。
    pub fn publish(&mut self, au: SharedAu) -> Vec<String> {
        self.history.push_back(au.clone());
        while self.history.len() > HISTORY_AUS {
            self.history.pop_front();
        }
        let mut dropped = vec![];
        self.subscribers.retain(|(id, tx)| match tx.try_send(au.clone()) {
            Ok(()) => true,
            Err(TrySendError::Full(_)) => { dropped.push(id.clone()); false }
            Err(TrySendError::Disconnected(_)) => false,
        });
        dropped
    }
}

pub struct MemberHandle {
    pub id: String,
    pub ip: Ipv4Addr,
    pub route: ChannelRoute,
    pub runtime: RuntimeWriter,
}

pub struct GroupSession {
    pub identity: SourceIdentity,
    pub timeline: Mutex<SharedTimeline>,
    pub offset: Arc<SharedTimeOffset>,
    pub broadcaster: Arc<Mutex<Broadcaster>>,
    pub members: Mutex<Vec<MemberHandle>>,
    next_index: Mutex<u64>,
}

impl GroupSession {
    pub fn new(identity: SourceIdentity, initial_members: usize, playback_delay_us: i64) -> Self {
        Self {
            identity,
            timeline: Mutex::new(SharedTimeline::default()),
            offset: Arc::new(SharedTimeOffset::new(initial_members.max(1), playback_delay_us)),
            broadcaster: Arc::new(Mutex::new(Broadcaster::default())),
            members: Mutex::new(Vec::new()),
            next_index: Mutex::new(0),
        }
    }

    /// 平台层（MediaCodec / AudioToolbox）每产出一组 AU（各路由一个）调用一次。
    /// 节奏由调用方保证 ≈21.333ms/AU。
    pub fn push_access_unit(&self, aac_by_route: HashMap<ChannelRoute, Vec<u8>>) -> Vec<String> {
        let mut idx = self.next_index.lock().unwrap();
        let ts = self.timeline.lock().unwrap().stamp(*idx, crate::clock::now_us());
        let au = SharedAu { index: *idx, ts, aac_by_route: Arc::new(aac_by_route) };
        *idx += 1;
        self.broadcaster.lock().unwrap().publish(au)
    }

    /// 连接一台音箱并启动其发送线程。初始成员：并行调用（屏障在 RTSP PLAY 处汇合）；晚加入：直接复用 offset。
    pub fn connect_member(self: &Arc<Self>, id: String, ip: Ipv4Addr, route: ChannelRoute) -> Result<()> {
        if self.members.lock().unwrap().len() >= MAX_MEMBERS {
            return Err(Error::Protocol("group full".into()));
        }
        let mut ctl = ControlSession::connect(ip)?;
        ctl.bootstrap_legacy(&self.identity)?; // TODO: 按能力/抓包结论选择 Safety 链路
        ctl.prepare_playback(&self.identity)?;
        let local = *ctl.local_addr()?.ip();

        let timer = crate::clock_sync::TimerServer::bind(local)?;
        let timer_port = timer.port();
        std::thread::spawn(move || timer.run(vec![ip]));

        let listener = TcpListener::bind((local, 0))?;
        let port = listener.local_addr()?.port();
        ctl.send_open(local, port)?;

        let accept = |l: &TcpListener| -> Result<TcpStream> {
            l.set_nonblocking(false)?;
            let (s, _) = l.accept()?; // TODO: 10s 超时
            s.set_nodelay(true)?;
            Ok(s)
        };
        let rtsp_sock = accept(&listener)?;
        let rtsp = SourceRtsp::new(local, timer_port);
        let offset = self.offset.clone();
        let late_join = offset.current().is_some();
        let (tx_ready, rx_ready) = std::sync::mpsc::channel::<Result<i64>>();
        let rtsp_thread_sock = rtsp_sock.try_clone()?;
        let media_info_ctl = ctl.runtime_writer();
        std::thread::spawn(move || {
            let mut br = BufReader::new(rtsp_thread_sock);
            let r = rtsp.handshake(&mut br, || {
                // 在 RTSP PLAY 应答后通过运行期控制通道发送 SetMediaInfo(0x0012)。
                let _ = media_info_ctl.write(crate::commands::SET_MEDIA_INFO, br#"{"mArtist":"Android","mAlbum":"","mTitle":"System Audio","mDuration":2147483647,"id":"","mCoverUrl":"","status":0,"volume":0,"mArt":"","mSourceName":"Phone","mDeviceState":2}"#.to_vec());
            }, || Ok(if late_join { offset.current().unwrap() } else { offset.barrier() }));
            let _ = tx_ready.send(r);
            // 运行期：回应 OPTIONS/GET_PARAMETER，吞掉 VIDEO_LATENCY（遥测可用作“音箱正在播放”的可观测信号）
            loop {
                let Ok(m) = crate::rtsp::read_message(&mut br) else { break };
                if m.is_response() || m.method() == "VIDEO_LATENCY" { continue; }
                let _ = br.get_mut().write_all(&rtsp.generic_ok(m.cseq().unwrap_or(0)));
            }
        });
        let _aux = accept(&listener)?; // 第二条：辅助（image）连接，保持打开即可
        let mut audio = accept(&listener)?; // 第三条：音频
        rx_ready.recv_timeout(Duration::from_secs(10)).map_err(|_| Error::Timeout("RTSP".into()))??;

        let runtime = ctl.runtime_writer();
        let hb = runtime.clone();
        std::thread::spawn(move || loop {
            std::thread::sleep(Duration::from_secs(5));
            if hb.heartbeat().is_err() { break; }
        });
        let rw = runtime.clone();
        std::thread::spawn(move || {
            let _ = ctl.runtime_read_loop(rw, |f, st| log::debug!("ctl {:#06x} state={:?}", f.command, st.state));
        });

        let rx = self.broadcaster.lock().unwrap().subscribe(&id);
        std::thread::spawn(move || {
            let mut pk = Packetizer::new(PacketizerState::default());
            while let Ok(au) = rx.recv() {
                let Some(aac) = au.aac_by_route.get(&route) else { break };
                for wf in pk.packetize(aac, &au.ts) {
                    if audio.write_all(&wf).is_err() { return; }
                }
            }
        });
        self.members.lock().unwrap().push(MemberHandle { id, ip, route, runtime });
        Ok(())
    }

    pub fn set_volume(&self, member_id: Option<&str>, v: u8) -> Result<()> {
        for m in self.members.lock().unwrap().iter().filter(|m| member_id.map_or(true, |id| m.id == id)) {
            m.runtime.set_volume(v)?;
        }
        Ok(())
    }

    pub fn remove_member(&self, member_id: &str) -> Result<()> {
        self.broadcaster.lock().unwrap().unsubscribe(member_id);
        let mut ms = self.members.lock().unwrap();
        if let Some(pos) = ms.iter().position(|m| m.id == member_id) {
            let m = ms.remove(pos);
            let _ = m.runtime.close();
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn late_joiner_gets_history() {
        let mut b = Broadcaster::default();
        for i in 0..60u64 {
            b.publish(SharedAu { index: i, ts: OutputTimestamp { pts_90khz: i, pcr_27mhz: 0 }, aac_by_route: Arc::new(HashMap::new()) });
        }
        let rx = b.subscribe("late");
        assert_eq!(rx.try_recv().unwrap().index, 60 - HISTORY_AUS as u64);
    }
}
