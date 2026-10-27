//! On Windows, compiles the icon resource into the .exe with windres (part of the MinGW toolchain).

use std::path::PathBuf;
use std::process::Command;

fn main() {
    println!("cargo:rerun-if-changed=../packaging/windows/gbook.rc");
    println!("cargo:rerun-if-changed=../packaging/windows/gbook.ico");
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() != Ok("windows") {
        return;
    }
    let out = PathBuf::from(std::env::var("OUT_DIR").unwrap()).join("gbook-res.o");
    let rc = PathBuf::from(std::env::var("CARGO_MANIFEST_DIR").unwrap())
        .join("../packaging/windows/gbook.rc");
    let windres = std::env::var("WINDRES").unwrap_or_else(|_| "windres".into());
    let ok = Command::new(&windres)
        .arg(&rc)
        .args(["-O", "coff", "-o"])
        .arg(&out)
        .status()
        .map(|s| s.success())
        .unwrap_or(false);
    if ok {
        println!("cargo:rustc-link-arg-bins={}", out.display());
    } else {
        println!("cargo:warning=windres failed; the .exe will have no icon");
    }
}
