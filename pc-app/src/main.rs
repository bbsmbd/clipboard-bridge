#![cfg_attr(windows, windows_subsystem = "windows")]
//! Clipboard Bridge V1 — PC app.
//! Windows: GUI + tray + clipboard sync. Other OS: headless test mode (used for automated tests).

mod config;
mod i18n;
mod imgconv;
mod net;
mod proto;
#[cfg(windows)]
mod gui;
#[cfg(windows)]
mod winutil;

#[cfg(windows)]
fn main() {
    gui::run();
}

/// Headless mode (Linux): prints events, `send <text>` on stdin sends a clip (\n escaped as \\n).
#[cfg(not(windows))]
fn main() {
    use std::io::BufRead;
    let dir = config::config_dir();
    let cfg = config::Config::load(&dir);
    println!("PIN {}", cfg.pin);
    let shared = net::Shared::new(
        cfg,
        dir,
        Box::new(|e| {
            use std::io::Write;
            match e {
                net::Event::Log(m) => println!("LOG {}", m),
                net::Event::Sessions => println!("SESSIONS"),
                net::Event::Clip(t) => println!("CLIP {}", t.replace('\n', "\\n")),
                net::Event::Image(d, m) => println!("IMG {} {} {}", m, d.len(), proto::sha_hex(&d)),
                net::Event::Paired(n) => println!("PAIRED {}", n),
            }
            let _ = std::io::stdout().flush();
        }),
    );
    if let Err(e) = shared.start() {
        eprintln!("start failed: {}", e);
        std::process::exit(2);
    }
    let _ = i18n::tr("bn", "title");
    for line in std::io::stdin().lock().lines() {
        let line = match line {
            Ok(l) => l,
            Err(_) => break,
        };
        if let Some(t) = line.strip_prefix("send ") {
            shared.send_clip(t.replace("\\n", "\n"));
        } else if let Some(p) = line.strip_prefix("sendimg ") {
            if let Ok(d) = std::fs::read(p.trim()) {
                let m = imgconv::mime_of(&d).to_string();
                shared.send_image(d, m);
            }
        } else if line == "newpin" {
            println!("PIN {}", shared.new_pin());
        } else if line == "count" {
            println!("COUNT {}", shared.session_count());
        } else if line == "quit" {
            break;
        }
    }
}
