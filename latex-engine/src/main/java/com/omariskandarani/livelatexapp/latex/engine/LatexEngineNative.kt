package com.omariskandarani.livelatexapp.latex.engine

/**
 * JNI bridge to `liblivelatex_engine.so`. Stub returns [ERR_NOT_BUILT] until Tectonic/TeX are linked in native code.
 */
object LatexEngineNative {

    const val ERR_OK = 0
    const val ERR_NOT_BUILT = -1
    const val ERR_TEXLIVE_NOT_IMPLEMENTED = -2
    const val ERR_INVALID_ARGS = -3

    @Volatile
    private var loadAttempted = false

    @Volatile
    private var libraryLoaded = false

    fun isLibraryLoaded(): Boolean {
        ensureLoaded()
        return libraryLoaded
    }

    private fun ensureLoaded() {
        if (loadAttempted) return
        synchronized(this) {
            if (loadAttempted) return
            loadAttempted = true
            try {
                System.loadLibrary("livelatex_engine")
                libraryLoaded = true
            } catch (_: UnsatisfiedLinkError) {
                libraryLoaded = false
            }
        }
    }

    fun nativeGetBuildInfo(): String {
        ensureLoaded()
        if (!libraryLoaded) return "Native library not loaded"
        return try {
            nativeGetBuildInfoImpl()
        } catch (e: UnsatisfiedLinkError) {
            e.message ?: "UnsatisfiedLinkError"
        }
    }

    fun compileTexToPdf(texPath: String, workDir: String, engineKind: LatexEngineKind): Int {
        ensureLoaded()
        if (!libraryLoaded) return ERR_NOT_BUILT
        return nativeCompileTexToPdf(texPath, workDir, engineKind.prefValue)
    }

    fun pdfToSvg(pdfPath: String, svgPath: String): Int {
        ensureLoaded()
        if (!libraryLoaded) return ERR_NOT_BUILT
        return nativePdfToSvg(pdfPath, svgPath)
    }

    @JvmStatic
    private external fun nativeCompileTexToPdf(texPath: String, workDir: String, engineKind: Int): Int

    @JvmStatic
    private external fun nativePdfToSvg(pdfPath: String, svgPath: String): Int

    @JvmStatic
    private external fun nativeGetBuildInfoImpl(): String
}
