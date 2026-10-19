package com.cloudmail.app.presentation

import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

/** Small portable rich text model. Email HTML is generated only from escaped text. */
internal data class TextMark(val start: Int, val end: Int, val tag: String)
internal data class RichText(val text: String, val marks: List<TextMark> = emptyList()) {
    fun annotated() = buildAnnotatedString {
        append(text)
        marks.forEach { mark -> addStyle(when(mark.tag) { "b" -> SpanStyle(fontWeight = FontWeight.Bold); "i" -> SpanStyle(fontStyle = FontStyle.Italic); else -> SpanStyle(textDecoration = TextDecoration.Underline) }, mark.start.coerceIn(0, text.length), mark.end.coerceIn(0, text.length)) }
    }
    fun toggle(start: Int, end: Int, tag: String): RichText {
        if (start >= end || tag !in listOf("b", "i", "u")) return this
        val clamped = TextMark(start.coerceIn(0, text.length), end.coerceIn(0, text.length), tag)
        val existing = marks.firstOrNull { it.tag == tag && it.start <= clamped.start && it.end >= clamped.end }
        return copy(marks = if (existing == null) marks + clamped else marks - existing)
    }
    fun edit(next: String): RichText {
        var prefix = 0
        while (prefix < minOf(text.length, next.length) && text[prefix] == next[prefix]) prefix++
        var suffix = 0
        while (suffix < minOf(text.length, next.length) - prefix && text[text.lastIndex - suffix] == next[next.lastIndex - suffix]) suffix++
        val oldEnd = text.length - suffix
        val newEnd = next.length - suffix
        val delta = next.length - text.length
        return RichText(next, marks.mapNotNull { mark ->
            val start = when { mark.start < prefix -> mark.start; mark.start >= oldEnd -> mark.start + delta; else -> prefix }
            val end = when { mark.end <= prefix -> mark.end; mark.end >= oldEnd -> mark.end + delta; else -> newEnd }
            if (start < end) TextMark(start.coerceIn(0, next.length), end.coerceIn(0, next.length), mark.tag) else null
        })
    }
    fun html(): String = buildString {
        append("<div>")
        var active = emptyList<String>()
        text.forEachIndexed { index, char ->
            val tags = listOf("b", "i", "u").filter { tag -> marks.any { it.tag == tag && index >= it.start && index < it.end } }
            if (tags != active) { active.asReversed().forEach { append("</$it>") }; tags.forEach { append("<$it>") }; active = tags }
            append(when (char) { '&' -> "&amp;"; '<' -> "&lt;"; '>' -> "&gt;"; '"' -> "&quot;"; '\'' -> "&#39;"; '\n' -> "<br>"; else -> char.toString() })
        }
        active.asReversed().forEach { append("</$it>") }
        append("</div>")
    }
    companion object {
        fun restore(text: String, html: String): RichText {
            if (!html.startsWith("<div>") || !html.endsWith("</div>")) return RichText(text)
            val result = StringBuilder()
            val marks = mutableListOf<TextMark>()
            val open = mutableMapOf<String, Int>()
            val pieces = Regex("<[^>]*>|&(?:amp|lt|gt|quot|#39);|[^<&]+|[<&]").findAll(html)
            pieces.forEach { match ->
                val part = match.value
                when {
                    part in listOf("<b>", "<i>", "<u>") -> open[part.substring(1, 2)] = result.length
                    part in listOf("</b>", "</i>", "</u>") -> { val tag = part.substring(2, 3); open.remove(tag)?.let { marks += TextMark(it, result.length, tag) } }
                    part == "<br>" -> result.append('\n')
                    part.startsWith("<") -> Unit
                    else -> result.append(when(part) { "&amp;" -> "&"; "&lt;" -> "<"; "&gt;" -> ">"; "&quot;" -> "\""; "&#39;" -> "'"; else -> part })
                }
            }
            return if (result.toString() == text) RichText(text, marks) else RichText(text)
        }
    }
}
