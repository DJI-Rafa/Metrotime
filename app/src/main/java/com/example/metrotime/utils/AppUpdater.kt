package com.example.metrotime.utils

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.File

object AppUpdater {
    data class UpdateInfo(
        val versionCode: Int = 0,
        val versionName: String = "",
        val downloadUrl: String = "",
        val releaseNotes: String = ""
    )

    private var receiverRegistered = false
    private var downloadId: Long = -1
    private const val APK_FILENAME = "metrotime_update.apk"

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    val uri = manager.getUriForDownloadedFile(downloadId)
                    
                    if (uri != null) {
                        val installIntent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/vnd.android.package-archive")
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                        }
                        try {
                            context.startActivity(installIntent)
                        } catch (e: Exception) {
                            e.printStackTrace()
                            android.widget.Toast.makeText(context, "Erro ao abrir o instalador. Vai aos Downloads e toca em '$APK_FILENAME'.", android.widget.Toast.LENGTH_LONG).show()
                        }
                    } else {
                        android.widget.Toast.makeText(context, "Download falhou. Tenta novamente.", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    fun downloadAndInstall(context: Context, url: String) {
        // 1. Verificar se pode instalar apps desconhecidas
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                android.widget.Toast.makeText(context, "Precisas de ativar a instalação de apps desconhecidas para o Metrotime!", android.widget.Toast.LENGTH_LONG).show()
                val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                try {
                    context.startActivity(settingsIntent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                return
            }
        }

        // 2. Apagar ficheiro antigo se existir (evitar erro de "ficheiro já existe")
        try {
            val oldFile = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), APK_FILENAME)
            if (oldFile.exists()) {
                oldFile.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 3. Registar receiver para saber quando o download acabou
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                ContextCompat.RECEIVER_EXPORTED
            )
            receiverRegistered = true
        }

        // 4. Iniciar download
        try {
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle("Atualização Metrotime")
                .setDescription("A transferir nova versão...")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, APK_FILENAME)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                .setMimeType("application/vnd.android.package-archive")

            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadId = manager.enqueue(request)
            android.widget.Toast.makeText(context, "Download iniciado! Vais receber uma notificação quando terminar.", android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            android.widget.Toast.makeText(context, "Erro ao iniciar download: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
