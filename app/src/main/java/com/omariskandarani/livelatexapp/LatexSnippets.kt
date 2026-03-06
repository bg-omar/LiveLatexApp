package com.omariskandarani.livelatexapp

/**
 * Builds LaTeX snippet strings for insert-at-cursor features:
 * lists, tables, figures, TikZ.
 */
object LatexSnippets {

    fun enumerateItems(items: List<String>): String {
        if (items.isEmpty()) return "\\begin{enumerate}\n\\item \n\\end{enumerate}"
        val body = items.joinToString("\n") { "  \\item ${escapeLatexContent(it)}" }
        return "\\begin{enumerate}\n$body\n\\end{enumerate}"
    }

    fun itemizeItems(items: List<String>): String {
        if (items.isEmpty()) return "\\begin{itemize}\n\\item \n\\end{itemize}"
        val body = items.joinToString("\n") { "  \\item ${escapeLatexContent(it)}" }
        return "\\begin{itemize}\n$body\n\\end{itemize}"
    }

    /**
     * @param rows List of rows; each row is a list of cell strings.
     * @param firstRowHeader If true, add \\hline after first row.
     * @param alignPerColumn e.g. "lcc" for left, center, center. Default "l" for all.
     */
    fun tabular(
        rows: List<List<String>>,
        firstRowHeader: Boolean = false,
        alignPerColumn: String? = null
    ): String {
        if (rows.isEmpty()) return "\\begin{tabular}{l}\n\\hline\n\\end{tabular}"
        val cols = rows.maxOfOrNull { it.size } ?: 0
        if (cols == 0) return "\\begin{tabular}{l}\n\\hline\n\\end{tabular}"
        val align = alignPerColumn?.take(cols)?.padEnd(cols, 'l') ?: "l".repeat(cols)
        val sb = StringBuilder()
        sb.append("\\begin{tabular}{$align}\n")
        sb.append("\\hline\n")
        rows.forEachIndexed { i, row ->
            val cells = (0 until cols).map { j -> escapeLatexContent(row.getOrNull(j)?.trim() ?: "") }
            sb.append(cells.joinToString(" & "))
            sb.append(" \\\\\n")
            if (firstRowHeader && i == 0) sb.append("\\hline\n")
        }
        sb.append("\\hline\n")
        sb.append("\\end{tabular}")
        return sb.toString()
    }

    /**
     * Full figure block with includegraphics.
     */
    fun figureWithImage(path: String, caption: String = "", label: String = ""): String {
        val cap = if (caption.isNotEmpty()) "\\caption{${escapeLatexContent(caption)}}" else "\\caption{}"
        val lab = if (label.isNotEmpty()) "\\label{$label}" else ""
        return """
            \begin{figure}[ht]
            \centering
            \includegraphics[width=\linewidth]{$path}
            $cap
            $lab
            \end{figure}
        """.trimIndent()
    }

    /**
     * Minimal includegraphics only (no figure wrapper).
     */
    fun includegraphics(path: String, width: String = "\\linewidth"): String {
        return "\\includegraphics[width=$width]{$path}"
    }

    enum class TikzTemplate(val label: String, val snippet: String) {
        EMPTY("Empty picture", """
            \begin{tikzpicture}
            \end{tikzpicture}
        """.trimIndent()),
        CIRCLE("Circle", """
            \begin{tikzpicture}
            \draw (0,0) circle (1);
            \end{tikzpicture}
        """.trimIndent()),
        RECTANGLE("Rectangle", """
            \begin{tikzpicture}
            \draw (0,0) rectangle (2,1);
            \end{tikzpicture}
        """.trimIndent()),
        ARROW("Arrow", """
            \begin{tikzpicture}
            \draw[->] (0,0) -- (2,0);
            \end{tikzpicture}
        """.trimIndent()),
        GRID("Grid", """
            \begin{tikzpicture}
            \draw[step=0.5] (0,0) grid (2,2);
            \end{tikzpicture}
        """.trimIndent()),
        NODE("Node with text", """
            \begin{tikzpicture}
            \node at (0,0) {text};
            \end{tikzpicture}
        """.trimIndent()),
    }

    fun tikzSnippet(template: TikzTemplate): String = template.snippet

    /** Escape only characters that break list/item content (e.g. % starts comment). */
    private fun escapeLatexContent(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("&", "\\&")
    }
}
