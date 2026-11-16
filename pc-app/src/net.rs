//! Network core: UDP discovery beacon + TCP server with PIN pairing and encrypted sessions.
//! Platform independent (tested on Linux, shipped on Windows).

use crate::config::{Config, Phone};
use crate::proto::{self, s, KIND_ENC, KIND_PLAIN};
use serde_json::{json, Value};
use std::io;
use std::net::{IpAddr, Ipv4Addr, Shutdown, SocketAddr, TcpListener, TcpStream, UdpSocket};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{channel, Sender};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

pub enum Event {
    Log(String),
    Sessions,
    Clip(String),
    Image(Vec<u8>, String),
    Paired(String),
}

pub struct Session {
    pub phone_id: String,
    pub name: String,
    pub addr: SocketAddr,
    key: [u8; 32],
    writer: Mutex<TcpStream>,
}

impl Session {
    fn send(&self, v: &Value) -> io::Result<()> {
        let mut w = self.writer.lock().unwrap();
        proto::send_enc(&mut *w, &self.key, v)
    }
    fn send_file(&self, h: &Value, data: &[u8]) -> io::Result<()> {
        let mut w = self.writer.lock().unwrap();
        proto::send_file(&mut *w, &self.key, h, data)
    }
    fn kill(&self) {
        if let Ok(w) = self.writer.lock() {
            let _ = w.shutdown(Shutdown::Both);
        }
    }
}

pub struct Shared {
    pub cfg: Mutex<Config>,
    pub dir: PathBuf,
    pub paused: AtomicBool,
    sessions: Mutex<Vec<Arc<Session>>>,
    fails: Mutex<(u32, Option<Instant>)>,
    notify: Box<dyn Fn(Event) + Send + Sync>,
    out_tx: Mutex<Option<Sender<Outgoing>>>,
}

pub enum Outgoing {
    Text(String),
    Image(Vec<u8>, String),
}

const MAX_FAILS: u32 = 5;
const LOCK_SECS: u64 = 60;

impl Shared {
    pub fn new(cfg: Config, dir: PathBuf, notify: Box<dyn Fn(Event) + Send + Sync>) -> Arc<Shared> {
        Arc::new(Shared {
            cfg: Mutex::new(cfg),
            dir,
            paused: AtomicBool::new(false),
            sessions: Mutex::new(vec![]),
            fails: Mutex::new((0, None)),
            notify,
            out_tx: Mutex::new(None),
        })
    }

    pub fn log(&self, m: impl Into<String>) {
        (self.notify)(Event::Log(m.into()));
    }

    /// Binds sockets and starts all worker threads. Err = TCP port busy.
    pub fn start(self: &Arc<Self>) -> Result<(), String> {
        let listener = TcpListener::bind(("0.0.0.0", proto::TCP_PORT))
            .map_err(|e| format!("TCP {}: {}", proto::TCP_PORT, e))?;
        let me = self.clone();
        thread::spawn(move || me.accept_loop(listener));

        let me = self.clone();
        thread::spawn(move || me.udp_loop());

        let (tx, rx) = channel::<Outgoing>();
        *self.out_tx.lock().unwrap() = Some(tx);
        let me = self.clone();
        thread::spawn(move || {
            for o in rx {
                match o {
                    Outgoing::Text(t) => me.push_clip(&t),
                    Outgoing::Image(d, m) => me.push_image(&d, &m),
                }
            }
        });
        Ok(())
    }

    pub fn session_count(&self) -> usize {
        self.sessions.lock().unwrap().len()
    }

    pub fn session_names(&self) -> Vec<String> {
        let mut v: Vec<String> = self.sessions.lock().unwrap().iter().map(|s| s.name.clone()).collect();
        v.dedup();
        v
    }

    /// Queue a PC clipboard text for all connected phones (non-blocking).
    pub fn send_clip(&self, text: String) {
        if let Some(tx) = self.out_tx.lock().unwrap().as_ref() {
            let _ = tx.send(Outgoing::Text(text));
        }
    }

    /// Queue an image (PNG/JPEG bytes) for all connected phones.
    pub fn send_image(&self, data: Vec<u8>, mime: String) {
        if let Some(tx) = self.out_tx.lock().unwrap().as_ref() {
            let _ = tx.send(Outgoing::Image(data, mime));
        }
    }

