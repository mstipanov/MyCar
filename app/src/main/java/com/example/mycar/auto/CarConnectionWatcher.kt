package com.example.mycar.auto

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.mycar.MainActivity
import com.example.mycar.R
import com.example.mycar.Settings
import com.example.mycar.capture.ScreenMirrorService

/**
 * Watches Android Auto's connection state so mirroring follows the car, with no interaction.
 *
 * Android Auto publishes its connection state through a content provider and broadcasts
 * `CAR_CONNECTION_UPDATED` when it changes. Neither reaches an app that is not running, so this
 * has to be a (quiet) foreground service to survive between drives. On the transition to
 * "projecting" it launches [MainActivity] with [MainActivity.EXTRA_AUTO_START], which asks for
 * capture consent; that launch is only allowed from the background because the app holds
 * "Display over other apps" (`SYSTEM_ALERT_WINDOW`). On the way back it stops mirroring, so
 * capture never keeps running against a car that is no longer there.
 */
class CarConnectionWatcher : Service() {

    private var receiver: BroadcastReceiver? = null

    /** Edge-trigger state: only act when we go from not-connected to connected. */
    private var wasConnected = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = queryConnection()
        }
        // The broadcast comes from Android Auto, a different app, so the receiver must be exported.
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(ACTION_CAR_CONNECTION_UPDATED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        queryConnection()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        receiver?.let { runCatching { unregisterReceiver(it) } }
        receiver = null
        super.onDestroy()
    }

    private fun queryConnection() {
        onConnectionState(connectionState(this))
    }

    private fun onConnectionState(state: Int) {
        val connected = state == CONNECTION_PROJECTION
        val was = wasConnected
        wasConnected = connected
        when {
            connected && !was -> startMirroring()
            !connected && was -> stopMirroring()
        }
    }

    /** Rising edge: the car started projecting, so bring mirroring up by itself. */
    private fun startMirroring() {
        if (ScreenMirrorService.isRunning) return
        if (!Settings.autoStart(this)) return

        // Without this the activity start is silently blocked (Android 10+ background launch rule).
        if (!android.provider.Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Android Auto connected, but \"Display over other apps\" is not granted")
            return
        }
        Log.i(TAG, "Android Auto connected; starting mirroring")
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_AUTO_START, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /**
     * Falling edge: the car stopped projecting. Capture left running would keep mirroring into a
     * car that is gone (and keep the screen awake and landscape-locked), so stop it. Auto-start
     * covers the next connection; Android asks for capture consent again when it comes back.
     */
    private fun stopMirroring() {
        if (!ScreenMirrorService.isRunning) return
        Log.i(TAG, "Android Auto disconnected; stopping mirroring")
        ScreenMirrorService.stop(this)
    }

    private fun buildNotification(): android.app.Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.watcher_notification_channel),
                NotificationManager.IMPORTANCE_MIN,
            ),
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mirror)
            .setContentTitle(getString(R.string.watcher_notification_title))
            .setContentText(getString(R.string.watcher_notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    companion object {
        private const val TAG = "CarConnectionWatcher"
        private const val CHANNEL_ID = "car_connection"
        private const val NOTIFICATION_ID = 42

        private const val ACTION_CAR_CONNECTION_UPDATED =
            "androidx.car.app.connection.action.CAR_CONNECTION_UPDATED"
        private const val COLUMN_STATE = "CarConnectionState"
        private const val CONNECTION_NOT_CONNECTED = 0
        private const val CONNECTION_PROJECTION = 2

        private val CONNECTION_URI: Uri =
            Uri.Builder().scheme("content").authority("androidx.car.app.connection").build()

        /**
         * Android Auto's live connection state, or [CONNECTION_NOT_CONNECTED] when it cannot be
         * read. Shared with the mirror service, which uses it to decide whether a capture that the
         * system stopped on its own should be asked for again.
         */
        fun connectionState(context: Context): Int = runCatching {
            context.contentResolver
                .query(CONNECTION_URI, arrayOf(COLUMN_STATE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else CONNECTION_NOT_CONNECTED
                }
        }.getOrNull() ?: CONNECTION_NOT_CONNECTED

        /** Whether Android Auto is currently projecting, i.e. the car is showing us. */
        fun isProjecting(context: Context): Boolean =
            connectionState(context) == CONNECTION_PROJECTION

        /** Idempotently starts the watcher. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CarConnectionWatcher::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CarConnectionWatcher::class.java))
        }
    }
}
