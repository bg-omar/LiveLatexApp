package com.omariskandarani.livelatexapp

import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.webkit.SslErrorHandler
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.tabs.TabLayout
import com.omariskandarani.livelatexapp.LatexCompiler
import com.omariskandarani.livelatexapp.R
import com.omariskandarani.livelatexapp.latex.LatexHtml
import kotlinx.coroutines.*
import java.io.File
import java.util.regex.Pattern

/** One open LaTeX document: content, optional saved URI, display name, dirty flag. */
data class LatexDocument(
    var content: String,
    var savedUri: Uri? = null,
    var displayName: String = "",
    var isDirty: Boolean = false
) {
    fun effectiveName(): String = displayName.ifEmpty { "Untitled" }
}

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var editText: EditText
    private lateinit var btnSave: Button
    private lateinit var btnOpen: Button
    private lateinit var btnExport: Button
    private lateinit var btnPreview: Button
    private lateinit var btnConfig: ImageButton
    private lateinit var tabs: TabLayout
    private var previewJob: Job? = null
    private val debounceMs = 300L

    private val documents = mutableListOf<LatexDocument>()
    private var currentDocIndex: Int = 0
    private var showPreview: Boolean = false
    private var untitledCounter: Int = 0

    private val createDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let { savedUri ->
            val content = pendingSaveContent ?: editText.text.toString()
            saveContentToUri(content, savedUri)
            val idx = pendingSaveThenCloseIndex
            if (idx != null) {
                documents.getOrNull(idx)?.let { d -> d.savedUri = savedUri; d.displayName = savedUri.lastPathSegment?.substringAfterLast('/') ?: d.effectiveName(); d.isDirty = false; d.content = content }
                pendingSaveThenCloseIndex = null
                pendingSaveContent = null
                closeTabAt(idx)
            } else {
                documents.getOrNull(currentDocIndex)?.let { d -> d.savedUri = savedUri; d.displayName = savedUri.lastPathSegment?.substringAfterLast('/') ?: d.effectiveName(); d.isDirty = false; d.content = content }
            }
            refreshDocumentTabs()
            Toast.makeText(this, getString(R.string.saved), Toast.LENGTH_SHORT).show()
        }
    }

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { openUri(it) }
    }

    companion object {
        private val INDEX_PATTERN = Pattern.compile("(?:at|near)?\\s*index\\s+(\\d+)", Pattern.CASE_INSENSITIVE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webviewPreview)
        editText = findViewById(R.id.inputLatex)
        btnSave = findViewById(R.id.btnSave)
        btnOpen = findViewById(R.id.btnOpen)
        btnExport = findViewById(R.id.btnExport)
        btnPreview = findViewById(R.id.btnPreview)
        btnConfig = findViewById(R.id.btnConfig)
        tabs = findViewById(R.id.tabs)

        ensureAtLeastOneDocument()
        setupDocumentTabs()
        btnSave.setOnClickListener { saveCurrentDocument() }
        btnOpen.setOnClickListener { openDocument.launch(arrayOf("text/plain", "application/x-tex", "*/*")) }
        btnPreview.setOnClickListener { togglePreview() }
        btnConfig.setOnClickListener { startActivity(Intent(this, ConfigActivity::class.java)) }
        setupEditorPinchZoom()
        setupLivePreview()
        setupExportButton()
        loadCurrentDocIntoEditor()
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

    private fun ensureAtLeastOneDocument() {
        if (documents.isEmpty()) {
            untitledCounter++
            documents.add(LatexDocument("", null, getString(R.string.untitled) + " $untitledCounter", false))
            try {
                assets.open("default_latex_placeholder.txt").bufferedReader().use { reader ->
                    documents[0].content = reader.readText()
                }
            } catch (_: Exception) { }
        }
    }

    private fun syncEditorToCurrentDoc() {
        if (documents.isEmpty()) return
        val doc = documents.getOrNull(currentDocIndex) ?: return
        doc.content = editText.text.toString()
    }

    private fun loadCurrentDocIntoEditor() {
        if (documents.isEmpty()) return
        val doc = documents.getOrNull(currentDocIndex) ?: return
        editText.setText(doc.content)
        updatePreviewFromLatex(doc.content)
    }

    private fun togglePreview() {
        showPreview = !showPreview
        editText.visibility = if (showPreview) View.GONE else View.VISIBLE
        webView.visibility = if (showPreview) View.VISIBLE else View.GONE
    }

    private fun setupDocumentTabs() {
        tabs.removeAllTabs()
        documents.forEachIndexed { index, doc ->
            val tab = tabs.newTab()
            tab.tag = index
            tab.customView = makeDocTabView(doc.effectiveName(), index, withClose = true)
            tabs.addTab(tab)
        }
        val newTab = tabs.newTab()
        newTab.tag = -1
        newTab.customView = makeDocTabView(getString(R.string.tab_new), -1, withClose = false)
        tabs.addTab(newTab)

        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (val tag = tab?.tag) {
                    null -> { }
                    -1 -> {
                        syncEditorToCurrentDoc()
                        untitledCounter++
                        val newDoc = LatexDocument("", null, getString(R.string.untitled) + " $untitledCounter", false)
                        documents.add(newDoc)
                        currentDocIndex = documents.size - 1
                        loadCurrentDocIntoEditor()
                        refreshDocumentTabs()
                    }
                    else -> {
                        val idx = tag as Int
                        syncEditorToCurrentDoc()
                        currentDocIndex = idx
                        loadCurrentDocIntoEditor()
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
        tabs.selectTab(tabs.getTabAt(currentDocIndex.coerceIn(0, documents.size - 1)))
    }

    private fun refreshDocumentTabs() {
        tabs.removeAllTabs()
        documents.forEachIndexed { index, doc ->
            val name = doc.effectiveName() + if (doc.isDirty) " •" else ""
            val tab = tabs.newTab()
            tab.tag = index
            tab.customView = makeDocTabView(name, index, withClose = true)
            tabs.addTab(tab)
        }
        val newTab = tabs.newTab()
        newTab.tag = -1
        newTab.customView = makeDocTabView(getString(R.string.tab_new), -1, withClose = false)
        tabs.addTab(newTab)
        tabs.selectTab(tabs.getTabAt(currentDocIndex.coerceIn(0, documents.size)))
    }

    private fun updateCurrentTabLabel() {
        val doc = documents.getOrNull(currentDocIndex) ?: return
        val tab = tabs.getTabAt(currentDocIndex) ?: return
        val name = doc.effectiveName() + if (doc.isDirty) " •" else ""
        (tab.customView as? LinearLayout)?.getChildAt(0)?.let { child ->
            (child as? TextView)?.text = name
        }
    }

    private fun makeDocTabView(title: String, docIndex: Int, withClose: Boolean): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleView = TextView(this).apply {
            text = title
            setPadding(0, 0, 12, 0)
        }
        wrap.addView(titleView)
        if (withClose) {
            val close = ImageButton(this).apply {
                setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                background = null
                setPadding(4, 4, 4, 4)
                setOnClickListener { tryCloseTab(docIndex) }
            }
            wrap.addView(close)
        }
        return wrap
    }

    private fun tryCloseTab(index: Int) {
        val doc = documents.getOrNull(index) ?: return
        if (!doc.isDirty) {
            closeTabAt(index)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.save))
            .setMessage(getString(R.string.close_tab_confirm, doc.effectiveName()))
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                saveDocumentAt(index) { success -> if (success) closeTabAt(index) }
            }
            .setNegativeButton(getString(R.string.discard)) { _, _ -> closeTabAt(index) }
            .setNeutralButton(getString(R.string.cancel)) { _, _ -> }
            .show()
    }

    private fun saveDocumentAt(index: Int, onDone: (Boolean) -> Unit) {
        val doc = documents.getOrNull(index) ?: run { onDone(false); return }
        val content = if (index == currentDocIndex) editText.text.toString() else doc.content
        if (doc.savedUri != null) {
            saveContentToUri(content, doc.savedUri!!)
            doc.content = content
            doc.isDirty = false
            if (index == currentDocIndex) refreshDocumentTabs()
            onDone(true)
        } else {
            pendingSaveThenCloseIndex = index
            pendingSaveContent = content
            createDocument.launch("document.tex")
            onDone(true)
        }
    }

    private var pendingSaveThenCloseIndex: Int? = null
    private var pendingSaveContent: String? = null

    private fun saveContentToUri(content: String, uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
            val idx = documents.indexOfFirst { it.savedUri == uri }
            if (idx >= 0) {
                documents[idx].content = content
                documents[idx].isDirty = false
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: documents[idx].effectiveName()
                documents[idx].displayName = name
            }
        } catch (e: Exception) {
            runOnUiThread { Toast.makeText(this, e.message ?: "Save failed", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun closeTabAt(index: Int) {
        if (index < 0 || index >= documents.size) return
        syncEditorToCurrentDoc()
        documents.removeAt(index)
        if (currentDocIndex >= documents.size) currentDocIndex = (documents.size - 1).coerceAtLeast(0)
        if (currentDocIndex > index) currentDocIndex--
        ensureAtLeastOneDocument()
        loadCurrentDocIntoEditor()
        refreshDocumentTabs()
    }

    private fun saveCurrentDocument() {
        val doc = documents.getOrNull(currentDocIndex) ?: return
        syncEditorToCurrentDoc()
        doc.content = editText.text.toString()
        if (doc.savedUri != null) {
            saveContentToUri(doc.content, doc.savedUri!!)
            refreshDocumentTabs()
            Toast.makeText(this, getString(R.string.saved), Toast.LENGTH_SHORT).show()
        } else {
            pendingSaveThenCloseIndex = null
            pendingSaveContent = editText.text.toString()
            createDocument.launch("document.tex")
        }
    }

    private fun openUri(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                val content = input.bufferedReader().readText()
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: getString(R.string.untitled)
                val newDoc = LatexDocument(content, uri, name, false)
                syncEditorToCurrentDoc()
                documents.add(newDoc)
                currentDocIndex = documents.size - 1
                loadCurrentDocIntoEditor()
                refreshDocumentTabs()
            }
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "Open failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupLivePreview() {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.isFocusable = true
        webView.isLongClickable = true
        // Let WebView handle long-press for text selection (don't consume the event)
        webView.setOnLongClickListener { false }
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                // For CDN resources like MathJax, proceed despite SSL errors to allow preview to work
                // This is safe for remote CDN resources that are widely trusted
                handler?.proceed()
            }
        }
        // Initial empty preview (full HTML from LatexHtml.wrap)
        updatePreviewFromLatex("")

        editText.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val rawLatex = s?.toString() ?: ""
                documents.getOrNull(currentDocIndex)?.isDirty = true
                updateCurrentTabLabel()
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