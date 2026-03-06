package com.omariskandarani.livelatexapp

object LatexTemplates {

    data class Template(
        val name: String,
        val description: String,
        val content: String
    )

    val templates = listOf(
        Template(
            name = "Empty Document",
            description = "Blank LaTeX document",
            content = """
\documentclass{article}
\usepackage[utf8]{inputenc}

\title{Untitled}
\author{}
\date{\today}

\begin{document}

\maketitle

\section{Introduction}

Your content here.

\end{document}
            """.trimIndent()
        ),

        Template(
            name = "Article with Math",
            description = "Article with common math packages",
            content = """
\documentclass{article}
\usepackage[utf8]{inputenc}
\usepackage{amsmath, amssymb, amsthm}
\usepackage{physics}

\title{Mathematical Document}
\author{}
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
            name = "Beamer Presentation",
            description = "Slide presentation template",
            content = """
\documentclass{beamer}
\usetheme{Madrid}
\usecolortheme{default}

\title{Presentation Title}
\author{Your Name}
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
            name = "Report",
            description = "Academic report with sections",
            content = """
\documentclass[12pt]{report}
\usepackage[utf8]{inputenc}
\usepackage{graphicx}
\usepackage{hyperref}

\title{Report Title}
\author{Your Name}
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
            name = "Letter",
            description = "Formal letter template",
            content = """
\documentclass{letter}
\usepackage[utf8]{inputenc}

\signature{Your Name}
\address{Your Address \\ City, State ZIP}

\begin{document}

\begin{letter}{Recipient Name \\ Recipient Address \\ City, State ZIP}

\opening{Dear Sir or Madam,}

This is the body of the letter.

\closing{Sincerely,}

\end{letter}

\end{document}
            """.trimIndent()
        ),

        Template(
            name = "Homework",
            description = "Math/physics homework template",
            content = """
\documentclass{article}
\usepackage[utf8]{inputenc}
\usepackage{amsmath, amssymb}
\usepackage{physics}
\usepackage{enumitem}

\title{Homework Assignment}
\author{Your Name}
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

    fun getTemplate(index: Int): Template? {
        return templates.getOrNull(index)
    }

    fun getTemplateByName(name: String): Template? {
        return templates.find { it.name == name }
    }
}


