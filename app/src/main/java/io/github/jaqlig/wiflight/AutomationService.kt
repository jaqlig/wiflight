package io.github.jaqlig.wiflight

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

class AutomationService : Service() {
    private val app get() = application as FlightApplication
    private val store get() = app.store
    private var engine: AutomationEngine? = null
    private lateinit var notificationBuilder: Notification.Builder
    private var lastNotification: String? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Wi-Fi automation", NotificationManager.IMPORTANCE_LOW),
        )
        notificationBuilder = createNotificationBuilder()
        startForeground(NOTIFICATION, notification("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE) {
            store.enabled(false)
            store.log("Automation paused")
        }
        if (engine == null) {
            engine = AutomationEngine(this,
                onStatus = ::updateNotification,
                onFinished = { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() },
                onFailure = {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION,
                        notification("Action failed · open app for recovery"))
                    stopForeground(STOP_FOREGROUND_DETACH)
                    stopSelf()
                },
            )
            engine!!.start()
        } else engine!!.recheck()
        return if (store.state.value.enabled || store.state.value.owned) START_STICKY else START_NOT_STICKY
    }

    private fun updateNotification(message: String) {
        if (lastNotification == message) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(message))
    }

    private fun notification(message: String): Notification {
        lastNotification = message
        return notificationBuilder.setContentText(message).build()
    }

    private fun createNotificationBuilder(): Notification.Builder {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val pause = PendingIntent.getService(this, 1,
            Intent(this, AutomationService::class.java).setAction(ACTION_PAUSE), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_flight)
            .setContentTitle("WiFlight")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Pause", pause).build())
    }

    override fun onDestroy() {
        engine?.stop()
        engine = null
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        private const val CHANNEL = "automation"
        private const val NOTIFICATION = 1
        private val ACTION_PAUSE = "${BuildConfig.APPLICATION_ID}.PAUSE"
        fun start(context: Context) {
            context.startForegroundService(Intent(context, AutomationService::class.java))
        }
    }
}
