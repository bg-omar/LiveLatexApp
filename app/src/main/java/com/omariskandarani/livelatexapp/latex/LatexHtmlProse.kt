package com.omariskandarani.livelatexapp.latex

import java.util.regex.Matcher
import kotlin.text.RegexOption

/**
 * LaTeX prose-to-HTML conversions. Part of LatexHtml multi-file object.
 */

internal fun convertSections(s: String, absOffset: Int): String {
    fun inject(kind: String, tag: String, input: String): String {
        val rx = Regex("""\\$kind\*?\{([^\u007D]*)\}""")
        return rx.replace(input) { m ->
            val title = m.groupValues[1]
            val id    = "$kind-${slugify(title)}"
            val abs   = absOffset + input.substring(0, m.range.first).count { it == '\n' } + 1
            val htm   = latexProseToHtmlWithMath(title)
            """<span class="llmark" data-id="$id" data-abs="$abs"></span><$tag id="$id">$htm</$tag>"""
        }
    }
    var t = s
    t = inject("section", "h2", t)
    t = inject("subsection", "h3", t)
    t = inject("subsubsection", "h4", t)
    t = Regex("""\\paragraph\{([^\u007D]*)\}""").replace(t) { m ->
        val title = m.groupValues[1]
        val id    = "paragraph-${slugify(title)}"
        val abs   = absOffset + t.substring(0, m.range.first).count { it == '\n' } + 1
        val htm   = latexProseToHtmlWithMath(title)
        """<span class="llmark" data-id="$id" data-abs="$abs"></span><h5 id="$id" style="margin:1em 0 .3em 0;">$htm</h5>"""
    }
    t = t.replace(Regex("""\\texorpdfstring\{([^\u007D]*)\}\{([^\u007D]*)\}""")) {
        latexProseToHtmlWithMath(it.groupValues[2])
    }
    t = t.replace(
        Regex("""\\appendix"""),
        """<hr style="border:none;border-top:1px solid var(--border);margin:16px 0;"/>"""
    )
    return t
}

internal fun convertLlmark(s: String, absOffset: Int): String {
    val rx = Regex("""\\llmark(?:\[([^]]*)])?\{([^\u007D]*)\}""")
    return rx.replace(s) { m ->
        val titleOpt = m.groupValues[1]
        val key      = m.groupValues[2].ifBlank { "mark" }
        val id       = "mark-${slugify(key)}"
        val absLine  = absOffset + s.substring(0, m.range.first).count { it == '\n' } + 1
        val capHtml  = if (titleOpt.isNotBlank())
            """<div style="opacity:.7;margin:.2em 0;">${latexProseToHtmlWithMath(titleOpt)}</div>"""
        else ""
        """<span class="llmark" data-id="$id" data-abs="$absLine"></span>$capHtml"""
    }
}

internal fun unescapeLatexSpecials(t0: String): String {
    var t = t0
    t = Regex("""\\\$""").replace(t, Matcher.quoteReplacement("$"))
    t = Regex("""\\&""").replace(t, "&")
    t = Regex("""\\%""").replace(t, "%")
    t = Regex("""\\#""").replace(t, "#")
    t = Regex("""\\_""").replace(t, "_")
    t = Regex("""\\\{""").replace(t, "{")
    t = Regex("""\\\}""").replace(t, "}")
    t = Regex("""\\~\{\}""").replace(t, "~")
    t = Regex("""\\\^\{\}""").replace(t, "^")
    return t
}

internal val MATH_ENVS = setOf(
    "equation", "equation*", "align", "align*", "aligned", "gather", "gather*",
    "multline", "multline*", "flalign", "flalign*", "alignat", "alignat*",
    "bmatrix", "pmatrix", "vmatrix", "Bmatrix", "Vmatrix", "smallmatrix",
    "matrix", "cases", "split"
)

internal fun proseNoBr(s: String): String =
    latexProseToHtmlWithMath(s).replace(Regex("(?i)<br\\s*/?>\\s*"), " ")