    fn push_image(&self, data: &[u8], mime: &str) {
        let list: Vec<Arc<Session>> = self.sessions.lock().unwrap().clone();
        let h = json!({"t": "img", "id": proto::random_id(), "mime": mime, "size": data.len()});
        for ses in list {
            if let Err(e) = ses.send_file(&h, data) {
                self.log(format!("image send failed to {}: {}", ses.name, e));
                ses.kill();
            }
        }
    }

    fn push_clip(&self, text: &str) {
        let list: Vec<Arc<Session>> = self.sessions.lock().unwrap().clone();
        let msg = json!({"t": "clip", "id": proto::random_id(), "text": proto::to_lf(text)});
        for ses in list {
            if let Err(e) = ses.send(&msg) {
                self.log(format!("send failed to {}: {}", ses.name, e));
                ses.kill();
            }
        }
    }

    pub fn new_pin(&self) -> String {
        let mut c = self.cfg.lock().unwrap();
        c.pin = proto::random_pin();
        c.salt = proto::b64e(&proto::random_bytes(16));
        c.save(&self.dir);
        *self.fails.lock().unwrap() = (0, None);
        c.pin.clone()
    }

    pub fn unpair_all(&self) {
        {
            let mut c = self.cfg.lock().unwrap();
            c.phones.clear();
            c.save(&self.dir);
        }
        let list: Vec<Arc<Session>> = self.sessions.lock().unwrap().drain(..).collect();
        for s in list {
            let _ = s.send(&json!({"t": "unpaired"}));
            s.kill();
        }
        (self.notify)(Event::Sessions);
    }

    pub fn set_lang(&self, lang: &str) {
        let mut c = self.cfg.lock().unwrap();
        c.lang = lang.to_string();
        c.lang_chosen = true;
        c.save(&self.dir);
    }

    // ---------------- UDP discovery ----------------
    fn udp_loop(self: Arc<Self>) {
        let sock = match UdpSocket::bind(("0.0.0.0", proto::UDP_PORT)) {
            Ok(s) => s,
            Err(e) => {
                self.log(format!("UDP {} busy ({}), beacon only", proto::UDP_PORT, e));
                match UdpSocket::bind(("0.0.0.0", 0)) {
                    Ok(s) => s,
                    Err(_) => return,
                }
            }
        };
        let _ = sock.set_broadcast(true);
        let _ = sock.set_read_timeout(Some(Duration::from_millis(500)));
        let mut last_beacon = Instant::now() - Duration::from_secs(10);
        let mut targets: Vec<Ipv4Addr> = vec![];
        let mut last_targets = Instant::now() - Duration::from_secs(60);
        let mut buf = [0u8; 512];
        loop {
            if last_targets.elapsed() > Duration::from_secs(15) {
                targets = broadcast_targets();
                last_targets = Instant::now();
            }
            if last_beacon.elapsed() >= Duration::from_secs(2) {
                let b = self.beacon();
                for t in &targets {
                    let _ = sock.send_to(b.as_bytes(), (*t, proto::UDP_PORT));
                }
                last_beacon = Instant::now();
            }
            match sock.recv_from(&mut buf) {
                Ok((n, from)) => {
                    let m = String::from_utf8_lossy(&buf[..n]);
                    if m.starts_with("CBV1?") {
                        let _ = sock.send_to(self.beacon().as_bytes(), from);
                    }
                }
                Err(e) => {
                    // Windows reports ICMP "port unreachable" as WSAECONNRESET on the next recv:
                    // never spin on errors.
                    if e.kind() != std::io::ErrorKind::WouldBlock && e.kind() != std::io::ErrorKind::TimedOut {
                        thread::sleep(Duration::from_millis(100));
                    }
                }
            }
        }
    }

    fn beacon(&self) -> String {
        let c = self.cfg.lock().unwrap();
        let name: String = c.pc_name.chars().filter(|ch| *ch != '|').take(40).collect();
        format!("CBV1|{}|{}|{}", c.pc_id, proto::TCP_PORT, name)
    }

    // ---------------- TCP ----------------
    fn accept_loop(self: Arc<Self>, listener: TcpListener) {
        self.log(format!("TCP {} ready", proto::TCP_PORT));
        for conn in listener.incoming() {
            match conn {
                Ok(stream) => {
                    let me = self.clone();
                    thread::spawn(move || me.handle(stream));
                }
                Err(_) => thread::sleep(Duration::from_millis(200)),
            }
        }
    }

