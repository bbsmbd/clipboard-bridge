//! Win32 window + tray icon + clipboard listener. All UI work happens on the GUI thread;
//! network threads only push events and post WM_APP_EVENT.
#![cfg(windows)]

use crate::config::{self, Config};
use crate::i18n::tr;
use crate::net::{self, Event, Shared};
use crate::proto;
use crate::winutil::{self, wide};
use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, AtomicIsize, AtomicPtr, Ordering};
use std::sync::{Arc, Mutex};
use windows_sys::Win32::Foundation::*;
use windows_sys::Win32::Graphics::Gdi::*;
use windows_sys::Win32::System::DataExchange::AddClipboardFormatListener;
use windows_sys::Win32::System::LibraryLoader::GetModuleHandleW;
use windows_sys::Win32::System::Threading::CreateMutexW;
use windows_sys::Win32::UI::Shell::*;
use windows_sys::Win32::UI::WindowsAndMessaging::*;

const CLASS: &str = "ClipboardBridgeV1Wnd";
const EM_SETSEL: u32 = 0x00B1;
const EM_REPLACESEL: u32 = 0x00C2;
const EM_SETLIMITTEXT: u32 = 0x00C5;
const SS_CENTER: u32 = 0x0001;
const WM_APP_EVENT: u32 = WM_APP + 1;
const WM_APP_TRAY: u32 = WM_APP + 2;
const WM_APP_SHOW: u32 = WM_APP + 3;

const ID_NEWPIN: usize = 101;
const ID_PAUSE: usize = 102;
const ID_UNPAIR: usize = 103;
const ID_LANG: usize = 104;
const ID_LOG: usize = 105;
const ID_T_SHOW: usize = 201;
const ID_T_PAUSE: usize = 202;
const ID_T_QUIT: usize = 203;

static HWND_MAIN: AtomicIsize = AtomicIsize::new(0);
static STATUS_HWND: AtomicIsize = AtomicIsize::new(0);
static PIN_HWND: AtomicIsize = AtomicIsize::new(0);
static PINTITLE_HWND: AtomicIsize = AtomicIsize::new(0);
static GREY1: AtomicIsize = AtomicIsize::new(0);
static GREY2: AtomicIsize = AtomicIsize::new(0);
static GREY3: AtomicIsize = AtomicIsize::new(0);
static GREY4: AtomicIsize = AtomicIsize::new(0);
static BR_BG: AtomicIsize = AtomicIsize::new(0);
static BR_PANEL: AtomicIsize = AtomicIsize::new(0);
static F_HEADER: AtomicIsize = AtomicIsize::new(0);
static F_SUB: AtomicIsize = AtomicIsize::new(0);
static DPI: AtomicIsize = AtomicIsize::new(96);
const C_GREEN: u32 = 0x004B_8C1B; // #1B8C4B (BGR)
const C_PANEL: u32 = 0x00F1_F6F2;
const C_BORDER: u32 = 0x00D9_E3DB;
const C_GREY: u32 = 0x0070_6B66;

fn s(v: i32) -> i32 {
    v * (DPI.load(Ordering::SeqCst) as i32).max(96) / 96
}