internal fun formatInlineProseNonMath(s0: String): String {
    fun apply(t0: String, alreadyEscaped: Boolean): String {
        var t = t0
        t = t.replace(Regex("""(?<!\\)\\\\\s*"""), "<br/>")
        if (!alreadyEscaped) {
            t = unescapeLatexSpecials(t)
            t = t.replace("\\&", "&amp;")
        }
        t = Regex("""\\verb\*?(.)(.+?)\1""", RegexOption.DOT_MATCHES_ALL)
            .replace(t) { m ->
                val code = htmlEscapeAll(m.groupValues[2])
                "<code>$code</code>"
            }
        t = t.replace(Regex("""\\noindent\b"""), "")
        t = t.replace(Regex("""\\smallbreak\b"""), """<div style="height:.5em"></div>""")
            .replace(Regex("""\\medbreak\b"""), """<div style="height:1em"></div>""")
            .replace(Regex("""\\bigbreak\b"""), """<div style="height:1.5em"></div>""")
        val rec: (String) -> String = { inner -> apply(inner, true) }
        t = replaceCmd1ArgBalanced(t, "textbf") { "<strong>${rec(it)}</strong>" }
        t = replaceCmd1ArgBalanced(t, "emph") { "<em>${rec(it)}</em>" }
        t = replaceCmd1ArgBalanced(t, "textit") { "<em>${rec(it)}</em>" }
        t = replaceCmd1ArgBalanced(t, "itshape") { "<em>${rec(it)}</em>" }
        t = replaceCmd1ArgBalanced(t, "underline") { "<u>${rec(it)}</u>" }
        t = replaceCmd1ArgBalanced(t, "uline") { "<u>${rec(it)}</u>" }
        t = replaceCmd1ArgBalanced(t, "footnotesize") { "<small>${rec(it)}</small>" }
        t = replaceCmd1ArgBalanced(t, "mbox") { """<span style="white-space:nowrap;">${rec(it)}</span>""" }
        t = replaceCmd1ArgBalanced(t, "fbox") {
            """<span style="display:inline-block;border:1px solid var(--fg);padding:0 .25em;">${rec(it)}</span>"""
        }
        t = replaceTextSymbols(t)
        t = t.replace("\u0001", "<").replace("\u0002", ">")
        return t
    }
    return apply(s0, false)
}

internal fun convertParagraphsOutsideTags(html: String): String {
    val rxTag = Regex("(<[^>]+>)")
    val parts = rxTag.split(html)
    val tags  = rxTag.findAll(html).map { it.value }.toList()
    val out = StringBuilder(html.length + 256)
    for (i in parts.indices) {
        val chunkRaw = parts[i]
        if (!chunkRaw.contains('<') && !chunkRaw.contains('>')) {
            val chunk = chunkRaw.trim()
            if (chunk.isNotEmpty()) {
                if (Regex("""\n{2,}""").containsMatchIn(chunk)) {
                    val paras = chunk.split(Regex("""\n{2,}"""))
                        .map { it.trim() }.filter { it.isNotEmpty() }
                        .joinToString("") { p -> "<p>${latexProseToHtmlWithMath(p)}</p>" }
                    out.append(paras)
                } else {
                    out.append(latexProseToHtmlWithMath(chunk))
                }
            }
        } else {
            out.append(chunkRaw)
        }
        if (i < tags.size) out.append(tags[i])
    }
    return out.toString()
        .replace(Regex("""<(li|dd|dt)>\s*<p>(.*?)</p>\s*</\1>""", RegexOption.DOT_MATCHES_ALL)) { m ->
            "<${m.groupValues[1]}>${m.groupValues[2]}</${m.groupValues[1]}>"
        }
        .replace(Regex("""(<figcaption[^>]*>)\s*<p>(.*?)</p>\s*(</figcaption>)""", RegexOption.DOT_MATCHES_ALL), "$1$2$3")
}

/**
 * Convert LaTeX prose to HTML, preserving math regions ($...$, \[...\], \(...\)).
 */
internal fun latexProseToHtmlWithMath(s: String): String {
    fun tryWrap(cmd: String, openIdx: Int): String? {
        if (!s.regionMatches(openIdx, "\\$cmd", 0, cmd.length + 1)) return null
        var j = openIdx + cmd.length + 1
        while (j < s.length && s[j].isWhitespace()) j++
        if (j >= s.length || s[j] != '{') return null
        val close = findBalancedBraceAllowMath(s, j)
        if (close < 0) return null
        val inner = s.substring(j + 1, close)
        val before = s.substring(0, openIdx)
        val after  = s.substring(close + 1)
        val tag = when (cmd) {
            "textbf" -> "strong"
            "emph", "textit", "itshape" -> "em"
            "underline", "uline" -> "u"
            "small", "footnotesize" -> "small"
            else -> return null
        }
        return before + "<$tag>" + latexProseToHtmlWithMath(inner) + "</$tag>" + latexProseToHtmlWithMath(after)
    }
    run {
        var i = s.indexOf('\\')
        while (i >= 0) {
            for (cmd in arrayOf("textbf", "emph", "textit", "itshape", "underline", "uline", "small", "footnotesize")) {
                val rep = tryWrap(cmd, i)
                if (rep != null) return rep
            }
            i = s.indexOf('\\', i + 1)
        }
    }
    val sb = StringBuilder()
    var i = 0
    val n = s.length
    fun startsAt(idx: Int, tok: String): Boolean =
        idx >= 0 && idx + tok.length <= n && s.regionMatches(idx, tok, 0, tok.length)
    while (i < n) {
        val nextDollar = run {
            var j = s.indexOf('$', i)
            while (j >= 0 && j < n && isEscaped(s, j)) j = s.indexOf('$', j + 1)
            j
        }
        val nextBracket = s.indexOf("\\[", i)
        val nextParen   = s.indexOf("\\(", i)
        val nextBegin   = s.indexOf("\\begin{", i)
        val candidates = listOf(nextDollar, nextBracket, nextParen, nextBegin).filter { it >= 0 }
        val next = if (candidates.isEmpty()) n else candidates.minOrNull()!!
        sb.append(formatInlineProseNonMath(s.substring(i, next)))
        if (next == n) break
        if (next == nextDollar) {
            val isDouble = startsAt(next, "$$")
            val closeIdx = if (isDouble) s.indexOf("$$", next + 2) else s.indexOf('$', next + 1)
            val end = if (closeIdx >= 0) closeIdx + (if (isDouble) 2 else 1) else n
            sb.append(s.substring(next, end)); i = end; continue
        }
        if (next == nextBracket) {
            val closeIdx = s.indexOf("\\]", next + 2)
            val end = if (closeIdx >= 0) closeIdx + 2 else n
            sb.append(s.substring(next, end)); i = end; continue
        }
        if (next == nextParen) {
            val closeIdx = s.indexOf("\\)", next + 2)
            val end = if (closeIdx >= 0) closeIdx + 2 else n
            sb.append(s.substring(next, end)); i = end; continue
        }
        if (next == nextBegin) {
            val nameOpen = next + "\\begin{".length
            val nameClose = s.indexOf('}', nameOpen)
            val env = if (nameClose > nameOpen) s.substring(nameOpen, nameClose) else ""
            if (env in MATH_ENVS) {
                val endTok = "\\end{$env}"
                val endAt = s.indexOf(endTok, nameClose + 1).let { if (it < 0) n else it + endTok.length }
                sb.append(s.substring(next, endAt)); i = endAt; continue
            }
            sb.append("\\begin{"); i = nameOpen
        }
    }
    return sb.toString()
}

