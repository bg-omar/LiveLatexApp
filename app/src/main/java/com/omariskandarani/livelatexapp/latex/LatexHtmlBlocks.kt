package com.omariskandarani.livelatexapp.latex

import java.util.LinkedHashMap
import kotlin.text.RegexOption

/**
 * LaTeX block conversions (tables, figures, tcolorbox, etc.). Part of LatexHtml multi-file object.
 */

internal data class ColSpec(val align: String?, val widthPct: Int?)

internal fun convertTcolorboxes(s: String): String {
    val rx = rxBetween("\\begin{tcolorbox}", "\\end{tcolorbox}", """(?:\[(.*?)\])?(.+?)""")
    return rx.replace(s) { m ->
        val opts = (m.groupValues.getOrNull(1) ?: "").trim()
        val body = m.groupValues[2].trim()
        val kv = parseTcolorOptions(opts)
        val titleHtml = kv["title"]?.let { latexProseToHtmlWithMath(it) } ?: ""
        val colBack   = kv["colback"]?.let { xcolorToCss(it) } ?: "#f8fafc"
        val colFrame  = kv["colframe"]?.let { xcolorToCss(it) } ?: "#1e3a8a"
        val inner = latexProseToHtmlWithMath(body)
        buildString {
            append("<div class=\"tcb\" style=\"")
            append("background:").append(colBack).append(';')
            append("border:1px solid ").append(colFrame).append(';')
            append("border-left-width:4px;border-radius:8px;")
            append("padding:10px 12px;margin:12px 0;\">")
            if (titleHtml.isNotEmpty()) {
                append("<div class=\"tcb-title\" style=\"font-weight:600;margin-bottom:6px;\">")
                append(titleHtml).append("</div>")
            }
            append("<div class=\"tcb-body\">").append(inner).append("</div></div>")
        }
    }
}

internal fun parseTcolorOptions(s: String): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    var i = 0
    fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }
    while (i < s.length) {
        skipWs()
        val eq = s.indexOf('=', i)
        if (eq < 0) break
        val key = s.substring(i, eq).trim()
        var j = eq + 1
        skipWs()
        val value: String
        if (j < s.length && s[j] == '{') {
            val close = findBalancedBrace(s, j)
            value = if (close > j) s.substring(j + 1, close) else ""
            i = if (close > j) close + 1 else s.length
        } else {
            var depth = 0
            var k = j
            while (k < s.length) {
                when (s[k]) {
                    '{' -> depth++
                    '}' -> depth = maxOf(0, depth - 1)
                    ',' -> if (depth == 0) break
                }
                k++
            }
            value = s.substring(j, k).trim()
            i = if (k < s.length && s[k] == ',') k + 1 else k
        }
        if (key.isNotEmpty()) out[key] = value
    }
    return out
}

internal fun xcolorToCss(x: String): String {
    fun base(c: String): IntArray = when (c.lowercase()) {
        "black" -> intArrayOf(0, 0, 0)
        "white" -> intArrayOf(255, 255, 255)
        "red" -> intArrayOf(220, 38, 38)
        "green" -> intArrayOf(22, 163, 74)
        "blue" -> intArrayOf(37, 99, 235)
        "cyan" -> intArrayOf(6, 182, 212)
        "magenta", "violet", "purple" -> intArrayOf(168, 85, 247)
        "yellow" -> intArrayOf(234, 179, 8)
        "orange" -> intArrayOf(249, 115, 22)
        "gray", "grey" -> intArrayOf(156, 163, 175)
        "brown" -> intArrayOf(150, 95, 59)
        else -> intArrayOf(30, 58, 138)
    }
    val m = Regex("""^\s*([A-Za-z]+)(?:!([0-9]{1,3})!([A-Za-z]+))?\s*$""").matchEntire(x)
    if (m != null) {
        val c1 = base(m.groupValues[1])
        val pct = m.groupValues[2].toIntOrNull()
        val c2Name = m.groupValues[3]
        val rgb = if (pct != null && c2Name.isNotEmpty()) {
            val t = pct.coerceIn(0, 100) / 100.0
            val c2 = base(c2Name)
            intArrayOf(
                (c1[0] * (1 - t) + c2[0] * t).toInt(),
                (c1[1] * (1 - t) + c2[1] * t).toInt(),
                (c1[2] * (1 - t) + c2[2] * t).toInt()
            )
        } else c1
        return String.format("#%02x%02x%02x", rgb[0], rgb[1], rgb[2])
    }
    return "#1e3a8a"
}

