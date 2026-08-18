package com.junkfood.seal.music

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicHistoryTest {
    @Test
    fun `today filter respects the local calendar day`() {
        val zone = TimeZone.getTimeZone("America/Anchorage")
        fun timestamp(day: Int, hour: Int, minute: Int) =
            Calendar.getInstance(zone)
                .apply {
                    clear()
                    set(2026, Calendar.AUGUST, day, hour, minute)
                }
                .timeInMillis

        val today = timestamp(18, 22, 30)
        val earlierToday = timestamp(18, 0, 1)
        val yesterday = timestamp(17, 23, 59)

        assertTrue(isHeardToday(earlierToday, now = today, timeZone = zone))
        assertFalse(isHeardToday(yesterday, now = today, timeZone = zone))
    }
}
