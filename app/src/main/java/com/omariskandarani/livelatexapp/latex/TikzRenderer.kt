package com.omariskandarani.livelatexapp.latex

import java.io.File
import java.security.MessageDigest

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
        val pkgs = Regex("""\\usepackage(?:\[[^\]]*])?\{[^\u007D]+}""")
            .findAll(preamble).joinToString("\n") { it.value }
        val tikzsets = collectBalanced("\\tikzset", preamble).joinToString("\n")
        val libsSet = collectUsetikzlibsFromSource(preamble).toSortedSet()
        val libsLine = if (libsSet.isNotEmpty()) "\\usetikzlibrary{${libsSet.joinToString(",")}}\n" else ""
        val needsTikzCd = Regex("""\\begin\{tikzcd}|\\usepackage\{tikz-cd}""").containsMatchIn(preamble)
        return buildString {
            appendLine("\\usepackage{tikz}")
            if (needsTikzCd) appendLine("\\usepackage{tikz-cd}")
            if (pkgs.isNotBlank()) appendLine(pkgs)
            append(libsLine)
            if (tikzsets.isNotBlank()) appendLine(tikzsets)
        }
    }

    private val TIKZ_PLACEHOLDER = """<div class="tikz-placeholder" style="display:block;margin:12px 0;padding:12px;background:var(--border, #e5e7eb);color:var(--muted, #6b7280);border-radius:6px;">[TikZ diagram — export to PDF to see full document]</div>"""

    fun convertTikzPictures(htmlLike: String, fullSourceNoComments: String, tikzPreamble: String): String {
        val rx = Regex(
            """\\begin\{tikzpicture}(\[[^\]]*])?(.+?)\\end\{tikzpicture}""",
            RegexOption.DOT_MATCHES_ALL
        )
        val userMacros = extractNewcommands(fullSourceNoComments)
        val texMacroDefs = buildTexNewcommands(userMacros)
        val srcLibs = collectUsetikzlibsFromSource(fullSourceNoComments)

        return rx.replace(htmlLike) { m ->
            val opts = m.groupValues[1]
            val body = m.groupValues[2].trim()
            val hay = opts + "\n" + body
            val autoLibs = buildSet {
                if (Regex("""-\{?Latex""").containsMatchIn(hay) || Regex(""">=\s*Latex""").containsMatchIn(hay)) add("arrows.meta")
                if (Regex("""\b(left|right|above|below)\s*=\s*|[^=]\bof\b""").containsMatchIn(hay)) add("positioning")
                if (Regex("""use\s+Hobby\s+shortcut|invert\s+soft\s+blanks|\[blank=""").containsMatchIn(hay)) addAll(listOf("hobby", "topaths"))
                if (Regex("""\\begin\{knot}|\bflip crossing/""").containsMatchIn(hay)) addAll(listOf("knots", "hobby", "intersections", "decorations.pathreplacing", "shapes.geometric", "spath3", "topaths"))
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
            val cache = tikzCacheDir()
            val svg = File(cache, "$key.svg")
            if (svg.exists()) {
                return@replace """<span class="tikz-wrap" style="display:block;margin:12px 0;">${svg.readText()}</span>"""
            }
            return@replace TIKZ_PLACEHOLDER
        }
    }

    fun convertSstTikzMacros(s: String, srcNoComments: String): String {
        val preSeen = collectTikzPreamble(srcNoComments)
        val needsSst = Regex("""\\SST[A-Za-z]""").containsMatchIn(s) &&
                !Regex("""\\usetikzlibrary\{[^\u007D]*\bsstknots\b""").containsMatchIn(preSeen)
        val preamble = if (needsSst) preSeen + "\n\\usetikzlibrary{sstknots}\n" else preSeen
        val rx = Regex("""\\SST[A-Za-z]+(?:\[[^\]]*])?(?:\{[^{}]*}){0,3}""")
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
        Regex("""\\usetikzlibrary\{([^\u007D]*)}""")
            .findAll(src)
            .flatMap { it.groupValues[1].split(',') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    private fun htmlEscapeAll(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun extractNewcommands(s: String): Map<String, Macro> {
        val out = linkedMapOf<String, Macro>()
        val rxNewStart = Regex("""\\newcommand\{\\([A-Za-z@]+)\}(?:\[(\d+)])?(?:\[[^\]]*])?\{""")
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

private fun escapeHtmlKeepBackslashes(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
