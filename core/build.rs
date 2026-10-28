fn main() {
    let target_os = std::env::var("CARGO_CFG_TARGET_OS").unwrap_or_default();
    // unrar's Windows backend uses the registry and crypto APIs directly, but
    // the vendored build does not declare them. Check the target, not the host,
    // so Android cross-builds from Windows stay clean.
    if target_os == "windows" {
        println!("cargo:rustc-link-lib=advapi32");
        println!("cargo:rustc-link-lib=crypt32");
        println!("cargo:rustc-link-lib=userenv");
    }
}
