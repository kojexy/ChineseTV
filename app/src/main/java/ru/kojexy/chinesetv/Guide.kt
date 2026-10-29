package ru.kojexy.chinesetv

import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Text for the on-screen programme guide. */
object Guide {
    val time: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val day = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    private val shortDays = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    fun episodes(a: Airing): String = when {
        a.show.episodes.size == 1 -> "film"
        a.episodes.first == a.episodes.last -> "episode ${a.episodes.first + 1}"
        else -> "episodes ${a.episodes.first + 1}–${a.episodes.last + 1}"
    }

    fun dayName(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "today"
        today.plusDays(1) -> "tomorrow"
        else -> date.format(day)
    }

    fun days(slot: Slot): String = when {
        slot.date != null -> "once, ${slot.date.format(day)}"
        slot.days.isEmpty() || slot.days.size == 7 -> "every day"
        slot.days == setOf(1, 2, 3, 4, 5) -> "Mon–Fri"
        slot.days == setOf(6, 7) -> "weekends"
        else -> slot.days.sorted().joinToString(" ") { shortDays[it - 1] }
    }

    fun countdown(from: ZonedDateTime, to: ZonedDateTime): String {
        val s = Duration.between(from, to).seconds.coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    /** Today and tomorrow, one line per airing; ▶ marks what is on now, ✓ what has finished. */
    fun text(schedule: Schedule, now: ZonedDateTime): String {
        val today = now.toLocalDate()
        val onNow = Broadcast.onAir(schedule, now)?.airing
        return listOf(today, today.plusDays(1)).mapNotNull { date ->
            val airings = Broadcast.airingsOn(schedule, date, now.zone)
            if (airings.isEmpty()) return@mapNotNull null
            val title = if (date == today) "Today · 今天" else "Tomorrow · 明天"
            val lines = airings.joinToString("\n") { a ->
                val mark = when {
                    a == onNow -> "▶"
                    !a.end.isAfter(now) -> "✓"
                    else -> "  "
                }
                "$mark ${a.start.format(time)}–${a.end.format(time)}  ${a.show.title} · ${episodes(a)}"
            }
            "$title\n$lines"
        }.joinToString("\n\n")
    }
}
