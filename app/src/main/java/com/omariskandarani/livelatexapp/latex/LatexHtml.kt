package com.omariskandarani.livelatexapp.latex

import java.io.File
import java.io.IOException
import java.nio.file.Paths
import java.util.LinkedHashMap
import kotlin.text.Regex
import kotlin.text.RegexOption

/**
 * Minimal LaTeX → HTML previewer for prose + MathJax math.
 * - Parses user \newcommand / \def into MathJax macros
 * - Converts common prose constructs (sections, lists, tables, theorems, etc.)
 * - Leaves math regions intact ($...$, \[...\], \(...\), equation/align/...)
 * - Inserts invisible line anchors to sync scroll with editor
 */
object LatexHtml {
    private val lazyTikzJobs = java.util.Collections.synchronizedMap(LinkedHashMap<String, String>())

    /** Registers a TikZ job for [renderLazyTikzKeyToSvg] / preview “Render TikZ” buttons. */
    @JvmStatic
    internal fun registerTikzRenderJob(key: String, texDoc: String) {
        lazyTikzJobs[key] = texDoc
    }
    // Last computed line maps between original main file lines and merged (inlined) lines
    private var lineMapOrigToMergedJson: String? = null
    private var lineMapMergedToOrigJson: String? = null

    // ─────────────────────────── PUBLIC ENTRY ───────────────────────────
    // BEGIN_DOCUMENT, END_DOCUMENT, slugify → LatexHtmlParsing.kt

    fun wrap(
        texSource: String,
        tikzRenderButtonLabel: String = "Render TikZ",
        tikzNoCompilerNote: String? = null,
    ): String {
        lazyTikzJobs.clear()
        val srcNoComments = stripLineComments(texSource)
        val userMacros    = extractNewcommands(srcNoComments)
        val macrosJs      = buildMathJaxMacros(userMacros)
        val titleMeta     = extractTitleMeta(srcNoComments)
        val tikzPreamble  = TikzRenderer.collectTikzPreamble(srcNoComments)

        // Find body & absolute line offset of the first body line
        val beginIdx  = texSource.indexOf(BEGIN_DOCUMENT)
        val absOffset = if (beginIdx >= 0)
            texSource.substring(0, beginIdx).count { it == '\n' } + 1
        else
            1

        val body0 = stripPreamble(texSource)
        val body1 = stripLineComments(body0)
        // Prepend title/author/date so they always appear before the abstract in the preview
        val body1b = if (titleMeta.title != null || titleMeta.authors != null || titleMeta.dateRaw != null)
            buildMakTitleHtml(titleMeta) + "\n\n" + body1
        else body1
        val body2 = sanitizeForMathJaxProse(body1b)
        val body2b = convertIncludeGraphics(body2)

        val body2c = TikzRenderer.convertTikzPictures(
            body2b, srcNoComments, tikzPreamble, tikzRenderButtonLabel, tikzNoCompilerNote
        )
        val body2d = TikzRenderer.convertSstTikzMacros(body2c, srcNoComments)

        val body3 = applyProseConversions(body2d, titleMeta, absOffset, srcNoComments, tikzPreamble)
        val body3b = convertParagraphsOutsideTags(body3)
        val body4 = applyInlineFormattingOutsideTags(body3b)
        val body4c = fixInlineBoundarySpaces(body4)
        // Insert anchors (no blanket escaping here; we preserve math)
        val withAnchors = injectLineAnchors(body4c, absOffset, everyN = 1)

        return buildHtml(withAnchors, macrosJs, lineMapOrigToMergedJson, lineMapMergedToOrigJson)
    }

    private fun applyProseConversions(s: String, meta: TitleMeta, absOffset: Int,
                                      fullSourceNoComments: String, tikzPreamble: String): String {
        var t = s
        t = convertLlmark(t, absOffset)
        t = convertMakeTitle(t, meta)
        t = convertSiunitx(t)
        t = convertHref(t)
        t = convertSections(t, absOffset)
        t = convertFigureEnvs(t)
        t = convertIncludeGraphics(t)
        t = convertMulticols(t)
        t = convertLongtablesToTables(t)
        t = convertTcolorboxes(t)
        t = convertTableEnvs(t)
        t = convertItemize(t)
        t = convertEnumerate(t)
        t = convertDescription(t)
        t = convertTabulars(t)
        t = convertTheBibliography(t)
        t = stripAuxDirectives(t)
        return t
    }

