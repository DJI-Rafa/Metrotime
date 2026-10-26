package com.example.metrotime.work

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.*
import com.example.metrotime.location.Locator
import com.example.metrotime.prefs.Prefs
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Periodic WorkManager job that runs every 15 minutes as a safety net.
 * If the foreground service was killed by an aggressive OEM battery manager
 * (Xiaomi MIUI, Huawei EMUI, Oppo ColorOS, etc.), this worker:
 *   1. Pushes a fresh location to Firebase so friends see you're alive
 *   2. Tries to restart the foreground service
 */
class LocationKeepAliveWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Rede de segurança se o serviço tiver sido morto: comboio seguido, widget CP e perturbações
        runCatching { CpFollow.check(applicationContext) }
        runCatching { CpWidgetUpdater.updateAll(applicationContext) }
        runCatching { BusWidgetUpdater.updateAll(applicationContext) }
        runCatching { DisruptionAlerts.check(applicationContext) }
        try {
            // Partilha com os amigos ou registo de viagens: o serviço tem de estar a correr
            val tracking = Prefs.isTripTracking(applicationContext)
            val uid = FirebaseAuth.getInstance().currentUser?.uid
            if (uid == null && !tracking) return Result.success()
            if (!Prefs.isContinuousLocation(applicationContext) && !tracking) return Result.success()

            // 1. Push a location update directly (bypass the service)
            withContext(Dispatchers.IO) {
                val loc = Locator.getCurrentLocation(applicationContext)
                if (uid != null && Prefs.isContinuousLocation(applicationContext) && loc != null && !Prefs.isMapSharingBlocked(applicationContext)) {
                    val db = FirebaseDatabase.getInstance("https://metrotime-569ec-default-rtdb.europe-west1.firebasedatabase.app")
                    val map = mapOf(
                        "lat" to loc.first,
                        "lon" to loc.second,
                        "timestamp" to System.currentTimeMillis()
                    )
                    db.getReference("locations").child(uid).updateChildren(map)
                }
            }

            // 2. Try to restart the foreground service if it's not running
            try {
                val intent = Intent(applicationContext, WidgetUpdateService::class.java).apply {
                    action = WidgetUpdateService.ACTION_FETCH_DATA
                }
                ContextCompat.startForegroundService(applicationContext, intent)
            } catch (e: Exception) {
                // On Android 12+ this may fail from background — that's OK, 
                // the next user interaction will restart it
                e.printStackTrace()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "location_keep_alive"

        /**
         * Enqueue a periodic 15-minute keep-alive worker.
         * Uses ExistingPeriodicWorkPolicy.KEEP so it doesn't restart if already running.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<LocationKeepAliveWorker>(
                15, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 5, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
