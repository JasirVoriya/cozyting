//! RTP(v2, PT=33 MP2T, SSRC 0xDEADBEEF) 与 `$`+24bit 长度 TCP 线帧封装。

pub const PT_MP2T: u8 = 33;
pub const SSRC: u32 = 0xdead_beef;

pub fn rtp_packet(seq: u16, ts: u32, payload: &[u8], marker: bool) -> Vec<u8> {
    let mut v = Vec::with_capacity(12 + payload.len());
    v.push(0x80);
    v.push((if marker { 0x80 } else { 0 }) | PT_MP2T);
    v.extend_from_slice(&seq.to_be_bytes());
    v.extend_from_slice(&ts.to_be_bytes());
    v.extend_from_slice(&SSRC.to_be_bytes());
    v.extend_from_slice(payload);
    v
}

pub fn dollar24(rtp: &[u8]) -> Vec<u8> {
    assert!(rtp.len() <= 0xff_ffff);
    let n = rtp.len();
    let mut v = Vec::with_capacity(n + 4);
    v.extend_from_slice(&[0x24, (n >> 16) as u8, (n >> 8) as u8, n as u8]);
    v.extend_from_slice(rtp);
    v
}
