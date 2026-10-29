package ru.kojexy.chinesetv

import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** Used when a file's length is unknown, so the clock can still move on to the next episode. */
const val FALLBACK_DURATION_MS = 45 * 60_000L

fun Episode.lengthMs() = if (durationMs > 0) durationMs else FALLBACK_DURATION_MS

/** One airing of a slot: [episodes] of [show], back to back from [start]. */
data class Airing(val slot: Slot, val show: Show, val start: ZonedDateTime, val episodes: IntRange) {
    val end: ZonedDateTime
        get() = start.plus(Duration.ofMillis(episodes.sumOf { show.episodes[it].lengthMs() }))

    fun key(episode: Int) = "${slot.id}@${start.toEpochSecond()}#$episode"
}

/** What is on the screen right now: [episode] of the airing, [positionMs] into it. */
data class OnAir(val airing: Airing, val episode: Int, val positionMs: Long, val episodeStart: ZonedDateTime) {
    val key get() = airing.key(episode)
    val item get() = airing.show.episodes[episode]
    val episodeEnd: ZonedDateTime get() = episodeStart.plus(Duration.ofMillis(item.lengthMs()))
}

/** The broadcast clock. Pure functions of the schedule and the time, so the app can be closed and reopened at any moment. */
object Broadcast {

    fun airsOn(slot: Slot, date: LocalDate): Boolean =
        if (slot.date != null) date == slot.date
        else !date.isBefore(slot.since) && (slot.days.isEmpty() || date.dayOfWeek.value in slot.days)

    /** How many times a repeating slot has aired on the days from [Slot.since] up to, not including, [date]. */
    fun airingsBefore(slot: Slot, date: LocalDate): Int {
        val total = ChronoUnit.DAYS.between(slot.since, date)
        if (total <= 0) return 0
        if (slot.days.isEmpty()) return total.toInt()
        val weeks = total / 7
        var count = (weeks * slot.days.size).toInt()
        var d = slot.since.plusDays(weeks * 7)
        while (d.isBefore(date)) {
            if (d.dayOfWeek.value in slot.days) count++
            d = d.plusDays(1)
        }
        return count
    }

    fun firstEpisodeOn(slot: Slot, date: LocalDate): Int =
        if (slot.date != null) slot.startEpisode
        else slot.startEpisode + airingsBefore(slot, date) * slot.episodesPerAiring

    /** The airing of [slot] on [date], or null if it does not air that day or the series has already ended. */
    fun airing(schedule: Schedule, slot: Slot, date: LocalDate, zone: ZoneId): Airing? {
        if (!airsOn(slot, date)) return null
        val show = schedule.show(slot.showId) ?: return null
        val first = firstEpisodeOn(slot, date)
        if (first >= show.episodes.size) return null
        val last = minOf(first + slot.episodesPerAiring, show.episodes.size) - 1
        val start = date.atTime(LocalTime.of(slot.minuteOfDay / 60, slot.minuteOfDay % 60)).atZone(zone)
        return Airing(slot, show, start, first..last)
    }

    fun airingsOn(schedule: Schedule, date: LocalDate, zone: ZoneId): List<Airing> =
        schedule.slots.mapNotNull { airing(schedule, it, date, zone) }.sortedBy { it.start.toInstant() }

    /**
     * What is playing at [now]. Airings that started on the two previous days are checked too, so a long
     * evening block that runs past midnight keeps going. If two airings overlap, the one that started later
     * is on screen (it interrupts the other, like a real channel); the earlier one keeps running underneath.
     */
    fun onAir(schedule: Schedule, now: ZonedDateTime): OnAir? {
        val today = now.toLocalDate()
        val running = (2 downTo 0)
            .flatMap { airingsOn(schedule, today.minusDays(it.toLong()), now.zone) }
            .filter { !it.start.isAfter(now) && it.end.isAfter(now) }
            .maxByOrNull { it.start.toInstant() } ?: return null
        var offset = Duration.between(running.start, now).toMillis()
        var episodeStart = running.start
        for (i in running.episodes) {
            val length = running.show.episodes[i].lengthMs()
            if (offset < length) return OnAir(running, i, offset, episodeStart)
            offset -= length
            episodeStart = episodeStart.plus(Duration.ofMillis(length))
        }
        return null
    }

    /** The first airing that starts after [now], looking [days] ahead. */
    fun next(schedule: Schedule, now: ZonedDateTime, days: Int = 14): Airing? =
        (0..days).asSequence()
            .flatMap { airingsOn(schedule, now.toLocalDate().plusDays(it.toLong()), now.zone) }
            .firstOrNull { it.start.isAfter(now) }

    /** The next airing of one slot that has not started yet. */
    fun nextOf(schedule: Schedule, slot: Slot, now: ZonedDateTime): Airing? {
        if (slot.date != null) return airing(schedule, slot, slot.date, now.zone)?.takeIf { it.start.isAfter(now) }
        return (0..7).firstNotNullOfOrNull { d ->
            airing(schedule, slot, now.toLocalDate().plusDays(d.toLong()), now.zone)?.takeIf { it.start.isAfter(now) }
        }
    }

    /**
     * The day a new or changed slot starts counting from. If today's start time has already passed, counting
     * starts tomorrow, so the chosen episode is not silently "aired" in the past.
     */
    fun countingFrom(minuteOfDay: Int, now: ZonedDateTime): LocalDate {
        val today = now.toLocalDate()
        return if (now.toLocalTime() < LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)) today else today.plusDays(1)
    }
}
