package com.omariskandarani.livelatexapp.latex.engine

import android.content.Context
import java.io.File

/**
 * High-level offline LaTeX compile for TikZ preview: `.tex` → PDF (native) → SVG (native or PNG-embedded SVG fallback).
 */
object LatexEngineFacade {

    /** True when JNI loaded and at least one compile API could theoretically succeed (native not returning NOT_BUILT). */
    fun isNativeRuntimeOperational(): Boolean = LatexEngineNative.isLibraryLoaded()

    fun statusSummary(context: Context): String {
        val kind = LatexEnginePrefs.getEngineKind(context)
        val base = LatexEngineNative.nativeGetBuildInfo()
        return buildString {
            append(kind.name)
            append(": ")
            append(base)
            if (!LatexEngineNative.isLibraryLoaded()) {
                append(" (")
                append("lib not loaded")
                append(')')
            }
        }
    }

    /**
     * Compiles [texDocument] written to [workDir]/fig.tex, expects [workDir]/fig.pdf on success,
     * then produces SVG at [outputSvg] (or PNG-in-SVG via [PdfFirstPagePngEmbedder]).
     *
     * @return null if compile or conversion failed
     */
    fun compileStandaloneTikzToSvg(
        context: Context,
        texDocument: String,
        workDir: File,
        outputSvg: File,
    ): File? {
        workDir.mkdirs()
        val texFile = File(workDir, "fig.tex")
        val pdfFile = File(workDir, "fig.pdf")
        val kind = LatexEnginePrefs.getEngineKind(context)
        texFile.writeText(texDocument)

        if (kind == LatexEngineKind.TEX_LIVE) {
            File(workDir, "native_compile.log").writeText(
                "TeX Live JNI backend is not implemented yet; use Tectonic or build the second native target.\n"
            )
            return null
        }

        val compileRc = LatexEngineNative.compileTexToPdf(
            texFile.absolutePath,
            workDir.absolutePath,
            kind
        )
        File(workDir, "native_compile.log").writeText(
            "rc=$compileRc kind=$kind ${LatexEngineNative.nativeGetBuildInfo()}\n"
        )
        if (compileRc != LatexEngineNative.ERR_OK || !pdfFile.exists()) {
            return null
        }

        val svgTmp = File(workDir, "fig_native.svg")
        val svgRc = LatexEngineNative.pdfToSvg(pdfFile.absolutePath, svgTmp.absolutePath)
        if (svgRc == LatexEngineNative.ERR_OK && svgTmp.exists() && svgTmp.length() > 0) {
            svgTmp.copyTo(outputSvg, overwrite = true)
            return outputSvg
        }

        if (PdfFirstPagePngEmbedder.writeSvgWithEmbeddedFirstPage(pdfFile, outputSvg)) {
            return outputSvg
        }
        return null
    }
}