internal fun convertTabulars(text: String): String {
    val out = StringBuilder(text.length + 512)
    var i = 0
    while (true) {
        val start = text.indexOf("\\begin{tabular}{", i)
        if (start < 0) { out.append(text.substring(i)); break }
        out.append(text.substring(i, start))
        val colOpen = text.indexOf('{', start + "\\begin{tabular}".length)
        val colClose = findBalancedBrace(text, colOpen)
        if (colOpen < 0 || colClose < 0) { out.append(text.substring(start)); break }
        val spec = text.substring(colOpen + 1, colClose)
        val cols = parseColSpecBalanced(spec)
        val endTag = text.indexOf("\\end{tabular}", colClose + 1)
        if (endTag < 0) { out.append(text.substring(start)); break }
        var body = text.substring(colClose + 1, endTag).trim()
        body = body
            .replace("\\toprule", "")
            .replace("\\midrule", "")
            .replace("\\bottomrule", "")
            .replace(Regex("""(?m)^\s*\\hline\s*$"""), "")
            .replace(Regex("""(?<!\\)\\\\\s*\[(?:.*?)]""", RegexOption.DOT_MATCHES_ALL), "\\\\")
            .replace(Regex("""\\arraystretch\s*=\s*([0-9]*\.?[0-9]+)"""), "")
            .replace(Regex("""\\tabcolsep\s*=\s*([0-9]*\.?[0-9]+)"""), "")
            .replace(Regex("""(?m)^\s*\\setlength\{\\tabcolsep\}\{(.*?)\}.*$""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""(?m)^\s*\\renewcommand\{\\arraystretch\}\{(.*?)\}.*$""", RegexOption.DOT_MATCHES_ALL), "")
            .trim()
        body = body.replace(Regex("""(?i)<br\s*/?>"""), "\\\\")
        val rows = Regex("""(?<!\\)\\\\\s*""").split(body).map { it.trim() }.filter { it.isNotEmpty() }
        val trs = rows.joinToString("") { row ->
            val cells = row.split('&').map { it.trim() }
            var cellIdx = 0
            val tds = cols.joinToString("") { col ->
                if (col.align == "space") {
                    "<td style=\"width:1em;border:none;\"></td>"
                } else {
                    val raw = if (cellIdx < cells.size) cells[cellIdx] else ""
                    cellIdx++
                    val style = buildString {
                        if (col.align != null) append("text-align:${col.align};")
                        if (col.widthPct != null) append("width:${col.widthPct}%;")
                        append("padding:4px 8px;border:1px solid var(--border);vertical-align:top;")
                    }
                    val cellHtml = latexProseToHtmlWithMath(raw)
                    "<td style=\"$style\">$cellHtml</td>"
                }
            }
            "<tr>$tds</tr>"
        }
        out.append("""<table style="border:1px solid var(--border);margin:12px 0;width:100%;">$trs</table>""")
        i = endTag + "\\end{tabular}".length
    }
    return out.toString()
}

internal fun parseColSpecBalanced(spec: String): List<ColSpec> {
    val cols = mutableListOf<ColSpec>()
    var i = 0
    fun skipGroup(openAt: Int): Int = findBalancedBrace(spec, openAt).coerceAtLeast(openAt)
    while (i < spec.length) {
        when (spec[i]) {
            'l' -> { cols += ColSpec("left", null); i++ }
            'c' -> { cols += ColSpec("center", null); i++ }
            'r' -> { cols += ColSpec("right", null); i++ }
            'p' -> {
                val o = spec.indexOf('{', i + 1)
                if (o > 0) {
                    val c = findBalancedBrace(spec, o)
                    val widthExpr = if (c > o) spec.substring(o + 1, c) else ""
                    cols += ColSpec("left", linewidthToPercent(widthExpr))
                    i = if (c > o) c + 1 else i + 1
                } else i++
            }
            '|', ' ' -> i++
            '@', '!', '>' -> {
                val o = spec.indexOf('{', i + 1)
                i = if (o > 0) skipGroup(o) + 1 else i + 1
            }
            else -> i++
        }
    }
    return cols
}

internal fun linewidthToPercent(expr: String): Int? {
    Regex("""^\s*([0-9]*\.?[0-9]+)\s*\\linewidth\s*$""").matchEntire(expr)?.let {
        val f = it.groupValues[1].toDoubleOrNull() ?: return null
        return (f * 100).toInt().coerceIn(1, 100)
    }
    Regex("""^\s*([0-9]{1,3})\s*%\s*$""").matchEntire(expr)?.let {
        return it.groupValues[1].toInt().coerceIn(1, 100)
    }
    return null
}

internal fun convertHref(s: String): String =
    s.replace(Regex("""\\href\{(.*?)\}\{(.*?)\}""", RegexOption.DOT_MATCHES_ALL)) { m ->
        val url = m.groupValues[1]
        val txt = m.groupValues[2]
        """<a href="${escapeHtmlKeepBackslashes(url)}" target="_blank" rel="noopener">${escapeHtmlKeepBackslashes(txt)}</a>"""
    }

internal fun stripAuxDirectives(s: String): String {
    var t = s
    t = t.replace(Regex("""\\addcontentsline\{(.*?)\}\{(.*?)\}\{(.*?)\}""", RegexOption.DOT_MATCHES_ALL), "")
    t = t.replace(Regex("""\\nocite\{(.*?)\}""", RegexOption.DOT_MATCHES_ALL), "")
    t = t.replace(Regex("""\\bibliographystyle\{(.*?)\}""", RegexOption.DOT_MATCHES_ALL), "")
    t = t.replace(
        Regex("""\\bibliography\{(.*?)\}""", RegexOption.DOT_MATCHES_ALL),
        """<div style="opacity:.7;margin:8px 0;">[References: compile in PDF mode]</div>"""
    )
    return t
}

