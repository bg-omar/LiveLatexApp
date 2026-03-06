package com.omariskandarani.livelatexapp.latex

import kotlin.text.Regex
import kotlin.text.lowercase
import kotlin.text.replace

/**
 * LaTeX parsing helpers. Part of LatexHtml multi-file object.
 */

internal const val BEGIN_DOCUMENT = "\\begin{document}"
internal const val END_DOCUMENT = "\\end{document}"

internal fun slugify(s: String): String =
    s.lowercase()
        .replace(Regex("""\\[A-Za-z@]+"""), "")
        .replace(Regex("""[^a-z0-9]+"""), "-")
        .trim('-')

internal fun isEscaped(s: String, i: Int): Boolean {
    var k = i - 1
    var bs = 0
    while (k >= 0 && s[k] == '\\') { bs++; k-- }
    return (bs and 1) == 1
}

internal fun htmlEscapeAll(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

internal fun replaceTextSymbols(t0: String): String {
    var t = t0
    t = t.replace(Regex("""\\textellipsis\b"""), "…")
        .replace(Regex("""\\textquotedblleft\b"""), "\u201C")
        .replace(Regex("""\\textquotedblright\b"""), "\u201D")
        .replace(Regex("""\\textquoteleft\b"""), "'")
        .replace(Regex("""\\textquoteright\b"""), "'")
        .replace(Regex("""\\textemdash\b"""), "—")
        .replace(Regex("""\\textendash\b"""), "–")
        .replace(Regex("""\\textfractionsolidus\b"""), "⁄")
        .replace(Regex("""\\textdiv\b"""), "÷")
        .replace(Regex("""\\texttimes\b"""), "×")
        .replace(Regex("""\\textminus\b"""), "−")
        .replace(Regex("""\\textpm\b"""), "±")
        .replace(Regex("""\\textsurd\b"""), "√")
        .replace(Regex("""\\textlnot\b"""), "¬")
        .replace(Regex("""\\textasteriskcentered\b"""), "∗")
        .replace(Regex("""\\textbullet\b"""), "•")
        .replace(Regex("""\\textperiodcentered\b"""), "·")
        .replace(Regex("""\\textdaggerdbl\b"""), "‡")
        .replace(Regex("""\\textdagger\b"""), "†")
        .replace(Regex("""\\textsection\b"""), "§")
        .replace(Regex("""\\textparagraph\b"""), "¶")
        .replace(Regex("""\\textbardbl\b"""), "‖")
        .replace(Regex("""\\textbackslash\b"""), "&#92;")
    return t
}

/** Keep only the document body; do not show preamble/metadata (everything before \begin{document}). */
internal fun stripPreamble(s: String): String {
    val begin = s.indexOf(BEGIN_DOCUMENT)
    val end   = s.lastIndexOf(END_DOCUMENT)
    return if (begin >= 0 && end > begin) s.substring(begin + BEGIN_DOCUMENT.length, end) else ""
}

/**
 * Remove % line comments (safe heuristic):
 * cuts at the first unescaped % per line (so \% is preserved).
 */
internal fun stripLineComments(s: String): String =
    s.lines().joinToString("\n") { line ->
        val cut = firstUnescapedPercent(line)
        if (cut >= 0) line.substring(0, cut) else line
    }

internal fun firstUnescapedPercent(line: String): Int {
    var i = 0
    while (true) {
        val j = line.indexOf('%', i)
        if (j < 0) return -1
        var bs = 0
        var k = j - 1
        while (k >= 0 && line[k] == '\\') { bs++; k-- }
        if (bs % 2 == 0) return j  // even backslashes → % is not escaped
        i = j + 1                   // odd backslashes → escaped, keep searching
    }
}

internal fun findBalancedBrace(s: String, open: Int): Int {
    if (open < 0 || open >= s.length || s[open] != '{') return -1
    var depth = 0
    var i = open
    while (i < s.length) {
        when (s[i]) {
            '{' -> depth++
            '}' -> { depth--; if (depth == 0) return i }
            '\\' -> if (i + 1 < s.length) i++ // skip next char
        }
        i++
    }
    return -1
}

internal fun replaceCmd1ArgBalanced(s: String, cmd: String, wrap: (String) -> String): String {
    val rx = Regex("""\\$cmd\s*\{""")
    val sb = StringBuilder(s.length)
    var pos = 0
    while (true) {
        val m = rx.find(s, pos) ?: break
        val start = m.range.first
        val braceOpen = m.range.last
        val braceClose = findBalancedBrace(s, braceOpen)
        if (braceClose < 0) {
            sb.append(s, pos, start + 1)
            pos = start + 1
            continue
        }
        sb.append(s, pos, start)
        val inner = s.substring(braceOpen + 1, braceClose)
        sb.append(wrap(inner))
        pos = braceClose + 1
    }
    sb.append(s, pos, s.length)
    return sb.toString()
}

/** Balanced { ... } that tolerates $...$, \[...\], \(...\) and nested braces. */
internal fun findBalancedBraceAllowMath(s: String, open: Int): Int {
    if (open < 0 || open >= s.length || s[open] != '{') return -1
    var i = open
    var depth = 0
    var inDollar = false
    var inDoubleDollar = false
    var inBracket = false
    var inParen = false

    fun startsAt(idx: Int, tok: String) =
        idx >= 0 && idx + tok.length <= s.length && s.regionMatches(idx, tok, 0, tok.length)

    while (i < s.length) {
        if (startsAt(i, "$$")) { inDoubleDollar = !inDoubleDollar; i += 2; continue }
        if (!inDoubleDollar && s[i] == '$') { inDollar = !inDollar; i++; continue }
        if (!inDollar && !inDoubleDollar) {
            if (startsAt(i, "\\[")) { inBracket = true; i += 2; continue }
            if (startsAt(i, "\\]") && inBracket) { inBracket = false; i += 2; continue }
            if (startsAt(i, "\\(")) { inParen = true; i += 2; continue }
            if (startsAt(i, "\\)") && inParen) { inParen = false; i += 2; continue }
        }
        if (!inDollar && !inDoubleDollar && !inBracket && !inParen) {
            when (s[i]) {
                '{' -> { depth++; }
                '}' -> { depth--; if (depth == 0) return i }
                '\\' -> { if (i + 1 < s.length) i++ }
            }
        } else {
            if (s[i] == '\\' && i + 1 < s.length) i++
        }
        i++
    }
    return -1
}

internal fun peelTopLevelTextWrapper(raw: String): Pair<String, String?> {
    fun peel(cmd: String, tag: String): Pair<String, String?>? {
        val rx = Regex("""^\s*\\$cmd\s*\{""")
        val m = rx.find(raw) ?: return null
        val open = m.range.last
        val close = findBalancedBrace(raw, open)
        if (close < 0) return null
        val tail = raw.substring(close + 1).trim()
        if (tail.isNotEmpty()) return null
        val inner = raw.substring(open + 1, close)
        return inner to tag
    }
    return peel("textbf", "strong")
        ?: peel("emph", "em")
        ?: peel("textit", "em")
        ?: (raw to null)
}
