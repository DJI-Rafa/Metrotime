package com.example.metrotime.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.metrotime.R
import com.example.metrotime.data.ArrivalsRepo
import com.example.metrotime.data.RemoteArrivalsSource
import kotlinx.coroutines.*
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

class WidgetTickService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var remoteSource: RemoteArrivalsSource

    override fun onCreate() {
        super.onCreate()
        remoteSource = RemoteArrivalsSource(this)
    }

    companion object {
        const val ACTION_START = "com.example.metrotime.TICK_START"
        const val ACTION_STOP = "com.example.metrotime.TICK_STOP"

        private const val NOTIFICATION_CHANNEL_ID = "widget_tick_channel"
        private const val NOTIFICATION_ID = 2

        fun start(context: Context) {
            val intent = Intent(context, WidgetTickService::class.java).apply { action = ACTION_START }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, WidgetTickService::class.java).apply { action = ACTION_STOP }
            context.stopService(intent)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, createNotification())
                
                scope.coroutineContext.cancelChildren()
                scope.launch {
                    // Loop exactly 40 times.
                    repeat(40) {
                        try {
                            ArrivalsRepo.currentArrivals.value?.station?.let { station ->
                                val data = remoteSource.fetchArrivals(station.apiId)
                                ArrivalsRepo.newArrivals(data)

                                withContext(Dispatchers.Main) {
                                    WidgetUpdateService.requestRedraw(applicationContext)
                                }
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                        }
                        delay(TimeUnit.SECONDS.toMillis(5))
                    }
                    // After the loop finishes, stop the service.
                    stopSelf(startId)
                }
            }
            ACTION_STOP -> {
                stopForeground(true)
                scope.cancel()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun createNotification(): Notification {
        val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, "Widget Updates", NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("MetroTime Widget")
            .setContentText("O widget está a atualizar em tempo real.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
