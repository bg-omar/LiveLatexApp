package com.omariskandarani.livelatexapp.latex

import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.text.RegexOption

/**
 * LaTeX→HTML utility functions. Part of LatexHtml multi-file object.
 */

internal fun fixInlineBoundarySpaces(html: String): String =
    Regex(
        """</(?:strong|em|u|small|code|span)>(?=(?:<(?!/)|[A-Za-z0-9(]))""",
        RegexOption.IGNORE_CASE
    ).replace(html) { it.value + " " }

internal data class TitleMeta(
    val title: String?,
    val authors: String?,
    val dateRaw: String?
)

internal fun findLastCmdArg(src: String, cmd: String): String? {
    val rx = Regex("""\\$cmd\s*\{""")
    var pos = 0
    var last: String? = null
    while (true) {
        val m = rx.find(src, pos) ?: break
        val open = m.range.last
        val close = findBalancedBrace(src, open)
        if (close < 0) break
        last = src.substring(open + 1, close)
        pos = close + 1
    }
    return last
}

internal fun extractTitleMeta(srcNoComments: String): TitleMeta {
    val ttl = findLastCmdArg(srcNoComments, "title")
    val aut = findLastCmdArg(srcNoComments, "author")
    val dat = findLastCmdArg(srcNoComments, "date")
    return TitleMeta(ttl, aut, dat)
}

internal fun renderDate(dateRaw: String?): String? {
    if (dateRaw == null) return null
    val today = LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM d, yyyy"))
    val trimmed = dateRaw.trim()
    if (trimmed.isEmpty()) return ""
    val replaced = trimmed.replace("""\today""", today)
    return latexProseToHtmlWithMath(replaced)
}

internal fun splitAuthors(raw: String): List<String> =
    Regex("""\\and""").split(raw).map { it.trim() }.filter { it.isNotEmpty() }

internal fun processThanksWithin(text: String, notes: MutableList<String>): String {
    var s = text
    while (true) {
        val i = s.indexOf("""\thanks{"""); if (i < 0) break
        val open = s.indexOf('{', i + 1); if (open < 0) break
        val close = findBalancedBrace(s, open); if (close < 0) break
        val content = s.substring(open + 1, close)
        notes += latexProseToHtmlWithMath(content)
        val n = notes.size
        s = s.substring(0, i) + "<sup>$n</sup>" + s.substring(close + 1)
    }
    return s
}

internal fun buildMakTitleHtml(meta: TitleMeta): String {
    val notes = mutableListOf<String>()
    val titleHtml = meta.title?.let { latexProseToHtmlWithMath(processThanksWithin(it, notes)) } ?: ""
    val authorsHtml = meta.authors?.let { raw ->
        val parts = splitAuthors(raw).map { p ->
            val withMarks = processThanksWithin(p, notes)
            """<span class="author">${latexProseToHtmlWithMath(withMarks)}</span>"""
        }
        parts.joinToString("""<span class="author-sep" style="padding:0 .6em;opacity:.5;">·</span>""")
    } ?: ""
    val dateHtml = renderDate(meta.dateRaw) ?: ""
    val notesHtml = if (notes.isEmpty()) "" else {
        val lis = notes.mapIndexed { idx, txt -> """<li value="${idx+1}">$txt</li>""" }.joinToString("")
        """<ol class="title-notes" style="margin:.6em 0 0 1.2em;font-size:.95em;">$lis</ol>"""
    }
    return """
<div class="maketitle" style="margin:8px 0 16px;border-bottom:1px solid var(--border);padding-bottom:8px;">
  ${if (titleHtml.isNotEmpty()) """<h1 style="margin:0 0 .25em 0;">$titleHtml</h1>""" else ""}
  ${if (authorsHtml.isNotEmpty()) """<div class="authors" style="margin:.2em 0;">$authorsHtml</div>""" else ""}
  ${if (dateHtml.isNotEmpty()) """<div class="date" style="opacity:.8;margin-top:.15em;">$dateHtml</div>""" else ""}
  $notesHtml
</div>
""".trim()
}

/** Remove \maketitle (title block is already prepended at start of body so it appears before abstract). */
internal fun convertMakeTitle(body: String, meta: TitleMeta): String =
    body.replace(Regex("""\\maketitle\b"""), "")

