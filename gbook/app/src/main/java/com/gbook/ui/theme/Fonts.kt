package com.gbook.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.gbook.R

/*
 * Apple Books' reading fonts (Athelas, Charter, Georgia, Iowan, New York, San Francisco, Seravek…) are
 * licensed for Apple platforms only, so GBook bundles the closest SIL Open Font License equivalents.
 */

private fun family(vararg fonts: Pair<Int, Int>) = FontFamily(fonts.map { (res, w) -> Font(res, FontWeight(w)) })

val Literata = family(R.font.literata_400 to 400, R.font.literata_600 to 600, R.font.literata_700 to 700)
val Charis = family(R.font.charis_400 to 400, R.font.charis_700 to 700)
val SourceSerif = family(R.font.sourceserif_400 to 400, R.font.sourceserif_600 to 600, R.font.sourceserif_700 to 700)
val Newsreader = family(R.font.newsreader_400 to 400, R.font.newsreader_600 to 600, R.font.newsreader_700 to 700)
val Gelasio = family(R.font.gelasio_400 to 400, R.font.gelasio_600 to 600, R.font.gelasio_700 to 700)
val CrimsonPro = family(R.font.crimson_400 to 400, R.font.crimson_600 to 600, R.font.crimson_700 to 700)
val EBGaramond = family(R.font.garamond_400 to 400, R.font.garamond_600 to 600, R.font.garamond_700 to 700)
val Inter = family(R.font.inter_400 to 400, R.font.inter_500 to 500, R.font.inter_600 to 600, R.font.inter_700 to 700)
val DMSans = family(R.font.dmsans_400 to 400, R.font.dmsans_500 to 500, R.font.dmsans_600 to 600, R.font.dmsans_700 to 700)

/** App UI sans. */
val AppSans = DMSans

/** Display serif for large titles (Apple uses New York). */
val DisplaySerif = Newsreader

/** Serif for quotes and highlight snippets outside the reader. */
val QuoteSerif = Literata

/** [like] names the Apple Books font each one stands in for. */
class ReadingFont(val id: String, val name: String, val like: String, val family: FontFamily)

val ReadingFonts = listOf(
    ReadingFont("literata", "Literata", "Original", Literata),
    ReadingFont("charis", "Charis", "Charter", Charis),
    ReadingFont("sourceserif", "Source Serif", "Iowan", SourceSerif),
    ReadingFont("newsreader", "Newsreader", "New York", Newsreader),
    ReadingFont("gelasio", "Gelasio", "Georgia", Gelasio),
    ReadingFont("crimson", "Crimson Pro", "Athelas", CrimsonPro),
    ReadingFont("garamond", "EB Garamond", "Palatino", EBGaramond),
    ReadingFont("inter", "Inter", "San Francisco", Inter),
    ReadingFont("dmsans", "DM Sans", "Avenir Next", DMSans),
)

fun readingFont(id: String) = ReadingFonts.firstOrNull { it.id == id } ?: ReadingFonts[0]
