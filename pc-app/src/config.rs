//! Persistent settings: %APPDATA%\ClipboardBridgeV1\config.json
//! Holds PC id, the permanent PIN + salt (changes only on "New PIN"), paired phones and language.
//! Clipboard contents are never written to disk.

use crate::proto;
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct Phone {
    pub id: String,
    pub name: String,
    pub key: String, // base64 32-byte AES key
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct Config {
    pub pc_id: String,
    pub pc_name: String,
    pub pin: String,
    pub salt: String,
    #[serde(default)]
    pub phones: Vec<Phone>,
    #[serde(default = "default_lang")]
    pub lang: String,
    /// true once the user pressed the language button (otherwise English is used)
    #[serde(default)]
    pub lang_chosen: bool,
}

fn default_lang() -> String {
    "en".to_string()
}

pub fn config_dir() -> PathBuf {
    if let Ok(d) = std::env::var("CB_HOME") {
        return PathBuf::from(d);
    }
    #[cfg(windows)]
    {
        if let Ok(a) = std::env::var("APPDATA") {
            return PathBuf::from(a).join("ClipboardBridgeV1");
        }
    }
    std::env::current_dir().unwrap_or_else(|_| PathBuf::from(".")).join("cb-home")
}

pub fn host_name() -> String {
    for k in ["COMPUTERNAME", "HOSTNAME"] {
        if let Ok(v) = std::env::var(k) {
            if !v.trim().is_empty() {
                return v.trim().to_string();
            }
        }
    }
    if let Ok(v) = std::fs::read_to_string("/etc/hostname") {
        if !v.trim().is_empty() {
            return v.trim().to_string();
        }
    }
    "PC".to_string()
}

impl Config {
    pub fn fresh() -> Config {
        Config {
            pc_id: proto::random_id(),
            pc_name: host_name(),
            pin: proto::random_pin(),
            salt: proto::b64e(&proto::random_bytes(16)),
            phones: vec![],
            lang: default_lang(),
            lang_chosen: false,
        }
    }

    fn valid(&self) -> bool {
        self.pin.len() == 6
            && self.pin.chars().all(|c| c.is_ascii_digit())
            && proto::b64d(&self.salt).map(|s| s.len() == 16).unwrap_or(false)
            && !self.pc_id.is_empty()
    }

    pub fn load(dir: &Path) -> Config {
        let p = dir.join("config.json");
        if let Ok(txt) = std::fs::read_to_string(&p) {
            if let Ok(mut c) = serde_json::from_str::<Config>(&txt) {
                if c.valid() {
                    c.pc_name = host_name();
                    c.phones.retain(|ph| {
                        proto::b64d(&ph.key).map(|k| k.len() == 32).unwrap_or(false)
                    });
                    if !c.lang_chosen || c.lang != "bn" {
                        c.lang = "en".into();
                    }
                    return c;
                }
            }
        }
        let c = Config::fresh();
        c.save(dir);
        c
    }

    pub fn save(&self, dir: &Path) {
        let _ = std::fs::create_dir_all(dir);
        let tmp = dir.join("config.json.tmp");
        let dst = dir.join("config.json");
        if let Ok(txt) = serde_json::to_string_pretty(self) {
            if std::fs::write(&tmp, txt).is_ok() {
                let _ = std::fs::remove_file(&dst);
                let _ = std::fs::rename(&tmp, &dst);
            }
        }
    }

    pub fn phone_key(&self, id: &str) -> Option<[u8; 32]> {
        self.phones
            .iter()
            .find(|p| p.id == id)
            .and_then(|p| proto::b64d(&p.key))
            .and_then(|k| proto::key_from_vec(&k))
    }
}