    // buildHtml → LatexHtmlTemplate.kt

    // ───────────────────────────── MACROS ─────────────────────────────
    private data class Macro(val def: String, val nargs: Int)

    private fun extractNewcommands(s: String): Map<String, Macro> {
        val out = LinkedHashMap<String, Macro>()
        fun parseCommand(cmd: String) {
            val rx = Regex("""\\$cmd\s*\{\\([A-Za-z@]+)\}(?:\s*\[(\d+)])?(?:\s*\[(.*?)\])?\s*\{""", RegexOption.DOT_MATCHES_ALL)
            var pos = 0
            while (true) {
                val m = rx.find(s, pos) ?: break
                val name = m.groupValues[1]
                val nargs = m.groupValues[2].ifEmpty { "0" }.toInt()
                val bodyOpen = m.range.last
                val bodyClose = findBalancedBrace(s, bodyOpen)
                if (bodyClose < 0) { pos = bodyOpen + 1; continue }
                val body = s.substring(bodyOpen + 1, bodyClose).trim()
                out[name] = Macro(body, nargs)
                pos = bodyClose + 1
            }
        }
        parseCommand("newcommand")
        parseCommand("renewcommand")
        parseCommand("providecommand")
        run {
            val rx = Regex("""\\def\\([A-Za-z@]+)\s*\{""")
            var pos = 0
            while (true) {
                val m = rx.find(s, pos) ?: break
                val name = m.groupValues[1]
                val open = m.range.last
                val close = findBalancedBrace(s, open)
                if (close < 0) { pos = open + 1; continue }
                val body = s.substring(open + 1, close).trim()
                out.putIfAbsent(name, Macro(body, 0))
                pos = close + 1
            }
        }
        run {
            val rx = Regex("""\\DeclareMathOperator\*?\s*\{\\([A-Za-z@]+)\}\s*\{""")
            var pos = 0
            while (true) {
                val m = rx.find(s, pos) ?: break
                val name = m.groupValues[1]
                val open = m.range.last
                val close = findBalancedBrace(s, open)
                if (close < 0) { pos = open + 1; continue }
                val opText = s.substring(open + 1, close).trim()
                out.putIfAbsent(name, Macro("\\operatorname{$opText}", 0))
                pos = close + 1
            }
        }
        return out
    }

    private fun buildMathJaxMacros(user: Map<String, Macro>): String {
        val base = linkedMapOf(
            "ae" to Macro("\\unicode{x00E6}", 0),
            "AE" to Macro("\\unicode{x00C6}", 0),
            "vb" to Macro("\\mathbf{#1}", 1),
            "bm" to Macro("\\boldsymbol{#1}", 1),
            "dv" to Macro("\\frac{d #1}{d #2}", 2),
            "pdv" to Macro("\\frac{\\partial #1}{\\partial #2}", 2),
            "abs" to Macro("\\left|#1\\right|", 1),
            "norm" to Macro("\\left\\lVert #1\\right\\rVert", 1),
            "qty" to Macro("\\left(#1\\right)", 1),
            "qtyb" to Macro("\\left[#1\\right]", 1),
            "qed" to Macro("\\square", 0),
            "si" to Macro("\\mathrm{#1}", 1),
            "num" to Macro("{#1}", 1),
            "textrm" to Macro("\\mathrm{#1}", 1),
            "Lam" to Macro("\\Lambda", 0),
            "rc" to Macro("r_c", 0),
        )
        val merged = LinkedHashMap<String, Macro>()
        merged.putAll(base)
        merged.putAll(user)
        val parts = merged.map { (k, v) ->
            if (v.nargs > 0) "\"$k\": [${jsonEscape(v.def)}, ${v.nargs}]"
            else "\"$k\": ${jsonEscape(v.def)}"
        }
        return "{${parts.joinToString(",")}}"
    }

