package com.omariskandarani.livelatexapp.latex.engine

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * When native PDF→SVG is unavailable, embed the first PDF page as a PNG inside a minimal SVG for WebView preview.
 */
object PdfFirstPagePngEmbedder {

    fun writeSvgWithEmbeddedFirstPage(pdfFile: File, outSvg: File, maxEdgePx: Int = 1600): Boolean {
        if (!pdfFile.exists() || !pdfFile.canRead()) return false
        val rendered = renderFirstPageToPng(pdfFile, maxEdgePx) ?: return false
        val b64 = android.util.Base64.encodeToString(rendered.pngBytes, android.util.Base64.NO_WRAP)
        val w = rendered.width
        val h = rendered.height
        outSvg.parentFile?.mkdirs()
        outSvg.writeText(
            """<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink"
     width="${w}" height="${h}" viewBox="0 0 $w $h">
  <image width="$w" height="$h" xlink:href="data:image/png;base64,$b64"/>
</svg>
""".trimIndent()
        )
        return true
    }

    private data class RenderedPng(val width: Int, val height: Int, val pngBytes: ByteArray)

    private fun renderFirstPageToPng(pdfFile: File, maxEdgePx: Int): RenderedPng? {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        return try {
            pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            if (renderer.pageCount < 1) return null
            renderer.openPage(0).use { page ->
                val srcW = page.width.toFloat()
                val srcH = page.height.toFloat()
                val scale = minOf(maxEdgePx / srcW, maxEdgePx / srcH, 1f).coerceAtLeast(0.1f)
                val w = (srcW * scale).toInt().coerceAtLeast(1)
                val h = (srcH * scale).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val bytes = ByteArrayOutputStream().use { bos ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 92, bos)
                    bmp.recycle()
                    bos.toByteArray()
                }
                RenderedPng(w, h, bytes)
            }
        } catch (_: Exception) {
            null
        } finally {
            try {
                renderer?.close()
            } catch (_: Exception) {
            }
            try {
                pfd?.close()
            } catch (_: Exception) {
            }
        }
    }
}