internal fun convertTableEnvs(s: String): String {
    val rx = rxBetween("\\begin{table}", "\\end{table}", """(?:\[(?:.*?)\])?(.+?)""")
    return rx.replace(s) { m ->
        var body = m.groupValues[1]
        var captionHtml = ""
        val capRx = Regex("""\\caption\{(.*?)\}""", RegexOption.DOT_MATCHES_ALL)
        val cap = capRx.find(body)
        if (cap != null) {
            captionHtml = """<figcaption style=\"opacity:.8;margin:6px 0 10px;\">${escapeHtmlKeepBackslashes(cap.groupValues[1])}</figcaption>"""
            body = body.replace(cap.value, "")
        }
        body = body.replace(Regex("""\\centering"""), "")
            .replace(Regex("""\\label\{(.*?)\}""", RegexOption.DOT_MATCHES_ALL), "")
        """<figure style=\"margin:14px 0;\">$body$captionHtml</figure>"""
    }
}

internal fun convertTheBibliography(s: String): String {
    val rx = rxBetween("\\begin{thebibliography}", "\\end{thebibliography}", """\{(.*?)\}(.+?)""")
    return rx.replace(s) { m ->
        val body = m.groupValues[2]
        val entries = Regex("""\\bibitem\{(.*?)\}""", RegexOption.DOT_MATCHES_ALL)
            .split(body)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (entries.isEmpty()) return@replace ""
        val lis = entries.joinToString("") { "<li>${escapeHtmlKeepBackslashes(it)}</li>" }
        """<h4>References</h4><ol style="margin:12px 0 12px 24px;">$lis</ol>"""
    }
}

internal fun convertFigureEnvs(s: String): String {
    val rx = rxBetween("\\begin{figure}", "\\end{figure}", """(?:\[(?:.*?)\])?(.+?)""")
    return rx.replace(s) { m ->
        var body = m.groupValues[1]
        body = body.replace(Regex("""(?m)^\s*\\setlength\{\\tabcolsep\}\{(.*?)\}.*$""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""(?m)^\s*\\renewcommand\{\\arraystretch\}\{(.*?)\}.*$""", RegexOption.DOT_MATCHES_ALL), "")
        var imgHtml = ""
        val inc = Regex("""\\includegraphics(?:\[(?:.*?)\])?\{(.*?)\}""", RegexOption.DOT_MATCHES_ALL).find(body)
        if (inc != null) {
            val opts = Regex("""\\includegraphics(?:\[(.*?)\])?\{(.*?)\}""", RegexOption.DOT_MATCHES_ALL).find(inc.value)
            val (optStr, path) = if (opts != null) opts.groupValues[1] to opts.groupValues[2] else "" to inc.groupValues[1]
            val style = includeGraphicsStyle(optStr)
            val resolved = resolveImagePath(path)
            imgHtml = """<img src="$resolved" alt="" style="$style">"""
            body = body.replace(inc.value, "")
        }
        var captionHtml = ""
        run {
            val capIdx = body.indexOf("\\caption{")
            if (capIdx >= 0) {
                val open = body.indexOf('{', capIdx + "\\caption".length)
                val close = findBalancedBraceAllowMath(body, open)
                if (open >= 0 && close > open) {
                    val capTex = body.substring(open + 1, close)
                    captionHtml = """<figcaption style="opacity:.8;margin:6px 0 10px;">${latexProseToHtmlWithMath(capTex)}</figcaption>"""
                    body = body.removeRange(capIdx, close + 1)
                }
            }
        }
        body = body.replace(Regex("""\\centering"""), "")
            .replace(Regex("""\\label\{(.*?)\}""", RegexOption.DOT_MATCHES_ALL), "")
            .trim()
        val hasSubEnv = body.contains("\\begin{")
        val rest = if (body.isNotEmpty()) {
            if (hasSubEnv) "<div>$body</div>" else "<div>${latexProseToHtmlWithMath(body)}</div>"
        } else ""
        """<figure style="margin:14px 0;text-align:center;">$imgHtml$captionHtml$rest</figure>"""
    }
}

internal fun convertLongtablesToTables(s: String): String {
    val rx = rxBetween("\\begin{longtable}", "\\end{longtable}", """\{(.*?)\}(.+?)""")
    return rx.replace(s) { m ->
        val colspec = m.groupValues[1]
        var body    = m.groupValues[2]
        val capRe = Regex("""\\caption\{(.*?)\}\s*\\\\?""", RegexOption.DOT_MATCHES_ALL)
        var caption = ""
        capRe.find(body)?.let { cap ->
            caption = cap.value
            body    = body.replace(cap.value, "")
        }
        body = body
            .replace(Regex("""\\endfirsthead|\\endhead|\\endfoot|\\endlastfoot"""), "")
            .replace(Regex("""\\toprule|\\midrule|\\bottomrule"""), "")
            .trim()
        """
\begin{table}
$caption
\begin{tabular}{$colspec}
$body
\end{tabular}
\end{table}
        """.trimIndent()
    }
}
