package com.omariskandarani.livelatexapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.DocumentsContract
import android.net.http.SslError
import android.os.Bundle
import android.text.Editable
import android.text.Selection
import android.text.TextWatcher
import android.util.TypedValue
import android.view.KeyEvent
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.webkit.SslErrorHandler
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.drawerlayout.widget.DrawerLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.omariskandarani.livelatexapp.BuildConfig
import com.omariskandarani.livelatexapp.R
import com.omariskandarani.livelatexapp.cloud.CloudPrefs
import com.omariskandarani.livelatexapp.cloud.GitHubOAuthHelper
import com.omariskandarani.livelatexapp.cloud.GoogleDriveHelper
import com.omariskandarani.livelatexapp.latex.LatexHtml
import com.omariskandarani.livelatexapp.latex.LatexHighlighter
import kotlinx.coroutines.*
import java.util.ArrayDeque
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
    private lateinit var previewLoadingIndicator: View
    private lateinit var previewErrorBanner: View
    private lateinit var previewErrorBannerText: TextView
    private lateinit var editText: EditText
    private lateinit var editorContainer: View
    private lateinit var lineNumberView: com.omariskandarani.livelatexapp.LineNumberView
    private lateinit var btnMenu: ImageButton
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var drawerMenu: View
    private lateinit var btnPreview: Button
    private lateinit var saveStatusIcon: ImageView
    private lateinit var documentTabsContainer: LinearLayout
    private var previewJob: Job? = null
    private var highlightJob: Job? = null
    private var autoSaveJob: Job? = null
    private var undoPushJob: Job? = null
    private val debounceMs = 300L
    private val undoPushDelayMs = 1500L
    private val maxUndoSize = 50

    private val undoStack = ArrayDeque<String>()
    private val redoStack = ArrayDeque<String>()
    private var isUndoRedoAction = false

    private val documents = mutableListOf<LatexDocument>()
    private var currentDocIndex: Int = 0
    private var showPreview: Boolean = false
    private var untitledCounter: Int = 0
    private var lastPreviewErrorLine: Int? = null
    private var lastPreviewErrorMsg: String? = null

    private val createDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let { savedUri ->
            val content = pendingSaveContent ?: editText.text.toString()
            saveContentToUri(content, savedUri)
            try {
                contentResolver.takePersistableUriPermission(savedUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (_: Exception) { }
            val name = getFileNameFromUri(savedUri)
            RecentFilesPrefs.addRecent(this, savedUri.toString(), name)
            val idx = pendingSaveThenCloseIndex
            if (idx != null) {
                documents.getOrNull(idx)?.let { d -> d.savedUri = savedUri; d.displayName = name; d.isDirty = false; d.content = content }
                pendingSaveThenCloseIndex = null
                pendingSaveContent = null
                closeTabAt(idx)
            } else {
                documents.getOrNull(currentDocIndex)?.let { d -> d.savedUri = savedUri; d.displayName = name; d.isDirty = false; d.content = content }
            }
            refreshDocumentTabs()
            Toast.makeText(this, getString(R.string.saved), Toast.LENGTH_SHORT).show()
        }
    }

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { openUri(it) }
    }

    private val googleSignInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK) {
            GoogleDriveHelper.handleSignInResult(this, data)
        }
    }

    companion object {
        private val INDEX_PATTERN = Pattern.compile("(?:at|near)?\\s*index\\s+(\\d+)", Pattern.CASE_INSENSITIVE)
        private const val MORE_TABS_THRESHOLD = 5
        private const val MORE_TAB_TAG = -2
        private const val SYMBOL_MENU_ID_FIRST = 2000
        private val INSERT_SYMBOLS = listOf(
            "$" to "$", "\\" to "\\", "{" to "{", "}" to "}", "[" to "[", "]" to "]",
            "^" to "^", "_" to "_",
            "\\frac{}{}" to "\\frac{}{}", "\\begin{}…" to "\\begin{}\n\n\\end{}",
            "\\alpha" to "\\alpha", "\\beta" to "\\beta", "\\sum" to "\\sum", "\\int" to "\\int", "\\sqrt{}" to "\\sqrt{}"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCompatDelegate.setDefaultNightMode(EditorPrefs.getNightMode(this))
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webviewPreview)
        previewLoadingIndicator = findViewById(R.id.previewLoadingIndicator)
        previewErrorBanner = findViewById(R.id.previewErrorBanner)
        previewErrorBannerText = findViewById(R.id.previewErrorBannerText)
        val previewToolbar = findViewById<View>(R.id.previewToolbar)
        val btnPreviewRefresh = findViewById<Button>(R.id.btnPreviewRefresh)
        val btnPreviewCopy = findViewById<Button>(R.id.btnPreviewCopy)
        btnPreviewRefresh.contentDescription = getString(R.string.preview_refresh)
        btnPreviewCopy.contentDescription = getString(R.string.preview_copy)
        btnPreviewRefresh.setOnClickListener {
            syncEditorToCurrentDoc()
            updatePreviewFromLatex(editText.text.toString())
        }
        btnPreviewCopy.setOnClickListener {
            webView.evaluateJavascript("(function(){ var s = window.getSelection && window.getSelection().toString(); return s && s.length ? s : document.body ? document.body.innerText : ''; })();") { result ->
                val text = result?.trim('"')?.replace("\\n", "\n")?.replace("\\\"", "\"") ?: ""
                if (text.isNotEmpty()) {
                    (getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager)?.setPrimaryClip(android.content.ClipData.newPlainText("preview", text))
                    Toast.makeText(this@MainActivity, getString(R.string.preview_copy), Toast.LENGTH_SHORT).show()
                }
            }
        }
        editorContainer = findViewById(R.id.editorContainer)
        editText = findViewById(R.id.inputLatex)
        lineNumberView = findViewById(R.id.lineNumbers)
        btnMenu = findViewById(R.id.btnMenu)
        drawerLayout = findViewById(R.id.drawerLayout)
        drawerMenu = findViewById(R.id.drawerMenu)
        btnPreview = findViewById(R.id.btnPreview)
        documentTabsContainer = findViewById(R.id.documentTabsContainer)
        saveStatusIcon = findViewById(R.id.saveStatusIcon)
        saveStatusIcon.setOnClickListener {
            val doc = documents.getOrNull(currentDocIndex)
            val isDirty = doc?.isDirty == true
            Toast.makeText(
                this,
                if (isDirty) getString(R.string.unsaved_changes) + ". " + getString(R.string.save_hint_menu) else getString(R.string.saved),
                Toast.LENGTH_SHORT
            ).show()
        }
        val btnUndo = findViewById<ImageButton>(R.id.btnUndo)
        val btnRedo = findViewById<ImageButton>(R.id.btnRedo)

        btnMenu.setOnClickListener { drawerLayout.openDrawer(drawerMenu) }
        setupDrawerMenuListeners()
        btnPreview.setOnClickListener { togglePreview() }
        btnPreview.contentDescription = getString(R.string.preview)
        btnUndo.setOnClickListener { performUndo() }
        btnRedo.setOnClickListener { performRedo() }
        val btnFindReplace = findViewById<ImageButton>(R.id.btnFindReplace)
        val btnJumpToLine = findViewById<ImageButton>(R.id.btnJumpToLine)
        btnFindReplace.contentDescription = getString(R.string.find_and_replace)
        btnJumpToLine.contentDescription = getString(R.string.jump_to_line)
        btnFindReplace.setOnClickListener { showFindReplaceDialog() }
        btnJumpToLine.setOnClickListener { showJumpToLineDialog() }
        val btnInsertAdd = findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.btnInsertAdd)
        btnInsertAdd.contentDescription = getString(R.string.insert_add_button)
        btnInsertAdd.setOnClickListener { showInsertPopupMenu(it) }
        previewErrorBanner.setOnClickListener {
            if (showPreview) togglePreview()
            lastPreviewErrorLine?.let { line -> scrollEditorToLine(line) }
            previewErrorBanner.visibility = View.GONE
        }
        ensureAtLeastOneDocument()
        setupDocumentTabs()
        setupEditorPinchZoom()
        setupLivePreview()
        setupAutoSave()
        setupLineNumbers()
        loadCurrentDocIntoEditor()
        updateSaveStatusIcon()
        showTemplatesOnFirstLaunch()
        handleGitHubOAuthCallback(intent)
        handleOpenFileIntent(intent)
    }

    private fun updateSaveStatusIcon() {
        val doc = documents.getOrNull(currentDocIndex)
        val isDirty = doc?.isDirty == true
        saveStatusIcon.setImageResource(if (isDirty) R.drawable.ic_unsaved else R.drawable.ic_saved)
        saveStatusIcon.contentDescription = if (isDirty) getString(R.string.unsaved_changes) else getString(R.string.saved)
    }

    private fun showTemplatesOnFirstLaunch() {
        if (!EditorPrefs.isFirstLaunchDone(this)) {
            EditorPrefs.setFirstLaunchDone(this)
            showTemplatesDialog()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleGitHubOAuthCallback(intent)
        handleOpenFileIntent(intent)
    }

    /** When app is opened via "Open with" for a .tex file, open that file. */
    private fun handleOpenFileIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        if (uri.scheme == "livelatex") return
        if (uri.scheme != "content" && uri.scheme != "file") return
        // For file:// require .tex in path; for content:// we were chosen to open this file (intent filter matched)
        if (uri.scheme == "file") {
            val path = uri.lastPathSegment ?: ""
            if (!path.endsWith(".tex", ignoreCase = true)) return
        }
        openUri(uri)
        intent.setData(null)
    }

    private fun handleGitHubOAuthCallback(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "livelatex" || uri.host != "github-callback") return
        CoroutineScope(Dispatchers.Main).launch {
            GitHubOAuthHelper.handleCallback(
                this@MainActivity,
                uri,
                BuildConfig.GITHUB_CLIENT_ID,
                BuildConfig.GITHUB_CLIENT_SECRET.ifBlank { null }
            )
        }
    }

    private fun insertAtCursor(text: String) {
        val start = editText.selectionStart.coerceIn(0, editText.text.length)
        val end = editText.selectionEnd.coerceIn(0, editText.text.length)
        editText.text.replace(start, end, text)
        val newPos = start + text.length
        editText.setSelection(newPos.coerceIn(0, editText.text.length))
        documents.getOrNull(currentDocIndex)?.isDirty = true
        updateCurrentTabLabel()
    }

    private fun setupLineNumbers() {
        lineNumberView.setEditText(editText)
        lineNumberView.visibility = if (EditorPrefs.getShowLineNumbers(this)) View.VISIBLE else View.GONE
        val editorScrollView = findViewById<android.widget.ScrollView>(R.id.editorScrollView)
        val editorScrollBar = findViewById<VerticalScrollBar>(R.id.editorScrollBar)
        editorScrollView?.setOnScrollChangeListener { v, _, scrollY, _, _ ->
            lineNumberView.invalidate()
            val contentHeight = (v as? android.widget.ScrollView)?.getChildAt(0)?.height ?: 0
            val viewportHeight = v.height
            val maxScroll = (contentHeight - viewportHeight).coerceAtLeast(0)
            if (maxScroll > 0) {
                editorScrollBar?.setProgress(scrollY.toFloat() / maxScroll)
                editorScrollBar?.setThumbRatio(viewportHeight.toFloat() / contentHeight)
            } else {
                editorScrollBar?.setProgress(0f)
            }
        }
        editorScrollBar?.onProgressChange = { progress ->
            val sv = editorScrollView
            if (sv != null) {
                val contentHeight = sv.getChildAt(0)?.height ?: 0
                val viewportHeight = sv.height
                val maxScroll = (contentHeight - viewportHeight).coerceAtLeast(0)
                sv.scrollTo(0, (progress * maxScroll).toInt())
            }
        }
    }

    private fun syncEditorScrollBar() {
        val editorScrollView = findViewById<android.widget.ScrollView>(R.id.editorScrollView)
        val editorScrollBar = findViewById<VerticalScrollBar>(R.id.editorScrollBar) ?: return
        val contentHeight = editorScrollView?.getChildAt(0)?.height ?: 0
        val viewportHeight = editorScrollView?.height ?: 0
        val maxScroll = (contentHeight - viewportHeight).coerceAtLeast(0)
        if (maxScroll > 0 && contentHeight > 0) {
            editorScrollBar.setProgress(editorScrollView!!.scrollY.toFloat() / maxScroll)
            editorScrollBar.setThumbRatio(viewportHeight.toFloat() / contentHeight)
        } else {
            editorScrollBar.setProgress(0f)
        }
    }

    override fun onDestroy() {
        autoSaveJob?.cancel()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        if (event?.action == KeyEvent.ACTION_DOWN && event.isCtrlPressed) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_S -> { saveCurrentDocument(); return true }
                KeyEvent.KEYCODE_O -> { openDocument.launch(arrayOf("text/plain", "application/x-tex", "*/*")); return true }
                KeyEvent.KEYCODE_F -> { showFindReplaceDialog(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun setupAutoSave() {
        autoSaveJob?.cancel()
        if (!EditorPrefs.isAutoSaveEnabled(this)) return
        val intervalMs = EditorPrefs.getAutoSaveIntervalSec(this) * 1000L
        autoSaveJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                delay(intervalMs)
                val doc = documents.getOrNull(currentDocIndex) ?: continue
                if (doc.isDirty && doc.savedUri != null) {
                    syncEditorToCurrentDoc()
                    doc.content = editText.text.toString()
                    saveContentToUri(doc.content, doc.savedUri!!)
                    doc.isDirty = false
                    refreshDocumentTabs()
                }
            }
        }
    }

    private fun setupDrawerMenuListeners() {
        val fileSectionHeader = findViewById<View>(R.id.file_section_header)
        val fileSectionContent = findViewById<View>(R.id.file_section_content)
        val fileSectionChevron = findViewById<ImageView>(R.id.file_section_chevron)
        fileSectionHeader.setOnClickListener {
            val visible = fileSectionContent.visibility == View.VISIBLE
            fileSectionContent.visibility = if (visible) View.GONE else View.VISIBLE
            fileSectionChevron.setImageResource(if (visible) R.drawable.ic_expand_more else R.drawable.ic_expand_less)
        }

        val btnSave = findViewById<Button>(R.id.btnSave)
        val btnOpen = findViewById<Button>(R.id.btnOpen)
        val btnTemplates = findViewById<Button>(R.id.btnTemplates)
        val btnExportPdf = findViewById<Button>(R.id.btnExportPdf)
        val btnOptions = findViewById<Button>(R.id.btnOptions)
        val recentList = findViewById<LinearLayout>(R.id.recent_files_list)
        val btnClearRecent = findViewById<Button>(R.id.btnClearRecent)

        fun closeDrawer() { drawerLayout.closeDrawer(drawerMenu) }

        btnSave.setOnClickListener { saveCurrentDocument(); closeDrawer() }
        btnOpen.setOnClickListener {
            openDocument.launch(arrayOf("text/plain", "application/x-tex", "*/*"))
            closeDrawer()
        }
        btnTemplates.setOnClickListener {
            closeDrawer()
            showTemplatesDialog()
        }
        btnExportPdf.setOnClickListener {
            closeDrawer()
            exportPreviewToPdf()
        }
        val btnTemplateDefaults = findViewById<Button>(R.id.btnTemplateDefaults)
        btnTemplateDefaults.setOnClickListener {
            closeDrawer()
            showTemplateDefaultsDialog()
        }
        btnOptions.setOnClickListener {
            closeDrawer()
            showOptionsSheet()
        }

        btnClearRecent.setOnClickListener {
            RecentFilesPrefs.clearRecent(this)
            refreshDrawerRecentList()
        }

        drawerLayout.addDrawerListener(object : DrawerLayout.DrawerListener {
            override fun onDrawerOpened(drawerView: View) { refreshDrawerRecentList() }
            override fun onDrawerClosed(drawerView: View) {}
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {}
            override fun onDrawerStateChanged(newState: Int) {}
        })
        refreshDrawerRecentList()
    }

    private fun refreshDrawerRecentList() {
        val recentList = findViewById<LinearLayout>(R.id.recent_files_list)
        recentList.removeAllViews()
        val recentFiles = RecentFilesPrefs.getRecent(this)
        if (recentFiles.isEmpty()) {
            TextView(this).apply {
                text = getString(R.string.no_recent_files)
                setPadding(0, 12, 0, 12)
                setTextColor(0xFF9E9E9E.toInt())
            }.let { recentList.addView(it) }
        } else {
            recentFiles.forEach { entry ->
                val itemView = layoutInflater.inflate(R.layout.item_recent_file, recentList, false)
                val fileName = itemView.findViewById<TextView>(R.id.file_name)
                val filePath = itemView.findViewById<TextView>(R.id.file_path)
                val fileLastOpened = itemView.findViewById<TextView>(R.id.file_last_opened)
                val btnRemove = itemView.findViewById<ImageButton>(R.id.btn_remove)

                fileName.text = entry.displayName
                filePath.text = Uri.parse(entry.uri).path ?: entry.uri
                if (entry.lastOpenedAt > 0L) {
                    fileLastOpened.visibility = View.VISIBLE
                    fileLastOpened.text = getString(R.string.last_opened, android.text.format.DateUtils.getRelativeTimeSpanString(entry.lastOpenedAt, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS))
                } else {
                    fileLastOpened.visibility = View.GONE
                }

                itemView.setOnClickListener {
                    drawerLayout.closeDrawer(drawerMenu)
                    openRecentUri(entry.uri)
                }

                btnRemove.setOnClickListener {
                    RecentFilesPrefs.removeRecent(this, entry.uri)
                    recentList.removeView(itemView)
                    if (recentList.childCount == 0) {
                        TextView(this).apply {
                            text = getString(R.string.no_recent_files)
                            setPadding(0, 12, 0, 12)
                            setTextColor(0xFF9E9E9E.toInt())
                        }.let { recentList.addView(it) }
                    }
                }

                recentList.addView(itemView)
            }
        }
    }

    private fun showOptionsSheet() {
        val view = layoutInflater.inflate(R.layout.bottom_sheet_options, null)
        val dialog = MaterialAlertDialogBuilder(this).setView(view).create()

        val btnThemeLight = view.findViewById<Button>(R.id.btnThemeLight)
        val btnThemeDark = view.findViewById<Button>(R.id.btnThemeDark)
        val btnThemeSystem = view.findViewById<Button>(R.id.btnThemeSystem)
        btnThemeLight.setOnClickListener {
            EditorPrefs.setNightMode(this, AppCompatDelegate.MODE_NIGHT_NO)
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            dialog.dismiss()
            recreate()
        }
        btnThemeDark.setOnClickListener {
            EditorPrefs.setNightMode(this, AppCompatDelegate.MODE_NIGHT_YES)
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            dialog.dismiss()
            recreate()
        }
        btnThemeSystem.setOnClickListener {
            EditorPrefs.setNightMode(this, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            dialog.dismiss()
            recreate()
        }

        val btnConnectGitHub = view.findViewById<Button>(R.id.btnConnectGitHub)
        val btnConnectGoogleDrive = view.findViewById<Button>(R.id.btnConnectGoogleDrive)
        btnConnectGitHub.text = if (CloudPrefs.isGitHubConnected(this)) getString(R.string.connected_github) else getString(R.string.connect_github)
        btnConnectGoogleDrive.text = if (CloudPrefs.isGoogleDriveConnected(this)) getString(R.string.connected_google_drive) else getString(R.string.connect_google_drive)
        btnConnectGitHub.setOnClickListener {
            GitHubOAuthHelper.launchSignIn(this, BuildConfig.GITHUB_CLIENT_ID, BuildConfig.GITHUB_CLIENT_SECRET.ifBlank { null })
            dialog.dismiss()
        }
        btnConnectGoogleDrive.setOnClickListener {
            val client = GoogleDriveHelper.getSignInClient(this, BuildConfig.GOOGLE_WEB_CLIENT_ID.ifBlank { null })
            googleSignInLauncher.launch(client.signInIntent)
            dialog.dismiss()
        }

        val checkLineNumbers = view.findViewById<CheckBox>(R.id.checkLineNumbers)
        checkLineNumbers.isChecked = EditorPrefs.getShowLineNumbers(this)
        checkLineNumbers.setOnCheckedChangeListener { _, isChecked ->
            EditorPrefs.setShowLineNumbers(this@MainActivity, isChecked)
            lineNumberView.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        val documentStats = view.findViewById<TextView>(R.id.documentStats)
        val content = editText.text.toString()
        val words = content.split(Regex("\\s+")).count { it.isNotEmpty() }
        documentStats.text = getString(R.string.word_count, words, content.length)

        val seekFontSize = view.findViewById<android.widget.SeekBar>(R.id.seekEditorFontSize)
        val labelFontSize = view.findViewById<TextView>(R.id.labelEditorFontSize)
        val currentSp = EditorPrefs.getEditorFontSizeSp(this)
        val progress = (currentSp - EditorPrefs.MIN_FONT_SIZE_SP).toInt().coerceIn(0, seekFontSize.max)
        seekFontSize.progress = progress
        labelFontSize.text = "${progress + EditorPrefs.MIN_FONT_SIZE_SP.toInt()} sp"
        seekFontSize.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    val sizeSp = EditorPrefs.MIN_FONT_SIZE_SP + p
                    EditorPrefs.setEditorFontSizeSp(this@MainActivity, sizeSp)
                    labelFontSize.text = "${sizeSp.toInt()} sp"
                    applyEditorFontSize()
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        dialog.show()
    }

    private fun showTemplateDefaultsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_template_defaults, null)
        val dialog = MaterialAlertDialogBuilder(this).setView(view).create()

        val defaults = TemplateDefaultsPrefs.get(this)
        view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.editTemplateAuthor).setText(defaults.author)
        view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.editTemplateAffiliate).setText(defaults.affiliate)
        view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.editTemplateAddress).setText(defaults.address)
        view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.editTemplateEmail).setText(defaults.email)
        view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.editTemplateOrcid).setText(defaults.orcid)

        view.findViewById<Button>(R.id.btnTemplateDefaultsCancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<Button>(R.id.btnTemplateDefaultsSave).setOnClickListener {
            val newDefaults = TemplateDefaultsPrefs.TemplateDefaults(
                author = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.editTemplateAuthor).text.toString(),
                affiliate = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.editTemplateAffiliate).text.toString(),
                address = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.editTemplateAddress).text.toString(),
                email = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.editTemplateEmail).text.toString(),
                orcid = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.editTemplateOrcid).text.toString()
            )
            TemplateDefaultsPrefs.save(this, newDefaults)
            dialog.dismiss()
            Toast.makeText(this, getString(R.string.template_defaults_summary), Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    private fun showFindReplaceDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_find_replace, null)
        val dialog = MaterialAlertDialogBuilder(this).setView(view).create()

        val editFind = view.findViewById<EditText>(R.id.editFind)
        val editReplace = view.findViewById<EditText>(R.id.editReplace)
        val findMatchCount = view.findViewById<TextView>(R.id.findMatchCount)
        val btnFindPrevious = view.findViewById<Button>(R.id.btnFindPrevious)
        val btnFindNext = view.findViewById<Button>(R.id.btnFindNext)
        val btnReplace = view.findViewById<Button>(R.id.btnReplace)
        val btnReplaceAll = view.findViewById<Button>(R.id.btnReplaceAll)
        val btnClose = view.findViewById<Button>(R.id.btnFindClose)

        fun countMatches(): Int {
            val find = editFind.text.toString()
            if (find.isEmpty()) return 0
            return editText.text.toString().split(find).size - 1
        }

        fun currentMatchIndex(): Int {
            val find = editFind.text.toString()
            if (find.isEmpty()) return 0
            val text = editText.text.toString()
            val selStart = Selection.getSelectionStart(editText.text).coerceIn(0, text.length)
            var count = 0
            var idx = 0
            while (idx < text.length) {
                val found = text.indexOf(find, idx)
                if (found < 0) break
                count++
                if (selStart in found until found + find.length) return count
                idx = found + 1
            }
            return count
        }

        fun updateMatchCount() {
            val total = countMatches()
            if (total == 0) {
                findMatchCount.visibility = View.GONE
            } else {
                findMatchCount.visibility = View.VISIBLE
                val current = currentMatchIndex()
                findMatchCount.text = getString(R.string.find_match_count, current, total)
            }
        }

        fun findNext(forward: Boolean): Boolean {
            val find = editFind.text.toString()
            if (find.isEmpty()) return false
            val text = editText.text.toString()
            val start = Selection.getSelectionStart(editText.text).coerceIn(0, text.length)
            val end = Selection.getSelectionEnd(editText.text).coerceIn(0, text.length)
            val searchStart = if (forward) end else start - 1
            val idx = if (forward) {
                text.indexOf(find, searchStart).takeIf { it >= 0 }
                    ?: text.indexOf(find, 0).takeIf { it < searchStart }
            } else {
                text.lastIndexOf(find, searchStart.coerceAtLeast(0)).takeIf { it >= 0 }
                    ?: text.lastIndexOf(find, text.length).takeIf { it > searchStart }
            }
            if (idx != null && idx >= 0) {
                editText.setSelection(idx, idx + find.length)
                updateMatchCount()
                return true
            }
            return false
        }

        btnFindNext.setOnClickListener {
            if (!findNext(true)) Toast.makeText(this, getString(R.string.find) + ": no match", Toast.LENGTH_SHORT).show()
            else updateMatchCount()
        }
        btnFindPrevious.setOnClickListener {
            if (!findNext(false)) Toast.makeText(this, getString(R.string.find) + ": no match", Toast.LENGTH_SHORT).show()
            else updateMatchCount()
        }
        btnReplace.setOnClickListener {
            val find = editFind.text.toString()
            if (find.isEmpty()) return@setOnClickListener
            val rep = editReplace.text.toString()
            val start = Selection.getSelectionStart(editText.text)
            val end = Selection.getSelectionEnd(editText.text)
            val sel = editText.text.toString().substring(start.coerceAtLeast(0), end.coerceAtMost(editText.text.length))
            if (sel == find) {
                editText.text.replace(start, end, rep)
                documents.getOrNull(currentDocIndex)?.isDirty = true
                updateCurrentTabLabel()
                findNext(true)
                updateMatchCount()
            } else {
                if (!findNext(true)) Toast.makeText(this, getString(R.string.find) + ": no match", Toast.LENGTH_SHORT).show()
                else updateMatchCount()
            }
        }
        btnReplaceAll.setOnClickListener {
            val find = editFind.text.toString()
            if (find.isEmpty()) return@setOnClickListener
            val rep = editReplace.text.toString()
            val original = editText.text.toString()
            val count = original.split(find).size - 1
            if (count > 0) {
                val newText = original.replace(find, rep)
                editText.setText(newText)
                documents.getOrNull(currentDocIndex)?.content = newText
                documents.getOrNull(currentDocIndex)?.isDirty = true
                updateCurrentTabLabel()
                LatexHighlighter.applyHighlighting(editText.text)
                updatePreviewFromLatex(newText)
            }
            Toast.makeText(this, "Replaced $count occurrence(s)", Toast.LENGTH_SHORT).show()
        }
        btnClose.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private fun exportPreviewToPdf() {
        val printManager = getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
        val jobName = getString(R.string.app_name) + " - " + (documents.getOrNull(currentDocIndex)?.effectiveName() ?: "document")
        val adapter = webView.createPrintDocumentAdapter(jobName)
        printManager.print(jobName, adapter, PrintAttributes.Builder().build())
        Toast.makeText(this, getString(R.string.export_to_pdf) + " – " + getString(R.string.preview), Toast.LENGTH_SHORT).show()
    }

    private fun showJumpToLineDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_jump_to_line, null)
        val input = view.findViewById<EditText>(R.id.editLineNumber)
        val maxLine = editText.text.toString().count { it == '\n' } + 1
        input.hint = getString(R.string.line_number) + " (1–$maxLine)"
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.jump_to_line))
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val lineStr = input.text.toString()
                val line = lineStr.toIntOrNull()?.coerceIn(1, Int.MAX_VALUE) ?: return@setPositiveButton
                val text = editText.text.toString()
                var offset = 0
                var currentLine = 1
                for (i in text.indices) {
                    if (currentLine == line) {
                        offset = i
                        break
                    }
                    if (text[i] == '\n') currentLine++
                }
                if (currentLine == line) offset = text.length
                editText.setSelection(offset.coerceIn(0, text.length))
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ -> }
            .show()
    }

    private fun scrollEditorToLine(line: Int) {
        val text = editText.text.toString()
        var offset = 0
        var currentLine = 1
        for (i in text.indices) {
            if (currentLine == line) {
                offset = i
                break
            }
            if (text[i] == '\n') currentLine++
        }
        if (currentLine == line) offset = text.length
        editText.setSelection(offset.coerceIn(0, text.length))
        editText.requestFocus()
    }

    private fun showInsertListDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 16)
        }
        val typeLabel = TextView(this).apply {
            text = getString(R.string.insert_list_dialog_title)
            setPadding(0, 0, 0, 8)
        }
        layout.addView(typeLabel)
        val radioGroup = android.widget.RadioGroup(this).apply {
            addView(android.widget.RadioButton(this@MainActivity).apply {
                id = View.generateViewId()
                text = getString(R.string.insert_list_type_enumerate)
                isChecked = true
            })
            addView(android.widget.RadioButton(this@MainActivity).apply {
                id = View.generateViewId()
                text = getString(R.string.insert_list_type_itemize)
            })
        }
        layout.addView(radioGroup)
        val itemsLabel = TextView(this).apply {
            text = getString(R.string.insert_list_items_hint)
            setPadding(0, 24, 0, 8)
        }
        layout.addView(itemsLabel)
        val editItems = EditText(this).apply {
            setLines(6)
            minLines = 3
            hint = getString(R.string.insert_list_dialog_hint)
            contentDescription = getString(R.string.insert_list_items_hint)
            setPadding(16, 12, 16, 12)
        }
        layout.addView(editItems)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.insert_list))
            .setView(layout)
            .setPositiveButton(getString(R.string.insert_list)) { _, _ ->
                val useEnumerate = radioGroup.indexOfChild(radioGroup.findViewById(radioGroup.checkedRadioButtonId)) == 0
                val lines = editItems.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
                val snippet = if (useEnumerate) LatexSnippets.enumerateItems(lines) else LatexSnippets.itemizeItems(lines)
                insertAtCursor(snippet)
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ -> }
            .show()
    }

    private fun showInsertTableDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 16)
        }
        val rowsLabel = TextView(this).apply { text = getString(R.string.insert_table_rows); setPadding(0, 0, 0, 4) }
        val editRows = EditText(this).apply {
            setLines(1)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("3")
            setPadding(16, 8, 16, 8)
        }
        layout.addView(rowsLabel)
        layout.addView(editRows)
        val colsLabel = TextView(this).apply { text = getString(R.string.insert_table_cols); setPadding(0, 16, 0, 4) }
        val editCols = EditText(this).apply {
            setLines(1)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("3")
            setPadding(16, 8, 16, 8)
        }
        layout.addView(colsLabel)
        layout.addView(editCols)
        val checkHeader = CheckBox(this).apply {
            text = getString(R.string.insert_table_first_row_header)
            setPadding(0, 16, 0, 8)
        }
        layout.addView(checkHeader)
        val cellsLabel = TextView(this).apply {
            text = getString(R.string.insert_table_cells_hint)
            setPadding(0, 8, 0, 4)
        }
        layout.addView(cellsLabel)
        val editCells = EditText(this).apply {
            setLines(5)
            minLines = 3
            hint = "A, B, C\n1, 2, 3\n4, 5, 6"
            setPadding(16, 12, 16, 12)
        }
        layout.addView(editCells)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.insert_table))
            .setView(layout)
            .setPositiveButton(getString(R.string.insert_table)) { _, _ ->
                val rows = editRows.text.toString().toIntOrNull()?.coerceIn(1, 50) ?: 3
                val cols = editCols.text.toString().toIntOrNull()?.coerceIn(1, 20) ?: 3
                val firstRowHeader = checkHeader.isChecked
                val cellText = editCells.text.toString()
                val lines = cellText.lines().map { line ->
                    line.split(Regex("[\t,]+")).map { it.trim() }
                }.filter { it.isNotEmpty() }.take(rows)
                val padded = lines.map { row -> (0 until cols).map { row.getOrNull(it) ?: "" } }
                val tableRows = if (padded.isEmpty()) List(rows) { List(cols) { "" } } else padded + List((rows - padded.size).coerceAtLeast(0)) { List(cols) { "" } }
                val snippet = LatexSnippets.tabular(tableRows, firstRowHeader)
                insertAtCursor(snippet)
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ -> }
            .show()
    }

    private fun showInsertTikzDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 16)
        }
        val title = TextView(this).apply {
            text = getString(R.string.insert_tikz_dialog_title)
            setPadding(0, 0, 0, 16)
        }
        layout.addView(title)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(layout)
            .setNegativeButton(getString(R.string.cancel)) { _, _ -> }
            .create()
        for (template in LatexSnippets.TikzTemplate.entries) {
            val btn = Button(this).apply {
                text = template.label
                setOnClickListener {
                    insertAtCursor(LatexSnippets.tikzSnippet(template))
                    dialog.dismiss()
                }
            }
            layout.addView(btn)
        }
        dialog.show()
    }

    private fun showInsertPopupMenu(anchor: View) {
        val popup = android.widget.PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.menu_insert, popup.menu)
        popup.setForceShowIcon(true)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_insert_image -> { showInsertImageOptions(); true }
                R.id.action_insert_tikz -> { showInsertTikzDialog(); true }
                R.id.action_insert_table -> { showInsertTableDialog(); true }
                R.id.action_insert_list -> { showInsertListDialog(); true }
                R.id.action_insert_symbols -> {
                    // Defer so popup menu can dismiss first; avoids app losing focus / launcher transition
                    editText.postDelayed({ showSymbolsGridDialog() }, 120)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showSymbolsGridDialog() {
        if (!isFinishing) {
            val view = layoutInflater.inflate(R.layout.dialog_symbols_grid, null)
            val dialog = MaterialAlertDialogBuilder(this)
                .setView(view)
                .setCancelable(true)
                .create()

            val grid = view.findViewById<android.widget.GridLayout>(R.id.symbols_grid)
            val btnClose = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_symbols_close)
            btnClose.setOnClickListener { dialog.dismiss() }

            val columnCount = 4
            grid.rowCount = (INSERT_SYMBOLS.size + columnCount - 1) / columnCount
            grid.columnCount = columnCount
            val dp = resources.displayMetrics.density
            val margin = (dp * 4).toInt()
            val rowHeight = (dp * 40).toInt()
            for ((index, pair) in INSERT_SYMBOLS.withIndex()) {
                val (label, text) = pair
                val itemView = layoutInflater.inflate(R.layout.item_symbol_button, grid, false)
                val btn = itemView.findViewById<com.google.android.material.button.MaterialButton>(R.id.symbolButton)
                btn.text = label
                btn.setOnClickListener {
                    insertAtCursor(text)
                    dialog.dismiss()
                }
                val row = index / columnCount
                val col = index % columnCount
                val params = android.widget.GridLayout.LayoutParams().apply {
                    width = 0
                    height = rowHeight
                    setMargins(margin, margin, margin, margin)
                    rowSpec = android.widget.GridLayout.spec(row, 1)
                    columnSpec = android.widget.GridLayout.spec(col, 1f)
                }
                grid.addView(itemView, params)
            }

            dialog.show()
            dialog.window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.85f).toInt(),
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun showInsertImageOptions() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.insert_image))
            .setItems(arrayOf(getString(R.string.insert_image_gallery), getString(R.string.insert_image_camera))) { _, which ->
                if (which == 0) pickImageFromGallery.launch("image/*")
                else {
                    val imagesDir = getDir("latex_images", Context.MODE_PRIVATE)
                    val subDir = java.io.File(imagesDir, "images").apply { mkdirs() }
                    val name = "img_${System.currentTimeMillis()}.jpg"
                    val file = java.io.File(subDir, name)
                    takePictureFile = file
                    val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
                    takePictureForInsert.launch(uri)
                }
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ -> }
            .show()
    }

    private val pickImageFromGallery = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { copyImageUriToAppDirAndInsert(it) }
    }

    private var takePictureFile: java.io.File? = null
    private val takePictureForInsert = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        takePictureFile?.let { file ->
            if (success) {
                val relativePath = "images/${file.name}"
                insertAtCursor(LatexSnippets.figureWithImage(relativePath))
                setPreviewBaseDirForImages(file.parentFile?.parentFile?.absolutePath ?: getDir("latex_images", Context.MODE_PRIVATE).absolutePath)
            }
            takePictureFile = null
        }
    }

    private fun copyImageUriToAppDirAndInsert(uri: Uri) {
        val imagesDir = getDir("latex_images", Context.MODE_PRIVATE)
        val subDir = java.io.File(imagesDir, "images").apply { mkdirs() }
        val ext = contentResolver.getType(uri)?.let { when {
            it.contains("png") -> ".png"
            it.contains("jpeg") || it.contains("jpg") -> ".jpg"
            else -> ".png"
        } } ?: ".png"
        val name = "img_${System.currentTimeMillis()}$ext"
        val destFile = java.io.File(subDir, name)
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                java.io.FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            val relativePath = "images/$name"
            val latex = LatexSnippets.figureWithImage(relativePath)
            insertAtCursor(latex)
            setPreviewBaseDirForImages(imagesDir.absolutePath)
        } catch (e: Exception) {
            ErrorDialog.show(this, "Insert image: ${e.message ?: "Unknown error"}")
        }
    }

    private fun setPreviewBaseDirForImages(baseDir: String) {
        com.omariskandarani.livelatexapp.latex.currentBaseDir = baseDir
    }

    private fun showTemplatesDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_templates, null)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(view)
            .create()

        val templatesList = view.findViewById<LinearLayout>(R.id.templates_list)
        val btnCancel = view.findViewById<Button>(R.id.btnCancel)

        btnCancel.setOnClickListener { dialog.dismiss() }

        LatexTemplates.templates.forEachIndexed { index, template ->
            val itemView = layoutInflater.inflate(R.layout.item_template, templatesList, false)
            val templateName = itemView.findViewById<TextView>(R.id.template_name)
            val templateDescription = itemView.findViewById<TextView>(R.id.template_description)

            templateName.text = template.name
            templateDescription.text = template.description

            itemView.setOnClickListener {
                dialog.dismiss()
                createDocumentFromTemplate(template)
            }

            templatesList.addView(itemView)
        }

        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9f).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun createDocumentFromTemplate(template: LatexTemplates.Template) {
        syncEditorToCurrentDoc()
        untitledCounter++
        val defaults = TemplateDefaultsPrefs.get(this)
        val content = LatexTemplates.applyDefaults(template.content, defaults)
        val newDoc = LatexDocument(
            content = content,
            savedUri = null,
            displayName = "${template.name} $untitledCounter",
            isDirty = false
        )
        documents.add(newDoc)
        currentDocIndex = documents.size - 1
        loadCurrentDocIntoEditor()
        refreshDocumentTabs()
        drawerLayout.openDrawer(drawerMenu)
        Toast.makeText(this, "Created from ${template.name}", Toast.LENGTH_SHORT).show()
    }

    private fun openRecentUri(uriString: String) {
        val uri = Uri.parse(uriString)
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                val content = input.bufferedReader().readText()
                val name = getFileNameFromUri(uri)
                val newDoc = LatexDocument(content, uri, name, false)
                syncEditorToCurrentDoc()
                documents.add(newDoc)
                currentDocIndex = documents.size - 1
                loadCurrentDocIntoEditor()
                refreshDocumentTabs()
                drawerLayout.openDrawer(drawerMenu)
            }
        } catch (e: Exception) {
            ErrorDialog.show(this, getString(R.string.open_file) + ": " + (e.message ?: ""))
        }
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
        undoStack.clear()
        redoStack.clear()
        editText.setText(doc.content)
        LatexHighlighter.applyHighlighting(editText.text)
        updatePreviewFromLatex(doc.content)
    }

    private fun pushUndoState(state: String) {
        if (isUndoRedoAction) return
        if (undoStack.isNotEmpty() && undoStack.last() == state) return
        redoStack.clear()
        undoStack.addLast(state)
        while (undoStack.size > maxUndoSize) undoStack.removeFirst()
    }

    private fun performUndo() {
        if (undoStack.isEmpty()) return
        val current = editText.text.toString()
        isUndoRedoAction = true
        redoStack.addLast(current)
        val prev = undoStack.removeLast()
        editText.setText(prev)
        editText.setSelection(prev.length)
        documents.getOrNull(currentDocIndex)?.content = prev
        documents.getOrNull(currentDocIndex)?.isDirty = true
        LatexHighlighter.applyHighlighting(editText.text)
        updatePreviewFromLatex(prev)
        updateCurrentTabLabel()
        isUndoRedoAction = false
    }

    private fun performRedo() {
        if (redoStack.isEmpty()) return
        val current = editText.text.toString()
        isUndoRedoAction = true
        undoStack.addLast(current)
        val next = redoStack.removeLast()
        editText.setText(next)
        editText.setSelection(next.length)
        documents.getOrNull(currentDocIndex)?.content = next
        documents.getOrNull(currentDocIndex)?.isDirty = true
        LatexHighlighter.applyHighlighting(editText.text)
        updatePreviewFromLatex(next)
        updateCurrentTabLabel()
        isUndoRedoAction = false
    }

    private fun togglePreview() {
        showPreview = !showPreview
        editorContainer.visibility = if (showPreview) View.GONE else View.VISIBLE
        webView.visibility = if (showPreview) View.VISIBLE else View.GONE
        findViewById<View>(R.id.previewToolbar).visibility = if (showPreview) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btnInsertAdd).visibility = if (showPreview) View.GONE else View.VISIBLE
        btnPreview.text = if (showPreview) getString(R.string.editor) else getString(R.string.preview)
        btnPreview.contentDescription = if (showPreview) getString(R.string.editor) else getString(R.string.preview)
    }

    private fun setupDocumentTabs() {
        refreshDocumentTabs()
    }

    private fun addDocumentTabsToBar() {
        if (documents.size > MORE_TABS_THRESHOLD) {
            for (index in 0 until minOf(4, documents.size)) {
                val doc = documents[index]
                val name = doc.effectiveName() + if (doc.isDirty) " •" else ""
                val row = makeDocTabView(name, index, withClose = true, isDirty = doc.isDirty)
                row.tag = index
                row.setOnClickListener {
                    syncEditorToCurrentDoc()
                    currentDocIndex = index
                    loadCurrentDocIntoEditor()
                    refreshDocumentTabs()
                }
                documentTabsContainer.addView(row)
            }
            val moreRow = makeDocTabView(getString(R.string.more_tabs, documents.size), MORE_TAB_TAG, withClose = false, isDirty = false, contentDesc = getString(R.string.all_documents))
            moreRow.tag = MORE_TAB_TAG
            moreRow.setOnClickListener { showMoreTabsSheet() }
            documentTabsContainer.addView(moreRow)
        } else {
            documents.forEachIndexed { index, doc ->
                val name = doc.effectiveName() + if (doc.isDirty) " •" else ""
                val row = makeDocTabView(name, index, withClose = true, isDirty = doc.isDirty)
                row.tag = index
                row.setOnClickListener {
                    syncEditorToCurrentDoc()
                    currentDocIndex = index
                    loadCurrentDocIntoEditor()
                    refreshDocumentTabs()
                }
                documentTabsContainer.addView(row)
            }
        }
    }

    private fun showMoreTabsSheet() {
        val view = layoutInflater.inflate(R.layout.bottom_sheet_more_tabs, null)
        val dialog = MaterialAlertDialogBuilder(this).setView(view).create()
        val list = view.findViewById<LinearLayout>(R.id.more_tabs_list)
        list.removeAllViews()
        documents.forEachIndexed { index, doc ->
            val row = layoutInflater.inflate(R.layout.item_more_tab, list, false)
            val nameView = row.findViewById<TextView>(R.id.more_tab_name)
            val closeBtn = row.findViewById<ImageButton>(R.id.more_tab_close)
            nameView.text = doc.effectiveName() + if (doc.isDirty) " •" else ""
            row.setOnClickListener {
                syncEditorToCurrentDoc()
                currentDocIndex = index
                loadCurrentDocIntoEditor()
                refreshDocumentTabs()
                dialog.dismiss()
            }
            closeBtn.setOnClickListener {
                dialog.dismiss()
                tryCloseTab(index)
            }
            list.addView(row)
        }
        dialog.show()
    }

    private fun refreshDocumentTabs() {
        documentTabsContainer.removeAllViews()
        addDocumentTabsToBar()
        val newRow = makeDocTabView(getString(R.string.tab_new), -1, withClose = false, isDirty = false)
        newRow.tag = -1
        newRow.setOnClickListener {
            syncEditorToCurrentDoc()
            untitledCounter++
            val newDoc = LatexDocument("", null, getString(R.string.untitled) + " $untitledCounter", false)
            documents.add(newDoc)
            currentDocIndex = documents.size - 1
            loadCurrentDocIntoEditor()
            refreshDocumentTabs()
            drawerLayout.openDrawer(drawerMenu)
        }
        documentTabsContainer.addView(newRow)
        // Highlight selected row
        val selectedTag = if (documents.size > MORE_TABS_THRESHOLD) {
            if (currentDocIndex < 4) currentDocIndex else MORE_TAB_TAG
        } else {
            currentDocIndex.coerceIn(0, (documents.size - 1).coerceAtLeast(0))
        }
        val selectedColor = resolveColor(com.google.android.material.R.attr.colorPrimaryContainer)
        val defaultColor = resolveColor(com.google.android.material.R.attr.colorSurface)
        for (i in 0 until documentTabsContainer.childCount) {
            val row = documentTabsContainer.getChildAt(i)
            row.setBackgroundColor(if (row.tag == selectedTag) selectedColor else defaultColor)
        }
        updateSaveStatusIcon()
    }

    private fun resolveColor(attrRes: Int): Int {
        val typedValue = TypedValue()
        theme.resolveAttribute(attrRes, typedValue, true)
        return typedValue.data
    }

    private fun updateCurrentTabLabel() {
        val doc = documents.getOrNull(currentDocIndex) ?: return
        if (documents.size > MORE_TABS_THRESHOLD && currentDocIndex >= 4) return
        for (i in 0 until documentTabsContainer.childCount) {
            val row = documentTabsContainer.getChildAt(i)
            if (row.tag == currentDocIndex && row is LinearLayout) {
                val name = doc.effectiveName() + if (doc.isDirty) " •" else ""
                val titleIndex = if (row.childCount >= 3) 1 else 0
                (row.getChildAt(titleIndex) as? TextView)?.text = name
                row.contentDescription = getString(R.string.tab_document_desc, doc.effectiveName(), if (doc.isDirty) getString(R.string.unsaved_changes) else getString(R.string.saved))
                if (row.childCount >= 3) {
                    row.getChildAt(0).visibility = if (doc.isDirty) View.VISIBLE else View.GONE
                }
                break
            }
        }
        updateSaveStatusIcon()
    }

    private fun makeDocTabView(title: String, docIndex: Int, withClose: Boolean, isDirty: Boolean = false, contentDesc: String? = null): View {
        val statusDesc = if (isDirty) getString(R.string.unsaved_changes) else getString(R.string.saved)
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 8, 8, 8)
            contentDescription = when {
                withClose -> getString(R.string.tab_document_desc, title.replace(" •", ""), statusDesc)
                contentDesc != null -> contentDesc
                else -> getString(R.string.new_tab)
            }
        }
        if (withClose) {
            val dirtyDot = View(this).apply {
                background = getDrawable(R.drawable.tab_dirty_dot)
                layoutParams = LinearLayout.LayoutParams(12, 12).apply { marginEnd = 6 }
                visibility = if (isDirty) View.VISIBLE else View.GONE
            }
            wrap.addView(dirtyDot)
        }
        val titleView = TextView(this).apply {
            text = title
            setPadding(0, 0, 12, 0)
            textSize = 14f
        }
        wrap.addView(titleView)
        if (withClose) {
            val close = ImageButton(this).apply {
                setImageResource(R.drawable.ic_close)
                background = null
                setPadding(12, 12, 12, 12)
                layoutParams = LinearLayout.LayoutParams(48, 48)
                contentDescription = getString(R.string.close_tab)
                setOnClickListener { tryCloseTab(docIndex) }
            }
            wrap.addView(close)

            wrap.setOnLongClickListener {
                Toast.makeText(this, getString(R.string.tab_reorder_hint), Toast.LENGTH_SHORT).show()
                true
            }
        }
        return wrap
    }

    private fun tryCloseTab(index: Int) {
        val doc = documents.getOrNull(index) ?: return
        if (!doc.isDirty) {
            closeTabAt(index)
            return
        }
        if (EditorPrefs.getSkipDiscardConfirm(this)) {
            closeTabAt(index)
            return
        }
        val checkBox = CheckBox(this).apply {
            text = getString(R.string.dont_ask_discard)
            setPadding(0, 16, 0, 0)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.close_tab_confirm, doc.effectiveName())
                setPadding(0, 0, 0, 8)
            })
            addView(checkBox)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.save))
            .setView(layout)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                saveDocumentAt(index) { success -> if (success) closeTabAt(index) }
            }
            .setNegativeButton(getString(R.string.discard)) { _, _ ->
                if (checkBox.isChecked) EditorPrefs.setSkipDiscardConfirm(this@MainActivity, true)
                closeTabAt(index)
            }
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
                val name = getFileNameFromUri(uri)
                documents[idx].displayName = name
            }
        } catch (e: Exception) {
            runOnUiThread {
                drawerLayout.openDrawer(drawerMenu)
                ErrorDialog.show(this, e.message ?: "Save failed")
            }
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
            updateSaveStatusIcon()
            Toast.makeText(this, getString(R.string.saved), Toast.LENGTH_SHORT).show()
        } else {
            pendingSaveThenCloseIndex = null
            pendingSaveContent = editText.text.toString()
            createDocument.launch("document.tex")
        }
    }

    private fun openUri(uri: Uri) {
        try {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (_: SecurityException) { }
            contentResolver.openInputStream(uri)?.use { input ->
                val content = input.bufferedReader().readText()
                val name = getFileNameFromUri(uri)
                // Only add to recent if URI is a persistable document URI (e.g. /document/...).
                // URIs with /external/file/... from some file managers are not reopenable later.
                if (DocumentsContract.isDocumentUri(this, uri)) {
                    RecentFilesPrefs.addRecent(this, uri.toString(), name)
                }
                val newDoc = LatexDocument(content, uri, name, false)
                syncEditorToCurrentDoc()
                documents.add(newDoc)
                currentDocIndex = documents.size - 1
                loadCurrentDocIntoEditor()
                refreshDocumentTabs()
                drawerLayout.openDrawer(drawerMenu)
            }
        } catch (e: Exception) {
            ErrorDialog.show(this, e.message ?: "Open failed")
        }
    }

    private fun getFileNameFromUri(uri: Uri): String {
        // Try to get the display name from the content resolver first
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val displayNameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (displayNameIndex >= 0) {
                    val displayName = cursor.getString(displayNameIndex)
                    if (!displayName.isNullOrEmpty()) {
                        return displayName
                    }
                }
            }
        }
        // Fallback to parsing the URI path
        return uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
            ?: getString(R.string.untitled)
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
                // Debounced preview update
                previewJob?.cancel()
                previewJob = CoroutineScope(Dispatchers.Main).launch {
                    delay(debounceMs)
                    updatePreviewFromLatex(rawLatex)
                }
                // Debounced syntax highlighting (keeps cursor stable)
                highlightJob?.cancel()
                highlightJob = CoroutineScope(Dispatchers.Main).launch {
                    delay(150L)
                    LatexHighlighter.applyHighlighting(s)
                }
                // Debounced push to undo stack
                if (!isUndoRedoAction) {
                    undoPushJob?.cancel()
                    undoPushJob = CoroutineScope(Dispatchers.Main).launch {
                        delay(undoPushDelayMs)
                        pushUndoState(rawLatex)
                    }
                }
                lineNumberView.invalidate()
                editText.post { syncEditorScrollBar() }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun updatePreviewFromLatex(latexCode: String) {
        CoroutineScope(Dispatchers.Default).launch {
            withContext(Dispatchers.Main) {
                previewLoadingIndicator.visibility = if (showPreview) View.VISIBLE else View.GONE
            }
            // So that \includegraphics{images/...} resolve in preview (inserted images live here)
            com.omariskandarani.livelatexapp.latex.currentBaseDir = applicationContext.getDir("latex_images", Context.MODE_PRIVATE).absolutePath
            var wrapped = ""
            var html = ""
            try {
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
                html = LatexHtml.wrap(wrapped)
                withContext(Dispatchers.Main) {
                    lastPreviewErrorLine = null
                    lastPreviewErrorMsg = null
                    previewErrorBanner.visibility = View.GONE
                }
            } catch (e: Exception) {
                val lineNum = previewErrorLineNumber(e, wrapped)
                val lineInfo = if (lineNum != null) " (line $lineNum)" else ""
                val fullMsg = e.message ?: e.toString()
                val msg = fullMsg.replace("<", "&lt;").replace(">", "&gt;")
                html = """<!DOCTYPE html><html><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1"></head><body style="user-select: text; -webkit-user-select: text; -webkit-touch-callout: default;"><div style="user-select: text; -webkit-user-select: text; -webkit-touch-callout: default; padding: 1em;"><p style="color:#b91c1c; white-space: pre-wrap; user-select: text; -webkit-user-select: text; -webkit-touch-callout: default;">Preview error$lineInfo: $msg</p></div></body></html>"""
                val displayMsg = if (fullMsg.length > 120) fullMsg.take(117) + "…" else fullMsg
                withContext(Dispatchers.Main) {
                    lastPreviewErrorLine = lineNum
                    lastPreviewErrorMsg = fullMsg
                    previewErrorBannerText.text = if (lineNum != null) getString(R.string.preview_error_banner, lineNum, displayMsg) else getString(R.string.preview_error_banner_no_line, displayMsg)
                    previewErrorBanner.contentDescription = (if (lineNum != null) getString(R.string.preview_error_banner, lineNum, fullMsg) else getString(R.string.preview_error_banner_no_line, fullMsg)) + ". Tap to go to line or open editor."
                    previewErrorBanner.visibility = View.VISIBLE
                }
            }
            withContext(Dispatchers.Main) {
                webView.loadDataWithBaseURL(
                    "https://example.com/",
                    html,
                    "text/html",
                    "UTF-8",
                    null
                )
                previewLoadingIndicator.visibility = View.GONE
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

}