package ru.kojexy.chinesetv

import java.time.LocalDate

/** One video file or stream. [durationMs] tells the broadcast clock when the next episode starts. */
data class Episode(
    val title: String,
    val uri: String,
    val durationMs: Long,
    val subtitleUri: String? = null,
)

/** A series (many episodes) or a film (one episode). */
data class Show(val id: String, val title: String, val episodes: List<Episode>)

/**
 * A fixed place in the programme: [show] starts at [minuteOfDay] and plays [episodesPerAiring] episodes back to back.
 *
 * Which episode airs is never stored as "progress". It is counted from the calendar: every airing on or after
 * [since] moves the series on by [episodesPerAiring], whether anybody watched or not. That is what makes it TV.
 */
data class Slot(
    val id: String,
    val showId: String,
    val minuteOfDay: Int,
    /** ISO days of week, 1 = Monday … 7 = Sunday. Empty means every day. */
    val days: Set<Int> = emptySet(),
    /** Set for a one-off airing (a film). [days] and [since] are then ignored. */
    val date: LocalDate? = null,
    val episodesPerAiring: Int = 1,
    /** 0-based episode shown at the first airing on or after [since]. */
    val startEpisode: Int = 0,
    val since: LocalDate,
)

data class Schedule(val shows: List<Show> = emptyList(), val slots: List<Slot> = emptyList()) {
    fun show(id: String) = shows.firstOrNull { it.id == id }
}
