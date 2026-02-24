package com.omariskandarani.livelatexapp

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.tabs.TabLayout
import com.omariskandarani.livelatexapp.LatexCompiler
import com.omariskandarani.livelatexapp.R
import com.omariskandarani.livelatexapp.latex.LatexHtml
import kotlinx.coroutines.*
import java.io.File
import java.util.regex.Pattern

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var editText: EditText
    private lateinit var btnExport: Button
    private lateinit var btnConfig: ImageButton
    private lateinit var tabs: TabLayout
    private var previewJob: Job? = null
    private val debounceMs = 300L

    companion object {
        private val INDEX_PATTERN = Pattern.compile("(?:at|near)?\\s*index\\s+(\\d+)", Pattern.CASE_INSENSITIVE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webviewPreview)
        editText = findViewById(R.id.inputLatex)
        btnExport = findViewById(R.id.btnExport)
        btnConfig = findViewById(R.id.btnConfig)
        tabs = findViewById(R.id.tabs)

        setupTabs()
        btnConfig.setOnClickListener { startActivity(Intent(this, ConfigActivity::class.java)) }
        setupEditorPinchZoom()
        setupLivePreview()
        setupExportButton()
        loadDefaultPlaceholder()
    }

    private fun setupEditorPinchZoom() {
        applyEditorFontSize()

        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                var sizeSp = EditorPrefs.getEditorFontSizeSp(this@MainActivity)
                sizeSp *= detector.scaleFactor
                sizeSp = sizeSp.coerceIn(EditorPrefs.MIN_FONT_SIZE_SP, EditorPrefs.MAX_FONT_SIZE_SP)
                EditorPrefs.setEditorFontSizeSp(this@MainActivity, sizeSp)
                editText.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
                return true
            }
        })

        editText.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            false
        }
    }

    private fun applyEditorFontSize() {
        editText.setTextSize(TypedValue.COMPLEX_UNIT_SP, EditorPrefs.getEditorFontSizeSp(this))
    }

    override fun onResume() {
        super.onResume()
        if (::editText.isInitialized) applyEditorFontSize()
    }

    /** Temporary: load testfile-style content as initial editor text. */
    private fun loadDefaultPlaceholder() {
        try {
            assets.open("default_latex_placeholder.txt").bufferedReader().use { reader ->
                editText.setText(reader.readText())
            }
        } catch (_: Exception) { /* ignore if asset missing */ }
    }

    private fun setupTabs() {
        tabs.addTab(tabs.newTab().setText(getString(R.string.tab_editor)))
        tabs.addTab(tabs.newTab().setText(getString(R.string.tab_preview)))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> { editText.visibility = View.VISIBLE; webView.visibility = View.GONE }
                    1 -> { editText.visibility = View.GONE; webView.visibility = View.VISIBLE }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
        // Start on Editor
        editText.visibility = View.VISIBLE
        webView.visibility = View.GONE
    }

    private fun setupLivePreview() {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.isFocusable = true
        webView.isLongClickable = true
        // Let WebView handle long-press for text selection (don't consume the event)
        webView.setOnLongClickListener { false }
        webView.webViewClient = WebViewClient()
        // Initial empty preview (full HTML from LatexHtml.wrap)
        updatePreviewFromLatex("")

        editText.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val rawLatex = s?.toString() ?: ""
                previewJob?.cancel()
                previewJob = CoroutineScope(Dispatchers.Main).launch {
                    delay(debounceMs)
                    updatePreviewFromLatex(rawLatex)
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun updatePreviewFromLatex(latexCode: String) {
        CoroutineScope(Dispatchers.Default).launch {
            var wrapped = ""
            val html = try {
                wrapped = if (latexCode.isBlank()) {
                    """
                    \documentclass{article}
                    \begin{document}
                    (Type LaTeX above; preview updates as you type.)
                    \end{document}
                    """.trimIndent()
                } else {
                    if (!latexCode.contains("\\begin{document}")) {
                        """
                        \documentclass{article}
                        \begin{document}
                        $latexCode
                        \end{document}
                        """.trimIndent()
                    } else latexCode
                }
                LatexHtml.wrap(wrapped)
            } catch (e: Exception) {
                val lineNum = previewErrorLineNumber(e, wrapped)
                val lineInfo = if (lineNum != null) " (line $lineNum)" else ""
                val msg = (e.message ?: e.toString()).replace("<", "&lt;").replace(">", "&gt;")
                """<!DOCTYPE html><html><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1"></head><body style="user-select: text; -webkit-user-select: text; -webkit-touch-callout: default;"><div style="user-select: text; -webkit-user-select: text; -webkit-touch-callout: default; padding: 1em;"><p style="color:#b91c1c; white-space: pre-wrap; user-select: text; -webkit-user-select: text; -webkit-touch-callout: default;">Preview error$lineInfo: $msg</p></div></body></html>"""
            }
            withContext(Dispatchers.Main) {
                webView.loadDataWithBaseURL(
                    "https://example.com/",
                    html,
                    "text/html",
                    "UTF-8",
                    null
                )
            }
        }
    }

    /**
     * Tries to infer a source line number from the exception (e.g. regex "at index N")
     * and the LaTeX string that was being processed.
     */
    private fun previewErrorLineNumber(e: Exception, latex: String): Int? {
        if (latex.isEmpty()) return null
        val msg = e.message ?: return null
        // e.g. "at index 34" or "near index 15" (Android regex errors)
        val m = INDEX_PATTERN.matcher(msg)
        if (!m.find()) return null
        val index = m.group(1)?.toIntOrNull() ?: return null
        val safeIndex = index.coerceIn(0, latex.length)
        val line = latex.take(safeIndex).count { it == '\n' } + 1
        return line
    }

    private fun setupExportButton() {
        btnExport.setOnClickListener {
            val latexCode = editText.text.toString()
            if (latexCode.isBlank()) return@setOnClickListener

            btnExport.isEnabled = false
            btnExport.text = getString(R.string.compiling_and_downloading)

            // Run Heavy Rust compilation in the background
            CoroutineScope(Dispatchers.IO).launch {

                // Define output path
                val exportDir = File(filesDir, "exports")
                exportDir.mkdirs()
                val outputFile = File(exportDir, "document.pdf")

                // Define cache path (Tectonic needs this to save downloaded packages)
                val cacheDirPath = cacheDir.absolutePath

                // CALL THE RUST ENGINE
                val success = LatexCompiler.compilePdf(
                    latexCode,
                    outputFile.absolutePath,
                    cacheDirPath
                )

                // Return to Main Thread to update UI
                withContext(Dispatchers.Main) {
                    btnExport.isEnabled = true
                    btnExport.text = getString(R.string.export_to_pdf)

                    if (success) {
                        Toast.makeText(this@MainActivity, getString(R.string.pdf_saved, outputFile.name), Toast.LENGTH_LONG).show()
                        // Here you could launch an Intent to view the PDF
                    } else {
                        Toast.makeText(this@MainActivity, getString(R.string.compilation_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}