/// Green header band + rounded PIN panel (drawn; controls sit on top).
unsafe fn paint(hwnd: HWND) {
    let mut ps: PAINTSTRUCT = std::mem::zeroed();
    let hdc = BeginPaint(hwnd, &mut ps);
    let mut rc: RECT = std::mem::zeroed();
    GetClientRect(hwnd, &mut rc);
    let head = RECT { left: 0, top: 0, right: rc.right, bottom: s(56) };
    let gb = CreateSolidBrush(C_GREEN);
    FillRect(hdc, &head, gb);
    DeleteObject(gb as HGDIOBJ);
    SetBkMode(hdc, TRANSPARENT as i32);
    SetTextColor(hdc, 0x00FF_FFFF);
    let old = SelectObject(hdc, F_HEADER.load(Ordering::SeqCst) as HGDIOBJ);
    let mut t1 = RECT { left: s(20), top: 0, right: rc.right - s(20), bottom: s(56) };
    let title = wide("Clipboard Bridge");
    DrawTextW(hdc, title.as_ptr(), -1, &mut t1, DT_LEFT | DT_SINGLELINE | DT_VCENTER);
    SelectObject(hdc, old);
    // PIN panel
    let pen = CreatePen(PS_SOLID, s(1).max(1), C_BORDER);
    let pb = BR_PANEL.load(Ordering::SeqCst) as HBRUSH;
    let op = SelectObject(hdc, pen as HGDIOBJ);
    let ob = SelectObject(hdc, pb as HGDIOBJ);
    RoundRect(hdc, s(16), s(166), rc.right - s(16), s(280), s(18), s(18));
    SelectObject(hdc, op);
    SelectObject(hdc, ob);
    DeleteObject(pen as HGDIOBJ);
    EndPaint(hwnd, &ps);
}
static STATE_COLOR: AtomicIsize = AtomicIsize::new(0); // 0 red, 1 green, 2 grey
static EVENTS: Mutex<VecDeque<Event>> = Mutex::new(VecDeque::new());
static UI: AtomicPtr<Ui> = AtomicPtr::new(std::ptr::null_mut());
static QUITTING: AtomicBool = AtomicBool::new(false);

struct Ui {
    hwnd: HWND,
    status: HWND,
    phones: HWND,
    ip: HWND,
    pin_title: HWND,
    pin: HWND,
    b_newpin: HWND,
    b_pause: HWND,
    b_unpair: HWND,
    b_lang: HWND,
    tip: HWND,
    log: HWND,
    shared: Arc<Shared>,
    last_remote: Option<String>,
    last_sent: Option<String>,
    last_img_remote: Option<String>,
    last_img_sent: Option<String>,
    icon_on: HICON,
    icon_off: HICON,
    tray_added: bool,
    taskbar_created: u32,
    hint_shown: bool,
}

fn ui() -> Option<&'static mut Ui> {
    let p = UI.load(Ordering::SeqCst);
    if p.is_null() { None } else { unsafe { Some(&mut *p) } }
}

fn push_event(e: Event) {
    EVENTS.lock().unwrap().push_back(e);
    let h = HWND_MAIN.load(Ordering::SeqCst);
    if h != 0 {
        unsafe {
            PostMessageW(h as HWND, WM_APP_EVENT, 0, 0);
        }
    }
}

fn lang(sh: &Shared) -> String {
    sh.cfg.lock().unwrap().lang.clone()
}

fn set_text(h: HWND, s: &str) {
    let w = wide(s);
    unsafe {
        SetWindowTextW(h, w.as_ptr());
    }
}

fn make_font(px: i32, bold: bool) -> HFONT {
    let face = wide("Nirmala UI");
    unsafe {
        CreateFontW(
            -px, 0, 0, 0,
            if bold { FW_BOLD as i32 } else { FW_NORMAL as i32 },
            0, 0, 0,
            DEFAULT_CHARSET as u32, OUT_DEFAULT_PRECIS as u32, CLIP_DEFAULT_PRECIS as u32,
            CLEARTYPE_QUALITY as u32, (DEFAULT_PITCH as u32) | (FF_DONTCARE as u32),
            face.as_ptr(),
        )
    }
}

unsafe fn child(parent: HWND, class: &str, text: &str, style: u32, x: i32, y: i32, w: i32, h: i32, id: usize, font: HFONT) -> HWND {
    let c = wide(class);
    let t = wide(text);
    let hwnd = CreateWindowExW(
        0, c.as_ptr(), t.as_ptr(), WS_CHILD | WS_VISIBLE | style,
        x, y, w, h, parent, id as HMENU, GetModuleHandleW(std::ptr::null()), std::ptr::null(),
    );
    SendMessageW(hwnd, WM_SETFONT, font as WPARAM, 1);
    hwnd
}

