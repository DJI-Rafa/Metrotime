package com.example.metrotime.utils

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.metrotime.data.FIREBASE_DB_URL
import com.example.metrotime.data.intOrNull
import com.example.metrotime.data.stringOrNull
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await
import java.io.File

object AppUpdater {

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val downloadUrl: String,
        val releaseNotes: String,
        val forceUpdate: Boolean = false
    )

    private fun installedVersionCode(context: Context): Long = try {
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    } catch (_: PackageManager.NameNotFoundException) { 0L }

    private fun isHttps(url: String) = Uri.parse(url).scheme.equals("https", ignoreCase = true)

    /** Lê `app_update` e devolve a atualização se for mais recente e tiver um link https. */
    suspend fun fetchAvailableUpdate(context: Context): UpdateInfo? = try {
        val snap = FirebaseDatabase.getInstance(FIREBASE_DB_URL).getReference("app_update").get().await()
        val code = snap.child("versionCode").intOrNull() ?: 0
        val url = snap.child("downloadUrl").stringOrNull().orEmpty()
        if (code > installedVersionCode(context) && isHttps(url)) {
            UpdateInfo(
                versionCode = code,
                versionName = snap.child("versionName").stringOrNull().orEmpty(),
                downloadUrl = url,
                releaseNotes = snap.child("releaseNotes").stringOrNull().orEmpty()
            )
        } else null
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        e.printStackTrace()
        null
    }

    fun downloadAndInstall(context: Context, url: String) {
        if (!isHttps(url)) {
            Toast.makeText(context, "Link de atualização inválido.", Toast.LENGTH_LONG).show()
            return
        }
        try {
            Toast.makeText(context, "A descarregar atualização...", Toast.LENGTH_SHORT).show()
            startDownload(context.applicationContext, url)
        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback: open URL in browser
            openInBrowser(context, url)
        }
    }

    private fun startDownload(context: Context, url: String) {
        try {
            // Clean up old APK files
            val destination = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "metrotime_update.apk")
            if (destination.exists()) destination.delete()

            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle("Metrotime Update")
                .setDescription("A descarregar nova versão...")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationUri(Uri.fromFile(destination))
                .setMimeType("application/vnd.android.package-archive")

            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = manager.enqueue(request)

            val onComplete = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                    if (id == downloadId) {
                        try {
                            // Verify download succeeded
                            val query = DownloadManager.Query().setFilterById(downloadId)
                            manager.query(query).use { cursor ->
                                if (cursor.moveToFirst()) {
                                    val statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                                    val status = cursor.getInt(statusCol)
                                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                                        installApk(ctx, destination)
                                    } else {
                                        Toast.makeText(ctx, "Download falhou. A abrir no browser...", Toast.LENGTH_LONG).show()
                                        openInBrowser(ctx, url)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            Toast.makeText(ctx, "Erro na instalação. A abrir no browser...", Toast.LENGTH_LONG).show()
                            openInBrowser(ctx, url)
                        }
                        try {
                            context.unregisterReceiver(this)
                        } catch (_: Exception) {}
                    }
                }
            }

            // O DownloadManager é outra app, por isso o receiver tem de ser exportado; o conteúdo do
            // broadcast não é confiado — o estado é confirmado no DownloadManager e o APK é verificado.
            androidx.core.content.ContextCompat.registerReceiver(context, onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Erro no download. A abrir no browser...", Toast.LENGTH_LONG).show()
            openInBrowser(context, url)
        }
    }

    /**
     * Só aceita um APK desta mesma app, mais recente e assinado com o mesmo certificado.
     * Sem isto, quem controlasse o link podia pôr qualquer APK à frente do utilizador.
     */
    private fun isTrustedUpdate(context: Context, apk: File): Boolean {
        val pm = context.packageManager
        // As versões com PackageInfoFlags só existem no Android 13+; no 12 as antigas, com um Int
        val modern = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive: PackageInfo = (
            if (modern) pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
            else @Suppress("DEPRECATION") pm.getPackageArchiveInfo(apk.absolutePath, flags)
        ) ?: return false
        if (archive.packageName != context.packageName) return false
        if (archive.longVersionCode <= installedVersionCode(context)) return false

        val installed = if (modern) pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        else @Suppress("DEPRECATION") pm.getPackageInfo(context.packageName, flags)
        val installedSigners = installed.signingInfo?.apkContentsSigners?.toSet().orEmpty()
        val archiveSigners = archive.signingInfo?.apkContentsSigners?.toSet().orEmpty()
        return installedSigners.isNotEmpty() && installedSigners == archiveSigners
    }

    private fun installApk(context: Context, file: File) {
        try {
            if (!isTrustedUpdate(context, file)) {
                file.delete()
                Toast.makeText(context, "A atualização descarregada não é válida e foi descartada.", Toast.LENGTH_LONG).show()
                return
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Não foi possível abrir o instalador. Verifica as permissões.", Toast.LENGTH_LONG).show()
        }
    }

    private fun openInBrowser(context: Context, url: String) {
        if (!isHttps(url)) return
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
