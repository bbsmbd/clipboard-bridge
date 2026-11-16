//! Clipboard Bridge V1 wire protocol (shared with the Android app).
//!
//! TCP frame: [u32 big-endian length][kind byte][payload]
//!   length = 1 + payload.len()
//!   kind 'P' = plain JSON (handshake only), 'E' = AES-256-GCM(iv12 || ciphertext+tag16) of JSON
//! UDP (port 54545): PC beacon "CBV1|<pcId>|<tcpPort>|<pcName>", phone query "CBV1?"
//! PIN key = PBKDF2-HMAC-SHA256(pin, salt16, 50000, 32 bytes)

use aes_gcm::aead::{generic_array::GenericArray, Aead};
use aes_gcm::{Aes256Gcm, KeyInit};
use base64::Engine;
use rand::RngCore;
use serde_json::Value;
use sha2::Sha256;
use std::io::{self, Read, Write};

pub const UDP_PORT: u16 = 54545;
pub const TCP_PORT: u16 = 54546;
pub const PROTO_VERSION: i64 = 1;
pub const MAX_FRAME: usize = 32 * 1024 * 1024;
pub const MAX_IMAGE_BYTES: usize = 20 * 1024 * 1024;
pub const MAX_TEXT_CHARS: usize = 1_000_000;
pub const PBKDF2_ITERS: u32 = 50_000;
pub const KIND_PLAIN: u8 = b'P';
pub const KIND_ENC: u8 = b'E';
/// 'F' = AES-256-GCM( [u32 BE header length][header JSON][raw bytes] ) — used for images
pub const KIND_FILE: u8 = b'F';

pub fn b64e(b: &[u8]) -> String {
    base64::engine::general_purpose::STANDARD.encode(b)
}

pub fn b64d(s: &str) -> Option<Vec<u8>> {
    base64::engine::general_purpose::STANDARD.decode(s.trim()).ok()
}

pub fn random_bytes(n: usize) -> Vec<u8> {
    let mut v = vec![0u8; n];
    rand::thread_rng().fill_bytes(&mut v);
    v
}

pub fn random_id() -> String {
    random_bytes(8).iter().map(|b| format!("{:02x}", b)).collect()
}

pub fn random_pin() -> String {
    let mut b = [0u8; 4];
    rand::thread_rng().fill_bytes(&mut b);
    format!("{:06}", u32::from_le_bytes(b) % 900_000 + 100_000)
}

pub fn pin_key(pin: &str, salt: &[u8]) -> [u8; 32] {
    let mut out = [0u8; 32];
    pbkdf2::pbkdf2_hmac::<Sha256>(pin.as_bytes(), salt, PBKDF2_ITERS, &mut out);
    out
}

pub fn encrypt(key: &[u8; 32], plain: &[u8]) -> Vec<u8> {
    let cipher = Aes256Gcm::new(GenericArray::from_slice(key));
    let iv = random_bytes(12);
    let ct = cipher
        .encrypt(GenericArray::from_slice(&iv), plain)
        .expect("aes-gcm encrypt");
    let mut out = iv;
    out.extend_from_slice(&ct);
    out
}

pub fn decrypt(key: &[u8; 32], data: &[u8]) -> Option<Vec<u8>> {
    if data.len() < 12 + 16 {
        return None;
    }
    let cipher = Aes256Gcm::new(GenericArray::from_slice(key));
    cipher
        .decrypt(GenericArray::from_slice(&data[..12]), &data[12..])
        .ok()
}

pub fn write_frame<W: Write>(w: &mut W, kind: u8, payload: &[u8]) -> io::Result<()> {
    if payload.len() + 1 > MAX_FRAME {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "frame too large"));
    }
    let len = (payload.len() + 1) as u32;
    let mut buf = Vec::with_capacity(payload.len() + 5);
    buf.extend_from_slice(&len.to_be_bytes());
    buf.push(kind);
    buf.extend_from_slice(payload);
    w.write_all(&buf)?;
    w.flush()
}