internal fun convertMulticols(s: String): String {
    val rx = Regex("""\\begin\{multicols\}\{(\d+)\}(.+?)\\end\{multicols\}""", RegexOption.DOT_MATCHES_ALL)
    return rx.replace(s) { m ->
        val n = (m.groupValues[1].toIntOrNull() ?: 2).coerceIn(1, 8)
        val body = latexProseToHtmlWithMath(m.groupValues[2].trim())
        """<div class="multicol" style="-webkit-column-count:$n;column-count:$n;-webkit-column-gap:1.2em;column-gap:1.2em;">$body</div>"""
    }
}

internal fun convertItemize(s: String): String {
    val rx = Regex("""\\begin\{itemize\}(?:\[[^\]]*])?(.+?)\\end\{itemize\}""", RegexOption.DOT_MATCHES_ALL)
    return rx.replace(s) { m ->
        val body = m.groupValues[1]
        val parts = Regex("""(?m)^\s*\\item\s*""")
            .split(body).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return@replace ""
        val lis = parts.joinToString("") { item -> "<li>${proseNoBr(item)}</li>" }
        """<ul style="margin:12px 0 12px 24px;">$lis</ul>"""
    }
}

internal fun convertEnumerate(s: String): String {
    val rx = Regex("""\\begin\{enumerate\}(?:\[[^\]]*])?(.+?)\\end\{enumerate\}""", RegexOption.DOT_MATCHES_ALL)
    return rx.replace(s) { m ->
        val body = m.groupValues[1]
        val parts = Regex("""(?m)^\s*\\item\s*""")
            .split(body).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return@replace ""
        val lis = parts.joinToString("") { item -> "<li>${proseNoBr(item)}</li>" }
        """<ol style="margin:12px 0 12px 24px;">$lis</ol>"""
    }
}

internal fun convertDescription(s: String): String {
    val rxEnv = Regex("""\\begin\{description\}(?:\[[^\]]*])?(.+?)\\end\{description\}""", RegexOption.DOT_MATCHES_ALL)
    return rxEnv.replace(s) { envMatch ->
        val body = envMatch.groupValues[1]
        val rxItem = Regex("""(?ms)^\s*\\item(?:\s*\[([^\]]*)])?\s*(.*?)\s*(?=^\s*\\item|\z)""")
        val items = rxItem.findAll(body).map { m ->
            val rawLabel   = m.groupValues[1]
            val rawContent = m.groupValues[2]
            val (peeled, tag) = peelTopLevelTextWrapper(rawLabel)
            val labelHtmlInner = latexProseToHtmlWithMath(peeled)
            val labelHtml = when (tag) {
                "strong" -> if (labelHtmlInner.contains("<strong>", ignoreCase = true)) labelHtmlInner else "<strong>$labelHtmlInner</strong>"
                "em"     -> if (labelHtmlInner.contains("<em>", ignoreCase = true)) labelHtmlInner else "<em>$labelHtmlInner</em>"
                else     -> labelHtmlInner
            }
            val contentHtml = latexProseToHtmlWithMath(rawContent)
            val termHtml = when {
                labelHtml.isBlank() -> ""
                labelHtml.contains("<strong>", ignoreCase = true) -> labelHtml
                else -> "<strong>$labelHtml</strong>"
            }
            "<dt>$termHtml</dt><dd>$contentHtml</dd>"
        }.joinToString("")
        """<dl style="margin:12px 0 12px 24px;">$items</dl>"""
    }
}
