package ru.kojexy.chinesetv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class BroadcastTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val min = 60_000L

    // 2026-11-02 is a Monday.
    private val monday = LocalDate.of(2026, 11, 2)

    private fun show(id: String, episodes: Int, length: Long = 45 * min) =
        Show(id, id, (1..episodes).map { Episode("E$it", "file://$id/$it.mp4", length) })

    private fun at(date: LocalDate, h: Int, m: Int, s: Int = 0) = ZonedDateTime.of(date.atTime(h, m, s), zone)

    @Test
    fun dailySlotMovesOnEvenWhenNobodyWatches() {
        val schedule = Schedule(
            listOf(show("fanhua", 30)),
            listOf(Slot("a", "fanhua", 20 * 60, episodesPerAiring = 2, since = monday)),
        )
        // Wednesday is the third airing: episodes 5 and 6 (indices 4 and 5).
        val now = Broadcast.onAir(schedule, at(monday.plusDays(2), 20, 10))
        assertNotNull(now)
        assertEquals(4, now!!.episode)
        assertEquals(10 * min, now.positionMs)
    }

    @Test
    fun secondEpisodeFollowsTheFirst() {
        val schedule = Schedule(
            listOf(show("s", 10)),
            listOf(Slot("a", "s", 20 * 60, episodesPerAiring = 2, since = monday)),
        )
        val now = Broadcast.onAir(schedule, at(monday, 20, 50))!!
        assertEquals(1, now.episode)
        assertEquals(5 * min, now.positionMs)
        assertEquals(at(monday, 20, 45), now.episodeStart)
        assertNull(Broadcast.onAir(schedule, at(monday, 21, 30)))
    }

    @Test
    fun weekdaySlotSkipsTheWeekend() {
        val slot = Slot("a", "s", 19 * 60, days = setOf(1, 2, 3, 4, 5), since = monday)
        val schedule = Schedule(listOf(show("s", 40)), listOf(slot))
        // Next Monday: five airings before it.
        val nextMonday = monday.plusDays(7)
        assertEquals(5, Broadcast.airingsBefore(slot, nextMonday))
        assertEquals(5, Broadcast.onAir(schedule, at(nextMonday, 19, 1))!!.episode)
        assertNull(Broadcast.onAir(schedule, at(monday.plusDays(5), 19, 1)))
        // Long ranges use whole weeks plus the rest.
        assertEquals(5 * 10 + 2, Broadcast.airingsBefore(slot, monday.plusDays(72)))
    }

    @Test
    fun seriesEndsWhenEpisodesRunOut() {
        val schedule = Schedule(
            listOf(show("s", 3)),
            listOf(Slot("a", "s", 20 * 60, episodesPerAiring = 2, since = monday)),
        )
        // Day 2 has only episode 3 left.
        val day2 = Broadcast.airing(schedule, schedule.slots[0], monday.plusDays(1), zone)!!
        assertEquals(2..2, day2.episodes)
        assertNull(Broadcast.airing(schedule, schedule.slots[0], monday.plusDays(2), zone))
        assertNull(Broadcast.next(schedule, at(monday.plusDays(1), 21, 0)))
    }

    @Test
    fun laterAiringInterruptsAnEarlierOne() {
        val schedule = Schedule(
            listOf(show("long", 5), show("film", 1, 90 * min)),
            listOf(
                Slot("a", "long", 19 * 60, episodesPerAiring = 5, since = monday),
                Slot("b", "film", 20 * 60, date = monday, since = monday),
            ),
        )
        assertEquals("film", Broadcast.onAir(schedule, at(monday, 20, 30))!!.airing.show.id)
        // The film ends at 21:30; the long block (until 22:45) is back, as if it never stopped.
        val after = Broadcast.onAir(schedule, at(monday, 21, 40))!!
        assertEquals("long", after.airing.show.id)
        assertEquals(3, after.episode)
    }

    @Test
    fun airingRunsPastMidnight() {
        val schedule = Schedule(
            listOf(show("s", 10)),
            listOf(Slot("a", "s", 23 * 60 + 30, days = setOf(1), episodesPerAiring = 2, since = monday)),
        )
        val now = Broadcast.onAir(schedule, at(monday.plusDays(1), 0, 20))!!
        assertEquals(1, now.episode)
        assertEquals(5 * min, now.positionMs)
    }

    @Test
    fun oneOffFilmAirsOnlyOnItsDate() {
        val schedule = Schedule(
            listOf(show("film", 1, 120 * min)),
            listOf(Slot("f", "film", 21 * 60, date = monday.plusDays(3), since = monday)),
        )
        assertNull(Broadcast.onAir(schedule, at(monday, 21, 10)))
        assertNotNull(Broadcast.onAir(schedule, at(monday.plusDays(3), 22, 59)))
        assertEquals(at(monday.plusDays(3), 21, 0), Broadcast.next(schedule, at(monday, 12, 0))!!.start)
    }

    @Test
    fun countingStartsTomorrowIfTodaysTimeHasPassed() {
        assertEquals(monday, Broadcast.countingFrom(20 * 60, at(monday, 19, 59)))
        assertEquals(monday.plusDays(1), Broadcast.countingFrom(20 * 60, at(monday, 20, 0)))
    }

    @Test
    fun nextOfSlotSkipsTheRunningAiring() {
        val slot = Slot("a", "s", 20 * 60, since = monday)
        val schedule = Schedule(listOf(show("s", 10)), listOf(slot))
        val next = Broadcast.nextOf(schedule, slot, at(monday, 20, 5))!!
        assertEquals(1..1, next.episodes)
    }

    @Test
    fun unknownDurationFallsBackSoTheClockMovesOn() {
        val s = Show("s", "s", listOf(Episode("E1", "x", 0), Episode("E2", "y", 0)))
        val schedule = Schedule(listOf(s), listOf(Slot("a", "s", 20 * 60, episodesPerAiring = 2, since = monday)))
        assertEquals(1, Broadcast.onAir(schedule, at(monday, 20, 50))!!.episode)
    }

    @Test
    fun guideMarksWhatIsOnNow() {
        val schedule = Schedule(listOf(show("s", 10)), listOf(Slot("a", "s", 20 * 60, since = monday)))
        val text = Guide.text(schedule, at(monday, 20, 1))
        assertTrue(text, text.contains("▶ 20:00–20:45  s · episode 1"))
        assertEquals("12:05", Guide.countdown(at(monday, 20, 0), at(monday, 20, 12, 5)))
        assertEquals("1:00:00", Guide.countdown(at(monday, 20, 0), at(monday, 21, 0)))
    }

    @Test
    fun episodeFilesSortLikePeopleRead() {
        val names = listOf("第10集.mp4", "第2集.mp4", "第1集.mp4", "E09.mkv", "E10.mkv")
        assertEquals(
            listOf("E09.mkv", "E10.mkv", "第1集.mp4", "第2集.mp4", "第10集.mp4"),
            names.sortedWith(NaturalOrder),
        )
    }
}
