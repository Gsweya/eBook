package com.gbook.data

import kotlin.math.pow

/** Builds Instagram-style posts from highlights and from chapter text (extractive, on-device). */
object PostGenerator {
    private val STOP = """
        a about above after again against all am an and any are as at be because been before being below
        between both but by can could did do does doing down during each few for from further had has have
        having he her here hers herself him himself his how i if in into is it its itself just me more most my
        myself no nor not now of off on once only or other our ours ourselves out over own same she should so
        some such than that the their theirs them themselves then there these they this those through to too
        under until up very was we were what when where which while who whom why will with would you your
        yours yourself yourselves also one two may might must said says like even much many us upon
    """.trimIndent().split(Regex("\\s+")).toSet()

    /** Front/back matter that makes poor posts. */
    private val MATTER = Regex(
        "^(praise|contents|table of contents|copyright|dedication|acknowledg|index|notes|endnotes|about the (author|publisher)|also by|title page|cover|bibliography|epigraph|further reading|credits|newsletter|sign up|front matter)",
        RegexOption.IGNORE_CASE,
    )

    private fun isMatter(ch: Chapter) =
        MATTER.containsMatchIn(ch.title.trim()) || ch.blocks.sumOf { if (it.type == 0) it.text.length else 0 } < 3500

    private val SENTENCE = Regex("(?<=[.!?][\"”’)]?)\\s+(?=[\"“‘(]?[A-Z])")

    fun sentences(text: String) = text.split(SENTENCE).map { it.trim() }.filter { it.isNotEmpty() }

    private fun words(s: String) = Regex("[A-Za-z']+").findAll(s.lowercase()).map { it.value }
        .filter { it.length > 3 && it !in STOP }

    private class Candidate(val chapter: Int, val text: String, val score: Double)

    /** Ranked candidate sentences per chapter, scored by book-wide content-word frequency. */
    private fun rank(content: BookContent): Map<Int, List<Candidate>> {
        val freq = HashMap<String, Int>()
        content.chapters.forEach { c -> c.blocks.forEach { b -> if (b.type == 0) words(b.text).forEach { freq.merge(it, 1, Int::plus) } } }
        return content.chapters.mapIndexed { ci, ch ->
            ci to ch.blocks.filter { it.type == 0 }.flatMap { b -> sentences(b.text) }
                .filter { it.length in 70..260 && !it.contains("http") && it.count { c -> c == '"' || c == '“' } < 3 }
                .map { s ->
                    val ws = words(s).toSet()
                    val base = ws.sumOf { (freq[it] ?: 0).toDouble().pow(0.5) } / (ws.size + 4.0).pow(0.6)
                    val cue = if (Regex("\\b(is|means|the key|important|must|never|always|because)\\b", RegexOption.IGNORE_CASE).containsMatchIn(s)) 1.15 else 1.0
                    Candidate(ci, s, base * cue)
                }
                .sortedByDescending { it.score }
        }.toMap()
    }

    fun fromHighlight(book: Book, h: Highlight, chapterTitle: String) = Post(
        id = "p-" + h.id, bookId = book.id, kind = PostKind.QUOTE,
        slides = listOfNotNull(tidyQuote(h.text), h.note?.takeIf { it.isNotBlank() }),
        chapterTitle = chapterTitle, chapter = h.chapter, highlightId = h.id, style = (h.color + 1) % 6,
    )

    /** Marks mid-sentence selections with ellipses so the quote reads cleanly on its own. */
    private fun tidyQuote(raw: String): String {
        var t = raw.trim().trim(',', ';', ':', '—', '-').trim()
        if (t.firstOrNull()?.isLowerCase() == true) t = "…$t"
        if (t.lastOrNull()?.let { it in ".!?\"”’)…" } == false) t = "$t…"
        return t
    }

    /** Key-idea posts (single slide) plus chapter-recap carousels. */
    fun fromBook(book: Book, content: BookContent, ideasPerChapter: Int = 1, recaps: Boolean = true): List<Post> {
        val ranked = rank(content)
        val now = System.currentTimeMillis()
        val out = mutableListOf<Post>()
        content.chapters.forEachIndexed { ci, ch ->
            val cands = ranked[ci].orEmpty()
            if (cands.isEmpty() || isMatter(ch)) return@forEachIndexed
            cands.take(ideasPerChapter).forEachIndexed { k, c ->
                out += Post(
                    id = "i-${book.id}-$ci-$k", bookId = book.id, kind = PostKind.IDEA, slides = listOf(c.text),
                    chapterTitle = ch.title, chapter = ci, style = (ci + k) % 6, createdAt = now - out.size,
                )
            }
            if (recaps && cands.size >= 4) {
                // Keep the top sentences in reading order so the carousel reads like a summary.
                val top = cands.take(4).map { it.text }
                val ordered = sentencesInOrder(ch, top)
                out += Post(
                    id = "r-${book.id}-$ci", bookId = book.id, kind = PostKind.RECAP,
                    slides = listOf(ch.title) + ordered, chapterTitle = ch.title, chapter = ci,
                    style = (ci + 3) % 6, createdAt = now - out.size,
                )
            }
        }
        return out
    }

    private fun sentencesInOrder(ch: Chapter, picks: List<String>): List<String> {
        val all = ch.blocks.filter { it.type == 0 }.flatMap { sentences(it.text) }
        return picks.sortedBy { p -> all.indexOf(p).let { if (it < 0) Int.MAX_VALUE else it } }
    }
}
