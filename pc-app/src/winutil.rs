//! Windows helpers: clipboard (UTF-16), autostart, firewall, old-version cleanup.
#![cfg(windows)]

use std::os::windows::process::CommandExt;
use std::process::Command;
use windows_sys::Win32::Foundation::*;
use windows_sys::Win32::System::DataExchange::*;
use windows_sys::Win32::System::Memory::*;
use windows_sys::Win32::System::Ole::{CF_DIB, CF_UNICODETEXT};
use windows_sys::Win32::System::Registry::*;
use windows_sys::Win32::System::Threading::{WaitForSingleObject, GetExitCodeProcess};
use windows_sys::Win32::UI::Shell::*;
use windows_sys::Win32::UI::WindowsAndMessaging::SW_HIDE;

const CREATE_NO_WINDOW: u32 = 0x0800_0000;

pub fn wide(s: &str) -> Vec<u16> {
    s.encode_utf16().chain(std::iter::once(0)).collect()
}

unsafe fn open_clipboard(hwnd: HWND) -> bool {
    for _ in 0..25 {
        if OpenClipboard(hwnd) != 0 {
            return true;
        }
        std::thread::sleep(std::time::Duration::from_millis(20));
    }
    false
}

pub fn get_clip_text(hwnd: HWND) -> Option<String> {
    unsafe {
        if IsClipboardFormatAvailable(CF_UNICODETEXT as u32) == 0 {
            return None;
        }
        if !open_clipboard(hwnd) {
            return None;
        }
        let mut out = None;
        let h = GetClipboardData(CF_UNICODETEXT as u32);
        if !h.is_null() {
            let p = GlobalLock(h) as *const u16;
            if !p.is_null() {
                let max = GlobalSize(h) / 2;
                let mut n = 0usize;
                while n < max && *p.add(n) != 0 {
                    n += 1;
                }
                let sl = std::slice::from_raw_parts(p, n);
                out = Some(String::from_utf16_lossy(sl));
                GlobalUnlock(h);
            }
        }
        CloseClipboard();
        out
    }
}

pub fn set_clip_text(hwnd: HWND, text: &str) -> bool {
    let crlf = text.replace("\r\n", "\n").replace('\n', "\r\n");
    let w: Vec<u16> = crlf.encode_utf16().chain(std::iter::once(0)).collect();
    unsafe {
        if !open_clipboard(hwnd) {
            return false;
        }
        EmptyClipboard();
        let bytes = w.len() * 2;
        let h = GlobalAlloc(GMEM_MOVEABLE, bytes);
        let mut ok = false;
        if !h.is_null() {
            let p = GlobalLock(h) as *mut u16;
            if !p.is_null() {
                std::ptr::copy_nonoverlapping(w.as_ptr(), p, w.len());
                GlobalUnlock(h);
                if !SetClipboardData(CF_UNICODETEXT as u32, h).is_null() {
                    ok = true;
                }
            }
            if !ok {
                GlobalFree(h);
            }
        }
        CloseClipboard();
        ok
    }
}

fn png_format() -> u32 {
    let n = wide("PNG");
    unsafe { RegisterClipboardFormatW(n.as_ptr()) }
}

pub fn clip_has_text() -> bool {
    unsafe { IsClipboardFormatAvailable(CF_UNICODETEXT as u32) != 0 }
}

unsafe fn read_global(h: HANDLE) -> Option<Vec<u8>> {
    if h.is_null() {
        return None;
    }
    let p = GlobalLock(h) as *const u8;
    if p.is_null() {
        return None;
    }
    let n = GlobalSize(h);
    let v = std::slice::from_raw_parts(p, n).to_vec();
    GlobalUnlock(h);
    Some(v)
}

unsafe fn put_global(fmt: u32, data: &[u8]) -> bool {
    let h = GlobalAlloc(GMEM_MOVEABLE, data.len().max(1));
    if h.is_null() {
        return false;
    }
    let p = GlobalLock(h) as *mut u8;
    if p.is_null() {
        GlobalFree(h);
        return false;
    }
    std::ptr::copy_nonoverlapping(data.as_ptr(), p, data.len());
    GlobalUnlock(h);
    if SetClipboardData(fmt, h).is_null() {
        GlobalFree(h);
        return false;
    }
    true
}

/// Image on the clipboard as PNG bytes (prefers the "PNG" format browsers/Office put there).
pub fn get_clip_image(hwnd: HWND) -> Option<Vec<u8>> {
    let pf = png_format();
    unsafe {
        let has_png = pf != 0 && IsClipboardFormatAvailable(pf) != 0;
        let has_dib = IsClipboardFormatAvailable(CF_DIB as u32) != 0;
        if !has_png && !has_dib {
            return None;
        }
        if !open_clipboard(hwnd) {
            return None;
        }
        let mut png = None;
        if has_png {
            png = read_global(GetClipboardData(pf)).filter(|b| crate::imgconv::is_png(b));
        }
        let mut dib = None;
        if png.is_none() && has_dib {
            dib = read_global(GetClipboardData(CF_DIB as u32));
        }
        CloseClipboard();
        if png.is_some() {
            return png;
        }
        dib.and_then(|d| crate::imgconv::dib_to_png(&d))
    }
}

