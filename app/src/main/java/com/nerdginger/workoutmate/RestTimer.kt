package com.nerdginger.workoutmate

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.CountDownTimer
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Rest countdown between sets.
 *
 * The countdown runs in Kotlin rather than in a JS `setInterval` so it keeps
 * ticking when the screen is off, and finishes with a tone and a vibration the
 * user notices from across a gym. An ongoing notification shows the remaining
 * time while the app is in the background.
 *
 * Known limitation: this is tied to the Activity, so if Android kills the
 * process mid-rest the timer dies with it. That's an acceptable trade for v1 —
 * a foreground service is a lot of machinery for a two-minute countdown. If it
 * proves flaky in real use, `AlarmManager` is the next step up.
 */
class RestTimer(
    private val activity: MainActivity,
    private val onEvent: (event: String, remainingSec: Int) -> Unit,
) {

    private var countDown: CountDownTimer? = null
    private var lastNotifiedSec = -1

    val isRunning: Boolean get() = countDown != null

    fun start(seconds: Int) {
        cancel(notify = false)
        val clamped = seconds.coerceIn(MIN_SECONDS, MAX_SECONDS)
        ensureChannel()

        countDown = object : CountDownTimer(clamped * 1000L, TICK_MS) {
            override fun onTick(millisUntilFinished: Long) {
                // Round up so a 90s rest shows "90" immediately rather than "89".
                val remaining = ((millisUntilFinished + 999) / 1000).toInt()
                onEvent(EVENT_TICK, remaining)
                if (remaining != lastNotifiedSec) {
                    lastNotifiedSec = remaining
                    showNotification(remaining)
                }
            }

            override fun onFinish() {
                countDown = null
                lastNotifiedSec = -1
                clearNotification()
                alert()
                onEvent(EVENT_DONE, 0)
            }
        }.also { it.start() }

        onEvent(EVENT_TICK, clamped)
    }

    fun cancel(notify: Boolean = true) {
        countDown?.cancel()
        countDown = null
        lastNotifiedSec = -1
        clearNotification()
        if (notify) onEvent(EVENT_CANCELLED, 0)
    }

    private fun alert() {
        // The tone has to outlive the call that starts it, so this waits before
        // releasing the generator — on a worker thread, because `onFinish` runs
        // on the UI thread and sleeping there would freeze the app mid-workout.
        //
        // release() goes in a finally rather than using `use`: ToneGenerator
        // only became AutoCloseable in API 33, so `use` would compile against
        // the current SDK and then fail at runtime on older devices.
        Thread {
            runCatching {
                val tone = ToneGenerator(AudioManager.STREAM_ALARM, TONE_VOLUME)
                try {
                    tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, TONE_MS)
                    Thread.sleep(TONE_MS.toLong() + 50)
                } finally {
                    tone.release()
                }
            }
        }.apply { isDaemon = true }.start()

        runCatching { vibrator()?.vibrate(VibrationEffect.createWaveform(VIBRATION_PATTERN, -1)) }
    }

    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (activity.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            activity.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private fun ensureChannel() {
        val manager = activity.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Rest timer", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows the countdown between sets"
                setShowBadge(false)
            }
        )
    }

    private fun showNotification(remainingSec: Int) {
        if (!canPostNotifications()) return
        val intent = Intent(activity, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            activity, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(activity, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Resting")
            .setContentText(formatRemaining(remainingSec))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(pending)
            .build()

        runCatching { NotificationManagerCompat.from(activity).notify(NOTIFICATION_ID, notification) }
    }

    private fun clearNotification() {
        runCatching { NotificationManagerCompat.from(activity).cancel(NOTIFICATION_ID) }
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun formatRemaining(totalSec: Int): String {
        val minutes = totalSec / 60
        val seconds = totalSec % 60
        return if (minutes > 0) "%d:%02d remaining".format(minutes, seconds) else "${seconds}s remaining"
    }

    companion object {
        const val EVENT_TICK = "rest-tick"
        const val EVENT_DONE = "rest-done"
        const val EVENT_CANCELLED = "rest-cancelled"

        private const val CHANNEL_ID = "rest_timer"
        private const val NOTIFICATION_ID = 1001
        private const val TICK_MS = 250L
        private const val MIN_SECONDS = 5
        private const val MAX_SECONDS = 60 * 30
        private const val TONE_VOLUME = 100
        private const val TONE_MS = 700
        private val VIBRATION_PATTERN = longArrayOf(0, 400, 200, 400)
    }
}
