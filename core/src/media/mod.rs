//! 媒体封装：AAC-LC 48kHz 立体声 → ADTS → PES(stream_id 0xC0) → MPEG-TS → RTP(PT=33) → `$`+24bit 长度 → 第三条反向 TCP。
//!
//! 已确认（MiPlayForWindows android `MiPlayMediaPacketizer.kt:39-320`；docs/miplay-real-legacy-source-fresh-session.md:141-145）：
//! - 每 AU 1024 采样（21.333ms），码率 MiPlayForWindows 用 256kbps（MiPlayAudioPipeline.kt:11），官方抓包 192kbps 也被接受；
//! - TS PID：PAT 0x0000、PMT 0x0100、PCR 0x1000、AAC 0x1100，stream_type 0x0F；
//! - PAT/PMT/PCR 在 AU#0 以及 AU#13 起每 5 个 AU 插一次；
//! - 一个 RTP 包最多 7×188 字节 TS，最后一片置 marker；SSRC 0xDEADBEEF；RTP 时间戳 = PES PTS（90kHz）低 32 位；
//! - 帧封装是 `0x24 + 24bit 长度 + RTP`，**不是**标准 RTSP interleaved 的 `$ ch len16`。
//! 现代链路：ADTS 前缀 AES-CBC 加密（auth::safety::MirrorKeys），PES private data 带 IV（字段布局需抓包确认）。

pub mod adts;
pub mod mpegts;
pub mod rtp;

use crate::timeline::OutputTimestamp;

#[derive(Clone, Debug)]
pub struct PacketizerState {
    pub au_index: u64,
    pub rtp_seq: u16,
    pub cc_pat: u8,
    pub cc_pmt: u8,
    pub cc_audio: u8,
}

impl Default for PacketizerState {
    /// 初值与已验证实现一致：PAT/PMT 连续计数从 1 开始，音频从 0 开始（MiPlayMediaPacketizer.kt:14-18）。
    fn default() -> Self {
        Self { au_index: 0, rtp_seq: 0, cc_pat: 1, cc_pmt: 1, cc_audio: 0 }
    }
}

/// 每个接收端一个 Packetizer（序号/连续计数独立），但输入的 AAC 与时间戳在组内共享。
pub struct Packetizer {
    pub state: PacketizerState,
}

impl Packetizer {
    pub fn new(state: PacketizerState) -> Self {
        Self { state }
    }

    pub fn tables_due(au_index: u64) -> bool {
        au_index == 0 || (au_index >= 13 && (au_index - 13) % 5 == 0)
    }

    /// 返回若干条可直接写入音频 TCP 的线帧。
    pub fn packetize(&mut self, raw_aac: &[u8], ts: &OutputTimestamp) -> Vec<Vec<u8>> {
        let adts = adts::prepend_adts(raw_aac);
        let st = &mut self.state;
        let tables = Self::tables_due(st.au_index);
        let mut tsbytes = Vec::with_capacity(188 * 8);
        if tables {
            tsbytes.extend_from_slice(&mpegts::pat(&mut st.cc_pat));
            tsbytes.extend_from_slice(&mpegts::pmt(&mut st.cc_pmt));
            tsbytes.extend_from_slice(&mpegts::pcr_packet(ts.pcr_27mhz));
        }
        let pes = mpegts::pes(&adts, ts.pts_90khz);
        tsbytes.extend_from_slice(&mpegts::packetize_pes(&pes, &mut st.cc_audio));
        let chunks: Vec<&[u8]> = tsbytes.chunks(7 * 188).collect();
        let n = chunks.len();
        let mut out = Vec::with_capacity(n);
        for (i, c) in chunks.into_iter().enumerate() {
            let pkt = rtp::rtp_packet(st.rtp_seq, ts.pts_90khz as u32, c, i + 1 == n);
            st.rtp_seq = st.rtp_seq.wrapping_add(1);
            out.push(rtp::dollar24(&pkt));
        }
        st.au_index += 1;
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn first_au_has_tables_and_dollar_framing() {
        let mut p = Packetizer::new(Default::default());
        let ts = OutputTimestamp { pts_90khz: 0, pcr_27mhz: 27_000_000 };
        let frames = p.packetize(&[0x21; 300], &ts);
        assert_eq!(frames[0][0], 0x24);
        let len = u32::from_be_bytes([0, frames[0][1], frames[0][2], frames[0][3]]) as usize;
        assert_eq!(len + 4, frames[0].len());
        assert_eq!(frames[0][4 + 1] & 0x7f, 33);
        assert_eq!(frames[0][4 + 12], 0x47);
        assert!(Packetizer::tables_due(13) && Packetizer::tables_due(18) && !Packetizer::tables_due(14));
    }
}
