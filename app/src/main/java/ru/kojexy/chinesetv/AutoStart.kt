package ru.kojexy.chinesetv

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.time.Duration
import java.time.ZonedDateTime

/**
 * Everything that makes the channel start without being asked:
 * an alarm at the start of every airing, and (if enabled) a small standby service that reopens the
 * channel when the screen comes back on while a programme is running.
 */
object AutoStart {
    private const val CHANNEL_ON_AIR = "on_air"
    private const val CHANNEL_STANDBY = "standby"
    private const val NOTE_ON_AIR = 1
    private const val NOTE_STANDBY = 2

    fun isTv(context: Context) =
        (context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType ==
            Configuration.UI_MODE_TYPE_TELEVISION

    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** On by default on a TV, off on a phone. */
    fun reopensOnWake(context: Context) = prefs(context).getBoolean("reopen_on_wake", isTv(context))

    fun setReopensOnWake(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("reopen_on_wake", on).apply()
        refresh(context)
    }

    fun canOpenItself(context: Context) = Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(context)

    fun canNotify(context: Context) =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun refresh(context: Context) {
        scheduleNextAlarm(context)
        val standby = Intent(context, StandbyService::class.java)
        if (reopensOnWake(context) && ScheduleStore.load(context).slots.isNotEmpty()) {
            runCatching { ContextCompat.startForegroundService(context, standby) }
        } else {
            context.stopService(standby)
        }
    }

    fun scheduleNextAlarm(context: Context) {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = PendingIntent.getBroadcast(
            context, 0, Intent(context, AiringAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val next = Broadcast.next(ScheduleStore.load(context), ZonedDateTime.now())
        if (next == null) {
            alarms.cancel(pending)
            return
        }
        val at = next.start.toInstant().toEpochMilli()
        try {
            if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            }
        } catch (e: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    /**
     * Opens the channel if something is on air (or starts within [leadSeconds]).
     * Android only lets an app open itself from the background with "Display over other apps";
     * without it, a full-screen notification is the closest thing.
     */
    fun openIfOnAir(context: Context, leadSeconds: Long = 0) {
        if (App.visible) return
        val onAir = Broadcast.onAir(ScheduleStore.load(context), ZonedDateTime.now().plusSeconds(leadSeconds)) ?: return
        val intent = Intent(context, PlayerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (canOpenItself(context)) {
            try {
                context.startActivity(intent)
                return
            } catch (e: Exception) {
            }
        }
        notifyOnAir(context, onAir, intent)
    }

    private fun notifyOnAir(context: Context, onAir: OnAir, intent: Intent) {
        if (!canNotify(context)) return
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ON_AIR, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(context.getString(R.string.channel_on_air)).build()
        )
        val open = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val left = Duration.between(ZonedDateTime.now(), onAir.airing.end).toMillis().coerceAtLeast(60_000)
        val note = NotificationCompat.Builder(context, CHANNEL_ON_AIR)
            .setSmallIcon(R.drawable.ic_tv)
            .setContentTitle(context.getString(R.string.on_air_now, onAir.airing.show.title))
            .setContentText(context.getString(R.string.on_air_text))
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .setAutoCancel(true)
            .setTimeoutAfter(left)
            .build()
        try {
            manager.notify(NOTE_ON_AIR, note)
        } catch (e: SecurityException) {
        }
    }

    fun standbyNotification(context: Context): android.app.Notification {
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_STANDBY, NotificationManagerCompat.IMPORTANCE_MIN)
                .setName(context.getString(R.string.channel_standby)).build()
        )
        val open = PendingIntent.getActivity(
            context, 1, Intent(context, PlayerActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_STANDBY)
            .setSmallIcon(R.drawable.ic_tv)
            .setContentTitle(context.getString(R.string.standby_title))
            .setContentText(context.getString(R.string.standby_text))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    const val STANDBY_NOTE_ID = NOTE_STANDBY
}

/** Fires at the start of each airing. */
class AiringAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Alarms can arrive a moment early; anything starting within a minute counts.
        AutoStart.openIfOnAir(context, leadSeconds = 60)
        AutoStart.scheduleNextAlarm(context)
    }
}

/** After a reboot or an update the alarm is gone, so set it again; a TV switched on mid-programme opens the channel. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AutoStart.refresh(context)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && AutoStart.reopensOnWake(context)) {
            AutoStart.openIfOnAir(context)
        }
    }
}

/**
 * Screen-on is only delivered to receivers registered at runtime, so something has to stay alive to hear it.
 * This is the "TV on standby" part: switch the TV on during a programme and the channel is back.
 */
class StandbyService : Service() {
    private val wake = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            AutoStart.openIfOnAir(context)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ServiceCompat.startForeground(
            this, AutoStart.STANDBY_NOTE_ID, AutoStart.standbyNotification(this),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_DREAMING_STOPPED)
        }
        ContextCompat.registerReceiver(this, wake, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        unregisterReceiver(wake)
        super.onDestroy()
    }
}
