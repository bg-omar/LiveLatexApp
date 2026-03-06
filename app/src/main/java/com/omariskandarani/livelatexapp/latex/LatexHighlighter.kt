package com.omariskandarani.livelatexapp.latex

import android.graphics.Color
import android.text.Editable
import android.text.Spannable
import android.text.style.ForegroundColorSpan
import java.util.regex.Pattern

/**
 * LaTeX syntax highlighting for an Editable (e.g. EditText):
 * - Comments (% to EOL) → gray
 * - Commands (\begin, \end, \%, \$, \{, \}, etc.) → blue
 * - Math delimiters \( \), \[ \], $ → purple
 * - Braces { } after \begin/\end (environment names) → teal
 *
 * Order: apply comments first, then math, then commands, then env names
 * so that overlapping regions are styled by the right rule.
 */
object LatexHighlighter {

    // % to end of line (but not \%)
    private val COMMENT = Pattern.compile("(?<!\\\\)%[^\n]*")

    // LaTeX command: \ + letters (@ allowed) OR \ + single non-letter (\$, \%, \{, \}, \&, \_, etc.)
    private val COMMAND = Pattern.compile("\\\\[a-zA-Z@]+|\\\\[^a-zA-Z]")

    // Math: \(, \), \[, \], and standalone $
    private val MATH_OPEN = Pattern.compile("\\\\\\(|\\\\\\[")
    private val MATH_CLOSE = Pattern.compile("\\\\\\)|\\\\\\]")
    private val MATH_INLINE = Pattern.compile("\\$")

    // \begin{env} and \end{env} – highlight env name (content of first {...} after \begin or \end)
    private val BEGIN_END = Pattern.compile("\\\\(?:begin|end)\\s*\\{([^{}]*)\\}")

    @JvmStatic
    fun applyHighlighting(editable: Editable?) {
        if (editable == null) return
        val text = editable.toString()
        if (text.isEmpty()) return

        val selStart = android.text.Selection.getSelectionStart(editable)
        val selEnd = android.text.Selection.getSelectionEnd(editable)

        clearOurSpans(editable)

        applyCommentSpans(editable, text)
        applyMathSpans(editable, text)
        applyCommandSpans(editable, text)
        applyEnvironmentNameSpans(editable, text)

        if (selStart in 0..editable.length && selEnd in 0..editable.length) {
            android.text.Selection.setSelection(editable, selStart, selEnd)
        }
    }

    private fun clearOurSpans(editable: Editable) {
        val spans = editable.getSpans(0, editable.length, ForegroundColorSpan::class.java)
        for (span in spans) {
            editable.removeSpan(span)
        }
    }

    private fun applyCommentSpans(editable: Editable, text: String) {
        val gray = Color.GRAY
        val matcher = COMMENT.matcher(text)
        while (matcher.find()) {
            setSpan(editable, matcher.start(), matcher.end(), gray)
        }
    }

    private fun applyCommandSpans(editable: Editable, text: String) {
        val blue = Color.parseColor("#1976D2")
        val matcher = COMMAND.matcher(text)
        while (matcher.find()) {
            setSpan(editable, matcher.start(), matcher.end(), blue)
        }
    }

    private fun applyMathSpans(editable: Editable, text: String) {
        val purple = Color.parseColor("#7B1FA2")
        var m = MATH_OPEN.matcher(text)
        while (m.find()) setSpan(editable, m.start(), m.end(), purple)
        m = MATH_CLOSE.matcher(text)
        while (m.find()) setSpan(editable, m.start(), m.end(), purple)
        m = MATH_INLINE.matcher(text)
        while (m.find()) setSpan(editable, m.start(), m.end(), purple)
    }

    private fun applyEnvironmentNameSpans(editable: Editable, text: String) {
        val teal = Color.parseColor("#00897B")
        val matcher = BEGIN_END.matcher(text)
        while (matcher.find()) {
            // Group 1 is the env name inside { }
            val start = matcher.start(1)
            val end = matcher.end(1)
            setSpan(editable, start, end, teal)
        }
    }

    private fun setSpan(editable: Editable, start: Int, end: Int, color: Int) {
        editable.setSpan(
            ForegroundColorSpan(color),
            start,
            end,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }
}