/// Puts an image (PNG/JPEG/WebP...) on the clipboard as CF_DIB + "PNG". Returns the PNG bytes used.
pub fn set_clip_image(hwnd: HWND, data: &[u8]) -> Option<Vec<u8>> {
    let (dib, png) = crate::imgconv::to_dib_and_png(data)?;
    let pf = png_format();
    unsafe {
        if !open_clipboard(hwnd) {
            return None;
        }
        EmptyClipboard();
        let ok = put_global(CF_DIB as u32, &dib);
        if pf != 0 {
            put_global(pf, &png);
        }
        CloseClipboard();
        if ok { Some(png) } else { None }
    }
}

const RUN_KEY: &str = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";

/// Start on boot (HKCU Run, hidden in tray) and remove the old Python version's entry.
pub fn set_autostart() {
    let exe = match std::env::current_exe() {
        Ok(p) => p,
        Err(_) => return,
    };
    let val = format!("\"{}\" --tray", exe.display());
    unsafe {
        let mut hk: HKEY = std::ptr::null_mut();
        let k = wide(RUN_KEY);
        if RegOpenKeyExW(HKEY_CURRENT_USER, k.as_ptr(), 0, KEY_SET_VALUE, &mut hk) == ERROR_SUCCESS {
            let name = wide("ClipboardBridgeV1");
            let data = wide(&val);
            RegSetValueExW(hk, name.as_ptr(), 0, REG_SZ, data.as_ptr() as *const u8, (data.len() * 2) as u32);
            let old = wide("ClipboardBridge");
            RegDeleteValueW(hk, old.as_ptr());
            RegCloseKey(hk);
        }
    }
}

/// Stop the old Python build (it holds the same ports).
pub fn stop_old_version() {
    let _ = Command::new("taskkill")
        .args(["/F", "/IM", "clipboard_bridge_pc.exe"])
        .creation_flags(CREATE_NO_WINDOW)
        .output();
}

fn rule_exists(name: &str) -> bool {
    Command::new("netsh")
        .args(["advfirewall", "firewall", "show", "rule", &format!("name={}", name)])
        .creation_flags(CREATE_NO_WINDOW)
        .output()
        .map(|o| o.status.success())
        .unwrap_or(false)
}

/// Returns Some(true)=added now, Some(false)=already OK, None=failed/declined.
pub fn ensure_firewall() -> Option<bool> {
    if rule_exists("ClipboardBridge V1 TCP") && rule_exists("ClipboardBridge V1 UDP") {
        return Some(false);
    }
    let exe = std::env::current_exe().ok()?.display().to_string();
    let cmd = format!(
        "/c netsh advfirewall firewall delete rule name=\"ClipboardBridge V1 TCP\" & \
         netsh advfirewall firewall delete rule name=\"ClipboardBridge V1 UDP\" & \
         netsh advfirewall firewall delete rule name=\"ClipboardBridge V1 App\" & \
         netsh advfirewall firewall add rule name=\"ClipboardBridge V1 TCP\" dir=in action=allow protocol=TCP localport=54546 profile=any & \
         netsh advfirewall firewall add rule name=\"ClipboardBridge V1 UDP\" dir=in action=allow protocol=UDP localport=54545 profile=any & \
         netsh advfirewall firewall add rule name=\"ClipboardBridge V1 App\" dir=in action=allow program=\"{}\" enable=yes profile=any",
        exe
    );
    unsafe {
        let verb = wide("runas");
        let file = wide("cmd.exe");
        let params = wide(&cmd);
        let mut sei: SHELLEXECUTEINFOW = std::mem::zeroed();
        sei.cbSize = std::mem::size_of::<SHELLEXECUTEINFOW>() as u32;
        sei.fMask = SEE_MASK_NOCLOSEPROCESS;
        sei.lpVerb = verb.as_ptr();
        sei.lpFile = file.as_ptr();
        sei.lpParameters = params.as_ptr();
        sei.nShow = SW_HIDE;
        if ShellExecuteExW(&mut sei) == 0 || sei.hProcess.is_null() {
            return None;
        }
        WaitForSingleObject(sei.hProcess, 60_000);
        let mut code: u32 = 0;
        GetExitCodeProcess(sei.hProcess, &mut code);
        CloseHandle(sei.hProcess);
    }
    if rule_exists("ClipboardBridge V1 TCP") { Some(true) } else { None }
}

pub fn local_time() -> String {
    unsafe {
        let mut st: windows_sys::Win32::Foundation::SYSTEMTIME = std::mem::zeroed();
        windows_sys::Win32::System::SystemInformation::GetLocalTime(&mut st);
        format!("{:02}:{:02}:{:02}", st.wHour, st.wMinute, st.wSecond)
    }
}
