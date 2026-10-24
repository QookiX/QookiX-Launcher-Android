fn main() {
    tauri_build::try_build(tauri_build::Attributes::new()).expect("failed to run tauri build");
}
