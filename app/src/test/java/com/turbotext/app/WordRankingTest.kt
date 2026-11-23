package com.turbotext.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WordRankingTest {
    private fun first(vararg c: WordRanking.Candidate) = WordRanking.order(c.toList()).first().word

    @Test fun moreUsedWinsAfterOneOtherPick() {
        // "me" picked 5 times, then "of" once: "me" stays first.
        assertEquals("me", first(
            WordRanking.Candidate("of", 2, 1, 300),
            WordRanking.Candidate("me", 2, 5, 200)
        ))
    }

    @Test fun threeBeatsTwo() = assertEquals("of", first(
        WordRanking.Candidate("of", 2, 3, 100),
        WordRanking.Candidate("me", 2, 2, 300)
    ))

    @Test fun unusedFollowDictionary() = assertEquals("of", first(
        WordRanking.Candidate("me", 2, 0, 200),
        WordRanking.Candidate("of", 2, 0, 300)
    ))

    @Test fun exactWordsBeforeLonger() = assertEquals("me", first(
        WordRanking.Candidate("men", 3, 50, 999),
        WordRanking.Candidate("me", 2, 0, 1)
    ))

    @Test fun pairNeedsTwoPicksAndALead() {
        assertNull(WordRanking.pairLeader(mapOf("of" to 1)))
        assertNull(WordRanking.pairLeader(mapOf("of" to 2, "me" to 2)))
        assertEquals("of", WordRanking.pairLeader(mapOf("of" to 3, "me" to 2)))
    }
}