fn append_log(u: &Ui, msg: &str) {
    // only the latest event is shown, as one quiet line
    set_text(u.log, &format!("{}   {}", winutil::local_time(), msg));
}

fn tray_update(u: &mut Ui, msg: u32) {
    let connected = u.shared.session_count() > 0;
    unsafe {
        let mut nid: NOTIFYICONDATAW = std::mem::zeroed();
        nid.cbSize = std::mem::size_of::<NOTIFYICONDATAW>() as u32;
        nid.hWnd = u.hwnd;
        nid.uID = 1;
        nid.uFlags = NIF_MESSAGE | NIF_ICON | NIF_TIP;
        nid.uCallbackMessage = WM_APP_TRAY;
        nid.hIcon = if connected { u.icon_on } else { u.icon_off };
        let l = lang(&u.shared);
        let tip = format!("Clipboard Bridge V1 — {}", if connected { tr(&l, "connected") } else { tr(&l, "waiting") });
        for (i, c) in tip.encode_utf16().take(127).enumerate() {
            nid.szTip[i] = c;
        }
        if msg == NIM_ADD {
            u.tray_added = Shell_NotifyIconW(NIM_ADD, &nid) != 0;
        } else if u.tray_added {
            if Shell_NotifyIconW(NIM_MODIFY, &nid) == 0 {
                u.tray_added = Shell_NotifyIconW(NIM_ADD, &nid) != 0;
            }
        } else {
            u.tray_added = Shell_NotifyIconW(NIM_ADD, &nid) != 0;
        }
    }
}

fn tray_balloon(u: &Ui, text: &str) {
    if !u.tray_added {
        return;
    }
    unsafe {
        let mut nid: NOTIFYICONDATAW = std::mem::zeroed();
        nid.cbSize = std::mem::size_of::<NOTIFYICONDATAW>() as u32;
        nid.hWnd = u.hwnd;
        nid.uID = 1;
        nid.uFlags = NIF_INFO;
        for (i, c) in text.encode_utf16().take(255).enumerate() {
            nid.szInfo[i] = c;
        }
        for (i, c) in "Clipboard Bridge V1".encode_utf16().enumerate() {
            nid.szInfoTitle[i] = c;
        }
        nid.dwInfoFlags = NIIF_INFO;
        Shell_NotifyIconW(NIM_MODIFY, &nid);
    }
}

fn tray_remove(u: &Ui) {
    unsafe {
        let mut nid: NOTIFYICONDATAW = std::mem::zeroed();
        nid.cbSize = std::mem::size_of::<NOTIFYICONDATAW>() as u32;
        nid.hWnd = u.hwnd;
        nid.uID = 1;
        Shell_NotifyIconW(NIM_DELETE, &nid);
    }
}

fn refresh(u: &mut Ui) {
    let l = lang(&u.shared);
    let paused = u.shared.paused.load(Ordering::SeqCst);
    let names = u.shared.session_names();
    let (pin, known): (String, Vec<String>) = {
        let c = u.shared.cfg.lock().unwrap();
        (c.pin.clone(), c.phones.iter().map(|p| p.name.clone()).collect())
    };
    let (st, col) = if paused {
        (tr(&l, "paused"), 2)
    } else if !names.is_empty() {
        (tr(&l, "connected"), 1)
    } else {
        (tr(&l, "waiting"), 0)
    };
    STATE_COLOR.store(col, Ordering::SeqCst);
    set_text(u.status, st);
    let ph = if !names.is_empty() {
        format!("{}{}", tr(&l, "phones_on"), names.join(", "))
    } else if !known.is_empty() {
        format!("{}{}", tr(&l, "phones_known"), known.join(", "))
    } else {
        tr(&l, "no_phone").to_string()
    };
    set_text(u.phones, &ph);
    let ips: Vec<String> = net::lan_ips().iter().take(3).map(|i| i.to_string()).collect();
    set_text(u.ip, &format!("{}{}   ({} {})", tr(&l, "ip"), if ips.is_empty() { "-".into() } else { ips.join(", ") }, tr(&l, "port"), proto::TCP_PORT));
    set_text(u.pin_title, tr(&l, "pin_title"));
    set_text(u.pin, &format!("{} {}", &pin[..3], &pin[3..]));
    set_text(u.b_newpin, tr(&l, "new_pin"));
    set_text(u.b_pause, tr(&l, if paused { "resume" } else { "pause" }));
    set_text(u.b_unpair, tr(&l, "unpair"));
    set_text(u.b_lang, if l == "en" { "বাংলা" } else { "English" });
    set_text(u.tip, tr(&l, "tip"));
    unsafe {
        InvalidateRect(u.status, std::ptr::null(), 1);
    }
    tray_update(u, NIM_MODIFY);
}