    fn handle(self: Arc<Self>, stream: TcpStream) {
        let addr = match stream.peer_addr() {
            Ok(a) => a,
            Err(_) => return,
        };
        let _ = stream.set_nodelay(true);
        let _ = stream.set_write_timeout(Some(Duration::from_secs(10)));
        let _ = stream.set_read_timeout(Some(Duration::from_secs(20)));
        let mut reader = match stream.try_clone() {
            Ok(r) => r,
            Err(_) => return,
        };
        let mut writer = stream;

        // 1. hello
        let hello = match proto::read_frame(&mut reader) {
            Ok((KIND_PLAIN, p)) => match proto::parse_json(&p) {
                Some(v) if s(&v, "t") == "hello" => v,
                _ => return,
            },
            _ => return,
        };
        let phone_id = s(&hello, "id");
        let phone_name = {
            let n = s(&hello, "name");
            if n.is_empty() { "Phone".to_string() } else { n.chars().take(40).collect() }
        };
        let mode = s(&hello, "mode");
        if phone_id.is_empty() || phone_id.len() > 64 {
            return;
        }

        let nonce = proto::b64e(&proto::random_bytes(16));
        let (pc_id, pc_name, salt_b64, pin, known) = {
            let c = self.cfg.lock().unwrap();
            (c.pc_id.clone(), c.pc_name.clone(), c.salt.clone(), c.pin.clone(), c.phone_key(&phone_id).is_some())
        };
        let locked_secs = self.locked_secs();
        let hi = json!({"t": "hi", "v": proto::PROTO_VERSION, "pc": pc_id, "name": pc_name,
                        "salt": salt_b64, "nonce": nonce, "known": known, "locked": locked_secs});
        if proto::send_plain(&mut writer, &hi).is_err() {
            return;
        }
        if mode == "probe" {
            self.log(format!("test connection from {} ({})", phone_name, addr.ip()));
            return;
        }

        // 2. auth or pair
        let (kind, payload) = match proto::read_frame(&mut reader) {
            Ok(x) => x,
            Err(_) => return,
        };
        if kind != KIND_ENC {
            return;
        }
        let session_key: [u8; 32];
        if mode == "pair" {
            if locked_secs > 0 {
                let _ = proto::send_plain(&mut writer, &json!({"t": "pair_fail", "reason": "locked", "secs": locked_secs}));
                return;
            }
            let salt = proto::b64d(&salt_b64).unwrap_or_default();
            let pk = proto::pin_key(&pin, &salt);
            let ok = proto::decrypt_json(&pk, &payload)
                .filter(|v| s(v, "t") == "pair" && s(v, "nonce") == nonce);
            if ok.is_none() {
                let n = self.register_fail();
                self.log(format!("wrong PIN from {} ({}) [{}/{}]", phone_name, addr.ip(), n, MAX_FAILS));
                let _ = proto::send_plain(&mut writer, &json!({"t": "pair_fail", "reason": "pin"}));
                return;
            }
            *self.fails.lock().unwrap() = (0, None);
            let newkey: [u8; 32] = proto::key_from_vec(&proto::random_bytes(32)).unwrap();
            {
                let mut c = self.cfg.lock().unwrap();
                c.phones.retain(|p| p.id != phone_id);
                c.phones.push(Phone { id: phone_id.clone(), name: phone_name.clone(), key: proto::b64e(&newkey) });
                c.save(&self.dir);
            }
            let reply = json!({"t": "pair_ok", "key": proto::b64e(&newkey), "pc": pc_id, "name": pc_name});
            if proto::send_enc(&mut writer, &pk, &reply).is_err() {
                return;
            }
            (self.notify)(Event::Paired(phone_name.clone()));
            session_key = newkey;
        } else {
            let key = match self.cfg.lock().unwrap().phone_key(&phone_id) {
                Some(k) => k,
                None => {
                    let _ = proto::send_plain(&mut writer, &json!({"t": "auth_fail", "reason": "unknown"}));
                    self.log(format!("unknown phone {} ({}) — pair again", phone_name, addr.ip()));
                    return;
                }
            };
            let ok = proto::decrypt_json(&key, &payload)
                .filter(|v| s(v, "t") == "auth" && s(v, "nonce") == nonce);
            if ok.is_none() {
                let _ = proto::send_plain(&mut writer, &json!({"t": "auth_fail", "reason": "key"}));
                self.log(format!("auth failed {} ({})", phone_name, addr.ip()));
                return;
            }
            if proto::send_enc(&mut writer, &key, &json!({"t": "auth_ok", "name": pc_name})).is_err() {
                return;
            }
            session_key = key;
        }

        // 3. session
        let _ = reader.set_read_timeout(Some(Duration::from_secs(70)));
        let ses = Arc::new(Session {
            phone_id: phone_id.clone(),
            name: phone_name.clone(),
            addr,
            key: session_key,
            writer: Mutex::new(writer),
        });
        {
            let mut list = self.sessions.lock().unwrap();
            // a phone reconnecting replaces its old (dead) session
            for old in list.iter().filter(|o| o.phone_id == phone_id) {
                old.kill();
            }
            list.retain(|o| o.phone_id != phone_id);
            list.push(ses.clone());
        }
        self.log(format!("connected: {} ({})", phone_name, addr.ip()));
        (self.notify)(Event::Sessions);

        loop {
            let (kind, payload) = match proto::read_frame(&mut reader) {
                Ok(x) => x,
                Err(_) => break,
            };
            if kind == proto::KIND_FILE {
                if let Some((h, data)) = proto::decrypt_file(&session_key, &payload) {
                    let _ = ses.send(&json!({"t": "ack", "id": s(&h, "id")}));
                    if s(&h, "t") == "img" && !data.is_empty() && data.len() <= proto::MAX_IMAGE_BYTES
                        && !self.paused.load(Ordering::SeqCst)
                    {
                        let mime = s(&h, "mime");
                        (self.notify)(Event::Image(data, if mime.is_empty() { "image/png".into() } else { mime }));
                    }
                }
                continue;
            }
            if kind != KIND_ENC {
                continue;
            }
            let v = match proto::decrypt_json(&session_key, &payload) {
                Some(v) => v,
                None => continue,
            };
            match s(&v, "t").as_str() {
                "ping" => {
                    if ses.send(&json!({"t": "pong"})).is_err() {
                        break;
                    }
                }
                "clip" => {
                    let text = s(&v, "text");
                    let _ = ses.send(&json!({"t": "ack", "id": s(&v, "id")}));
                    if !text.is_empty() && text.chars().count() <= proto::MAX_TEXT_CHARS {
                        if !self.paused.load(Ordering::SeqCst) {
                            (self.notify)(Event::Clip(text));
                        }
                    }
                }
                "bye" => break,
                _ => {}
            }
        }
        {
            let mut list = self.sessions.lock().unwrap();
            list.retain(|o| !Arc::ptr_eq(o, &ses));
        }
        ses.kill();
        self.log(format!("disconnected: {}", phone_name));
        (self.notify)(Event::Sessions);
    }

