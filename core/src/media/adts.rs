//! ADTS 头：MPEG-2/4 AAC-LC (profile 1)、48kHz (sf_idx 3)、立体声 2 声道、无 CRC（固定 7 字节头 `FF F9 4C 80 ...`）。
//! 注：`0xF9` = sync + ID=1 + layer 0 + protection_absent 1。

pub fn prepend_adts(raw: &[u8]) -> Vec<u8> {
    let len = raw.len() + 7;
    assert!(len <= 0x1fff, "AAC AU too large for ADTS");
    let mut v = Vec::with_capacity(len);
    v.extend_from_slice(&[
        0xff,
        0xf9,
        0x4c, // profile=1(LC) sf_idx=3(48k) private=0 ch_cfg 高位=0
        (0x80 + (len >> 11)) as u8, // ch_cfg 低2位=2
        ((len & 0x7ff) >> 3) as u8,
        (((len & 7) << 5) + 0x1f) as u8,
        0xfc,
    ]);
    v.extend_from_slice(raw);
    v
}

#[cfg(test)]
mod tests {
    #[test]
    fn length_field() {
        let a = super::prepend_adts(&[0u8; 100]);
        let len = (((a[3] & 3) as usize) << 11) | ((a[4] as usize) << 3) | ((a[5] as usize) >> 5);
        assert_eq!(len, 107);
    }
}