fn show_main(hwnd: HWND) {
    unsafe {
        ShowWindow(hwnd, SW_SHOW);
        ShowWindow(hwnd, SW_RESTORE);
        SetForegroundWindow(hwnd);
    }
}

fn on_clipboard_changed(u: &mut Ui) {
    if u.shared.paused.load(Ordering::SeqCst) {
        return;
    }
    if !winutil::clip_has_text() {
        on_clipboard_image(u);
        return;
    }
    let text = match winutil::get_clip_text(u.hwnd) {
        Some(t) => proto::to_lf(&t),
        None => return,
    };
    if text.trim().is_empty() {
        return;
    }
    if u.last_remote.as_deref() == Some(text.as_str()) || u.last_sent.as_deref() == Some(text.as_str()) {
        return;
    }
    u.last_remote = None;
    u.last_sent = Some(text.clone());
    let l = lang(&u.shared);
    if text.chars().count() > proto::MAX_TEXT_CHARS {
        append_log(u, tr(&l, "too_big"));
        return;
    }
    if u.shared.session_count() == 0 {
        append_log(u, tr(&l, "copied_nophone"));
        return;
    }
    let pv = proto::preview(&text);
    u.shared.send_clip(text);
    append_log(u, &format!("{}{}", tr(&l, "sent"), pv));
}

fn kb(n: usize) -> String {
    if n >= 1024 * 1024 { format!("{:.1} MB", n as f64 / 1048576.0) } else { format!("{} KB", (n + 1023) / 1024) }
}

fn on_clipboard_image(u: &mut Ui) {
    let png = match winutil::get_clip_image(u.hwnd) {
        Some(p) => p,
        None => return,
    };
    let h = proto::sha_hex(&png);
    if u.last_img_remote.as_deref() == Some(h.as_str()) || u.last_img_sent.as_deref() == Some(h.as_str()) {
        return;
    }
    u.last_img_remote = None;
    u.last_img_sent = Some(h);
    u.last_sent = None;
    let l = lang(&u.shared);
    if png.len() > proto::MAX_IMAGE_BYTES {
        append_log(u, tr(&l, "img_too_big"));
        return;
    }
    if u.shared.session_count() == 0 {
        append_log(u, tr(&l, "copied_nophone"));
        return;
    }
    let n = png.len();
    u.shared.send_image(png, "image/png".into());
    append_log(u, &format!("{}{}", tr(&l, "img_sent"), kb(n)));
}

