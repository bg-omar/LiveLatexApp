package com.omariskandarani.livelatexapp

object LatexTemplates {

    /** Placeholders replaced when creating from template: {{AUTHOR}}, {{AFFILIATE}}, {{ADDRESS}}, {{EMAIL}}, {{ORCID}}, {{TITLE}}, {{SUBJECT}}, {{CONTENT}}. */
    private const val P_AUTHOR = "{{AUTHOR}}"
    private const val P_AFFILIATE = "{{AFFILIATE}}"
    private const val P_ADDRESS = "{{ADDRESS}}"
    private const val P_EMAIL = "{{EMAIL}}"
    private const val P_ORCID = "{{ORCID}}"
    private const val P_TITLE = "{{TITLE}}"
    private const val P_SUBJECT = "{{SUBJECT}}"
    private const val P_CONTENT = "{{CONTENT}}"

    data class Template(
        val id: String,
        val name: String,
        val description: String,
        val content: String,
        val isBuiltin: Boolean = true
    )

    /** Replaces template placeholders with values from defaults (and optional letter fields). Leaves placeholder if value blank. */
    fun applyDefaults(
        content: String,
        defaults: TemplateDefaultsPrefs.TemplateDefaults,
        title: String = "",
        subject: String = "",
        letterContent: String = ""
    ): String {
        var out = content
        out = out.replace(P_AUTHOR, defaults.author)
        out = out.replace(P_AFFILIATE, defaults.affiliate)
        out = out.replace(P_ADDRESS, defaults.address)
        out = out.replace(P_EMAIL, defaults.email)
        out = out.replace(P_ORCID, defaults.orcid)
        out = out.replace(P_TITLE, title)
        out = out.replace(P_SUBJECT, subject)
        out = out.replace(P_CONTENT, letterContent)
        return out
    }

    /** Built-in presets (always available as starting points for new templates). */
    val BUILTIN_TEMPLATES = listOf(
        Template(
            id = "builtin_0",
            name = "Empty Document",
            description = "Blank LaTeX document",
            content = """
\documentclass{article}
\usepackage[utf8]{inputenc}

\title{Untitled}
\author{$P_AUTHOR}
\date{\today}

\begin{document}

\maketitle

\section{Introduction}

Your content here.

\end{document}
            """.trimIndent()
        ),

        Template(
            id = "builtin_1",
            name = "Article with Math",
            description = "Article with common math packages",
            content = """
\documentclass{article}
\usepackage[utf8]{inputenc}
\usepackage{amsmath, amssymb, amsthm}
\usepackage{physics}

\title{Mathematical Document}
\author{$P_AUTHOR}
\date{\today}

\begin{document}

\maketitle

\section{Introduction}

Here is an inline equation: ${'$'}E = mc^2${'$'}

And a display equation:
\begin{equation}
    \int_{-\infty}^{\infty} e^{-x^2} dx = \sqrt{\pi}
\end{equation}

Quantum mechanics notation:
${'$'}${'$'}\ket{\psi} = \alpha\ket{0} + \beta\ket{1}${'$'}${'$'}

\end{document}
            """.trimIndent()
        ),

        Template(
            id = "builtin_2",
            name = "Beamer Presentation",
            description = "Slide presentation template",
            content = """
\documentclass{beamer}
\usetheme{Madrid}
\usecolortheme{default}

\title{Presentation Title}
\author{$P_AUTHOR}
\date{\today}

\begin{document}

\frame{\titlepage}

\begin{frame}
\frametitle{Outline}
\tableofcontents
\end{frame}

\section{First Section}

\begin{frame}
\frametitle{First Slide}
\begin{itemize}
    \item Point 1
    \item Point 2
    \item Point 3
\end{itemize}
\end{frame}

\begin{frame}
\frametitle{Second Slide}
Some content here.
\end{frame}

\end{document}
            """.trimIndent()
        ),

        Template(
            id = "builtin_3",
            name = "Report",
            description = "Academic report with sections",
            content = """
\documentclass[12pt]{report}
\usepackage[utf8]{inputenc}
\usepackage{graphicx}
\usepackage{hyperref}

\title{Report Title}
\author{$P_AUTHOR}
\date{\today}

\begin{document}

\maketitle
\tableofcontents

\chapter{Introduction}
This is the introduction chapter.

\section{Background}
Background information.

\section{Objectives}
Project objectives.

\chapter{Methodology}
Description of methods used.

\chapter{Results}
Presentation of results.

\chapter{Conclusion}
Summary and conclusions.

\end{document}
            """.trimIndent()
        ),

        Template(
            id = "builtin_4",
            name = "Cover letter (journal)",
            description = "Submission cover letter with TITLE, SUBJECT, CONTENT placeholders",
            content = """
\documentclass[a4paper,10pt]{letter}

\usepackage[T1]{fontenc}
\usepackage[utf8]{inputenc}
\usepackage{lmodern}
\usepackage[hidelinks]{hyperref}
\usepackage{microtype}
\usepackage[margin=1in]{geometry}

% Sender info (from template defaults: author, affiliate, address, email, orcid)
\signature{$P_AUTHOR\\
$P_AFFILIATE\\
ORCID: $P_ORCID\\
Email: \href{mailto:$P_EMAIL}{$P_EMAIL}}

\address{$P_AUTHOR\\
$P_ADDRESS}

\date{\today}

\begin{document}

    \begin{letter}{$P_SUBJECT}

        \opening{Dear Editors,}

        $P_CONTENT

        \closing{Sincerely,}

    \end{letter}

\end{document}
            """.trimIndent()
        ),

        Template(
            id = "builtin_5",
            name = "Homework",
            description = "Math/physics homework template",
            content = """
\documentclass{article}
\usepackage[utf8]{inputenc}
\usepackage{amsmath, amssymb}
\usepackage{physics}
\usepackage{enumitem}

\title{Homework Assignment}
\author{$P_AUTHOR}
\date{\today}

\begin{document}

\maketitle

\section*{Problem 1}
\textbf{Question:} State the problem here.

\textbf{Solution:}

Your solution goes here.

\section*{Problem 2}
\textbf{Question:} State the problem here.

\textbf{Solution:}

Your solution goes here.

\end{document}
            """.trimIndent()
        )
    )

    /** @deprecated Use [BUILTIN_TEMPLATES] or [UserTemplatesPrefs.getEffectiveTemplates]. */
    @Deprecated("Use BUILTIN_TEMPLATES or UserTemplatesPrefs.getEffectiveTemplates")
    val templates: List<Template> get() = BUILTIN_TEMPLATES

    fun getTemplate(index: Int): Template? {
        return BUILTIN_TEMPLATES.getOrNull(index)
    }

    fun getTemplateByName(name: String): Template? {
        return BUILTIN_TEMPLATES.find { it.name == name }
    }
}