    private fun jsonEscape(tex: String): String =
        "\"" + tex
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "") + "\""

    // —— Config / cache ————————————————————————————————————————————————
    private fun sha256Hex(s: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
    private fun fileUrl(f: File) = f.toURI().toString()

    /** True when running on Android ART/Dalvik — no `pdflatex` / TeX toolchain in the app sandbox. */
    private val isAndroidRuntime: Boolean by lazy {
        try {
            Class.forName("android.os.Build")
            true
        } catch (_: ClassNotFoundException) {
            false
        }
    }

    // Try to find tools once and cache the result
    private data class TikzTools(val dvisvgm: String?, val pdf2svg: String?)
    private var _tikzTools: TikzTools? = null
    private fun findTikzTools(): TikzTools {
        _tikzTools?.let { return it }
        fun which(cmd: String): String? = try {
            val isWin = (System.getProperty("os.name") ?: "").lowercase().contains("win")
            val proc = ProcessBuilder(if (isWin) listOf("where", cmd) else listOf("which", cmd))
                .redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            val ok = proc.waitFor() == 0 && out.isNotBlank()
            if (ok) out.lineSequence().firstOrNull()?.trim() else null
        } catch (_: Throwable) {
            null
        }
        val tools = TikzTools(
            dvisvgm = which("dvisvgm"),
            pdf2svg = which("pdf2svg")
        )
        _tikzTools = tools
        return tools
    }

    private fun run(cmd: List<String>, cwd: File, timeoutMs: Long = 60_000): Pair<Boolean, String> = try {
        val pb = ProcessBuilder(cmd).directory(cwd).redirectErrorStream(true)

        // >>> Add TEXINPUTS so pdflatex finds local *.tex/ TikZ libs in your project
        currentBaseDir?.let { base ->
            val sep = if ((System.getProperty("os.name") ?: "").contains("win", true)) ";" else ":"
            val path = File(base).absolutePath
            // trailing separator keeps the default search path active
            pb.environment()["TEXINPUTS"] = path + sep + File(path, "tex").absolutePath + sep
        }
        // <<<

        val p = pb.start()
        val out = StringBuilder()
        val t = Thread { p.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }
        t.start()
        val finished = p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!finished) {
            p.destroyForcibly()
            false to "Timeout running: $cmd\n$out"
        } else {
            (p.exitValue() == 0) to out.toString()
        }
    } catch (e: IOException) {
        false to (e.message ?: e.toString())
    } catch (e: SecurityException) {
        false to (e.message ?: e.toString())
    } catch (e: Throwable) {
        // Android may surface errno failures not typed as IOException; never crash the app.
        false to (e.message ?: e.toString())
    }

    // convertLongtablesToTables → LatexHtmlBlocks.kt

    // --- path where we cache compiled SVGs
    private fun tikzCacheDir(): File {
        val base = currentBaseDir?.let(::File) ?: File(".")
        val dir  = File(base, ".livelatex-cache/tikz")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun sha1(s: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-1")
        val b  = md.digest(s.toByteArray(Charsets.UTF_8))
        return b.joinToString("") { "%02x".format(it) }
    }


    /** Compile a queued lazy TikZ job by key into the cache. Returns the SVG File on success, null on failure. */
    @JvmStatic
    fun renderLazyTikzKeyToSvg(key: String): File? {
        return try {
            if (isAndroidRuntime) return null

            val texDoc = synchronized(lazyTikzJobs) { lazyTikzJobs[key] } ?: return null

            val cache = tikzCacheDir()
            val svg = File(cache, "${sha1(texDoc)}.svg")
            if (svg.exists()) return svg

            val work = File(cache, "job-$key").apply { mkdirs() }
            val tex = File(work, "fig.tex")
            val pdf = File(work, "fig.pdf")
            tex.writeText(texDoc)

            val (ok1, log1) = run(
                listOf("pdflatex", "-interaction=nonstopmode", "-halt-on-error", "fig.tex"),
                work
            )
            if (!ok1 || !pdf.exists()) {
                File(work, "build.log").writeText(log1)
                return null
            }

            val tools = findTikzTools()
            val (ok2, log2) =
                if (tools.dvisvgm != null)
                    run(listOf(tools.dvisvgm!!, "--pdf", "--no-fonts", "--exact", "-n", "-o", "fig.svg", "fig.pdf"), work)
                else if (tools.pdf2svg != null)
                    run(listOf(tools.pdf2svg!!, "fig.pdf", "fig.svg"), work)
                else false to "Neither dvisvgm nor pdf2svg is available."
            if (!ok2) {
                File(work, "convert.log").writeText(log2)
                return null
            }

            val produced = File(work, "fig.svg")
            if (produced.exists()) {
                svg.writeText(produced.readText())
                svg
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }


    /** Directory containing [mainFilePath], or a non-empty fallback — never `""` (breaks [TikzRenderer] / path joins). */
    private fun directoryForMainTex(mainFilePath: String): String {
        val f = File(mainFilePath)
        f.parentFile?.absolutePath?.takeUnless { it.isEmpty() }?.let { return it }
        f.absoluteFile.parentFile?.absolutePath?.takeUnless { it.isEmpty() }?.let { return it }
        return File(".").absolutePath
    }

    /** Recursively inline all \input{...} and \include{...} files. */
    fun inlineInputs(source: String, baseDir: String, seen: MutableSet<String> = mutableSetOf()): String {
        val rx = Regex("""\\(input|include)\{(.+?)\}""", RegexOption.DOT_MATCHES_ALL)

        var result: String = source

        rx.findAll(source).forEach { m ->
            val cmd = m.groupValues[1]
            val rawPath = m.groupValues[2]
            // Try .tex, .sty, or no extension
            val candidates = listOf(rawPath, "$rawPath.tex", "$rawPath.sty")
            val filePath = candidates
                .map { Paths.get(baseDir, it).toFile() }
                .firstOrNull { it.exists() && it.isFile }
            val absPath = filePath?.absolutePath
            if (absPath != null && absPath !in seen) {
                seen += absPath
                val fileText = filePath.readText()
                val nextBase = filePath.parentFile?.absolutePath?.takeUnless { it.isEmpty() } ?: baseDir
                val inlined = inlineInputs(fileText, nextBase, seen)
                result = result.replace(m.value, inlined)
            } else if (absPath != null && absPath in seen) {
                result = result.replace(m.value, "% Circular input: $rawPath %")
            } else {
                result = result.replace(m.value, "% Missing input: $rawPath %")
            }
        }
        return result
    }

    fun wrapWithInputs(
        texSource: String,
        mainFilePath: String,
        tikzRenderButtonLabel: String = "Render TikZ",
        tikzNoCompilerNote: String? = null,
    ): String {
        val baseDir = directoryForMainTex(mainFilePath)
        currentBaseDir = baseDir  // package-level in LatexHtmlState.kt
        TikzRenderer.currentBaseDir = baseDir

        // Build marked source to compute orig→merged line mapping across \input/\include expansions
        val markerPrefix = "%%LLM"
        val origLines = texSource.split('\n')
        val marked = buildString(texSource.length + origLines.size * 10) {
            origLines.forEachIndexed { idx, line ->
                append(markerPrefix).append(idx + 1).append("%%").append(line)
                if (idx < origLines.lastIndex) append('\n')
            }
        }
        val inlinedMarked = inlineInputs(marked, baseDir)

        // Compute mapping orig line (1-based) -> merged line (1-based)
        val o2m = IntArray(origLines.size) { it + 1 }
        var searchFrom = 0
        for (i in 1..origLines.size) {
            val token = markerPrefix + i + "%%"
            val idx = inlinedMarked.indexOf(token, searchFrom)
            val pos = if (idx >= 0) idx else inlinedMarked.indexOf(token)
            if (pos >= 0) {
                val before = inlinedMarked.substring(0, pos)
                val mergedLine = before.count { it == '\n' } + 1
                o2m[i - 1] = mergedLine
                if (idx >= 0) searchFrom = idx + token.length
            } else {
                // token not found (rare): fallback to previous mapping or 1
                o2m[i - 1] = if (i > 1) o2m[i - 2] else 1
            }
        }

        // Strip markers
        val fullSource = inlinedMarked.replace(Regex("""${markerPrefix}\d+%%"""), "")

        // Build inverse mapping merged -> original using step function (last original line at/ before m)
        val mergedLinesCount = fullSource.count { it == '\n' } + 1
        val m2o = IntArray(mergedLinesCount) { 1 }
        var j = 0 // index into o2m (0-based)
        for (m in 1..mergedLinesCount) {
            while (j + 1 < o2m.size && o2m[j + 1] <= m) j++
            m2o[m - 1] = j + 1 // original line number (1-based)
        }

        // Cache JSON strings for HTML embedding
        lineMapOrigToMergedJson = o2m.joinToString(prefix = "[", postfix = "]") { it.toString() }
        lineMapMergedToOrigJson = m2o.joinToString(prefix = "[", postfix = "]") { it.toString() }

        val html = wrap(fullSource, tikzRenderButtonLabel, tikzNoCompilerNote)
        // keep baseDir for subsequent renders; do not clear to allow incremental refreshes
        return html
    }
}