fn drain_events(u: &mut Ui) {
    loop {
        let e = EVENTS.lock().unwrap().pop_front();
        let e = match e {
            Some(e) => e,
            None => break,
        };
        let l = lang(&u.shared);
        match e {
            Event::Log(m) => append_log(u, &m),
            Event::Sessions => refresh(u),
            Event::Paired(name) => {
                append_log(u, &format!("{}{}", tr(&l, "paired"), name));
                tray_balloon(u, &format!("{}{}", tr(&l, "paired"), name));
                refresh(u);
            }
            Event::Image(data, _mime) => {
                if u.shared.paused.load(Ordering::SeqCst) {
                    continue;
                }
                match winutil::set_clip_image(u.hwnd, &data) {
                    Some(png) => {
                        u.last_img_remote = Some(proto::sha_hex(&png));
                        u.last_img_sent = None;
                        u.last_remote = None;
                        append_log(u, &format!("{}{}", tr(&l, "img_recv"), kb(data.len())));
                    }
                    None => append_log(u, tr(&l, "img_bad")),
                }
            }
            Event::Clip(text) => {
                if u.shared.paused.load(Ordering::SeqCst) {
                    continue;
                }
                u.last_remote = Some(text.clone());
                u.last_sent = None;
                if winutil::set_clip_text(u.hwnd, &text) {
                    append_log(u, &format!("{}{}", tr(&l, "recv"), proto::preview(&text)));
                } else {
                    append_log(u, "clipboard busy — retry copy on phone");
                }
            }
        }
    }
}

fn tray_menu(u: &mut Ui) {
    let l = lang(&u.shared);
    let paused = u.shared.paused.load(Ordering::SeqCst);
    unsafe {
        let m = CreatePopupMenu();
        let a = wide(tr(&l, "tray_show"));
        let b = wide(tr(&l, if paused { "resume" } else { "pause" }));
        let c = wide(tr(&l, "tray_quit"));
        AppendMenuW(m, MF_STRING, ID_T_SHOW, a.as_ptr());
        AppendMenuW(m, MF_STRING, ID_T_PAUSE, b.as_ptr());
        AppendMenuW(m, MF_SEPARATOR, 0, std::ptr::null());
        AppendMenuW(m, MF_STRING, ID_T_QUIT, c.as_ptr());
        let mut pt = POINT { x: 0, y: 0 };
        GetCursorPos(&mut pt);
        SetForegroundWindow(u.hwnd);
        TrackPopupMenu(m, TPM_RIGHTBUTTON, pt.x, pt.y, 0, u.hwnd, std::ptr::null());
        PostMessageW(u.hwnd, WM_NULL, 0, 0);
        DestroyMenu(m);
    }
}

fn quit(u: &Ui) {
    QUITTING.store(true, Ordering::SeqCst);
    tray_remove(u);
    unsafe {
        DestroyWindow(u.hwnd);
    }
}

