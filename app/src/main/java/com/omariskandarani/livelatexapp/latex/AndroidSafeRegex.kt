package com.omariskandarani.livelatexapp.latex

import kotlin.text.Regex
import kotlin.text.RegexOption

/**
 * Android's [Regex] can throw on patterns with `[^}]`, `[^{}]`, or `\\begin...` parsed as `\\b`+`egin`.
 * Use [latexLiteral] / [rxBetween] for LaTeX tokens that must match verbatim.
 */
internal fun latexLiteral(tex: String): String = Regex.escape(tex)

internal fun rxBetween(startTex: String, endTex: String, middlePattern: String): Regex =
    Regex(latexLiteral(startTex) + middlePattern + latexLiteral(endTex), RegexOption.DOT_MATCHES_ALL)
