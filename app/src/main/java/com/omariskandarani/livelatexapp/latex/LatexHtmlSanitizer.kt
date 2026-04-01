package com.omariskandarani.livelatexapp.latex

import kotlin.text.RegexOption

/**
 * LaTeX sanitization for MathJax compatibility. Part of LatexHtml multi-file object.
 */

/** Convert abstract/center/theorem-like to HTML; drop unknown NON-math envs; keep math envs intact. */
internal fun sanitizeForMathJaxProse(bodyText: String): String {
    var s = bodyText
    s = s.replace("""\\titlepageOpen""".toRegex(), "")
        .replace("""\\titlepageClose""".toRegex(), "")

    s = s.replace(
        Regex("""\\begin\{center\}(.+?)\\end\{center\}""", RegexOption.DOT_MATCHES_ALL)
    ) { m -> """<div style="text-align:center;">${latexProseToHtmlWithMath(m.groupValues[1].trim())}</div>""" }

    s = s.replace(
        Regex("""\\begin\{abstract\}(.+?)\\end\{abstract\}""", RegexOption.DOT_MATCHES_ALL)
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
            Regex("""\\begin\{$env\}(?:\[(.*?)\])?(.+?)\\end\{$env\}""", RegexOption.DOT_MATCHES_ALL)
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

    val mathEnvs =
        "(?:equation\\*?|align\\*?|aligned\\*?|aligned|gather\\*?|multline\\*?|flalign\\*?|alignat\\*?|bmatrix|pmatrix|vmatrix|Bmatrix|Vmatrix|smallmatrix|matrix|cases|split)"
    val keepEnvs =
        "(?:$mathEnvs|tabular|table|longtable|figure|center|tikzpicture|tcolorbox|thebibliography|itemize|enumerate|description|multicols)"

    s = s.replace(Regex("""\\begin\{(?!$keepEnvs)\w+\}"""), "")
    s = s.replace(Regex("""\\end\{(?!$keepEnvs)\w+\}"""), "")

    return s
}

internal fun convertSiunitx(s: String): String {
    var t = s
    t = t.replace(Regex("""\\num\{([^\u007D]*)\}""")) { m ->
        val raw = m.groupValues[1].trim()
        val sci = Regex("""^\s*([+-]?\d+(?:\.\d+)?)[eE]([+-]?\d+)\s*$""").matchEntire(raw)
        if (sci != null) {
            val a = sci.groupValues[1]
            val b = sci.groupValues[2]
            "$a\\times 10^{${b}}"
        } else raw
    }
    t = t.replace(Regex("""\\si\{([^\u007D]*)\}""")) { m ->
        val u = m.groupValues[1].replace(".", "\\,").replace("~", "\\,")
        "\\mathrm{$u}"
    }
    t = t.replace(Regex("""\\SI\{([^\u007D]*)\}\{([^\u007D]*)\}""")) { m ->
        val num  = m.groupValues[1]
        val unit = m.groupValues[2]
        "\\num{$num}\\,\\si{$unit}"
    }
    t = t.replace(Regex("""\\textasciitilde\{\}"""), "~")
        .replace(Regex("""\\textasciitilde"""), "~")
        .replace(Regex("""\\&"""), "&")
    return t
}
