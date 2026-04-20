package com.omariskandarani.livelatexapp.latex

import kotlin.text.RegexOption

/**
 * LaTeX sanitization for MathJax compatibility. Part of LatexHtml multi-file object.
 */

/** Names kept as raw `\\begin{…}` / `\\end{…}` for MathJax (no regex — avoids Android/ICU pattern limits). */
private val SANITIZER_KEEP_BEGIN_END_ENVS = setOf(
    "equation", "equation*",
    "align", "align*",
    "aligned", "aligned*",
    "gather", "gather*",
    "multline", "multline*",
    "flalign", "flalign*",
    "alignat", "alignat*",
    "bmatrix", "pmatrix", "vmatrix", "Bmatrix", "Vmatrix", "smallmatrix", "matrix", "cases", "split",
    "tabular", "table", "longtable", "figure", "center", "tikzpicture", "tcolorbox",
    "thebibliography", "itemize", "enumerate", "description", "multicols",
)

/** Strip `\\begin{name}` / `\\end{name}` when [name] is not in [keep]. */
private fun stripDisallowedBeginEndEnvTags(text: String, keep: Set<String>): String {
    var s = text
    for (prefix in listOf("\\begin{", "\\end{")) {
        var i = 0
        while (i < s.length) {
            val j = s.indexOf(prefix, i)
            if (j < 0) break
            val nameStart = j + prefix.length
            val nameEnd = s.indexOf('}', nameStart)
            if (nameEnd < 0) {
                i = j + prefix.length
                continue
            }
            val env = s.substring(nameStart, nameEnd)
            if (env !in keep) {
                s = s.substring(0, j) + s.substring(nameEnd + 1)
                i = j
            } else {
                i = nameEnd + 1
            }
        }
    }
    return s
}

/** Convert abstract/center/theorem-like to HTML; drop unknown NON-math envs; keep math envs intact. */
internal fun sanitizeForMathJaxProse(bodyText: String): String {
    var s = bodyText
    s = s.replace("""\\titlepageOpen""".toRegex(), "")
        .replace("""\\titlepageClose""".toRegex(), "")

    s = s.replace(
        rxBetween("\\begin{center}", "\\end{center}", """(.+?)""")
    ) { m -> """<div style="text-align:center;">${latexProseToHtmlWithMath(m.groupValues[1].trim())}</div>""" }

    s = s.replace(
        rxBetween("\\begin{abstract}", "\\end{abstract}", """(.+?)""")
    ) { m ->
        val raw = m.groupValues[1].trim()
        val collapsedSingles = raw.replace(Regex("""(?<!\n)\n(?!\n)"""), " ")
        val html = proseNoBr(collapsedSingles)
        val merged =
            if (Regex("""<p\b""", RegexOption.IGNORE_CASE).containsMatchIn(html)) {
                Regex("""(?i)(<p\b[^>]*>)""").replaceFirst(html, "${'$'}1<strong>Abstract.</strong>&nbsp;")
            } else {
                "<strong>Abstract.</strong>&nbsp;$html"
            }
        """
    <div class="abstract-block" style="padding:12px;border-left:3px solid var(--border); background:#6b728022; margin:12px 0;">
      $merged
    </div>
    """.trimIndent()
    }

    val theoremLike = listOf("theorem", "lemma", "proposition", "corollary", "definition", "remark", "identity")
    for (env in theoremLike) {
        s = s.replace(
            Regex(
                latexLiteral("\\begin{$env}") + """(?:\[(.*?)\])?(.+?)""" + latexLiteral("\\end{$env}"),
                RegexOption.DOT_MATCHES_ALL
            )
        ) { m ->
            val ttl = m.groupValues[1].trim()
            val content = m.groupValues[2].trim()
            val head = if (ttl.isNotEmpty()) "$env ($ttl)" else env
            """
      <div style="font-weight:600;margin-bottom:6px;text-transform:capitalize;">$head.</div>
      ${latexProseToHtmlWithMath(content)}
    """.trimIndent()
        }
    }

    s = stripDisallowedBeginEndEnvTags(s, SANITIZER_KEEP_BEGIN_END_ENVS)

    return s
}

internal fun convertSiunitx(s: String): String {
    var t = s
    t = t.replace(Regex("""\\num\{(.*?)\}""", RegexOption.DOT_MATCHES_ALL)) { m ->
        val raw = m.groupValues[1].trim()
        val sci = Regex("""^\s*([+-]?\d+(?:\.\d+)?)[eE]([+-]?\d+)\s*$""").matchEntire(raw)
        if (sci != null) {
            val a = sci.groupValues[1]
            val b = sci.groupValues[2]
            "$a\\times 10^{${b}}"
        } else raw
    }
    t = t.replace(Regex("""\\si\{(.*?)\}""", RegexOption.DOT_MATCHES_ALL)) { m ->
        val u = m.groupValues[1].replace(".", "\\,").replace("~", "\\,")
        "\\mathrm{$u}"
    }
    t = t.replace(Regex("""\\SI\{(.*?)\}\{(.*?)\}""", RegexOption.DOT_MATCHES_ALL)) { m ->
        val num  = m.groupValues[1]
        val unit = m.groupValues[2]
        "\\num{$num}\\,\\si{$unit}"
    }
    t = t.replace(Regex("""\\textasciitilde\{\}"""), "~")
        .replace(Regex("""\\textasciitilde"""), "~")
        .replace(Regex("""\\&"""), "&")
    return t
}
