package com.louij2.s1dash

import org.junit.Assert.assertEquals
import org.junit.Test

class BackoffTest {
    @Test fun growsToTheCapAndStaysThere() {
        val b = Backoff()
        assertEquals(listOf(2_000L, 5_000L, 10_000L, 20_000L, 30_000L, 30_000L, 30_000L), List(7) { b.next() })
    }

    @Test fun successStartsAgainFromTheShortestWait() {
        val b = Backoff()
        repeat(4) { b.next() }
        b.reset()
        assertEquals(2_000L, b.next())
    }
}
