//! 最小 MPEG-TS 复用（单节目单音频流）。按 ISO/IEC 13818-1 独立实现。

pub const PID_PAT: u16 = 0x0000;
pub const PID_PMT: u16 = 0x0100;
pub const PID_PCR: u16 = 0x1000;
pub const PID_AUDIO: u16 = 0x1100;
pub const STREAM_TYPE_AAC_ADTS: u8 = 0x0f;

pub fn crc32_mpeg(data: &[u8]) -> u32 {
    let mut crc = 0xffff_ffffu32;
    for &b in data {
        crc ^= (b as u32) << 24;
        for _ in 0..8 {
            crc = if crc & 0x8000_0000 != 0 { (crc << 1) ^ 0x04c1_1db7 } else { crc << 1 };
        }
    }
    crc
}

fn header(pkt: &mut [u8; 188], pid: u16, pusi: bool, afc: u8, cc: u8) {
    pkt[0] = 0x47;
    pkt[1] = (if pusi { 0x40 } else { 0 }) | ((pid >> 8) as u8 & 0x1f);
    pkt[2] = pid as u8;
    pkt[3] = (afc << 4) | (cc & 0x0f);
}

fn psi(pid: u16, section_wo_crc: &[u8], cc: &mut u8) -> [u8; 188] {
    let mut p = [0xffu8; 188];
    header(&mut p, pid, true, 1, *cc);
    *cc = (*cc + 1) & 0x0f;
    p[4] = 0; // pointer_field
    let crc = crc32_mpeg(section_wo_crc);
    p[5..5 + section_wo_crc.len()].copy_from_slice(section_wo_crc);
    p[5 + section_wo_crc.len()..9 + section_wo_crc.len()].copy_from_slice(&crc.to_be_bytes());
    p
}

pub fn pat(cc: &mut u8) -> [u8; 188] {
    // table_id 0, section_length 13, tsid 0, version 1 current（0xc3 与已验证实现一致）, program 1 → PMT PID
    let s = [0x00, 0xb0, 0x0d, 0x00, 0x00, 0xc3, 0x00, 0x00, 0x00, 0x01,
             0xe0 | (PID_PMT >> 8) as u8, PID_PMT as u8];
    psi(PID_PAT, &s, cc)
}

pub fn pmt(cc: &mut u8) -> [u8; 188] {
    let s = [0x02, 0xb0, 0x12, 0x00, 0x01, 0xc3, 0x00, 0x00,
             0xe0 | (PID_PCR >> 8) as u8, PID_PCR as u8, 0xf0, 0x00,
             STREAM_TYPE_AAC_ADTS, 0xe0 | (PID_AUDIO >> 8) as u8, PID_AUDIO as u8, 0xf0, 0x00];
    psi(PID_PMT, &s, cc)
}

/// 独立 PCR 包（仅自适应字段）。MiPlayForWindows 抓包形态 continuity 固定 0。
pub fn pcr_packet(pcr_27mhz: u64) -> [u8; 188] {
    let mut p = [0xffu8; 188];
    header(&mut p, PID_PCR, true, 2, 0);
    p[4] = 183;
    p[5] = 0x10;
    let pcr = pcr_27mhz % ((1u64 << 33) * 300);
    let (base, ext) = (pcr / 300, pcr % 300);
    p[6] = (base >> 25) as u8;
    p[7] = (base >> 17) as u8;
    p[8] = (base >> 9) as u8;
    p[9] = (base >> 1) as u8;
    p[10] = (((base & 1) << 7) as u8) | 0x7e | ((ext >> 8) as u8 & 1);
    p[11] = ext as u8;
    p
}

pub fn pes(adts: &[u8], pts_90k: u64) -> Vec<u8> {
    let pts = pts_90k & 0x1_ffff_ffff;
    // 头数据长度 7 = PTS(5) + 2 字节 0xFF 填充，与 MiPlayForWindows 已真机验证的字节一致（MiPlayMediaPacketizer.kt:230-248）
    let pes_len = 3 + 7 + adts.len();
    let mut v = vec![0, 0, 1, 0xc0, (pes_len >> 8) as u8, pes_len as u8, 0x84, 0x80, 7];
    v.push(0x21 | (((pts >> 29) & 0x0e) as u8));
    v.push((pts >> 22) as u8);
    v.push((((pts >> 14) & 0xfe) | 1) as u8);
    v.push((pts >> 7) as u8);
    v.push((((pts << 1) & 0xfe) | 1) as u8);
    v.extend_from_slice(&[0xff, 0xff]);
    v.extend_from_slice(adts);
    v
}

pub fn packetize_pes(pes: &[u8], cc: &mut u8) -> Vec<u8> {
    let mut out = Vec::new();
    let mut off = 0;
    let mut first = true;
    while off < pes.len() {
        let n = (pes.len() - off).min(184);
        let mut p = [0xffu8; 188];
        if n == 184 {
            header(&mut p, PID_AUDIO, first, 1, *cc);
            p[4..188].copy_from_slice(&pes[off..off + n]);
        } else {
            header(&mut p, PID_AUDIO, first, 3, *cc);
            let af = 183 - n;
            p[4] = af as u8;
            if af > 0 { p[5] = 0 }
            p[5 + af..188].copy_from_slice(&pes[off..off + n]);
        }
        *cc = (*cc + 1) & 0x0f;
        out.extend_from_slice(&p);
        off += n;
        first = false;
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn crc_residue_zero() {
        let mut cc = 0;
        let p = pat(&mut cc);
        // section 从 p[5] 开始，长度 3+13，含 CRC；整体 CRC 应为 0
        assert_eq!(crc32_mpeg(&p[5..5 + 16]), 0);
    }
}
