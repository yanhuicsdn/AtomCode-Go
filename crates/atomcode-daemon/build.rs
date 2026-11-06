fn main() {
    // RustEmbed reads the epoch inside its procedural macro, outside Cargo's
    // automatic env tracking. Also watch added assets and a previously absent dist.
    println!("cargo:rerun-if-env-changed=SOURCE_DATE_EPOCH");
    println!("cargo:rerun-if-changed=../../webui/dist");
}