unsafe extern "system" fn wndproc(hwnd: HWND, msg: u32, wp: WPARAM, lp: LPARAM) -> LRESULT {
    match msg {
        WM_CLIPBOARDUPDATE => {
            if let Some(u) = ui() {
                on_clipboard_changed(u);
            }
            0
        }
        WM_APP_EVENT => {
            if let Some(u) = ui() {
                drain_events(u);
            }
            0
        }
        WM_APP_SHOW => {
            show_main(hwnd);
            0
        }
        WM_APP_TRAY => {
            let ev = (lp as u32) & 0xFFFF;
            if ev == WM_LBUTTONDBLCLK || ev == WM_LBUTTONUP {
                show_main(hwnd);
            } else if ev == WM_RBUTTONUP || ev == WM_CONTEXTMENU {
                if let Some(u) = ui() {
                    tray_menu(u);
                }
            }
            0
        }
        WM_COMMAND => {
            let id = wp & 0xFFFF;
            let code = (wp >> 16) & 0xFFFF;
            if code != BN_CLICKED as usize && id < 200 {
                return 0;
            }
            if let Some(u) = ui() {
                let l = lang(&u.shared);
                match id {
                    ID_NEWPIN => {
                        let p = u.shared.new_pin();
                        append_log(u, &format!("{}{}", tr(&l, "new_pin_log"), p));
                        refresh(u);
                    }
                    ID_PAUSE | ID_T_PAUSE => {
                        let p = !u.shared.paused.load(Ordering::SeqCst);
                        u.shared.paused.store(p, Ordering::SeqCst);
                        refresh(u);
                    }
                    ID_UNPAIR => {
                        let t = wide(tr(&l, "unpair_confirm"));
                        let c = wide("Clipboard Bridge V1");
                        if MessageBoxW(hwnd, t.as_ptr(), c.as_ptr(), MB_YESNO | MB_ICONQUESTION) == IDYES {
                            u.shared.unpair_all();
                            refresh(u);
                        }
                    }
                    ID_LANG => {
                        let n = if l == "en" { "bn" } else { "en" };
                        u.shared.set_lang(n); // explicit user choice is kept
                        refresh(u);
                    }
                    ID_T_SHOW => show_main(hwnd),
                    ID_T_QUIT => quit(u),
                    _ => {}
                }
            }
            0
        }
        WM_CTLCOLORSTATIC => {
            let hdc = wp as HDC;
            let ctl = lp;
            SetBkMode(hdc, TRANSPARENT as i32);
            if ctl == STATUS_HWND.load(Ordering::SeqCst) {
                let c = match STATE_COLOR.load(Ordering::SeqCst) {
                    1 => 0x0050_8A1B, // green (BGR)
                    2 => 0x0080_8080,
                    _ => 0x003B_3BC2, // red
                };
                SetTextColor(hdc, c);
            } else if ctl == PIN_HWND.load(Ordering::SeqCst) || ctl == PINTITLE_HWND.load(Ordering::SeqCst) {
                SetTextColor(hdc, if ctl == PIN_HWND.load(Ordering::SeqCst) { 0x0035_5E14 } else { C_GREY });
                SetBkColor(hdc, C_PANEL);
                return BR_PANEL.load(Ordering::SeqCst) as LRESULT;
            } else if ctl == GREY1.load(Ordering::SeqCst) || ctl == GREY2.load(Ordering::SeqCst) || ctl == GREY3.load(Ordering::SeqCst) || ctl == GREY4.load(Ordering::SeqCst) {
                SetTextColor(hdc, C_GREY);
            } else {
                SetTextColor(hdc, 0x0020_2020);
            }
            SetBkColor(hdc, 0x00FF_FFFF);
            BR_BG.load(Ordering::SeqCst) as LRESULT
        }
        WM_PAINT => {
            paint(hwnd);
            0
        }
        WM_CLOSE => {
            ShowWindow(hwnd, SW_HIDE);
            if let Some(u) = ui() {
                if !u.hint_shown {
                    u.hint_shown = true;
                    let l = lang(&u.shared);
                    tray_balloon(u, tr(&l, "tray_hint"));
                }
                if !u.tray_added {
                    // no tray available: minimize instead of hiding
                    ShowWindow(hwnd, SW_MINIMIZE);
                }
            }
            0
        }
        WM_DESTROY => {
            PostQuitMessage(0);
            0
        }
        _ => {
            if let Some(u) = ui() {
                if msg == u.taskbar_created && msg != 0 {
                    u.tray_added = false;
                    tray_update(u, NIM_ADD);
                    return 0;
                }
            }
            DefWindowProcW(hwnd, msg, wp, lp)
        }
    }
}

