package ru.kojexy.chinesetv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.UUID

fun newId() = UUID.randomUUID().toString().take(8)

/** The programme lives in one JSON file, in the same format that "Load a programme from a web link" reads. */
object ScheduleStore {
    private const val FILE = "schedule.json"

    @Volatile private var cached: Schedule? = null

    fun load(context: Context): Schedule {
        cached?.let { return it }
        val file = File(context.filesDir, FILE)
        val schedule = if (file.exists()) runCatching { ScheduleJson.read(file.readText()) }.getOrDefault(Schedule())
                       else Schedule()
        cached = schedule
        return schedule
    }

    fun save(context: Context, schedule: Schedule) {
        val tmp = File(context.filesDir, "$FILE.tmp")
        tmp.writeText(ScheduleJson.write(schedule))
        tmp.renameTo(File(context.filesDir, FILE))
        cached = schedule
        AutoStart.refresh(context)
    }
}

object ScheduleJson {

    fun write(s: Schedule): String = JSONObject().apply {
        put("version", 1)
        put("shows", JSONArray().apply {
            s.shows.forEach { show ->
                put(JSONObject().apply {
                    put("id", show.id)
                    put("title", show.title)
                    put("episodes", JSONArray().apply {
                        show.episodes.forEach { e ->
                            put(JSONObject().apply {
                                put("title", e.title)
                                put("uri", e.uri)
                                put("durationMs", e.durationMs)
                                e.subtitleUri?.let { put("subtitles", it) }
                            })
                        }
                    })
                })
            }
        })
        put("slots", JSONArray().apply {
            s.slots.forEach { slot ->
                put(JSONObject().apply {
                    put("id", slot.id)
                    put("showId", slot.showId)
                    put("time", "%02d:%02d".format(slot.minuteOfDay / 60, slot.minuteOfDay % 60))
                    put("days", JSONArray(slot.days.sorted()))
                    slot.date?.let { put("date", it.toString()) }
                    put("episodes", slot.episodesPerAiring)
                    // 1-based in the file, because people write these files by hand.
                    put("startEpisode", slot.startEpisode + 1)
                    put("since", slot.since.toString())
                })
            }
        })
    }.toString(2)

    fun read(text: String, now: ZonedDateTime = ZonedDateTime.now()): Schedule {
        val root = JSONObject(text)
        val shows = root.optJSONArray("shows").objects().map { o ->
            Show(
                id = o.str("id") ?: newId(),
                title = o.str("title") ?: "Untitled",
                episodes = o.optJSONArray("episodes").objects().mapIndexed { i, e ->
                    Episode(
                        title = e.str("title") ?: "Episode ${i + 1}",
                        uri = e.getString("uri"),
                        durationMs = e.optLong("durationMs", 0),
                        subtitleUri = e.str("subtitles"),
                    )
                },
            )
        }
        val slots = root.optJSONArray("slots").objects().map { o ->
            val (h, m) = o.getString("time").split(":").map { it.trim().toInt() }
            val minute = h * 60 + m
            Slot(
                id = o.str("id") ?: newId(),
                showId = o.getString("showId"),
                minuteOfDay = minute,
                days = o.optJSONArray("days").ints().toSet(),
                date = o.str("date")?.let { LocalDate.parse(it) },
                episodesPerAiring = o.optInt("episodes", 1).coerceAtLeast(1),
                startEpisode = (o.optInt("startEpisode", 1) - 1).coerceAtLeast(0),
                since = o.str("since")?.let { LocalDate.parse(it) } ?: Broadcast.countingFrom(minute, now),
            )
        }
        return Schedule(shows, slots)
    }

    private fun JSONObject.str(key: String): String? =
        if (isNull(key)) null else optString(key).ifEmpty { null }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

    private fun JSONArray?.ints(): List<Int> =
        if (this == null) emptyList() else (0 until length()).map { getInt(it) }
}
