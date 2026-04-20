# Offline LaTeX native engine (Rust / Tectonic)

The shipped JNI library in `../src/main/cpp/livelatex_jni.cpp` is a **stub**. To produce a real offline pipeline:

1. Create a Rust `cdylib` crate (e.g. `livelatex_engine`) that depends on [`tectonic`](https://crates.io/crates/tectonic) and exposes C ABI symbols called from JNI (or call Tectonic’s Rust API from `livelatex_jni.cpp` via a static Rust lib).
2. Cross-compile with [cargo-ndk](https://github.com/bbqsrc/cargo-ndk) for `arm64-v8a` and `x86_64`:
   ```bash
   cargo ndk -t arm64-v8a -o ../jniLibs build --release
   ```
3. Ship the TeX format **bundle** Tectonic expects (see Tectonic docs for cache/bundle layout) — either in `assets/` and extract on first launch, or embed in the APK as a split feature module.
4. Implement `nativePdfToSvg` by linking **dvisvgm**, **MuPDF** (`mutool`), or another PDF→SVG tool built for Android NDK.

Until those steps are done, Kotlin falls back to [PdfFirstPagePngEmbedder](../src/main/java/com/omariskandarani/livelatexapp/latex/engine/PdfFirstPagePngEmbedder.kt) **only after** a valid `fig.pdf` exists (i.e. after native compile succeeds).