pub fn read_frame<R: Read>(r: &mut R) -> io::Result<(u8, Vec<u8>)> {
    let mut lb = [0u8; 4];
    r.read_exact(&mut lb)?;
    let len = u32::from_be_bytes(lb) as usize;
    if len == 0 || len > MAX_FRAME {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "bad frame length"));
    }
    let mut buf = vec![0u8; len];
    r.read_exact(&mut buf)?;
    let kind = buf[0];
    buf.remove(0);
    Ok((kind, buf))
}

pub fn send_plain<W: Write>(w: &mut W, v: &Value) -> io::Result<()> {
    write_frame(w, KIND_PLAIN, v.to_string().as_bytes())
}

pub fn send_enc<W: Write>(w: &mut W, key: &[u8; 32], v: &Value) -> io::Result<()> {
    let ct = encrypt(key, v.to_string().as_bytes());
    write_frame(w, KIND_ENC, &ct)
}

pub fn send_file<W: Write>(w: &mut W, key: &[u8; 32], header: &Value, data: &[u8]) -> io::Result<()> {
    let h = header.to_string();
    let mut plain = Vec::with_capacity(4 + h.len() + data.len());
    plain.extend_from_slice(&(h.len() as u32).to_be_bytes());
    plain.extend_from_slice(h.as_bytes());
    plain.extend_from_slice(data);
    let ct = encrypt(key, &plain);
    write_frame(w, KIND_FILE, &ct)
}

/// Returns (header, data) of a decrypted 'F' frame.
pub fn decrypt_file(key: &[u8; 32], payload: &[u8]) -> Option<(Value, Vec<u8>)> {
    let p = decrypt(key, payload)?;
    if p.len() < 4 {
        return None;
    }
    let hl = u32::from_be_bytes([p[0], p[1], p[2], p[3]]) as usize;
    if 4 + hl > p.len() {
        return None;
    }
    let h = parse_json(&p[4..4 + hl])?;
    Some((h, p[4 + hl..].to_vec()))
}

pub fn sha_hex(b: &[u8]) -> String {
    use sha2::Digest;
    let d = sha2::Sha256::digest(b);
    d.iter().take(8).map(|x| format!("{:02x}", x)).collect()
}

pub fn parse_json(b: &[u8]) -> Option<Value> {
    serde_json::from_slice::<Value>(b).ok().filter(|v| v.is_object())
}

pub fn decrypt_json(key: &[u8; 32], data: &[u8]) -> Option<Value> {
    decrypt(key, data).and_then(|p| parse_json(&p))
}

pub fn key_from_vec(v: &[u8]) -> Option<[u8; 32]> {
    if v.len() != 32 {
        return None;
    }
    let mut k = [0u8; 32];
    k.copy_from_slice(v);
    Some(k)
}

pub fn s(v: &Value, k: &str) -> String {
    v.get(k).and_then(|x| x.as_str()).unwrap_or("").to_string()
}

/// PC→phone text: CRLF → LF. phone→PC text is converted back to CRLF on Windows.
pub fn to_lf(t: &str) -> String {
    t.replace("\r\n", "\n")
}

pub fn preview(t: &str) -> String {
    let one: String = t.chars().map(|c| if c == '\n' || c == '\r' { ' ' } else { c }).collect();
    let mut p: String = one.chars().take(40).collect();
    if one.chars().count() > 40 {
        p.push_str("...");
    }
    p
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn roundtrip() {
        let k = pin_key("123456", b"0123456789abcdef");
        let c = encrypt(&k, "হ্যালো world".as_bytes());
        assert_eq!(decrypt(&k, &c).unwrap(), "হ্যালো world".as_bytes());
        let k2 = pin_key("123457", b"0123456789abcdef");
        assert!(decrypt(&k2, &c).is_none());
        let mut buf = Vec::new();
        write_frame(&mut buf, KIND_PLAIN, b"{}").unwrap();
        let (kind, p) = read_frame(&mut &buf[..]).unwrap();
        assert_eq!(kind, KIND_PLAIN);
        assert_eq!(p, b"{}");
        assert_eq!(random_pin().len(), 6);
    }
}
