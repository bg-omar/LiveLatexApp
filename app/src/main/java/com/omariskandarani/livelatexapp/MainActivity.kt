package com.omariskandarani.livelatexapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.DocumentsContract
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.Editable
import android.text.Selection
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.KeyEvent
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import com.google.android.material.R as MaterialR
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.omariskandarani.livelatexapp.R
import com.omariskandarani.livelatexapp.cloud.CloudPrefs
import com.omariskandarani.livelatexapp.cloud.GitHubOAuthHelper
import com.omariskandarani.livelatexapp.cloud.GoogleDriveHelper
import com.omariskandarani.livelatexapp.latex.LatexHtml
import com.omariskandarani.livelatexapp.latex.LatexHighlighter
import com.omariskandarani.livelatexapp.latex.TikzRenderer
import com.omariskandarani.livelatexapp.tikz.TikzCanvasView
import com.omariskandarani.livelatexapp.tikz.TikzPresets
import kotlinx.coroutines.*
import java.io.File
import java.text.DateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.UUID
import java.util.regex.Pattern

/** One open LaTeX document: content, optional saved URI, display name, dirty flag. */
data class LatexDocument(
    var content: String,
    var savedUri: Uri? = null,
    var displayName: String = "",
    var isDirty: Boolean = false,
    /** When known, absolute path to the main .tex file on disk — enables \\input/\\include and local assets. */
    var sourceAbsolutePath: String? = null
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
    private lateinit var documentStatsBar: View
    private lateinit var documentStatsBottom: View
    private lateinit var textDocumentStatsWords: TextView
    private lateinit var textDocumentStatsChars: TextView
    private lateinit var findNavigationBar: View
    private lateinit var btnFindNavPrevious: ImageButton
    private lateinit var btnFindNavNext: ImageButton
    private lateinit var btnFindNavClose: ImageButton
    private lateinit var textFindNavMatch: TextView
    private lateinit var previewLayer: android.widget.FrameLayout
    private lateinit var entitlement: EntitlementRepository
    private lateinit var billingHelper: PlayBillingHelper
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
    /** Search string for header find bar after dialog is dismissed via Next/Previous. */
    private var activeFindQuery: String = ""
    private var activeReplaceQuery: String = ""
    private var proSectionExpanded: Boolean = false

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
                documents.getOrNull(idx)?.let { d ->
                    d.savedUri = savedUri; d.displayName = name; d.isDirty = false; d.content = content
                    resolveTexAbsolutePath(savedUri)?.let { path -> d.sourceAbsolutePath = path }
                }
                pendingSaveThenCloseIndex = null
                pendingSaveContent = null
                closeTabAt(idx)
            } else {
                documents.getOrNull(currentDocIndex)?.let { d ->
                    d.savedUri = savedUri; d.displayName = name; d.isDirty = false; d.content = content
                    resolveTexAbsolutePath(savedUri)?.let { path -> d.sourceAbsolutePath = path }
                }
            }
            refreshDocumentTabs()
            Toast.makeText(this, getString(R.string.saved), Toast.LENGTH_SHORT).show()
        }
    }

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { openUri(it) }
    }

    private val googleSignInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (!ENABLE_CLOUD_CONNECT) return@registerForActivityResult
        val data = result.data
        if (result.resultCode == RESULT_OK) {
            GoogleDriveHelper.handleSignInResult(this, data)
        }
    }

    companion object {
        /** Zet op `true` om GitHub- en Google Drive-koppeling weer te tonen en te activeren. */
        const val ENABLE_CLOUD_CONNECT = false

        private val INDEX_PATTERN = Pattern.compile("(?:at|near)?\\s*index\\s+(\\d+)", Pattern.CASE_INSENSITIVE)
        private const val MORE_TABS_THRESHOLD = 5
        private const val MORE_TAB_TAG = -2
    }

    @Suppress("DEPRECATION")
    private fun installedVersionCode(): Int {
        val pi = packageManager.getPackageInfo(packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pi.longVersionCode.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        } else {
            pi.versionCode
        }
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
        documentStatsBar = findViewById(R.id.documentStatsBar)
        documentStatsBottom = findViewById(R.id.documentStatsBottom)
        textDocumentStatsWords = findViewById(R.id.textDocumentStatsWords)
        textDocumentStatsChars = findViewById(R.id.textDocumentStatsChars)
        findNavigationBar = findViewById(R.id.findNavigationBar)
        btnFindNavPrevious = findViewById(R.id.btnFindNavPrevious)
        btnFindNavNext = findViewById(R.id.btnFindNavNext)
        btnFindNavClose = findViewById(R.id.btnFindNavClose)
        textFindNavMatch = findViewById(R.id.textFindNavMatch)
        previewLayer = findViewById(R.id.previewLayer)
        ViewCompat.setOnApplyWindowInsetsListener(documentStatsBar) { v, windowInsets ->
            val bars = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, v.paddingBottom)
            v.post { syncHeaderPaddingToContent() }
            windowInsets
        }
        documentStatsBar.post {
            ViewCompat.requestApplyInsets(documentStatsBar)
            syncHeaderPaddingToContent()
        }
        editText = findViewById(R.id.inputLatex)
        lineNumberView = findViewById(R.id.lineNumbers)
        btnMenu = findViewById(R.id.btnMenu)
        drawerLayout = findViewById(R.id.drawerLayout)
        drawerMenu = findViewById(R.id.drawerMenu)
        entitlement = EntitlementRepository.get(this)
        PlayBillingHelper.refreshEntitlementFromStore(this, entitlement)
        billingHelper = PlayBillingHelper(this, entitlement) { runOnUiThread { refreshProUi() } }
        billingHelper.startConnection { }
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
        btnFindReplace.contentDescription = getString(R.string.find_and_replace)
        btnFindReplace.setOnClickListener { showFindReplaceDialog() }
        val btnPunctuation = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPunctuation)
        btnPunctuation.setOnClickListener { showPunctuationDialog() }
        val btnInsertAdd = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnInsertAdd)
        btnInsertAdd.contentDescription = getString(R.string.insert_add_button)
        btnInsertAdd.setOnClickListener { v -> requirePro { showInsertPopupMenu(v) } }
        setupFindNavigationBar()
        previewErrorBanner.setOnClickListener { navigateFromPreviewErrorToEditor() }
        val opensExternalTex =
            intent?.action == Intent.ACTION_VIEW && intent?.data != null && intent.data?.scheme != "livelatex"
        if (opensExternalTex) {
            ensureAtLeastOneDocument()
        } else if (!tryRestoreLastRecentFile()) {
            // No blank tab until template picker or cancel; see showTemplatesOnFirstLaunch
        }
        setupDocumentTabs()
        setupEditorPinchZoom()
        setupLivePreview()
        setupAutoSave()
        setupLineNumbers()
        if (documents.isNotEmpty()) {
            loadCurrentDocIntoEditor()
        } else {
            editText.setText("")
        }
        updateSaveStatusIcon()
        handleGitHubOAuthCallback(intent)
        handleOpenFileIntent(intent)
        showTemplatesOnFirstLaunch(intent)
    }

    private fun updateSaveStatusIcon() {
        val doc = documents.getOrNull(currentDocIndex)
        val isDirty = doc?.isDirty == true
        saveStatusIcon.setImageResource(if (isDirty) R.drawable.ic_unsaved else R.drawable.ic_saved)
        saveStatusIcon.contentDescription = if (isDirty) getString(R.string.unsaved_changes) else getString(R.string.saved)
    }

    private fun syncHeaderPaddingToContent() {
        val h = documentStatsBar.height
        if (h <= 0) return
        val bottomPad = editorContainer.paddingBottom
        editorContainer.setPadding(0, h, 0, bottomPad)
        val pb = previewLayer.paddingBottom
        previewLayer.setPadding(0, h, 0, pb)
    }

    private fun updateDocumentStats() {
        if (!::textDocumentStatsWords.isInitialized) return
        val content = editText.text?.toString() ?: ""
        val words = content.split(Regex("\\s+")).count { it.isNotEmpty() }
        val chars = content.length
        textDocumentStatsWords.text = getString(R.string.stats_words_line, words)
        textDocumentStatsChars.text = getString(R.string.stats_chars_line, chars)
        val summary = getString(R.string.word_count, words, chars)
        documentStatsBottom.contentDescription =
            getString(R.string.document_statistics) + ". " + summary
    }

    private fun showTemplatesOnFirstLaunch(intent: Intent?) {
        val needTemplateDefaultsPrompt = EditorPrefs.getLastAuthorPromptVersionCode(this) < installedVersionCode()

        fun finishAuthorFlow() {
            if (needTemplateDefaultsPrompt) {
                showTemplateDefaultsDialog(
                    onDismiss = {
                        EditorPrefs.setLastAuthorPromptVersionCode(this@MainActivity, installedVersionCode())
                    }
                )
            }
        }

        fun ensureDocThenFinishAuthorFlow() {
            if (documents.isEmpty()) ensureAtLeastOneDocument()
            loadCurrentDocIntoEditor()
            refreshDocumentTabs()
            finishAuthorFlow()
        }

        // User opened a .tex from another app — go straight to editing/preview, not template picker
        val skipTemplatePicker =
            intent?.action == Intent.ACTION_VIEW && intent?.data != null && intent.data?.scheme != "livelatex"

        if (!EditorPrefs.isFirstLaunchDone(this)) {
            EditorPrefs.setFirstLaunchDone(this)
            if (skipTemplatePicker) {
                ensureDocThenFinishAuthorFlow()
                return
            }
            showTemplatesDialog(onDismiss = { ensureDocThenFinishAuthorFlow() })
            return
        }
        if (!skipTemplatePicker && documents.isEmpty()) {
            showTemplatesDialog(onDismiss = { ensureDocThenFinishAuthorFlow() })
            return
        }
        finishAuthorFlow()
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
        if (!ENABLE_CLOUD_CONNECT) return
        val uri = intent?.data ?: return
        if (uri.scheme != "livelatex" || uri.host != "github-callback") return
        CoroutineScope(Dispatchers.Main).launch {
            GitHubOAuthHelper.handleCallback(
                this@MainActivity,
                uri,
                getString(R.string.cloud_github_client_id),
                getString(R.string.cloud_github_client_secret).ifBlank { null }
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
        lineNumberView.setOnClickListener { showJumpToLineDialog() }
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
        billingHelper.endConnection()
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

        val proSectionHeader = findViewById<View>(R.id.pro_section_header)
        val proSectionContent = findViewById<View>(R.id.pro_section_content)
        val proSectionChevron = findViewById<ImageView>(R.id.pro_section_chevron)
        proSectionHeader.setOnClickListener {
            proSectionExpanded = !proSectionExpanded
            proSectionContent.visibility = if (proSectionExpanded) View.VISIBLE else View.GONE
            proSectionChevron.setImageResource(
                if (proSectionExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more
            )
            applyProStatusLineLimit()
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
            requirePro { exportPreviewToPdf() }
        }
        val btnTemplateDefaults = findViewById<Button>(R.id.btnTemplateDefaults)
        btnTemplateDefaults.setOnClickListener {
            closeDrawer()
            requirePro { showTemplateDefaultsDialog() }
        }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_pro_upgrade).setOnClickListener {
            closeDrawer()
            tryLaunchProPurchase()
        }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_pro_watch_ad).setOnClickListener {
            closeDrawer()
            tryShowRewardedForPro()
        }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_pro_enter_code).setOnClickListener {
            closeDrawer()
            showPromoCodeDialog()
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
            override fun onDrawerOpened(drawerView: View) {
                refreshDrawerRecentList()
                refreshProUi()
            }
            override fun onDrawerClosed(drawerView: View) {}
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {}
            override fun onDrawerStateChanged(newState: Int) {}
        })
        refreshDrawerRecentList()
        refreshProUi()
    }

    private fun refreshProUi() {
        val tv = findViewById<TextView>(R.id.text_pro_status)
        val st = entitlement.state.value
        tv.text = when {
            st.purchasedPro || st.debugProOverride -> getString(R.string.pro_status_purchased)
            entitlement.isProEffective() -> {
                val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                getString(R.string.pro_status_until, df.format(Date(st.proUntilEpochMs)))
            }
            else -> getString(R.string.pro_status_community)
        }
        applyProStatusLineLimit()
    }

    /** Keeps Pro status one line when section is collapsed; full text when expanded. */
    private fun applyProStatusLineLimit() {
        val tv = findViewById<TextView>(R.id.text_pro_status)
        if (proSectionExpanded) {
            tv.maxLines = 14
            tv.ellipsize = null
        } else {
            tv.maxLines = 1
            tv.ellipsize = TextUtils.TruncateAt.END
        }
    }

    private fun requirePro(action: () -> Unit) {
        if (entitlement.isProEffective()) action()
        else showProUpsellDialog()
    }

    private fun showProUpsellDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_pro_upsell, null)
        val dialog = MaterialAlertDialogBuilder(this).setTitle(R.string.pro_upsell_title).setView(view).create()
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_pro_purchase).setOnClickListener {
            dialog.dismiss()
            tryLaunchProPurchase()
        }
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_pro_rewarded).setOnClickListener {
            dialog.dismiss()
            tryShowRewardedForPro()
        }
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_pro_promo).setOnClickListener {
            dialog.dismiss()
            showPromoCodeDialog()
        }
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_pro_upsell_cancel).setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun tryLaunchProPurchase() {
        val pd = billingHelper.productDetails
        if (pd == null) {
            showErrorSnackbar(getString(R.string.pro_product_unavailable), "Retry") { tryLaunchProPurchase() }
            return
        }
        billingHelper.launchPurchaseFlow()
    }

    private fun tryShowRewardedForPro() {
        if (!RewardedProHelper.canWatchRewarded(entitlement)) {
            showErrorSnackbar(getString(R.string.pro_rewarded_cooldown), "Details") { showProUpsellDialog() }
            return
        }
        val unitId = getString(R.string.admob_rewarded_unit_id)
        RewardedProHelper.loadAndShow(
            this,
            unitId,
            entitlement,
            onSuccess = {
                entitlement.refreshFromStorage()
                refreshProUi()
                Toast.makeText(this, R.string.promo_ok, Toast.LENGTH_SHORT).show()
            },
            onFailure = { msg ->
                showErrorSnackbar(getString(R.string.pro_ad_failed, msg ?: "?"), "Retry") { tryShowRewardedForPro() }
            }
        )
    }

    private fun showPromoCodeDialog() {
        val input = EditText(this)
        input.hint = getString(R.string.promo_code_hint)
        input.setSingleLine(true)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pro_enter_code)
            .setView(input)
            .setPositiveButton(R.string.promo_redeem) { d, _ ->
                val code = input.text?.toString().orEmpty()
                when (val r = PromoCodeValidator.tryRedeem(code, BuildConfig.PROMO_HMAC_SECRET)) {
                    is PromoRedeemResult.Invalid -> showErrorSnackbar(getString(R.string.promo_invalid), "Edit") { showPromoCodeDialog() }
                    is PromoRedeemResult.Expired -> showErrorSnackbar(getString(R.string.promo_expired), "Edit") { showPromoCodeDialog() }
                    is PromoRedeemResult.Lifetime -> {
                        entitlement.setPurchasedPro(true)
                        Toast.makeText(this, R.string.promo_ok, Toast.LENGTH_LONG).show()
                    }
                    is PromoRedeemResult.Until -> {
                        entitlement.extendProUntil(r.epochMs)
                        Toast.makeText(this, R.string.promo_ok, Toast.LENGTH_LONG).show()
                    }
                }
                entitlement.refreshFromStorage()
                refreshProUi()
                d.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
                val fileLastOpened = itemView.findViewById<TextView>(R.id.file_last_opened)

                fileName.text = entry.displayName
                if (entry.lastOpenedAt > 0L) {
                    fileLastOpened.visibility = View.VISIBLE
                    fileLastOpened.text = getString(
                        R.string.last_opened,
                        android.text.format.DateUtils.getRelativeTimeSpanString(
                            entry.lastOpenedAt,
                            System.currentTimeMillis(),
                            android.text.format.DateUtils.MINUTE_IN_MILLIS
                        )
                    )
                } else {
                    fileLastOpened.visibility = View.GONE
                }

                itemView.setOnClickListener {
                    drawerLayout.closeDrawer(drawerMenu)
                    openRecentUri(entry.uri)
                }

                itemView.setOnLongClickListener { anchor ->
                    val popup = PopupMenu(this, anchor)
                    popup.menu.add(0, 0, 0, R.string.remove_from_recent)
                    popup.setOnMenuItemClickListener {
                        RecentFilesPrefs.removeRecent(this, entry.uri)
                        refreshDrawerRecentList()
                        true
                    }
                    popup.show()
                    true
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

        view.findViewById<View>(R.id.sectionCloud).visibility =
            if (ENABLE_CLOUD_CONNECT) View.VISIBLE else View.GONE
        if (ENABLE_CLOUD_CONNECT) {
            val btnConnectGitHub = view.findViewById<Button>(R.id.btnConnectGitHub)
            val btnConnectGoogleDrive = view.findViewById<Button>(R.id.btnConnectGoogleDrive)
            btnConnectGitHub.text = if (CloudPrefs.isGitHubConnected(this)) getString(R.string.connected_github) else getString(R.string.connect_github)
            btnConnectGoogleDrive.text = if (CloudPrefs.isGoogleDriveConnected(this)) getString(R.string.connected_google_drive) else getString(R.string.connect_google_drive)
            btnConnectGitHub.setOnClickListener {
                GitHubOAuthHelper.launchSignIn(
                    this,
                    getString(R.string.cloud_github_client_id),
                    getString(R.string.cloud_github_client_secret).ifBlank { null }
                )
                dialog.dismiss()
            }
            btnConnectGoogleDrive.setOnClickListener {
                val client = GoogleDriveHelper.getSignInClient(this, getString(R.string.cloud_google_web_client_id).ifBlank { null })
                googleSignInLauncher.launch(client.signInIntent)
                dialog.dismiss()
            }
        }

        val checkLineNumbers = view.findViewById<CheckBox>(R.id.checkLineNumbers)
        checkLineNumbers.isChecked = EditorPrefs.getShowLineNumbers(this)
        checkLineNumbers.setOnCheckedChangeListener { _, isChecked ->
            EditorPrefs.setShowLineNumbers(this@MainActivity, isChecked)
            lineNumberView.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        val editFontSize = view.findViewById<EditText>(R.id.editEditorFontSize)
        val btnFontSizeDown = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnEditorFontSizeDown)
        val btnFontSizeUp = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnEditorFontSizeUp)

        fun syncFontSizeField() {
            editFontSize.setText(EditorPrefs.getEditorFontSizeSp(this@MainActivity).toInt().toString())
        }

        fun applyFontSizeSp(sizeSp: Float) {
            val coerced = sizeSp.coerceIn(EditorPrefs.MIN_FONT_SIZE_SP, EditorPrefs.MAX_FONT_SIZE_SP)
            EditorPrefs.setEditorFontSizeSp(this@MainActivity, coerced)
            editFontSize.setText(coerced.toInt().toString())
            applyEditorFontSize()
        }

        fun commitFontSizeFromField() {
            val parsed = editFontSize.text?.toString()?.trim()?.toIntOrNull()
            if (parsed == null) {
                syncFontSizeField()
                return
            }
            applyFontSizeSp(parsed.toFloat())
        }

        syncFontSizeField()

        btnFontSizeDown.setOnClickListener {
            val cur = editFontSize.text?.toString()?.toIntOrNull()
                ?: EditorPrefs.getEditorFontSizeSp(this@MainActivity).toInt()
            applyFontSizeSp((cur - 1).toFloat())
        }
        btnFontSizeUp.setOnClickListener {
            val cur = editFontSize.text?.toString()?.toIntOrNull()
                ?: EditorPrefs.getEditorFontSizeSp(this@MainActivity).toInt()
            applyFontSizeSp((cur + 1).toFloat())
        }
        editFontSize.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                commitFontSizeFromField()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(editFontSize.windowToken, 0)
                true
            } else {
                false
            }
        }
        editFontSize.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) commitFontSizeFromField()
        }

        view.findViewById<Button>(R.id.btnManageTemplates).setOnClickListener {
            dialog.dismiss()
            showManageTemplatesDialog()
        }

        val checkDebugPro = view.findViewById<MaterialCheckBox>(R.id.checkDebugPro)
        if (BuildConfig.DEBUG) {
            checkDebugPro.visibility = View.VISIBLE
            checkDebugPro.isChecked = entitlement.state.value.debugProOverride
            checkDebugPro.setOnCheckedChangeListener { _, checked ->
                entitlement.setDebugProOverride(checked)
                refreshProUi()
            }
        }

        dialog.show()
    }

    private fun showRestoreOneHiddenBuiltinDialog(onRestored: () -> Unit) {
        val hiddenIds = UserTemplatesPrefs.getHiddenBuiltinIds(this)
        val hidden = LatexTemplates.BUILTIN_TEMPLATES.filter { it.id in hiddenIds }
        if (hidden.isEmpty()) return
        val labels = hidden.map { it.name }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.templates_restore_one_title)
            .setItems(labels) { _, which ->
                val t = hidden[which]
                UserTemplatesPrefs.unhideBuiltin(this, t.id)
                Toast.makeText(this, getString(R.string.templates_restore_one_done, t.name), Toast.LENGTH_SHORT).show()
                onRestored()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showManageTemplatesDialog() {
        if (!entitlement.isProEffective()) {
            showProUpsellDialog()
            return
        }
        val manageView = layoutInflater.inflate(R.layout.dialog_manage_templates, null)
        val dialog = MaterialAlertDialogBuilder(this).setView(manageView).create()
        val listHost = manageView.findViewById<LinearLayout>(R.id.manage_templates_list)
        val btnRestoreOneBuiltin = manageView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRestoreOneBuiltin)

        fun repopulate() {
            listHost.removeAllViews()
            btnRestoreOneBuiltin.visibility =
                if (UserTemplatesPrefs.getHiddenBuiltinIds(this@MainActivity).isNotEmpty()) View.VISIBLE else View.GONE
            val templates = UserTemplatesPrefs.getEffectiveTemplates(this)
            if (templates.isEmpty()) {
                TextView(this).apply {
                    text = getString(R.string.template_empty_list)
                    setPadding(0, 16, 0, 16)
                }.also { listHost.addView(it) }
                return
            }
            for (t in templates) {
                val row = layoutInflater.inflate(R.layout.item_manage_template_row, listHost, false)
                row.findViewById<TextView>(R.id.template_row_name).text = t.name
                row.findViewById<TextView>(R.id.template_row_desc).text = t.description
                row.findViewById<TextView>(R.id.template_row_tag).text =
                    if (t.isBuiltin) getString(R.string.template_tag_builtin) else getString(R.string.template_tag_custom)
                row.findViewById<Button>(R.id.btnTemplateRowEdit).setOnClickListener {
                    showTemplateEditorDialog(editing = t) { repopulate() }
                }
                row.findViewById<Button>(R.id.btnTemplateRowDelete).setOnClickListener {
                    MaterialAlertDialogBuilder(this)
                        .setMessage(getString(R.string.template_delete_confirm, t.name))
                        .setPositiveButton(R.string.delete) { _, _ ->
                            if (t.isBuiltin) UserTemplatesPrefs.hideBuiltin(this, t.id)
                            else UserTemplatesPrefs.deleteCustomTemplate(this, t.id)
                            repopulate()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
                listHost.addView(row)
            }
        }

        repopulate()

        manageView.findViewById<Button>(R.id.btnAddTemplate).setOnClickListener {
            showTemplateEditorDialog(editing = null) { repopulate() }
        }
        btnRestoreOneBuiltin.setOnClickListener {
            showRestoreOneHiddenBuiltinDialog { repopulate() }
        }
        manageView.findViewById<Button>(R.id.btnManageTemplatesClose).setOnClickListener { dialog.dismiss() }

        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun showTemplateEditorDialog(editing: LatexTemplates.Template? = null, onSaved: () -> Unit = {}) {
        val isNew = editing == null
        val view = layoutInflater.inflate(R.layout.dialog_edit_template, null)
        val dialog = MaterialAlertDialogBuilder(this).setView(view).create()

        view.findViewById<TextView>(R.id.edit_template_title).setText(
            if (isNew) R.string.template_new else R.string.template_edit_title
        )
        val startFromRow = view.findViewById<View>(R.id.startFromRow)
        val spinner = view.findViewById<Spinner>(R.id.spinnerStartFromBuiltin)
        val editName = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.editTemplateName)
        val editDesc = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.editTemplateDescription)
        val editBody = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.editTemplateBody)

        if (isNew) {
            startFromRow.visibility = View.VISIBLE
            val builtins = LatexTemplates.BUILTIN_TEMPLATES
            val names = builtins.map { it.name }
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinner.adapter = adapter
            var ignoreSpinner = true
            fun applyBuiltin(index: Int) {
                val t = builtins.getOrNull(index) ?: return
                editName.setText(t.name)
                editDesc.setText(t.description)
                editBody.setText(t.content)
            }
            applyBuiltin(0)
            spinner.post { ignoreSpinner = false }
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                    if (ignoreSpinner) return
                    applyBuiltin(position)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        } else {
            startFromRow.visibility = View.GONE
            val t = editing!!
            editName.setText(t.name)
            editDesc.setText(t.description)
            editBody.setText(t.content)
        }

        view.findViewById<Button>(R.id.btnEditTemplateCancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<Button>(R.id.btnEditTemplateSave).setOnClickListener {
            val name = editName.text?.toString()?.trim() ?: ""
            val desc = editDesc.text?.toString()?.trim() ?: ""
            val body = editBody.text?.toString() ?: ""
            if (name.isEmpty()) {
                showErrorSnackbar(getString(R.string.template_name_required), "Fix") { editName.requestFocus() }
                return@setOnClickListener
            }
            if (isNew) {
                val id = "custom_" + UUID.randomUUID().toString()
                UserTemplatesPrefs.upsertCustomTemplate(
                    this,
                    LatexTemplates.Template(id, name, desc, body, isBuiltin = false)
                )
            } else {
                val old = editing!!
                if (old.isBuiltin) {
                    UserTemplatesPrefs.hideBuiltin(this, old.id)
                    val id = "custom_" + UUID.randomUUID().toString()
                    UserTemplatesPrefs.upsertCustomTemplate(
                        this,
                        LatexTemplates.Template(id, name, desc, body, isBuiltin = false)
                    )
                } else {
                    UserTemplatesPrefs.upsertCustomTemplate(
                        this,
                        old.copy(name = name, description = desc, content = body)
                    )
                }
            }
            Toast.makeText(this, getString(R.string.template_saved), Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            onSaved()
        }

        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.95f).toInt(),
            (resources.displayMetrics.heightPixels * 0.85f).toInt()
        )
    }

    /** Same fields as menu “Edit author / address”. [onDismiss] runs when the dialog closes (save or cancel). */
    private fun showTemplateDefaultsDialog(onDismiss: (() -> Unit)? = null) {
        val view = layoutInflater.inflate(R.layout.dialog_template_defaults, null)
        val dialog = MaterialAlertDialogBuilder(this).setView(view).create()

        val defaults = TemplateDefaultsPrefs.get(this)
        view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.editTemplateAuthor).setText(defaults.author)
        view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.editTemplateAffiliate).setText(defaults.affiliate)
        view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.editTemplateAddress).setText(defaults.address)
        view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.editTemplateEmail).setText(defaults.email)
        view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.editTemplateOrcid).setText(defaults.orcid)

        dialog.setOnDismissListener { onDismiss?.invoke() }

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

    private fun hideIme() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
        imm.hideSoftInputFromWindow(editText.windowToken, 0)
        window.decorView?.rootView?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    private fun editorFindNext(find: String, forward: Boolean): Boolean {
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
        return if (idx != null && idx >= 0) {
            editText.setSelection(idx, idx + find.length)
            true
        } else {
            false
        }
    }

    private fun countFindMatchesForQuery(find: String): Int {
        if (find.isEmpty()) return 0
        return editText.text.toString().split(find).size - 1
    }

    private fun currentFindMatchOrdinalForQuery(find: String): Int {
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

    private fun refreshFindNavigationBar() {
        if (!::findNavigationBar.isInitialized || findNavigationBar.visibility != View.VISIBLE) return
        val q = activeFindQuery
        if (q.isEmpty()) {
            textFindNavMatch.visibility = View.GONE
            return
        }
        val total = countFindMatchesForQuery(q)
        if (total == 0) {
            textFindNavMatch.visibility = View.GONE
            return
        }
        textFindNavMatch.visibility = View.VISIBLE
        textFindNavMatch.text = getString(
            R.string.find_match_count,
            currentFindMatchOrdinalForQuery(q),
            total
        )
    }

    private fun clearFindNavigationMode() {
        activeFindQuery = ""
        activeReplaceQuery = ""
        if (::findNavigationBar.isInitialized) findNavigationBar.visibility = View.GONE
        if (::textFindNavMatch.isInitialized) textFindNavMatch.visibility = View.GONE
    }

    private fun setupFindNavigationBar() {
        btnFindNavPrevious.setOnClickListener {
            if (activeFindQuery.isEmpty()) return@setOnClickListener
            if (!editorFindNext(activeFindQuery, false)) {
                showErrorSnackbar(getString(R.string.find) + ": no match", "Find") { showFindReplaceDialog() }
            }
            refreshFindNavigationBar()
            scrollEditorToCaret()
        }
        btnFindNavNext.setOnClickListener {
            if (activeFindQuery.isEmpty()) return@setOnClickListener
            if (!editorFindNext(activeFindQuery, true)) {
                showErrorSnackbar(getString(R.string.find) + ": no match", "Find") { showFindReplaceDialog() }
            }
            refreshFindNavigationBar()
            scrollEditorToCaret()
        }
        btnFindNavClose.setOnClickListener {
            clearFindNavigationMode()
            hideIme()
        }
    }

    private fun dismissFindDialogShowHeaderNav(
        dialog: androidx.appcompat.app.AlertDialog,
        findStr: String,
        replaceStr: String,
        forward: Boolean
    ) {
        activeFindQuery = findStr
        activeReplaceQuery = replaceStr
        dialog.window?.let { w ->
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            w.currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
            imm.hideSoftInputFromWindow(w.decorView.windowToken, 0)
        }
        hideIme()
        dialog.dismiss()
        findNavigationBar.visibility = View.VISIBLE
        editText.clearFocus()
        if (findStr.isNotEmpty()) {
            if (!editorFindNext(findStr, forward)) {
                showErrorSnackbar(getString(R.string.find) + ": no match", "Find") { showFindReplaceDialog() }
            }
        }
        refreshFindNavigationBar()
        scrollEditorToCaret()
    }

    private fun showFindReplaceDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_find_replace, null)
        val dialog = MaterialAlertDialogBuilder(this).setView(view).create()

        val editFind = view.findViewById<EditText>(R.id.editFind)
        val editReplace = view.findViewById<EditText>(R.id.editReplace)
        val findMatchCount = view.findViewById<TextView>(R.id.findMatchCount)
        val btnFindPrevious = view.findViewById<ImageButton>(R.id.btnFindPrevious)
        val btnFindNext = view.findViewById<ImageButton>(R.id.btnFindNext)
        val btnReplace = view.findViewById<Button>(R.id.btnReplace)
        val btnReplaceAll = view.findViewById<Button>(R.id.btnReplaceAll)
        val btnClose = view.findViewById<Button>(R.id.btnFindClose)

        if (activeFindQuery.isNotEmpty()) {
            editFind.setText(activeFindQuery)
            editReplace.setText(activeReplaceQuery)
        }

        fun countMatches(): Int {
            val find = editFind.text.toString()
            if (find.isEmpty()) return 0
            return countFindMatchesForQuery(find)
        }

        fun currentMatchIndex(): Int {
            val find = editFind.text.toString()
            if (find.isEmpty()) return 0
            return currentFindMatchOrdinalForQuery(find)
        }

        fun updateMatchCount() {
            val total = countMatches()
            if (total == 0) {
                findMatchCount.visibility = View.GONE
            } else {
                findMatchCount.visibility = View.VISIBLE
                findMatchCount.text = getString(R.string.find_match_count, currentMatchIndex(), total)
            }
        }

        fun findNextInDialog(forward: Boolean): Boolean {
            val find = editFind.text.toString()
            if (find.isEmpty()) return false
            val ok = editorFindNext(find, forward)
            if (ok) updateMatchCount()
            return ok
        }

        btnFindNext.setOnClickListener {
            val find = editFind.text.toString()
            if (find.isEmpty()) return@setOnClickListener
            dismissFindDialogShowHeaderNav(dialog, find, editReplace.text.toString(), true)
        }
        btnFindPrevious.setOnClickListener {
            val find = editFind.text.toString()
            if (find.isEmpty()) return@setOnClickListener
            dismissFindDialogShowHeaderNav(dialog, find, editReplace.text.toString(), false)
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
                if (findNextInDialog(true)) {
                    // match count updated inside findNextInDialog
                } else {
                    updateMatchCount()
                }
            } else {
                if (!findNextInDialog(true)) showErrorSnackbar(getString(R.string.find) + ": no match", "Find") { showFindReplaceDialog() }
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
        btnClose.setOnClickListener {
            hideIme()
            dialog.dismiss()
            clearFindNavigationMode()
        }

        dialog.setOnShowListener { updateMatchCount() }
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
                scrollEditorToCaret()
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ -> }
            .show()
    }

    /** Scrolls the editor [ScrollView] so the caret is visible after [EditText.setSelection]. */
    private fun scrollEditorToCaret() {
        editText.post {
            val layout = editText.layout ?: return@post
            val sv = findViewById<ScrollView>(R.id.editorScrollView) ?: return@post
            val inner = sv.getChildAt(0) ?: return@post
            val sel = editText.selectionStart.coerceIn(0, editText.text.length)
            val line = layout.getLineForOffset(sel)
            val lineTop = layout.getLineTop(line)
            val yInScrollContent = editText.top + lineTop
            val viewport = sv.height
            val contentH = inner.height
            val maxScroll = (contentH - viewport).coerceAtLeast(0)
            val target = (yInScrollContent - viewport / 3).coerceIn(0, maxScroll)
            sv.smoothScrollTo(0, target)
            syncEditorScrollBar()
            lineNumberView.invalidate()
        }
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
        scrollEditorToCaret()
    }

    /** After a preview error: leave preview, jump to the reported line (if any), offer save when dirty. */
    private fun navigateFromPreviewErrorToEditor() {
        setPreviewVisible(false)
        val line = lastPreviewErrorLine
        if (line != null) {
            scrollEditorToLine(line)
        } else {
            editText.requestFocus()
            scrollEditorToCaret()
        }
        previewErrorBanner.visibility = View.GONE
        val doc = documents.getOrNull(currentDocIndex)
        if (doc?.isDirty == true) {
            saveCurrentDocument()
            updatePreviewFromLatex(editText.text.toString())
        }
    }

    private fun showInsertListDialog() {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (density * v).toInt()

        val itemEdits = mutableListOf<EditText>()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.insert_list_dialog_title)
            setPadding(0, 0, 0, dp(8))
        })

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
        root.addView(radioGroup)

        root.addView(TextView(this).apply {
            text = getString(R.string.insert_list_items_hint)
            setPadding(0, dp(16), 0, dp(8))
        })

        val listHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        fun addRow(at: Int, initial: String = "") {
            val edit = EditText(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                hint = getString(R.string.insert_list_row_hint)
                maxLines = 1
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                setText(initial)
            }
            val btnRemove = com.google.android.material.button.MaterialButton(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                text = "\u2212"
                textSize = 18f
                contentDescription = getString(R.string.insert_list_remove_row)
                minimumWidth = dp(40)
                minimumHeight = dp(40)
            }
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (at > 0) topMargin = dp(4)
                }
            }
            btnRemove.setOnClickListener {
                if (itemEdits.size <= 1) {
                    edit.setText("")
                } else {
                    val i = itemEdits.indexOf(edit)
                    if (i >= 0) {
                        itemEdits.removeAt(i)
                        listHost.removeViewAt(i)
                    }
                }
            }
            row.addView(btnRemove)
            row.addView(edit)
            val idx = at.coerceIn(0, itemEdits.size)
            itemEdits.add(idx, edit)
            listHost.addView(row, idx)
        }

        val tonalButtonContext = ContextThemeWrapper(this, MaterialR.style.Widget_Material3_Button_TonalButton)
        val btnAbove = com.google.android.material.button.MaterialButton(
            tonalButtonContext,
            null,
            0
        ).apply {
            text = getString(R.string.insert_list_add_line_above)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        btnAbove.setOnClickListener { addRow(0) }

        val btnBelow = com.google.android.material.button.MaterialButton(
            tonalButtonContext,
            null,
            0
        ).apply {
            text = getString(R.string.insert_list_add_line_below)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
            }
        }
        btnBelow.setOnClickListener { addRow(itemEdits.size) }

        root.addView(btnAbove)
        root.addView(listHost)
        root.addView(btnBelow)

        addRow(0, "")

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.insert_list))
            .setView(scroll)
            .setPositiveButton(getString(R.string.insert_list)) { _, _ ->
                val useEnumerate =
                    radioGroup.indexOfChild(radioGroup.findViewById(radioGroup.checkedRadioButtonId)) == 0
                val lines = itemEdits.map { it.text?.toString()?.trim() ?: "" }.filter { it.isNotEmpty() }
                val snippet =
                    if (useEnumerate) LatexSnippets.enumerateItems(lines) else LatexSnippets.itemizeItems(lines)
                insertAtCursor(snippet)
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ -> }
            .show()
    }

    private fun showInsertTableDialog() {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (density * v).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }

        val rowsLabel = TextView(this).apply {
            text = getString(R.string.insert_table_rows)
            setPadding(0, 0, 0, dp(4))
        }
        val editRows = EditText(this).apply {
            setLines(1)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("3")
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        root.addView(rowsLabel)
        root.addView(editRows)

        val colsLabel = TextView(this).apply {
            text = getString(R.string.insert_table_cols)
            setPadding(0, dp(12), 0, dp(4))
        }
        val editCols = EditText(this).apply {
            setLines(1)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("3")
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        root.addView(colsLabel)
        root.addView(editCols)

        val checkHeader = CheckBox(this).apply {
            text = getString(R.string.insert_table_first_row_header)
            setPadding(0, dp(12), 0, dp(8))
        }
        root.addView(checkHeader)

        val cellsLabel = TextView(this).apply {
            text = getString(R.string.insert_table_cells_hint)
            setPadding(0, dp(4), 0, dp(4))
        }
        root.addView(cellsLabel)

        val gridHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(gridHost)

        val cellEdits = mutableListOf<MutableList<EditText>>()

        fun parseRows() = editRows.text.toString().toIntOrNull()?.coerceIn(1, 50) ?: 3
        fun parseCols() = editCols.text.toString().toIntOrNull()?.coerceIn(1, 20) ?: 3

        fun captureValues(): List<List<String>> =
            cellEdits.map { row -> row.map { it.text?.toString() ?: "" } }

        fun syncGridToDimensions() {
            val rows = parseRows()
            val cols = parseCols()
            if (cellEdits.size == rows && cellEdits.isNotEmpty() && cellEdits[0].size == cols) return
            val old = captureValues()
            gridHost.removeAllViews()
            cellEdits.clear()
            for (r in 0 until rows) {
                val rowLayout = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        if (r > 0) topMargin = dp(4)
                    }
                }
                val rowList = mutableListOf<EditText>()
                for (c in 0 until cols) {
                    val ed = EditText(this@MainActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            marginEnd = if (c < cols - 1) dp(6) else 0
                        }
                        maxLines = 2
                        hint = getString(R.string.insert_table_cell_hint, r + 1, c + 1)
                        setPadding(dp(8), dp(6), dp(8), dp(6))
                        setText(old.getOrNull(r)?.getOrNull(c) ?: "")
                    }
                    rowList.add(ed)
                    rowLayout.addView(ed)
                }
                cellEdits.add(rowList)
                gridHost.addView(rowLayout)
            }
        }

        val syncFocus = View.OnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) syncGridToDimensions()
        }
        editRows.setOnFocusChangeListener(syncFocus)
        editCols.setOnFocusChangeListener(syncFocus)

        syncGridToDimensions()

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.insert_table))
            .setView(scroll)
            .setPositiveButton(getString(R.string.insert_table)) { _, _ ->
                syncGridToDimensions()
                val rows = parseRows()
                val cols = parseCols()
                val firstRowHeader = checkHeader.isChecked
                val tableRows =
                    (0 until rows).map { r ->
                        (0 until cols).map { c ->
                            cellEdits[r][c].text?.toString()?.trim() ?: ""
                        }
                    }
                val snippet = LatexSnippets.tabular(tableRows, firstRowHeader)
                insertAtCursor(snippet)
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ -> }
            .show()
    }

    private fun showInsertTikzDialog() {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (density * v).toInt()

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.insert_tikz_canvas_help)
            textSize = 12f
            setPadding(0, 0, 0, dp(8))
        })

        root.addView(TextView(this).apply { text = getString(R.string.insert_tikz_tool_label) })
        val toolNames = arrayOf(
            getString(R.string.insert_tikz_tool_knot),
            getString(R.string.insert_tikz_tool_line),
            getString(R.string.insert_tikz_tool_circle),
            getString(R.string.insert_tikz_tool_rectangle),
            getString(R.string.insert_tikz_tool_dot),
            getString(R.string.insert_tikz_tool_text)
        )
        val toolSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, toolNames)
        }
        root.addView(toolSpinner)

        root.addView(TextView(this).apply {
            text = getString(R.string.insert_tikz_preset_label)
            setPadding(0, dp(8), 0, 0)
        })
        val presetLabels = buildList {
            add(getString(R.string.insert_tikz_preset_custom))
            for (preset in TikzPresets.PRESETS) {
                add(getString(preset.labelRes))
            }
        }
        val presetSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, presetLabels)
        }
        root.addView(presetSpinner)

        root.addView(TextView(this).apply { text = getString(R.string.insert_tikz_flip_label) })
        val flipEt = EditText(this).apply {
            hint = getString(R.string.insert_tikz_flip_hint)
        }
        root.addView(flipEt)

        val guidesCb = CheckBox(this).apply {
            text = getString(R.string.insert_tikz_guides)
        }
        root.addView(guidesCb)

        val rotateRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val rotateTv = TextView(this).apply {
            text = getString(R.string.insert_tikz_rotate_label) + ": 0°"
        }
        val seekRotate = SeekBar(this).apply { max = 360 }
        rotateRow.addView(
            rotateTv,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        rotateRow.addView(
            seekRotate,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        root.addView(rotateRow)
        seekRotate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                rotateTv.text = getString(R.string.insert_tikz_rotate_label) + ": ${progress}°"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val canvas = TikzCanvasView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(380)
            )
            onRequestTextAt = { p ->
                val input = EditText(this@MainActivity)
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(getString(R.string.insert_tikz_label_dialog_title))
                    .setView(input)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        addLabel(p, input.text?.toString() ?: "")
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            }
        }
        root.addView(canvas)

        root.addView(TextView(this).apply {
            text = getString(R.string.insert_tikz_quick_templates)
            setPadding(0, dp(12), 0, dp(4))
        })

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.insert_tikz_dialog_title))
            .setView(scroll.apply { addView(root) })
            .setPositiveButton(getString(R.string.insert_tikz)) { _, _ ->
                insertAtCursor(
                    canvas.buildTikzString(
                        flipEt.text?.toString() ?: "",
                        guidesCb.isChecked,
                        seekRotate.progress.toFloat()
                    )
                )
            }
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
            root.addView(btn)
        }

        toolSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                canvas.tool = TikzCanvasView.Tool.entries[position]
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        presetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == 0) {
                    canvas.clearAll()
                    flipEt.setText("")
                    return
                }
                val preset = TikzPresets.PRESETS[position - 1]
                canvas.post {
                    canvas.loadPreset(preset.ptsUnits, preset.circlesUnits)
                    flipEt.setText(preset.flipList ?: "")
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        dialog.show()
    }

    private fun showInsertPopupMenu(anchor: View) {
        val popup = android.widget.PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.menu_insert, popup.menu)
        popup.setForceShowIcon(true)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_insert_image -> { pickImageFromGallery.launch("image/*"); true }
                R.id.action_insert_tikz -> { showInsertTikzDialog(); true }
                R.id.action_insert_table -> { showInsertTableDialog(); true }
                R.id.action_insert_list -> { showInsertListDialog(); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun punctuationPairsForDialog(): List<Pair<String, String>> {
        val defaults = PunctuationDefaults.ENTRIES
        val base = if (!entitlement.isProEffective()) {
            defaults
        } else {
            defaults + PunctuationPrefs.getCustomEntries(this).map { e -> e.label to e.insertText }
        }
        return PunctuationPrefs.orderedPunctuationPairs(this, base)
    }

    private fun fillPunctuationGrid(
        grid: android.widget.GridLayout,
        pairs: List<Pair<String, String>>,
        onPick: (String) -> Unit
    ) {
        grid.removeAllViews()
        val columnCount = 4
        grid.rowCount = (pairs.size + columnCount - 1) / columnCount
        grid.columnCount = columnCount
        val dp = resources.displayMetrics.density
        val margin = (dp * 4).toInt()
        val rowHeight = (dp * 40).toInt()
        for ((index, pair) in pairs.withIndex()) {
            val (label, text) = pair
            val itemView = layoutInflater.inflate(R.layout.item_symbol_button, grid, false)
            val btn = itemView.findViewById<com.google.android.material.button.MaterialButton>(R.id.symbolButton)
            btn.text = label
            btn.setOnClickListener { onPick(text) }
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
    }

    private fun showPunctuationDialog() {
        if (isFinishing) return
        val view = layoutInflater.inflate(R.layout.dialog_punctuation_grid, null)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(view)
            .setCancelable(true)
            .create()

        val grid = view.findViewById<android.widget.GridLayout>(R.id.punctuation_grid)
        val btnManage = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_punctuation_manage)
        val btnClose = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_punctuation_close)

        fun repopulate() {
            fillPunctuationGrid(grid, punctuationPairsForDialog()) { text ->
                PunctuationPrefs.recordInsertUse(this@MainActivity, text)
                insertAtCursor(text)
                dialog.dismiss()
            }
        }
        btnManage.visibility = if (entitlement.isProEffective()) View.VISIBLE else View.GONE
        btnManage.setOnClickListener {
            dialog.dismiss()
            showManageCustomPunctuationDialog { showPunctuationDialog() }
        }
        btnClose.setOnClickListener { dialog.dismiss() }
        repopulate()

        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9f).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun showManageCustomPunctuationDialog(done: (() -> Unit)? = null) {
        if (!entitlement.isProEffective()) {
            showProUpsellDialog()
            return
        }
        val scroll = ScrollView(this)
        val listHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(listHost, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        fun repopulate() {
            listHost.removeAllViews()
            val entries = PunctuationPrefs.getCustomEntries(this)
            if (entries.isEmpty()) {
                TextView(this).apply {
                    text = getString(R.string.template_empty_list)
                    setPadding(0, 16, 0, 16)
                }.also { listHost.addView(it) }
            } else {
                for (e in entries) {
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, 8, 0, 8)
                    }
                    val tv = TextView(this).apply {
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        text = "${e.label} → ${e.insertText.take(40)}"
                    }
                    val btnEdit = Button(this).apply {
                        text = getString(R.string.edit)
                        setOnClickListener {
                            showEditCustomPunctuationDialog(e) { repopulate() }
                        }
                    }
                    val btnDel = Button(this).apply {
                        text = getString(R.string.delete)
                        setOnClickListener {
                            PunctuationPrefs.delete(this@MainActivity, e.id)
                            repopulate()
                        }
                    }
                    row.addView(tv)
                    row.addView(btnEdit)
                    row.addView(btnDel)
                    listHost.addView(row)
                }
            }
            val btnAdd = com.google.android.material.button.MaterialButton(this).apply {
                text = getString(R.string.punctuation_add)
                setOnClickListener {
                    showEditCustomPunctuationDialog(null) { repopulate() }
                }
            }
            listHost.addView(btnAdd)
        }
        repopulate()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.punctuation_manage_custom)
            .setView(scroll)
            .setPositiveButton(R.string.close) { d, _ ->
                d.dismiss()
                done?.invoke()
            }
            .show()
    }

    private fun showEditCustomPunctuationDialog(
        existing: PunctuationPrefs.CustomEntry?,
        onSaved: () -> Unit
    ) {
        val view = layoutInflater.inflate(R.layout.dialog_punctuation_edit, null)
        val editLabel = view.findViewById<EditText>(R.id.edit_punct_label)
        val editInsert = view.findViewById<EditText>(R.id.edit_punct_insert)
        if (existing != null) {
            editLabel.setText(existing.label)
            editInsert.setText(existing.insertText)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) R.string.punctuation_add else R.string.edit)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val label = editLabel.text?.toString()?.trim().orEmpty()
                val ins = editInsert.text?.toString().orEmpty()
                if (label.isEmpty() && ins.isEmpty()) return@setPositiveButton
                val id = existing?.id ?: UUID.randomUUID().toString()
                PunctuationPrefs.upsert(this, PunctuationPrefs.CustomEntry(id, label.ifEmpty { ins.take(12) }, ins))
                onSaved()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private val pickImageFromGallery = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { copyImageUriToAppDirAndInsert(it) }
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
            showErrorSnackbar("Insert image failed: ${e.message ?: "Unknown error"}", "Pick Again") {
                pickImageFromGallery.launch("image/*")
            }
        }
    }

    private fun setPreviewBaseDirForImages(baseDir: String) {
        com.omariskandarani.livelatexapp.latex.currentBaseDir = baseDir
    }

    private fun filteredTemplatesForTier(): List<LatexTemplates.Template> {
        val all = UserTemplatesPrefs.getEffectiveTemplates(this)
        if (entitlement.isProEffective()) return all
        return all.filter { it.isBuiltin }
    }

    private fun showTemplatesDialog(onDismiss: (() -> Unit)? = null) {
        val templates = filteredTemplatesForTier()
        if (templates.isEmpty()) {
            showErrorSnackbar(getString(R.string.template_empty_list), "Manage") { showManageTemplatesDialog() }
            onDismiss?.invoke()
            return
        }

        val view = layoutInflater.inflate(R.layout.dialog_templates, null)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(view)
            .create()
        // Keep drawer closed while template picker is active to avoid returning to fly-in menu.
        drawerLayout.closeDrawer(drawerMenu)
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, drawerMenu)
        dialog.setOnDismissListener {
            drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, drawerMenu)
            onDismiss?.invoke()
        }

        val templatesList = view.findViewById<LinearLayout>(R.id.templates_list)
        val btnCancel = view.findViewById<Button>(R.id.btnCancel)

        btnCancel.setOnClickListener { dialog.dismiss() }

        templates.forEach { template ->
            val itemView = layoutInflater.inflate(R.layout.item_template, templatesList, false)
            val templateName = itemView.findViewById<TextView>(R.id.template_name)
            val templateDescription = itemView.findViewById<TextView>(R.id.template_description)

            templateName.text = template.name
            templateDescription.text = template.description

            itemView.setOnClickListener {
                drawerLayout.closeDrawer(drawerMenu)
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
        setPreviewVisible(false)
        drawerLayout.closeDrawer(drawerMenu)
        loadCurrentDocIntoEditor()
        refreshDocumentTabs()
        focusEditorAfterDocumentChange()
        Toast.makeText(this, "Created from ${template.name}", Toast.LENGTH_SHORT).show()
    }

    private fun openRecentUri(uriString: String) {
        val uri = try {
            Uri.parse(uriString)
        } catch (_: Exception) {
            showErrorSnackbar(getString(R.string.open_file) + ": invalid URI", "Open") {
                openDocument.launch(arrayOf("text/plain", "application/x-tex", "*/*"))
            }
            return
        }
        if (!loadUriIntoNewDocumentOrFalse(uri)) {
            showErrorSnackbar(getString(R.string.open_file) + ": failed", "Open") {
                openDocument.launch(arrayOf("text/plain", "application/x-tex", "*/*"))
            }
        }
    }

    /**
     * Same as [openUri] but returns success; used for startup restore and silent paths.
     * On failure the URI may be removed from recents by the caller.
     */
    private fun loadUriIntoNewDocumentOrFalse(uri: Uri): Boolean {
        return try {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            contentResolver.openInputStream(uri)?.use { input ->
                val content = input.bufferedReader().readText()
                val name = getFileNameFromUri(uri)
                if (DocumentsContract.isDocumentUri(this, uri)) {
                    RecentFilesPrefs.addRecent(this, uri.toString(), name)
                }
                val sourcePath = resolveTexAbsolutePath(uri)
                val newDoc = LatexDocument(content, uri, name, false, sourceAbsolutePath = sourcePath)
                syncEditorToCurrentDoc()
                documents.add(newDoc)
                currentDocIndex = documents.size - 1
                setPreviewVisible(true)
                loadCurrentDocIntoEditor()
                refreshDocumentTabs()
                focusEditorAfterDocumentChange()
                true
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    /** Opens the most recent restorable entry from [RecentFilesPrefs], dropping dead URIs. */
    private fun tryRestoreLastRecentFile(): Boolean {
        val entries = RecentFilesPrefs.getRecent(this)
        for (e in entries) {
            val uri = try {
                Uri.parse(e.uri)
            } catch (_: Exception) {
                RecentFilesPrefs.removeRecent(this, e.uri)
                continue
            }
            if (loadUriIntoNewDocumentOrFalse(uri)) {
                return true
            }
            RecentFilesPrefs.removeRecent(this, e.uri)
        }
        return false
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
        updateDocumentStats()
        updateCurrentTabLabel()
        updatePreviewFromLatex(doc.content)
    }

    /** Keep typing flow in the editor after creating/opening a document from menus/dialogs. */
    private fun focusEditorAfterDocumentChange() {
        if (showPreview) return
        editText.post {
            editText.requestFocus()
            scrollEditorToCaret()
        }
    }

    private fun showErrorSnackbar(
        message: CharSequence,
        actionLabel: CharSequence? = null,
        action: (() -> Unit)? = null
    ) {
        val root = findViewById<View>(android.R.id.content)
        val snack = Snackbar.make(root, message, Snackbar.LENGTH_LONG)
        if (actionLabel != null && action != null) {
            snack.setAction(actionLabel) { action() }
        }
        snack.show()
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
        setPreviewVisible(!showPreview)
    }

    /** Show rendered preview and hide the editor (e.g. after opening a file from another app). */
    private fun setPreviewVisible(show: Boolean) {
        if (showPreview == show) return
        showPreview = show
        editorContainer.visibility = if (showPreview) View.GONE else View.VISIBLE
        webView.visibility = if (showPreview) View.VISIBLE else View.GONE
        findViewById<View>(R.id.previewToolbar).visibility = if (showPreview) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btnInsertAdd).visibility = if (showPreview) View.GONE else View.VISIBLE
        findViewById<View>(R.id.btnPunctuation).visibility = if (showPreview) View.GONE else View.VISIBLE
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
        }
        documentTabsContainer.addView(newRow)
        // Highlight selected row
        val selectedTag = if (documents.size > MORE_TABS_THRESHOLD) {
            if (currentDocIndex < 4) currentDocIndex else MORE_TAB_TAG
        } else {
            currentDocIndex.coerceIn(0, (documents.size - 1).coerceAtLeast(0))
        }
        val selectedColor = resolveColor(MaterialR.attr.colorPrimaryContainer)
        val defaultColor = resolveColor(MaterialR.attr.colorSurface)
        for (i in 0 until documentTabsContainer.childCount) {
            val row = documentTabsContainer.getChildAt(i)
            row.setBackgroundColor(if (row.tag == selectedTag) selectedColor else defaultColor)
        }
        updateSaveStatusIcon()
        updateCurrentTabLabel()
    }

    private fun resolveColor(attrRes: Int): Int {
        val typedValue = TypedValue()
        theme.resolveAttribute(attrRes, typedValue, true)
        return typedValue.data
    }

    private fun updateCurrentTabLabel() {
        val doc = documents.getOrNull(currentDocIndex) ?: return
        if (documents.size > MORE_TABS_THRESHOLD && currentDocIndex >= 4) {
            for (i in 0 until documentTabsContainer.childCount) {
                val row = documentTabsContainer.getChildAt(i)
                if (row.tag == MORE_TAB_TAG && row is LinearLayout) {
                    val name = doc.effectiveName() + if (doc.isDirty) " •" else ""
                    val titleIndex = if (row.childCount >= 3) 1 else 0
                    (row.getChildAt(titleIndex) as? TextView)?.text =
                        "${getString(R.string.more_tabs, documents.size)} · $name"
                    row.contentDescription = getString(
                        R.string.tab_document_desc,
                        doc.effectiveName(),
                        if (doc.isDirty) getString(R.string.unsaved_changes) else getString(R.string.saved)
                    )
                    if (row.childCount >= 3) {
                        row.getChildAt(0).visibility = if (doc.isDirty) View.VISIBLE else View.GONE
                    }
                    break
                }
            }
            updateSaveStatusIcon()
            return
        }
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
                resolveTexAbsolutePath(uri)?.let { documents[idx].sourceAbsolutePath = it }
            }
        } catch (e: Exception) {
            runOnUiThread {
                showErrorSnackbar(e.message ?: "Save failed", "Save As") {
                    pendingSaveThenCloseIndex = null
                    pendingSaveContent = editText.text.toString()
                    createDocument.launch("document.tex")
                }
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
        if (!loadUriIntoNewDocumentOrFalse(uri)) {
            showErrorSnackbar(getString(R.string.open_file) + ": failed", "Open") {
                openDocument.launch(arrayOf("text/plain", "application/x-tex", "*/*"))
            }
        }
    }

    /**
     * Best-effort absolute path to the opened .tex file so [LatexHtml.wrapWithInputs] can resolve
     * \\input/\\include and \\includegraphics relative to the project folder. Works for [file] URIs
     * and many [content] URIs on local storage (primary external, raw id, or legacy _data).
     */
    private fun resolveTexAbsolutePath(uri: Uri): String? {
        try {
            if (uri.scheme == "file") {
                val p = uri.path ?: return null
                val f = File(p)
                return if (f.isFile) f.absolutePath else null
            }
            if (uri.scheme == "content" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                if (DocumentsContract.isDocumentUri(this, uri)) {
                    val docId = try {
                        DocumentsContract.getDocumentId(uri)
                    } catch (_: Exception) {
                        null
                    } ?: return null
                    when {
                        docId.startsWith("primary:") &&
                            uri.authority == "com.android.externalstorage.documents" -> {
                            val rel = docId.removePrefix("primary:")
                            val f = File(Environment.getExternalStorageDirectory(), rel)
                            if (f.isFile) return f.absolutePath
                        }
                        docId.startsWith("raw:") -> {
                            val f = File(docId.removePrefix("raw:"))
                            if (f.isFile) return f.absolutePath
                        }
                    }
                }
            }
            if (uri.scheme == "content") {
                val projection = arrayOf(MediaStore.MediaColumns.DATA)
                contentResolver.query(uri, projection, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                        if (idx >= 0) {
                            val path = c.getString(idx)
                            if (!path.isNullOrEmpty()) {
                                val f = File(path)
                                if (f.isFile) return f.absolutePath
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) { }
        return null
    }

    private fun getFileNameFromUri(uri: Uri): String {
        try {
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
        } catch (_: Exception) {
            // e.g. content://media/external/file/... — query can fail even when openInputStream works
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
            ?: getString(R.string.untitled)
    }

    private fun setupLivePreview() {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.isFocusable = true
        webView.isLongClickable = true
        webView.addJavascriptInterface(TikzPreviewJsBridge(), "TikzAndroid")
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
                updateDocumentStats()
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
            val doc = documents.getOrNull(currentDocIndex)
            val mainPath = doc?.sourceAbsolutePath?.takeIf { File(it).isFile }
            val imgBase = applicationContext.getDir("latex_images", Context.MODE_PRIVATE).absolutePath
            // Resolve \includegraphics and project-relative paths; keep in sync with LatexHtml.wrapWithInputs (which sets these again when mainPath != null).
            if (mainPath == null) {
                com.omariskandarani.livelatexapp.latex.currentBaseDir = imgBase
                TikzRenderer.currentBaseDir = imgBase
            } else {
                // parentFile / parent can be null or empty for odd paths; never use "" — TikzRenderer resolves relative to File("").
                val projectBase = File(mainPath).parentFile?.absolutePath?.takeUnless { it.isEmpty() } ?: imgBase
                com.omariskandarani.livelatexapp.latex.currentBaseDir = projectBase
                TikzRenderer.currentBaseDir = projectBase
            }
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
                val tikzBtn = getString(R.string.render_tikz)
                val tikzAndroidNote = getString(R.string.tikz_preview_no_compiler_note)
                html = if (mainPath != null) {
                    LatexHtml.wrapWithInputs(wrapped, mainPath, tikzBtn, tikzAndroidNote)
                } else {
                    LatexHtml.wrap(wrapped, tikzBtn, tikzAndroidNote)
                }
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
                    previewErrorBanner.contentDescription = (if (lineNum != null) getString(R.string.preview_error_banner, lineNum, fullMsg) else getString(R.string.preview_error_banner_no_line, fullMsg)) + ". Tap to fix: opens editor, goes to line if known, saves automatically when there are unsaved changes."
                    previewErrorBanner.visibility = View.VISIBLE
                }
            }
            withContext(Dispatchers.Main) {
                val previewBaseUrl = previewHtmlBaseUrl(com.omariskandarani.livelatexapp.latex.currentBaseDir, imgBase)
                webView.loadDataWithBaseURL(
                    previewBaseUrl,
                    html,
                    "text/html",
                    "UTF-8",
                    null
                )
                previewLoadingIndicator.visibility = View.GONE
            }
        }
    }

    /** Directory as `file://` URL with trailing slash for [WebView.loadDataWithBaseURL] so local figures load. */
    private inner class TikzPreviewJsBridge {
        @JavascriptInterface
        fun render(key: String) {
            runOnUiThread { renderTikzFromPreview(key) }
        }
    }

    private fun renderTikzFromPreview(key: String) {
        CoroutineScope(Dispatchers.Default).launch {
            val svg = LatexHtml.renderLazyTikzKeyToSvg(key)
            withContext(Dispatchers.Main) {
                if (svg != null) {
                    updatePreviewFromLatex(editText.text?.toString() ?: "")
                } else {
                    showErrorSnackbar(getString(R.string.tikz_render_failed), "Retry") { renderTikzFromPreview(key) }
                }
            }
        }
    }

    private fun previewHtmlBaseUrl(currentBaseDir: String?, fallbackFilesDir: String): String {
        val path = currentBaseDir?.takeUnless { it.isEmpty() } ?: fallbackFilesDir
        val dir = File(path)
        if (!dir.isDirectory) dir.mkdirs()
        val u = dir.toURI().toString()
        return if (u.endsWith("/")) u else "$u/"
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