package org.itantra.tts.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClauseSplitterTest {
    @Test
    fun `empty text splits to nothing`() {
        assertEquals(emptyList<String>(), ClauseSplitter.split(""))
        assertEquals(emptyList<String>(), ClauseSplitter.split("   "))
    }

    @Test
    fun `short sentence is not split`() {
        val text = "Hello there, how are you?"
        assertEquals(listOf(text), ClauseSplitter.split(text, maxChars = 60))
    }

    @Test
    fun `long sentence splits at commas`() {
        val text = "This is the first clause of the sentence, and this is the second clause, " +
            "and here is a third and final clause to wrap things up."
        val pieces = ClauseSplitter.split(text, maxChars = 60, minChars = 12)
        assertTrue("expected more than one piece, got $pieces", pieces.size > 1)
        // Reassembling (collapsing whitespace) should reproduce the original text losslessly.
        assertEquals(text, pieces.joinToString(" "))
        for (p in pieces) assertTrue("piece too long: '$p' (${p.length})", p.length <= 80)
    }

    @Test
    fun `no clause punctuation still respects max length by splitting on whitespace`() {
        val text = "word ".repeat(30).trim() // 149 chars, no commas/semicolons
        val pieces = ClauseSplitter.split(text, maxChars = 60, minChars = 12)
        assertTrue(pieces.size > 1)
        for (p in pieces) assertTrue("piece too long: '$p' (${p.length})", p.length <= 60)
        assertEquals(text, pieces.joinToString(" "))
    }

    @Test
    fun `short trailing pieces are merged into the previous piece`() {
        val pieces = ClauseSplitter.split("First clause here, ok", maxChars = 60, minChars = 12)
        // "ok" alone (2 chars) is well under minChars=12, so it must not appear as its own piece.
        assertTrue(pieces.none { it == "ok" })
    }
}
