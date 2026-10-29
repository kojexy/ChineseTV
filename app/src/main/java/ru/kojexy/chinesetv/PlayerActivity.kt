package ru.kojexy.chinesetv

import android.Manifest
import android.app.AlertDialog
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.time.Duration
import java.time.ZonedDateTime
import kotlin.math.abs

/**
 * The channel. There are no controls on purpose: no pause, no seek, no "next episode".
 * Every second it asks the broadcast clock what should be on screen and makes it so.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private lateinit var playerView: PlayerView
    private lateinit var badge: TextView
    private lateinit var info: View
    private lateinit var infoTitle: TextView
    private lateinit var infoDetails: TextView
    private lateinit var infoProgress: ProgressBar
    private lateinit var infoNext: TextView
    private lateinit var infoGuide: Button
    private lateinit var offAir: View
    private lateinit var clock: TextView
    private lateinit var offTitle: TextView
    private lateinit var offNext: TextView
    private lateinit var countdown: TextView
    private lateinit var errorText: TextView
    private lateinit var guide: TextView
    private lateinit var guideButton: Button

    private var player: ExoPlayer? = null
    private var schedule = Schedule()
    private var onAir: OnAir? = null
    private var playingKey: String? = null
    /** An episode whose file ended (or failed) before its slot did; we wait for the next one instead of replaying it. */
    private var finishedKey: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            update()
            handler.postDelayed(this, 1000)
        }
    }
    private val hideInfo = Runnable { info.visibility = View.GONE }

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!AutoStart.isTv(this) && Build.VERSION.SDK_INT >= 27) {
            // On a phone the programme starts even on the lock screen, like a TV that is already on.
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        playerView = findViewById(R.id.player)
        badge = findViewById(R.id.badge)
        info = findViewById(R.id.info)
        infoTitle = findViewById(R.id.info_title)
        infoDetails = findViewById(R.id.info_details)
        infoProgress = findViewById(R.id.info_progress)
        infoNext = findViewById(R.id.info_next)
        infoGuide = findViewById(R.id.info_guide)
        offAir = findViewById(R.id.off_air)
        clock = findViewById(R.id.clock)
        offTitle = findViewById(R.id.off_title)
        offNext = findViewById(R.id.off_next)
        countdown = findViewById(R.id.countdown)
        errorText = findViewById(R.id.error)
        guide = findViewById(R.id.guide)
        guideButton = findViewById(R.id.guide_button)

        playerView.useController = false
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        findViewById<View>(R.id.root).setOnClickListener { if (playingKey != null) toggleInfo() }
        infoGuide.setOnClickListener { openGuide() }
        guideButton.setOnClickListener { openGuide() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = askBeforeLeaving()
        })

        hideSystemBars()
        if (Build.VERSION.SDK_INT >= 33 && !AutoStart.isTv(this) && !AutoStart.canNotify(this)) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onStart() {
        super.onStart()
        schedule = ScheduleStore.load(this)
        player = ExoPlayer.Builder(this).build().also { p ->
            p.setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            // Chinese audio and subtitles first, when a file has several.
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setPreferredAudioLanguage("zh")
                .setPreferredTextLanguage("zh")
                .setSelectUndeterminedTextLanguage(true)
                .build()
            p.addListener(listener)
            playerView.player = p
        }
        playingKey = null
        finishedKey = null
        handler.post(tick)
        AutoStart.refresh(this)
    }

    override fun onStop() {
        handler.removeCallbacks(tick)
        handler.removeCallbacks(hideInfo)
        playerView.player = null
        player?.release()
        player = null
        playingKey = null
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) {
                finishedKey = playingKey
                update()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            errorText.text = getString(R.string.cannot_play, onAir?.item?.title ?: "", error.errorCodeName)
            errorText.visibility = View.VISIBLE
            finishedKey = playingKey
            update()
        }
    }

    private fun update() {
        val now = ZonedDateTime.now()
        val current = Broadcast.onAir(schedule, now)
        onAir = current
        when {
            current == null -> {
                stopPlayback()
                showOffAir(now, null)
            }
            current.key == finishedKey -> {
                stopPlayback()
                showOffAir(now, current)
            }
            current.key != playingKey -> start(current)
            else -> keepLive(current, now)
        }
        if (info.visibility == View.VISIBLE) fillInfo(now)
    }

    private fun start(current: OnAir) {
        val p = player ?: return
        val episode = current.item
        val item = MediaItem.Builder().setUri(episode.uri).apply {
            episode.subtitleUri?.let { s ->
                setSubtitleConfigurations(
                    listOf(
                        MediaItem.SubtitleConfiguration.Builder(Uri.parse(s))
                            .setMimeType(subtitleType(s))
                            .setLanguage("zh")
                            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                            .build()
                    )
                )
            }
        }.build()
        playingKey = current.key
        errorText.visibility = View.GONE
        p.setMediaItem(item, current.positionMs)
        p.prepare()
        p.playWhenReady = true
        offAir.visibility = View.GONE
        badge.visibility = View.VISIBLE
        showInfo()
    }

    /** If loading or a phone call made us fall behind, jump forward: the broadcast did not wait. */
    private fun keepLive(current: OnAir, now: ZonedDateTime) {
        val p = player ?: return
        if (!p.isPlaying) return
        val live = Duration.between(current.episodeStart, now).toMillis()
        if (abs(p.currentPosition - live) > 8_000) p.seekTo(live)
    }

    private fun stopPlayback() {
        if (playingKey != null) {
            player?.stop()
            player?.clearMediaItems()
            playingKey = null
        }
        badge.visibility = View.GONE
        info.visibility = View.GONE
    }

    private fun showOffAir(now: ZonedDateTime, waiting: OnAir?) {
        offAir.visibility = View.VISIBLE
        clock.text = now.format(Guide.time)
        val next = Broadcast.next(schedule, now)
        when {
            waiting != null -> {
                offTitle.setText(R.string.intermission)
                offNext.text = getString(R.string.intermission_text, waiting.airing.show.title)
                countdown.text = Guide.countdown(now, waiting.episodeEnd)
            }
            next == null -> {
                offTitle.setText(if (schedule.slots.isEmpty()) R.string.empty_title else R.string.ended_title)
                offNext.setText(R.string.empty_text)
                countdown.text = ""
            }
            else -> {
                offTitle.setText(R.string.off_air)
                offNext.text = getString(
                    R.string.next_programme,
                    next.show.title, Guide.episodes(next),
                    Guide.dayName(next.start.toLocalDate(), now.toLocalDate()), next.start.format(Guide.time),
                )
                countdown.text = Guide.countdown(now, next.start)
            }
        }
        if (waiting == null) errorText.visibility = View.GONE
        guide.text = Guide.text(schedule, now)
        if (currentFocus == null) guideButton.requestFocus()
    }

    private fun fillInfo(now: ZonedDateTime) {
        val current = onAir ?: return
        val a = current.airing
        infoTitle.text = a.show.title
        infoDetails.text = if (a.show.episodes.size == 1) {
            "${current.episodeStart.format(Guide.time)}–${current.episodeEnd.format(Guide.time)}"
        } else {
            getString(
                R.string.episode_of, current.episode + 1, a.show.episodes.size,
                current.episodeStart.format(Guide.time), current.episodeEnd.format(Guide.time),
            )
        }
        val length = current.item.lengthMs()
        infoProgress.progress = (Duration.between(current.episodeStart, now).toMillis() * 1000 / length).toInt()
        infoNext.text = if (current.episode < a.episodes.last) {
            getString(R.string.then_episode, current.episode + 2, current.episodeEnd.format(Guide.time))
        } else {
            Broadcast.next(schedule, now)?.let {
                getString(R.string.then_show, it.show.title, Guide.dayName(it.start.toLocalDate(), now.toLocalDate()), it.start.format(Guide.time))
            } ?: ""
        }
    }

    private fun showInfo() {
        fillInfo(ZonedDateTime.now())
        info.visibility = View.VISIBLE
        infoGuide.requestFocus()
        handler.removeCallbacks(hideInfo)
        handler.postDelayed(hideInfo, 6000)
    }

    private fun toggleInfo() {
        if (info.visibility == View.VISIBLE) info.visibility = View.GONE else showInfo()
    }

    /**
     * While a programme is on, the remote cannot pause, seek or skip. OK and the arrows show what is on;
     * media keys are swallowed. Back still works (with a question), and Home is never blocked.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (playingKey == null) return super.dispatchKeyEvent(event)
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
            -> return super.dispatchKeyEvent(event)

            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_GUIDE -> {
                if (event.action == KeyEvent.ACTION_UP) openGuide()
                return true
            }

            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            -> if (info.visibility == View.VISIBLE) {
                handler.removeCallbacks(hideInfo)
                handler.postDelayed(hideInfo, 6000)
                return super.dispatchKeyEvent(event)
            }
        }
        if (event.action == KeyEvent.ACTION_UP) showInfo()
        return true
    }

    private fun askBeforeLeaving() {
        val current = onAir
        if (current == null) {
            finish()
            return
        }
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.leave_title)
            .setMessage(getString(R.string.leave_message, current.airing.show.title))
            .setPositiveButton(R.string.keep_watching, null)
            .setNegativeButton(R.string.leave) { _, _ -> finish() }
            .create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus() }
        dialog.show()
    }

    private fun openGuide() {
        startActivity(android.content.Intent(this, ProgrammeActivity::class.java))
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun subtitleType(uri: String) = when (uri.substringAfterLast('.').lowercase().substringBefore('?')) {
        "vtt" -> MimeTypes.TEXT_VTT
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        else -> MimeTypes.APPLICATION_SUBRIP
    }
}
