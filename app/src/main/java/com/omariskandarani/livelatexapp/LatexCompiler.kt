package com.omariskandarani.livelatexapp

object LatexCompiler {
    init {
        // This must match the name of your Rust output library
        System.loadLibrary("rust_core")
    }

    external fun compilePdf(latexSource: String, outputPath: String, cachePath: String): Boolean
}