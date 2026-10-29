package ru.kojexy.chinesetv

import android.Manifest
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The TV guide: series, time slots, and the switches that let the channel start by itself. Works with a remote. */
class ProgrammeActivity : ComponentActivity() {
    private lateinit var list: LinearLayout

    private val askVideos = registerForActivityResult(ActivityResultContracts.RequestPermission()) { pickFolder() }
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(24), dp(32), dp(48))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundResource(R.drawable.off_air_bg)
            addView(list)
        })
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val focused = currentFocus?.tag
        list.removeAllViews()
        val s = ScheduleStore.load(this)
        val now = ZonedDateTime.now()

        heading("TV guide · 节目单")
        text(Guide.text(s, now).ifEmpty { "Nothing is scheduled yet." })

        section("Time slots")
        val onNow = Broadcast.onAir(s, now)?.airing?.slot?.id
        s.slots.sortedBy { it.minuteOfDay }.forEach { slot ->
            row(slotLabel(s, slot, now, slot.id == onNow), "slot:${slot.id}") { slotActions(slot) }
        }
        if (s.shows.isEmpty()) text("First add a series, then give it a time.")
        else row("+ Add a time slot", "add-slot") { chooseShowForSlot() }

        section("Series and films")
        s.shows.forEach { show ->
            val n = show.episodes.size
            row("${show.title} · ${if (n == 1) "film" else "$n episodes"}", "show:${show.id}") { showActions(show) }
        }
        row("+ Add a series from a folder on this device", "add-folder") { addFromFolder() }
        row("Load a programme from a web link (JSON)", "import") { importFromUrl() }

        section("Starting by itself")
        text(
            "At the start time the channel opens by itself. For that Android needs “Display over other apps”. " +
                "On a TV, also keep “Reopen when the screen turns on” switched on."
        )
        val overlay = AutoStart.canOpenItself(this)
        row("Display over other apps: ${if (overlay) "allowed ✓" else "not allowed — tap to allow"}", "overlay") { openOverlaySettings() }
        val wake = AutoStart.reopensOnWake(this)
        row("Reopen when the screen turns on during a programme: ${if (wake) "on" else "off"}", "wake") {
            AutoStart.setReopensOnWake(this, !wake)
            render()
        }
        if (Build.VERSION.SDK_INT >= 33) {
            val on = AutoStart.canNotify(this)
            row("Notifications: ${if (on) "on ✓" else "off — tap to allow"}", "notify") {
                if (!on) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (Build.VERSION.SDK_INT >= 31) {
            val alarms = getSystemService(android.app.AlarmManager::class.java)
            val exact = alarms.canScheduleExactAlarms()
            row("Start exactly on time: ${if (exact) "on ✓" else "off — tap to allow"}", "exact") {
                if (!exact) openSettings(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri()))
            }
        }
        if (Build.VERSION.SDK_INT >= 34) {
            val fullScreen = getSystemService(android.app.NotificationManager::class.java).canUseFullScreenIntent()
            row("Full-screen start without the overlay permission: ${if (fullScreen) "on ✓" else "off — tap to allow"}", "fullscreen") {
                if (!fullScreen) openSettings(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri()))
            }
        }

        section("")
        row("← Back to the channel", "back") { finish() }

        val restore = focused?.let { tag -> (0 until list.childCount).map { list.getChildAt(it) }.firstOrNull { it.tag == tag } }
        (restore ?: (0 until list.childCount).map { list.getChildAt(it) }.firstOrNull { it is Button })?.requestFocus()
    }

    private fun slotLabel(s: Schedule, slot: Slot, now: ZonedDateTime, onNow: Boolean): String {
        val show = s.show(slot.showId)?.title ?: "?"
        val time = "%02d:%02d".format(slot.minuteOfDay / 60, slot.minuteOfDay % 60)
        val each = if (slot.episodesPerAiring == 1) "1 episode" else "${slot.episodesPerAiring} episodes"
        val next = when {
            onNow -> "on air now"
            else -> Broadcast.nextOf(s, slot, now)?.let { "next: ${Guide.episodes(it)}" } ?: "finished"
        }
        return "$time · ${Guide.days(slot)} · $show · $each · $next"
    }

    // ---- slots ----

    private fun chooseShowForSlot() {
        val shows = ScheduleStore.load(this).shows
        if (shows.size == 1) return editSlot(shows[0], null)
        dialog().setTitle("Which series?")
            .setItems(shows.map { it.title }.toTypedArray()) { _, i -> editSlot(shows[i], null) }
            .show()
    }

    private fun slotActions(slot: Slot) {
        val s = ScheduleStore.load(this)
        val show = s.show(slot.showId) ?: return
        dialog().setTitle(show.title)
            .setItems(arrayOf("Change time or episodes", "Remove this time slot")) { _, i ->
                if (i == 0) editSlot(show, slot)
                else confirm("Remove ${show.title} at ${"%02d:%02d".format(slot.minuteOfDay / 60, slot.minuteOfDay % 60)}?") {
                    ScheduleStore.save(this, s.copy(slots = s.slots.filter { it.id != slot.id }))
                    render()
                }
            }
            .show()
    }

    private fun editSlot(show: Show, existing: Slot?) {
        val s = ScheduleStore.load(this)
        val now = ZonedDateTime.now()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }

        box.addView(label("Start time"))
        val hour = picker(0, 23, (existing?.minuteOfDay ?: 20 * 60) / 60) { "%02d".format(it) }
        val minute = picker(0, 11, ((existing?.minuteOfDay ?: 0) % 60 + 2) / 5 % 12) { "%02d".format(it * 5) }
        box.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(hour)
            addView(TextView(context).apply { text = " : "; textSize = 22f })
            addView(minute)
        })

        box.addView(label("Days (none ticked = every day)"))
        val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        val dayBoxes = names.mapIndexed { i, n ->
            CheckBox(this).apply {
                text = n
                isChecked = existing?.days?.contains(i + 1) == true
            }
        }
        box.addView(LinearLayout(this).apply { dayBoxes.take(4).forEach { addView(it) } })
        box.addView(LinearLayout(this).apply { dayBoxes.drop(4).forEach { addView(it) } })

        val dates = (0 until 60).map { now.toLocalDate().plusDays(it.toLong()) }
        val once = CheckBox(this).apply {
            text = "Only once (for a film), on:"
            isChecked = existing?.date != null || (existing == null && show.episodes.size == 1)
        }
        val dateFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
        val datePicker = picker(0, dates.size - 1, existing?.date?.let { d -> dates.indexOf(d).coerceAtLeast(0) } ?: 0) {
            Guide.dayName(dates[it], dates[0]).replaceFirstChar { c -> c.uppercase() }.let { name ->
                if (it < 2) "$name, ${dates[it].format(dateFmt)}" else name
            }
        }
        box.addView(once)
        box.addView(datePicker)
        fun syncOnce() {
            datePicker.isEnabled = once.isChecked
            dayBoxes.forEach { it.isEnabled = !once.isChecked }
        }
        once.setOnCheckedChangeListener { _, _ -> syncOnce() }
        syncOnce()

        val total = show.episodes.size
        val perAiring = picker(1, minOf(10, total), existing?.episodesPerAiring ?: minOf(2, total)) { it.toString() }
        val nextEpisode = existing?.let { Broadcast.nextOf(s, it, now)?.episodes?.first } ?: existing?.let { total - 1 } ?: 0
        val startAt = picker(1, total, nextEpisode + 1) { it.toString() }
        if (total > 1) {
            box.addView(label("Episodes in a row"))
            box.addView(perAiring)
            box.addView(label("Next episode to show (of $total)"))
            box.addView(startAt)
        }

        dialog().setTitle(show.title)
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                val minuteOfDay = hour.value * 60 + minute.value * 5
                val days = dayBoxes.indices.filter { dayBoxes[it].isChecked }.map { it + 1 }.toSet()
                val slot = Slot(
                    id = existing?.id ?: newId(),
                    showId = show.id,
                    minuteOfDay = minuteOfDay,
                    days = if (days.size == 7) emptySet() else days,
                    date = if (once.isChecked) dates[datePicker.value] else null,
                    episodesPerAiring = if (total > 1) perAiring.value else 1,
                    startEpisode = if (total > 1) startAt.value - 1 else 0,
                    since = Broadcast.countingFrom(minuteOfDay, ZonedDateTime.now()),
                )
                val fresh = ScheduleStore.load(this)
                ScheduleStore.save(this, fresh.copy(slots = fresh.slots.filter { it.id != slot.id } + slot))
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- shows ----

    private fun showActions(show: Show) {
        dialog().setTitle(show.title)
            .setItems(arrayOf("Rename", "Remove this series and its time slots")) { _, i ->
                if (i == 0) rename(show)
                else confirm("Remove ${show.title}? The video files stay on the device.") {
                    val s = ScheduleStore.load(this)
                    ScheduleStore.save(this, Schedule(s.shows.filter { it.id != show.id }, s.slots.filter { it.showId != show.id }))
                    render()
                }
            }
            .show()
    }

    private fun rename(show: Show) {
        val input = EditText(this).apply { setText(show.title); setSingleLine() }
        dialog().setTitle("Name").setView(input)
            .setPositiveButton("Save") { _, _ ->
                val title = input.text.toString().trim().ifEmpty { show.title }
                val s = ScheduleStore.load(this)
                ScheduleStore.save(this, s.copy(shows = s.shows.map { if (it.id == show.id) it.copy(title = title) else it }))
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addFromFolder() = askVideos.launch(MediaLibrary.permission())

    private fun pickFolder() {
        Thread {
            val folders = runCatching { MediaLibrary.folders(this) }.getOrDefault(emptyList())
            runOnUiThread {
                if (folders.isEmpty()) {
                    dialog().setTitle("No videos found")
                        .setMessage(
                            "Put each series in its own folder (for example Movies/繁花), one file per episode, " +
                                "on this device or a USB drive. File names decide the order: 01, 02, 03…\n\n" +
                                "If you said no to access to videos, allow it in the app's settings."
                        )
                        .setPositiveButton("OK", null).show()
                    return@runOnUiThread
                }
                dialog().setTitle("Choose the folder of a series")
                    .setItems(folders.map { "${it.name} — ${it.videos.size} videos" }.toTypedArray()) { _, i ->
                        val folder = folders[i]
                        val show = Show(newId(), folder.name, folder.videos)
                        val s = ScheduleStore.load(this)
                        ScheduleStore.save(this, s.copy(shows = s.shows + show))
                        render()
                        // Straight on to the time, so adding a series is one flow and not two decisions.
                        editSlot(show, null)
                    }
                    .show()
            }
        }.start()
    }

    private fun importFromUrl() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val input = EditText(this).apply {
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = "https://…/programme.json"
            setText(prefs.getString("import_url", ""))
        }
        dialog().setTitle("Programme link")
            .setMessage("A JSON file with series, episode links and times. See the README for the format.")
            .setView(input)
            .setPositiveButton("Load") { _, _ ->
                val url = input.text.toString().trim()
                prefs.edit().putString("import_url", url).apply()
                toast("Loading…")
                Thread {
                    val result = runCatching { withDurations(ScheduleJson.read(download(url))) }
                    runOnUiThread {
                        result.onSuccess { loaded ->
                            confirm("Replace the programme with ${loaded.shows.size} series and ${loaded.slots.size} time slots?") {
                                ScheduleStore.save(this, loaded)
                                render()
                            }
                        }.onFailure { toast("Could not load it: ${it.message}") }
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun download(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    /** Episodes without "durationMs" get it from the file itself, so the clock knows where each one ends. */
    private fun withDurations(s: Schedule): Schedule = s.copy(shows = s.shows.map { show ->
        show.copy(episodes = show.episodes.map { e ->
            if (e.durationMs > 0) e
            else e.copy(durationMs = runCatching {
                MediaMetadataRetriever().run {
                    try {
                        if (e.uri.startsWith("http")) setDataSource(e.uri, HashMap()) else setDataSource(this@ProgrammeActivity, Uri.parse(e.uri))
                        extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
                    } finally {
                        release()
                    }
                }
            }.getOrDefault(0L))
        })
    })

    // ---- permissions ----

    private fun openOverlaySettings() {
        if (AutoStart.canOpenItself(this)) return
        val opened = openSettings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri()), quiet = true)
        if (!opened) {
            dialog().setTitle("Display over other apps")
                .setMessage(
                    "This TV has no screen for this setting. Look in Settings → Apps → Special app access → " +
                        "Display over other apps.\n\nOr, from a computer with adb:\n" +
                        "adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow"
                )
                .setPositiveButton("OK", null).show()
        }
    }

    private fun openSettings(intent: Intent, quiet: Boolean = false): Boolean = try {
        startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        if (!quiet) toast("This device has no screen for that setting.")
        false
    }

    private fun packageUri() = Uri.parse("package:$packageName")

    // ---- little view helpers ----

    private fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    private fun confirm(question: String, yes: () -> Unit) {
        dialog().setMessage(question).setPositiveButton("Yes") { _, _ -> yes() }.setNegativeButton("No", null).show()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun heading(text: String) = list.addView(TextView(this).apply {
        this.text = text
        textSize = 28f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(getColor(R.color.text))
        setPadding(0, 0, 0, dp(12))
    })

    private fun section(text: String) = list.addView(TextView(this).apply {
        this.text = text
        textSize = 20f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(getColor(R.color.accent))
        setPadding(0, dp(24), 0, dp(8))
    })

    private fun text(text: String) = list.addView(TextView(this).apply {
        this.text = text
        textSize = 16f
        setTextColor(getColor(R.color.text_dim))
        setLineSpacing(0f, 1.25f)
        setPadding(0, dp(4), 0, dp(4))
    })

    private fun row(text: String, key: String, onClick: () -> Unit) = list.addView(Button(this).apply {
        this.text = text
        tag = key
        isAllCaps = false
        textSize = 17f
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setTextColor(getColor(R.color.text))
        setBackgroundResource(R.drawable.row_bg)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(4) }
        setOnClickListener { onClick() }
    })

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setPadding(0, dp(12), 0, dp(4))
    }

    private fun picker(min: Int, max: Int, value: Int, format: (Int) -> String) = NumberPicker(this).apply {
        minValue = min
        maxValue = max
        displayedValues = (min..max).map(format).toTypedArray()
        this.value = value.coerceIn(min, max)
        wrapSelectorWheel = false
        // No on-screen keyboard: arrows on the remote change the value.
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
