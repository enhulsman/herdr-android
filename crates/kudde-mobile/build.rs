// Links libghostty-vt statically when the `vt` feature is on. The library comes
// from scripts/build-ghostty.sh, one directory per target under build/ghostty/.
use std::env;
use std::path::PathBuf;

fn main() {
    println!("cargo:rerun-if-changed=build.rs");
    if env::var_os("CARGO_FEATURE_VT").is_none() {
        return;
    }
    let target = env::var("TARGET").expect("cargo sets TARGET");
    let (abi, script_target) = match target.as_str() {
        "aarch64-linux-android" => ("arm64-v8a", "aarch64-linux-android"),
        "x86_64-linux-android" => ("x86_64", "x86_64-linux-android"),
        "x86_64-unknown-linux-gnu" => ("host", "x86_64-linux-gnu"),
        other => panic!("the vt feature has no libghostty-vt build for {other}"),
    };
    let manifest = PathBuf::from(env::var("CARGO_MANIFEST_DIR").expect("cargo sets it"));
    let dir = manifest.join("../../build/ghostty").join(abi);
    let lib = dir.join("libghostty-vt.a");
    assert!(
        lib.exists(),
        "{} is missing: run scripts/build-ghostty.sh {script_target}",
        lib.display()
    );
    println!("cargo:rerun-if-changed={}", lib.display());
    println!("cargo:rustc-link-search=native={}", dir.display());
    println!("cargo:rustc-link-lib=static=ghostty-vt");
}
