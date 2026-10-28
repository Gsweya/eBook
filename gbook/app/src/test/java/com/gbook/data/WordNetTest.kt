package com.gbook.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Runs against an unpacked Open English WordNet in `$WORDNET_DIR` (see [Dictionary.DOWNLOAD_URL]); skipped without it. */
class WordNetTest {
    private val dir = System.getenv("WORDNET_DIR")?.let(::File)
    private fun wn(): WordNet { assumeTrue(dir?.isDirectory == true); return WordNet(dir!!) }

    @Test fun findsPlainWords() {
        val d = wn().define("serendipity")
        assertNotNull(d)
        assertEquals("good luck in making unexpected and fortunate discoveries", d!!.senses.first().meaning)
    }

    @Test fun firstAndLastWordsInTheIndex() {
        assertNotNull(wn().define("zymase"))
        assertNotNull(wn().define("a"))
    }

    @Test fun inflectedFormsFindTheirBase() {
        assertTrue(wn().define("running")!!.senses.any { it.pos == "verb" && it.lemma == "run" })
        assertTrue(wn().define("stopped")!!.senses.any { it.pos == "verb" && it.lemma == "stop" })
        assertEquals("child", wn().define("children")!!.word)
        assertEquals("library", wn().define("libraries")!!.word)
    }

    @Test fun phrasesExamplesAndSynonyms() {
        val d = wn().define("New York minute")
        assertNotNull(d)
        val eph = wn().define("ephemeral")!!
        assertTrue(eph.senses.any { "transient" in it.synonyms })
    }

    @Test fun unknownWordsAreNull() {
        assertNull(wn().define("qwzxv"))
    }

    @Test fun cleansSelections() {
        assertEquals("ephemeral", Dictionary.clean("“ephemeral,”"))
        assertEquals("Gatsby", Dictionary.clean("Gatsby’s"))
    }
}
