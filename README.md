# rust_core — LaTeX to PDF (Tectonic) for Android

This crate exposes a JNI function `compilePdf(latexSource, outputPath, cachePath)` for the LiveLatexApp Android app, using the [Tectonic](https://tectonic-typesetting.github.io/) engine.

## Building for Android

Tectonic depends on native libraries (ICU, Harfbuzz, Graphite2, FreeType, etc.). To build this crate for Android:

1. **Install Android NDK** and ensure `ndk-build` or `cargo-ndk` is available.

2. **Install dependencies** using one of the approaches from [Tectonic's build docs](https://tectonic-typesetting.github.io/book/latest/howto/build-tectonic/):
   - **vcpkg (Windows)**: `cargo install cargo-vcpkg`, then `cargo vcpkg build`, set `TECTONIC_DEP_BACKEND=vcpkg` and `VCPKG_ROOT`, then build for your host first to verify.
   - **System packages (Linux)**: Install `libharfbuzz-dev`, `libgraphite2-dev`, `libfreetype6-dev`, `libicu-dev`, etc., then build.

3. **Cross-compile for Android** (e.g. aarch64-linux-android, armv7-linux-androideabi, i686-linux-android, x86_64-linux-android) using `cargo-ndk` or a custom NDK toolchain. Copy the resulting `*.so` files into `app/src/main/jniLibs/<abi>/librust_core.so`.

## JNI

The Java/Kotlin class must be exactly:

- Package: `com.omariskandarani.livelatexapp`
- Class: `LatexCompiler`
- Method: `external fun compilePdf(latexSource: String, outputPath: String, cachePath: String): Boolean`

The native symbol is `Java_com_omariskandarani_livelatexapp_LatexCompiler_compilePdf`.