pub fn run() {
    let args: Vec<String> = std::env::args().collect();
    let tray_mode = args.iter().any(|a| a == "--tray" || a == "--silent");

    unsafe {
        // single instance: bring the running copy to front instead
        let mname = wide("Local\\ClipboardBridgeV1.SingleInstance");
        let _mutex = CreateMutexW(std::ptr::null(), 0, mname.as_ptr());
        if GetLastError() == ERROR_ALREADY_EXISTS {
            let cls = wide(CLASS);
            let other = FindWindowW(cls.as_ptr(), std::ptr::null());
            if !other.is_null() && !tray_mode {
                PostMessageW(other, WM_APP_SHOW, 0, 0);
            }
            return;
        }
    }

    winutil::stop_old_version();
    let dir = config::config_dir();
    let cfg = Config::load(&dir);
    let l = cfg.lang.clone();
    let fw = winutil::ensure_firewall();

    let shared = Shared::new(cfg, dir, Box::new(push_event));

    unsafe {
        let hinst = GetModuleHandleW(std::ptr::null());
        let cls = wide(CLASS);
        let mut icon_on = LoadIconW(hinst, 1 as *const u16);
        let mut icon_off = LoadIconW(hinst, 2 as *const u16);
        if icon_on.is_null() {
            icon_on = LoadIconW(std::ptr::null_mut(), IDI_APPLICATION);
        }
        if icon_off.is_null() {
            icon_off = icon_on;
        }
        let wc = WNDCLASSEXW {
            cbSize: std::mem::size_of::<WNDCLASSEXW>() as u32,
            style: CS_HREDRAW | CS_VREDRAW,
            lpfnWndProc: Some(wndproc),
            cbClsExtra: 0,
            cbWndExtra: 0,
            hInstance: hinst,
            hIcon: icon_on,
            hCursor: LoadCursorW(std::ptr::null_mut(), IDC_ARROW),
            hbrBackground: {
                let b = CreateSolidBrush(0x00FF_FFFF);
                BR_BG.store(b as isize, Ordering::SeqCst);
                BR_PANEL.store(CreateSolidBrush(C_PANEL) as isize, Ordering::SeqCst);
                b
            },
            lpszMenuName: std::ptr::null(),
            lpszClassName: cls.as_ptr(),
            hIconSm: icon_on,
        };
        RegisterClassExW(&wc);

        let sdc = GetDC(std::ptr::null_mut());
        let dpi = GetDeviceCaps(sdc, LOGPIXELSY as i32);
        ReleaseDC(std::ptr::null_mut(), sdc);
        DPI.store(dpi.max(96) as isize, Ordering::SeqCst);
        let sc = |v: i32| -> i32 { s(v) };

        let cw = sc(440);
        let ch = sc(520);
        let style = WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX;
        let mut rc = RECT { left: 0, top: 0, right: cw, bottom: ch };
        AdjustWindowRect(&mut rc, style, 0);
        let title = wide(tr(&l, "title"));
        let hwnd = CreateWindowExW(
            0, cls.as_ptr(), title.as_ptr(), style,
            CW_USEDEFAULT, CW_USEDEFAULT, rc.right - rc.left, rc.bottom - rc.top,
            std::ptr::null_mut(), std::ptr::null_mut(), hinst, std::ptr::null(),
        );
        if hwnd.is_null() {
            return;
        }

        let f_norm = make_font(sc(15), false);
        let f_status = make_font(sc(24), true);
        let f_pin = make_font(sc(46), true);
        let f_small = make_font(sc(13), false);
        let f_btn = make_font(sc(14), false);
        F_HEADER.store(make_font(sc(22), true) as isize, Ordering::SeqCst);
        F_SUB.store(make_font(sc(12), false) as isize, Ordering::SeqCst);
        let f_log = {
            let face = wide("Consolas");
            CreateFontW(-sc(12), 0, 0, 0, FW_NORMAL as i32, 0, 0, 0, DEFAULT_CHARSET as u32, 0, 0, CLEARTYPE_QUALITY as u32, 0, face.as_ptr())
        };
        let m = sc(16);
        let w = cw - 2 * m;
        let center = SS_CENTER as u32;
        let status = child(hwnd, "STATIC", "", center, m, sc(70), w, sc(36), 0, f_status);
        let phones = child(hwnd, "STATIC", "", center, m, sc(108), w, sc(44), 0, f_norm);
        let pin_title = child(hwnd, "STATIC", "", center, m + sc(8), sc(176), w - sc(16), sc(22), 0, f_small);
        let pin = child(hwnd, "STATIC", "", center, m + sc(8), sc(200), w - sc(16), sc(66), 0, f_pin);
        let ip = child(hwnd, "STATIC", "", center, m, sc(288), w, sc(22), 0, f_small);
        let gap = sc(8);
        let bw = (w - 2 * gap) / 3;
        let bh = sc(36);
        let bs = BS_PUSHBUTTON as u32 | WS_TABSTOP;
        let by = sc(318);
        let b_newpin = child(hwnd, "BUTTON", "", bs, m, by, bw, bh, ID_NEWPIN, f_btn);
        let b_pause = child(hwnd, "BUTTON", "", bs, m + bw + gap, by, bw, bh, ID_PAUSE, f_btn);
        let b_unpair = child(hwnd, "BUTTON", "", bs, m + 2 * (bw + gap), by, bw, bh, ID_UNPAIR, f_btn);
        let tip = child(hwnd, "STATIC", "", 0, m, sc(366), w, sc(58), 0, f_small);
        let log = child(hwnd, "STATIC", "", center | 0x0000_4000, m, sc(430), w, sc(40), ID_LOG, f_small);
        let footer = child(hwnd, "STATIC", "v1.1  •  Developed by Tarek Ahmed", 0, m, ch - sc(32), w - sc(110), sc(20), 0, f_small);
        let b_lang = child(hwnd, "BUTTON", "", bs, cw - m - sc(96), ch - sc(37), sc(96), sc(28), ID_LANG, f_small);
        PINTITLE_HWND.store(pin_title as isize, Ordering::SeqCst);
        GREY1.store(ip as isize, Ordering::SeqCst);
        GREY2.store(tip as isize, Ordering::SeqCst);
        GREY3.store(footer as isize, Ordering::SeqCst);
        GREY4.store(log as isize, Ordering::SeqCst);
        STATUS_HWND.store(status as isize, Ordering::SeqCst);
        PIN_HWND.store(pin as isize, Ordering::SeqCst);

        let tb = wide("TaskbarCreated");
        let ui_box = Box::new(Ui {
            hwnd, status, phones, ip, pin_title, pin, b_newpin, b_pause, b_unpair, b_lang, tip, log,
            shared: shared.clone(),
            last_remote: None,
            last_sent: None,
            last_img_remote: None,
            last_img_sent: None,
            icon_on, icon_off,
            tray_added: false,
            taskbar_created: RegisterWindowMessageW(tb.as_ptr()),
            hint_shown: tray_mode,
        });
        UI.store(Box::into_raw(ui_box), Ordering::SeqCst);
        HWND_MAIN.store(hwnd as isize, Ordering::SeqCst);

        if let Some(u) = ui() {
            tray_update(u, NIM_ADD);
            refresh(u);
            match fw {
                Some(true) => append_log(u, tr(&l, "fw_added")),
                Some(false) => append_log(u, tr(&l, "fw_ok")),
                None => append_log(u, tr(&l, "fw_fail")),
            }
        }

        // start networking (retry a few seconds in case the old version is still exiting)
        let mut started = Err(String::new());
        for _ in 0..10 {
            started = shared.start();
            if started.is_ok() {
                break;
            }
            std::thread::sleep(std::time::Duration::from_millis(500));
        }
        if let Err(e) = started {
            let t = wide(&format!("{}\n\n({})", tr(&l, "port_busy"), e));
            let c = wide("Clipboard Bridge V1");
            MessageBoxW(hwnd, t.as_ptr(), c.as_ptr(), MB_OK | MB_ICONERROR);
            if let Some(u) = ui() {
                tray_remove(u);
            }
            return;
        }
        AddClipboardFormatListener(hwnd);
        winutil::set_autostart();
        if let Some(u) = ui() {
            let pin = shared.cfg.lock().unwrap().pin.clone();
            append_log(u, &format!("{}{}", tr(&l, "ready"), pin));
        }

        if !tray_mode {
            ShowWindow(hwnd, SW_SHOW);
            UpdateWindow(hwnd);
        }

        let mut msg: MSG = std::mem::zeroed();
        while GetMessageW(&mut msg, std::ptr::null_mut(), 0, 0) > 0 {
            if IsDialogMessageW(hwnd, &msg) == 0 {
                TranslateMessage(&msg);
                DispatchMessageW(&msg);
            }
        }
        if let Some(u) = ui() {
            tray_remove(u);
        }
    }
}
