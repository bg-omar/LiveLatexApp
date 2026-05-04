package com.omariskandarani.livelatexapp.latex

import java.io.File
import java.security.MessageDigest
import kotlin.text.Regex
import kotlin.text.RegexOption

/**
 * TikZ renderer for Android: preamble collection and placeholder for diagrams.
 * No pdflatex/dvisvgm on device — TikZ blocks are shown as placeholders in preview;
 * full PDF is produced by Export via Rust/Tectonic.
 */
object TikzRenderer {

    var currentBaseDir: String? = null

    private fun tikzCacheDir(): File {
        val base = currentBaseDir?.let(::File) ?: File(".")
        val dir  = File(base, ".livelatex-cache/tikz")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun sha1(s: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val b  = md.digest(s.toByteArray(Charsets.UTF_8))
        return b.joinToString("") { "%02x".format(it) }
    }
    private fun sha256Hex(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
    private fun fileUrl(f: File) = f.toURI().toString()

    /** Optional `[...]` right after `\\begin{tikzpicture}`; avoids `\\[` / `\\]` in Regex (Android ICU). */
    private fun splitTikzPictureOptsAndBody(inner: String): Pair<String, String> {
        val s = inner.trimStart()
        if (!s.startsWith('[')) return "" to inner
        var depth = 0
        var i = 0
        while (i < s.length) {
            when (s[i]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return s.substring(0, i + 1).trim() to s.substring(i + 1)
                }
            }
            i++
        }
        return "" to inner
    }

    private fun collectBalanced(cmd: String, s: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (true) {
            val j = s.indexOf(cmd + "{", startIndex = i)
            if (j < 0) break
            val open = s.indexOf('{', j)
            val close = findBalancedBrace(s, open)
            if (open >= 0 && close > open) {
                out += s.substring(j, close + 1)
                i = close + 1
            } else {
                i = j + cmd.length
            }
        }
        return out
    }

    fun collectTikzPreamble(srcNoComments: String): String {
        val preamble = srcNoComments.substringBefore("\\begin{document}")
        val pkgs = Regex("""\\usepackage(?:\[(?:.*?)\])?\{(.+?)\}""", RegexOption.DOT_MATCHES_ALL)
            .findAll(preamble).joinToString("\n") { it.value }
        val tikzsets = collectBalanced("\\tikzset", preamble).joinToString("\n")
        val libsSet = collectUsetikzlibsFromSource(preamble).toSortedSet()
        val libsLine = if (libsSet.isNotEmpty()) "\\usetikzlibrary{${libsSet.joinToString(",")}}\n" else ""
        val needsTikzCd = preamble.contains("\\begin{tikzcd}") || preamble.contains("\\usepackage{tikz-cd}")
        return buildString {
            appendLine("\\usepackage{tikz}")
            if (needsTikzCd) appendLine("\\usepackage{tikz-cd}")
            if (pkgs.isNotBlank()) appendLine(pkgs)
            append(libsLine)
            if (tikzsets.isNotBlank()) appendLine(tikzsets)
        }
    }

    private fun tikzPreviewBlock(
        key: String,
        renderButtonLabel: String,
        svgMarkup: String?,
        /** When set (e.g. on Android), show this instead of the Render button when no cached SVG exists. */
        noCompilerNote: String? = null,
    ): String {
        val svgPart = if (!svgMarkup.isNullOrBlank()) {
            """<span class="tikz-wrap" style="display:block;margin:0 0 10px 0;">$svgMarkup</span>"""
        } else ""
        val footer = when {
            !svgMarkup.isNullOrBlank() -> ""
            !noCompilerNote.isNullOrBlank() -> {
                val esc = htmlEscapeAll(noCompilerNote)
                """<p class="ll-tikz-no-compiler-note" style="opacity:.9;font-size:0.92em;margin:10px 0 0 0;line-height:1.4;">$esc</p>"""
            }
            renderButtonLabel.isNotBlank() -> {
                val labelEsc = htmlEscapeAll(renderButtonLabel)
                """<button type="button" class="ll-render-tikz" style="font:inherit;padding:8px 14px;border-radius:6px;border:1px solid var(--border);background:var(--bg);color:var(--fg);cursor:pointer;" onclick="if(window.TikzAndroid){TikzAndroid.render('$key');}">$labelEsc</button>"""
            }
            else -> ""
        }
        return """
            <div class="ll-tikz-block" style="display:block;margin:12px 0;padding:12px;border:1px solid var(--border);border-radius:8px;background:var(--bg);">
              $svgPart
              $footer
            </div>
            """.trimIndent()
    }

    fun convertTikzPictures(
        htmlLike: String,
        fullSourceNoComments: String,
        tikzPreamble: String,
        renderButtonLabel: String = "Render TikZ",
        noCompilerNote: String? = null,
    ): String {
        val rx = rxBetween(
            "\\begin{tikzpicture}",
            "\\end{tikzpicture}",
            "(.*?)"
        )
        val userMacros = extractNewcommands(fullSourceNoComments)
        val texMacroDefs = buildTexNewcommands(userMacros)
        val srcLibs = collectUsetikzlibsFromSource(fullSourceNoComments)

        return rx.replace(htmlLike) { m ->
            val (opts, bodyRaw) = splitTikzPictureOptsAndBody(m.groupValues[1])
            val body = bodyRaw.trim()
            val hay = opts + "\n" + body
            val autoLibs = buildSet {
                if (Regex("""-\{?Latex""").containsMatchIn(hay) || Regex(""">=\s*Latex""").containsMatchIn(hay)) add("arrows.meta")
                if (Regex("""\b(left|right|above|below)\s*=\s*|[^=]\bof\b""").containsMatchIn(hay)) add("positioning")
                if (Regex("""use\s+Hobby\s+shortcut|invert\s+soft\s+blanks|\[blank=""").containsMatchIn(hay)) addAll(listOf("hobby", "topaths"))
                if (hay.contains("\\begin{knot}") || hay.contains("flip crossing/")) addAll(listOf("knots", "hobby", "intersections", "decorations.pathreplacing", "shapes.geometric", "spath3", "topaths"))
            }
            val allLibs = (srcLibs + autoLibs).toSortedSet()
            val libsLine = if (allLibs.isNotEmpty()) "\\usetikzlibrary{${allLibs.joinToString(",")}}\n" else ""
            val texDoc = """
\documentclass[tikz,border=1pt]{standalone}
\usepackage{amsmath,amssymb,bm}
\usepackage{tikz}
$libsLine
$texMacroDefs
$tikzPreamble
\usetikzlibrary{ spath3, intersections, arrows, knots, calc, hobby, decorations.pathreplacing, shapes.geometric, }
\begin{document}
\begin{tikzpicture}$opts
$body
\end{tikzpicture}
\end{document}
            """.trimIndent()
            val key = sha1(texDoc)
            LatexHtml.registerTikzRenderJob(key, texDoc)
            val cache = tikzCacheDir()
            val svg = File(cache, "$key.svg")
            val svgText = if (svg.exists()) svg.readText() else null
            return@replace tikzPreviewBlock(key, renderButtonLabel, svgText, noCompilerNote)
        }
    }

    fun convertSstTikzMacros(s: String, srcNoComments: String): String {
        val preSeen = collectTikzPreamble(srcNoComments)
        val needsSst = Regex("""\\SST[A-Za-z]""").containsMatchIn(s) &&
                !Regex("""\\usetikzlibrary\{(.*?)\}""", RegexOption.DOT_MATCHES_ALL).findAll(preSeen).any { m ->
                    Regex("""\bsstknots\b""").containsMatchIn(m.groupValues[1])
                }
        val preamble = if (needsSst) preSeen + "\n\\usetikzlibrary{sstknots}\n" else preSeen
        val rx = Regex(
            """\\SST[A-Za-z]+(?:\[(.*?)\])?(?:\{(.*?)\}(?:\{(.*?)\}(?:\{(.*?)\})?)?)?""",
            RegexOption.DOT_MATCHES_ALL
        )
        return rx.replace(s) { m ->
            val svg = renderTikzToSvg(preamble, m.value)
            if (svg != null)
                """<img src="${fileUrl(svg)}" alt="tikz" style="max-width:100%;height:auto;display:block;margin:10px auto;"/>"""
            else
                """<pre style="background:#0001;border:1px solid var(--border);padding:8px;overflow:auto;">[TikZ not rendered on device]\n${escapeHtmlKeepBackslashes(m.value)}</pre>"""
        }
    }

    private fun renderTikzToSvg(preamble: String, tikzEnvBlock: String): File? {
        return null // Android: no pdflatex/dvisvgm
    }

    private fun findBalancedBrace(s: String, open: Int): Int {
        if (open < 0 || open >= s.length || s[open] != '{') return -1
        var depth = 0
        var i = open
        while (i < s.length) {
            when (s[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return i }
                '\\' -> if (i + 1 < s.length) i++
            }
            i++
        }
        return -1
    }

    private data class Macro(val def: String, val nargs: Int)
    private fun buildTexNewcommands(macros: Map<String, Macro>): String {
        if (macros.isEmpty()) return ""
        val sb = StringBuilder()
        for ((name, m) in macros) {
            val nargs = m.nargs.coerceAtLeast(0)
            if (nargs == 0) sb.append("\\newcommand{\\$name}{${m.def}}\n")
            else sb.append("\\newcommand{\\$name}[$nargs]{${m.def}}\n")
        }
        return sb.toString()
    }

    private fun collectUsetikzlibsFromSource(src: String): Set<String> =
        Regex("""\\usetikzlibrary\{(.*?)\}""", RegexOption.DOT_MATCHES_ALL)
            .findAll(src)
            .flatMap { it.groupValues[1].split(',') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    private fun htmlEscapeAll(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun extractNewcommands(s: String): Map<String, Macro> {
        val out = linkedMapOf<String, Macro>()
        val rxNewStart = Regex("""\\newcommand\{\\([A-Za-z@]+)\}(?:\[(\d+)])?(?:\[(.*?)\])?\{""", RegexOption.DOT_MATCHES_ALL)
        var pos = 0
        while (true) {
            val m = rxNewStart.find(s, pos) ?: break
            val name = m.groupValues[1]
            val nargs = m.groupValues[2].ifEmpty { "0" }.toInt()
            val bodyOpen = m.range.last
            val bodyClose = findBalancedBrace(s, bodyOpen)
            if (bodyClose < 0) {
                pos = m.range.last + 1
                continue
            }
            val body = s.substring(bodyOpen + 1, bodyClose).trim()
            out[name] = Macro(body, nargs)
            pos = bodyClose + 1
        }
        val rxDef = Regex("""\\def\\([A-Za-z@]+)\{(.+?)\}""", RegexOption.DOT_MATCHES_ALL)
        rxDef.findAll(s).forEach { m ->
            out.putIfAbsent(m.groupValues[1], Macro(m.groupValues[2].trim(), 0))
        }
        return out
    }
}
