//! Image helpers: Windows clipboard bitmaps (DIB) <-> PNG. Platform independent so it is unit-tested.

use image::{ImageFormat, RgbaImage};
use std::io::Cursor;

pub fn is_png(b: &[u8]) -> bool {
    b.len() > 8 && &b[..8] == b"\x89PNG\r\n\x1a\n"
}

pub fn encode_png(img: &RgbaImage) -> Option<Vec<u8>> {
    let mut out = Cursor::new(Vec::new());
    img.write_to(&mut out, ImageFormat::Png).ok()?;
    Some(out.into_inner())
}

fn u16le(b: &[u8], o: usize) -> u32 {
    u16::from_le_bytes([b[o], b[o + 1]]) as u32
}
fn u32le(b: &[u8], o: usize) -> u32 {
    u32::from_le_bytes([b[o], b[o + 1], b[o + 2], b[o + 3]])
}

/// CF_DIB / CF_DIBV5 (header + pixels, no file header) -> RGBA image. 24/32-bit, BI_RGB/BI_BITFIELDS.
pub fn dib_to_rgba(dib: &[u8]) -> Option<RgbaImage> {
    if dib.len() < 40 {
        return None;
    }
    let hsize = u32le(dib, 0) as usize;
    let width = u32le(dib, 4) as i32;
    let height = u32le(dib, 8) as i32;
    let bpp = u16le(dib, 14);
    let comp = u32le(dib, 16);
    let clr_used = u32le(dib, 32) as usize;
    if width <= 0 || height == 0 || width > 30000 || height.abs() > 30000 {
        return None;
    }
    if !(bpp == 24 || bpp == 32) || !(comp == 0 || comp == 3) {
        return None;
    }
    let (rm, gm, bm, am) = if comp == 3 {
        let o = if hsize == 40 { 40 } else { 40 };
        if dib.len() < o + 12 {
            return None;
        }
        let a = if hsize >= 56 { u32le(dib, 52) } else { 0 };
        (u32le(dib, o), u32le(dib, o + 4), u32le(dib, o + 8), a)
    } else {
        (0x00FF_0000, 0x0000_FF00, 0x0000_00FF, 0)
    };
    let mut off = hsize + clr_used * 4;
    if comp == 3 && hsize == 40 {
        off += 12; // masks follow a plain BITMAPINFOHEADER
    }
    let w = width as usize;
    let h = height.unsigned_abs() as usize;
    let stride = ((w * bpp as usize + 31) / 32) * 4;
    if dib.len() < off + stride * h {
        return None;
    }
    let shift = |m: u32| m.trailing_zeros();
    let get = |px: u32, m: u32| -> u8 {
        if m == 0 {
            return 255;
        }
        let v = (px & m) >> shift(m);
        let bits = (m >> shift(m)).count_ones();
        if bits >= 8 { (v >> (bits - 8)) as u8 } else { ((v * 255) / ((1 << bits) - 1)) as u8 }
    };
    let mut img = RgbaImage::new(w as u32, h as u32);
    let mut any_alpha = false;
    for y in 0..h {
        let src_row = if height > 0 { h - 1 - y } else { y };
        let row = &dib[off + src_row * stride..];
        for x in 0..w {
            let (r, g, b, a) = if bpp == 24 {
                (row[x * 3 + 2], row[x * 3 + 1], row[x * 3], 255)
            } else {
                let px = u32le(row, x * 4);
                let a = if comp == 3 && am != 0 { get(px, am) } else { (px >> 24) as u8 };
                (get(px, rm), get(px, gm), get(px, bm), a)
            };
            if a != 0 {
                any_alpha = true;
            }
            img.put_pixel(x as u32, y as u32, image::Rgba([r, g, b, a]));
        }
    }
    if bpp == 32 && !any_alpha {
        // most apps leave the alpha byte at 0 in 32-bit DIBs: treat as opaque
        for p in img.pixels_mut() {
            p.0[3] = 255;
        }
    }
    Some(img)
}

pub fn dib_to_png(dib: &[u8]) -> Option<Vec<u8>> {
    encode_png(&dib_to_rgba(dib)?)
}

/// Any image file (PNG/JPEG/WebP/GIF/BMP) -> (CF_DIB bytes, PNG bytes) for the Windows clipboard.
pub fn to_dib_and_png(data: &[u8]) -> Option<(Vec<u8>, Vec<u8>)> {
    let img = image::load_from_memory(data).ok()?.to_rgba8();
    let (w, h) = img.dimensions();
    let mut dib = Vec::with_capacity(40 + (w * h * 4) as usize);
    dib.extend_from_slice(&40u32.to_le_bytes());
    dib.extend_from_slice(&(w as i32).to_le_bytes());
    dib.extend_from_slice(&(h as i32).to_le_bytes()); // bottom-up
    dib.extend_from_slice(&1u16.to_le_bytes());
    dib.extend_from_slice(&32u16.to_le_bytes());
    dib.extend_from_slice(&0u32.to_le_bytes()); // BI_RGB
    dib.extend_from_slice(&(w * h * 4).to_le_bytes());
    dib.extend_from_slice(&2835i32.to_le_bytes());
    dib.extend_from_slice(&2835i32.to_le_bytes());
    dib.extend_from_slice(&0u32.to_le_bytes());
    dib.extend_from_slice(&0u32.to_le_bytes());
    for y in (0..h).rev() {
        for x in 0..w {
            let p = img.get_pixel(x, y).0;
            dib.extend_from_slice(&[p[2], p[1], p[0], p[3]]);
        }
    }
    let png = if is_png(data) { data.to_vec() } else { encode_png(&img)? };
    Some((dib, png))
}

pub fn mime_of(data: &[u8]) -> &'static str {
    if is_png(data) {
        "image/png"
    } else if data.len() > 3 && data[0] == 0xFF && data[1] == 0xD8 {
        "image/jpeg"
    } else if data.len() > 12 && &data[..4] == b"RIFF" && &data[8..12] == b"WEBP" {
        "image/webp"
    } else if data.len() > 6 && &data[..3] == b"GIF" {
        "image/gif"
    } else {
        "application/octet-stream"
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn dib_roundtrip() {
        let mut img = RgbaImage::new(3, 2);
        for (i, p) in img.pixels_mut().enumerate() {
            *p = image::Rgba([i as u8 * 40, 200 - i as u8 * 10, 7, 255]);
        }
        let png = encode_png(&img).unwrap();
        let (dib, png2) = to_dib_and_png(&png).unwrap();
        assert_eq!(png, png2);
        let back = dib_to_rgba(&dib).unwrap();
        assert_eq!(back, img);
        assert_eq!(mime_of(&png), "image/png");
        // 32-bit DIB with zero alpha must come back opaque
        let mut d2 = dib.clone();
        for i in 0..6 {
            d2[40 + i * 4 + 3] = 0;
        }
        assert_eq!(dib_to_rgba(&d2).unwrap(), img);
    }
}