internal fun escapeHtmlKeepBackslashes(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

internal fun applyInlineFormattingOutsideTags(html: String): String {
    val tableRx = Regex("(?is)(<table\\b.*?</table>)")
    val segments = tableRx.split(html)
    val tables   = tableRx.findAll(html).map { it.value }.toList()
    val out = StringBuilder(html.length + 256)
    for (i in segments.indices) {
        out.append(applyInlineFormattingOutsideTags_NoTables(segments[i]))
        if (i < tables.size) out.append(tables[i])
    }
    return out.toString()
}

internal fun applyInlineFormattingOutsideTags_NoTables(html: String): String {
    val rx = Regex("(<[^>]+>)")
    val parts = rx.split(html)
    val tags  = rx.findAll(html).map { it.value }.toList()
    val out = StringBuilder(html.length + 256)
    for (i in parts.indices) {
        val chunk = parts[i]
        if (!chunk.contains('<') && !chunk.contains('>')) {
            out.append(latexProseToHtmlWithMath(chunk))
        } else out.append(chunk)
        if (i < tags.size) out.append(tags[i])
    }
    return out.toString()
}

internal fun injectLineAnchors(s: String, absOffset: Int, everyN: Int = 3): String {
    val mathEnvs = setOf(
        "equation", "equation*", "align", "align*", "aligned", "aligned*",
        "gather", "gather*", "multline", "multline*", "flalign", "flalign*",
        "alignat", "alignat*", "bmatrix", "pmatrix", "vmatrix", "Bmatrix", "Vmatrix",
        "smallmatrix", "matrix", "cases", "split"
    )
    var inHtmlTag = false
    var attrQuote: Char? = null
    var inHtmlComment = false
    var inVerbatimTag = false
    var verbatimTagName = ""
    fun isVerbatimOpenTag(name: String) = when (name.lowercase()) {
        "script", "style", "pre", "code", "textarea" -> true
        else -> false
    }
    var inDollar = false
    var inDoubleDollar = false
    var inBracket = false
    var inParen = false
    var envDepth = 0
    fun startsAt(idx: Int, tok: String) =
        idx + tok.length <= s.length && s.regionMatches(idx, tok, 0, tok.length)
    fun readHtmlTagName(from: Int): Pair<String, Int> {
        var i = from
        if (i < s.length && s[i] == '<') i++
        if (i < s.length && s[i] == '/') i++
        val start = i
        while (i < s.length) {
            val c = s[i]
            if (c.isWhitespace() || c == '>' || c == '/') break
            i++
        }
        return s.substring(start, i).lowercase() to i
    }
    var i = 0
    var line = 0
    val sb = StringBuilder(s.length + 1024)
    while (i < s.length) {
        if (!inHtmlComment) {
            if (startsAt(i, "<!--")) {
                inHtmlComment = true
                sb.append("<!--"); i += 4
                continue
            }
        } else {
            val end = s.indexOf("-->", i)
            if (end >= 0) {
                sb.append(s, i, end + 3)
                i = end + 3
            } else {
                sb.append(s.substring(i))
                i = s.length
            }
            continue
        }
        if (!inHtmlTag && startsAt(i, "<")) {
            val (tag, _) = readHtmlTagName(i)
            inHtmlTag = true
            attrQuote = null
            if (tag.isNotEmpty() && isVerbatimOpenTag(tag)) {
                inVerbatimTag = true
                verbatimTagName = tag
            }
            sb.append('<'); i += 1
            continue
        }
        if (inHtmlTag) {
            val c = s[i]
            sb.append(c); i++
            if (attrQuote == null) {
                if (c == '"' || c == '\'') attrQuote = c
                else if (c == '>') {
                    inHtmlTag = false
                    val lookBack = 64.coerceAtMost(sb.length)
                    val tail = sb.substring(sb.length - lookBack)
                    val closeTag = Regex("</\\s*([a-zA-Z0-9:-]+)\\s*>").find(tail)?.groups?.get(1)?.value?.lowercase()
                    if (inVerbatimTag && closeTag == verbatimTagName) {
                        inVerbatimTag = false
                        verbatimTagName = ""
                    }
                }
            } else {
                if (c == attrQuote) attrQuote = null
            }
            continue
        }
        if (inVerbatimTag) {
            val needle = "</$verbatimTagName>"
            val at = s.indexOf(needle, i, ignoreCase = true)
            if (at < 0) {
                sb.append(s.substring(i))
                i = s.length
            } else {
                sb.append(s, i, at)
                i = at
            }
            continue
        }
        if (!inBracket && !inParen) {
            if (startsAt(i, "$$")) {
                inDoubleDollar = !inDoubleDollar
                sb.append("$$"); i += 2; continue
            }
            if (!inDoubleDollar && s[i] == '$') {
                val prev = if (i > 0) s[i - 1] else ' '
                if (prev != '\\') {
                    inDollar = !inDollar
                    sb.append('$'); i += 1; continue
                }
            }
        }
        if (!inDollar && !inDoubleDollar) {
            if (startsAt(i, "\\[")) { inBracket = true;  sb.append("\\["); i += 2; continue }
            if (startsAt(i, "\\]") && inBracket) { inBracket = false; sb.append("\\]"); i += 2; continue }
            if (startsAt(i, "\\(")) { inParen = true;   sb.append("\\("); i += 2; continue }
            if (startsAt(i, "\\)") && inParen) { inParen = false;  sb.append("\\)"); i += 2; continue }
            if (startsAt(i, "\\begin{")) {
                val end  = s.indexOf('}', i + 7)
                val name = if (end > 0) s.substring(i + 7, end) else ""
                if (name in mathEnvs) envDepth++
                sb.append(s, i, (end + 1).coerceAtMost(s.length))
                i = (end + 1).coerceAtMost(s.length)
                continue
            }
            if (startsAt(i, "\\end{")) {
                val end  = s.indexOf('}', i + 5)
                val name = if (end > 0) s.substring(i + 5, end) else ""
                if (name in mathEnvs && envDepth > 0) envDepth--
                sb.append(s, i, (end + 1).coerceAtMost(s.length))
                i = (end + 1).coerceAtMost(s.length)
                continue
            }
        }
        val ch = s[i]
        if (ch == '\n') {
            line++
            sb.append('\n')
            val safeSpot =
                !inDollar && !inDoubleDollar &&
                !inBracket && !inParen &&
                envDepth == 0 &&
                !inHtmlTag && !inHtmlComment && !inVerbatimTag &&
                attrQuote == null
            if (safeSpot && (line % everyN == 0)) {
                val absLine = absOffset + line
                sb.append("<span class=\"syncline\" data-abs=\"$absLine\"></span>")
            }
            i++
            continue
        }
        sb.append(ch); i++
    }
    return sb.toString()
}

internal fun toFileUrl(f: File): String = f.toURI().toString()

/** Relative path under [baseDir] when possible, else `file:` URL — matches WebView `loadDataWithBaseURL` file base. */
private fun imgSrcPathForWebView(resolvedFile: File, baseDir: File): String {
    if (baseDir.path.isEmpty() || !baseDir.isDirectory) return toFileUrl(resolvedFile)
    return try {
        val bc = baseDir.canonicalFile
        val fc = resolvedFile.canonicalFile
        val bp = bc.absolutePath.let { p -> if (p.endsWith(File.separator)) p else "$p${File.separator}" }
        if (!fc.absolutePath.startsWith(bp)) return toFileUrl(resolvedFile)
        fc.absolutePath.removePrefix(bp).replace(File.separatorChar, '/')
    } catch (_: Exception) {
        toFileUrl(resolvedFile)
    }
}

internal fun resolveImagePath(path: String, baseDirFallback: String = "figures"): String {
    val p = path.trim()
    if (p.isEmpty()) return ""
    if (p.startsWith("http://") || p.startsWith("https://") || p.startsWith("data:")) return p
    val baseDir = currentBaseDir?.let { File(it) } ?: File("")
    val abs = File(p)
    val chosen: File = when {
        abs.isAbsolute && abs.exists() -> abs
        else -> {
            val rel = File(baseDir, p)
            val relFigures = File(baseDir, "figures${File.separator}$p")
            val hasExt = p.contains('.')
            val exts = listOf(".png", ".jpg", ".jpeg", ".svg", ".pdf")
            fun existingFile(f: File): File? {
                if (hasExt) return if (f.exists()) f else null
                for (e in exts) {
                    val c = File(f.parentFile ?: baseDir, f.name + e)
                    if (c.exists()) return c
                }
                return null
            }
            existingFile(rel) ?: existingFile(relFigures) ?: (if (hasExt) rel else File(rel.parentFile ?: baseDir, rel.name + exts.first()))
        }
    }
    return imgSrcPathForWebView(chosen, baseDir)
}

internal fun convertIncludeGraphics(latex: String): String {
    val rx = Regex("""\\includegraphics(\[.*?\])?\{(.+?)\}""", RegexOption.DOT_MATCHES_ALL)
    return rx.replace(latex) { match ->
        val opts = match.groups[1]?.value ?: ""
        val path = match.groups[2]?.value ?: ""
        val resolvedPath = resolveImagePath(path)
        val widthMatch = Regex("width=([0-9.]+)\\\\?\\w*").find(opts)
        val width = widthMatch?.groups?.get(1)?.value ?: ""
        val style = if (width.isNotEmpty()) " style=\"max-width:${(width.toFloatOrNull()?.let { it * 100 } ?: 70).toInt()}%\"" else " style=\"max-width:70%\""
        "<img src=\"$resolvedPath\" alt=\"figure\"$style>"
    }
}

internal fun includeGraphicsStyle(options: String): String {
    val mWidth = Regex("""width\s*=\s*([0-9]*\.?[0-9]+)\\linewidth""").find(options)
    if (mWidth != null) {
        val pct = (mWidth.groupValues[1].toDoubleOrNull() ?: 1.0) * 100.0
        return "max-width:${pct.coerceIn(1.0, 100.0)}%;height:auto;"
    }
    val absW = Regex("""width\s*=\s*([0-9]*\.?[0-9]+)(cm|mm|pt|px)""").find(options)
    if (absW != null) {
        val w = absW.groupValues[1]
        val unit = absW.groupValues[2]
        return "width:${w}${unit};height:auto;max-width:100%;"
    }
    val scale = Regex("""scale\s*=\s*([0-9]*\.?[0-9]+)""").find(options)?.groupValues?.get(1)?.toDoubleOrNull()
    if (scale != null) {
        val pct = (scale * 100.0).coerceIn(1.0, 500.0)
        return "max-width:${pct}%;height:auto;"
    }
    return "max-width:100%;height:auto;"
}
