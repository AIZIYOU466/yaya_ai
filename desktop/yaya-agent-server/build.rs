fn main() -> Result<(), Box<dyn std::error::Error>> {
    println!("cargo:rerun-if-changed=../../proto/agent.proto");
    tonic_build::compile("../../proto/agent.proto", &["../../proto"])?;

    if std::env::var("CARGO_FEATURE_FULL_LLAMA").is_ok() {
        match std::env::var("LLAMA_LIB_DIR") {
            Ok(dir) => println!("cargo:rustc-link-search=native={}", dir),
            Err(_) => println!(
                "cargo:warning=full-llama 已启用但 LLAMA_LIB_DIR 未设置，链接阶段可能失败（见 AGENTS.md R4）"
            ),
        }
        println!("cargo:rustc-link-lib=static=llama");
    }
    Ok(())
}
