use std::process::Command;
fn main() {
    println!("cargo:rerun-if-changed=res");
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() != Ok("windows") {
        return;
    }
    let out = std::env::var("OUT_DIR").unwrap();
    let obj = format!("{}/app_res.o", out);
    let windres = std::env::var("WINDRES").unwrap_or_else(|_| "x86_64-w64-mingw32-windres".into());
    let st = Command::new(&windres)
        .current_dir("res")
        .args(["app.rc", "-O", "coff", "-o", &obj])
        .status()
        .expect("windres not found");
    assert!(st.success(), "windres failed");
    println!("cargo:rustc-link-arg={}", obj);
}