    fn locked_secs(&self) -> u64 {
        let mut f = self.fails.lock().unwrap();
        if let Some(t) = f.1 {
            let el = t.elapsed().as_secs();
            if el < LOCK_SECS {
                return LOCK_SECS - el;
            }
            *f = (0, None);
        }
        0
    }

    fn register_fail(&self) -> u32 {
        let mut f = self.fails.lock().unwrap();
        f.0 += 1;
        let n = f.0;
        if f.0 >= MAX_FAILS {
            f.1 = Some(Instant::now());
        }
        n
    }
}

/// IPv4 addresses of this PC usable by phones (private LAN first).
pub fn lan_ips() -> Vec<Ipv4Addr> {
    let mut v: Vec<Ipv4Addr> = vec![];
    if let Ok(list) = if_addrs::get_if_addrs() {
        for i in list {
            if let IpAddr::V4(ip) = i.ip() {
                if !ip.is_loopback() && !ip.is_link_local() && !v.contains(&ip) {
                    v.push(ip);
                }
            }
        }
    }
    v.sort_by_key(|ip| {
        let o = ip.octets();
        if o[0] == 192 && o[1] == 168 { 0 } else if o[0] == 10 { 1 } else if o[0] == 172 && (16..32).contains(&o[1]) { 2 } else { 3 }
    });
    v
}

fn broadcast_targets() -> Vec<Ipv4Addr> {
    let mut v = vec![Ipv4Addr::BROADCAST];
    if let Ok(list) = if_addrs::get_if_addrs() {
        for i in list {
            if let if_addrs::IfAddr::V4(a) = i.addr {
                if a.ip.is_loopback() || a.ip.is_link_local() {
                    continue;
                }
                let b = match a.broadcast {
                    Some(b) => b,
                    None => {
                        let ip = u32::from(a.ip);
                        let m = u32::from(a.netmask);
                        Ipv4Addr::from(ip | !m)
                    }
                };
                if !v.contains(&b) {
                    v.push(b);
                }
            }
        }
    }
    v
}
