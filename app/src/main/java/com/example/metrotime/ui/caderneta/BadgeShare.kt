package com.example.metrotime.ui.caderneta

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.example.metrotime.data.caderneta.BadgeState
import com.example.metrotime.data.caderneta.CadernetaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException

/** Partilhar a imagem de um distintivo: nos stories do Instagram, na galeria ou noutra app. */
object BadgeShare {

    private const val INSTAGRAM = "com.instagram.android"
    // O ecrã do Instagram que abre logo os stories com a imagem; se mudar de nome, vai-se à partilha do Instagram
    private const val INSTAGRAM_STORY = "com.instagram.share.handleractivity.StoryShareHandlerActivity"
    private const val TITLE = "Partilhar distintivo"

    /** Guarda a imagem na cache da app e devolve o endereço para as outras apps a lerem. */
    private suspend fun cached(context: Context, image: Bitmap, id: String): Uri = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "partilhas").apply { mkdirs() }
        val file = File(dir, "metrotime-$id.png")
        FileOutputStream(file).use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    }

    private fun send(uri: Uri): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(null, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** Stories do Instagram; sem esse ecrã, a partilha do Instagram; sem Instagram, as outras apps. */
    suspend fun toInstagram(context: Context, image: Bitmap, id: String) {
        val uri = cached(context, image, id)
        val tries = listOf(send(uri).setClassName(INSTAGRAM, INSTAGRAM_STORY), send(uri).setPackage(INSTAGRAM))
        if (tries.any { runCatching { context.startActivity(it) }.isSuccess }) return
        Toast.makeText(context, "O Instagram não está instalado", Toast.LENGTH_SHORT).show()
        context.startActivity(Intent.createChooser(send(uri), TITLE))
    }

    /** A partilha do Android (WhatsApp, Telegram…). */
    suspend fun toOthers(context: Context, image: Bitmap, id: String) {
        val uri = cached(context, image, id)
        context.startActivity(Intent.createChooser(send(uri), TITLE))
    }

    /** Guarda na galeria, em Imagens/Metrotime. */
    suspend fun save(context: Context, image: Bitmap, id: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "Metrotime-$id-${System.currentTimeMillis()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Metrotime")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: return@runCatching false
            val written = resolver.openOutputStream(uri)?.use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: false
            if (!written) {
                resolver.delete(uri, null, null)
                return@runCatching false
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            true
        }.getOrDefault(false)
    }
}

private val SHARE_BG = Color(0xFF0B0C0E)

/** Os ícones dos botões de partilhar (traço, em 24 × 24). */
private enum class ShareIcon(val path: String, val circles: List<Triple<Float, Float, Float>> = emptyList()) {
    STORY("M9,2.5 H15 A3,3 0 0 1 18,5.5 V18.5 A3,3 0 0 1 15,21.5 H9 A3,3 0 0 1 6,18.5 V5.5 A3,3 0 0 1 9,2.5 Z M12,9 V15 M9,12 H15"),
    SAVE("M12,4 V15 M7.5,10.5 L12,15 L16.5,10.5 M5,19.5 H19"),
    MORE("M8.2,10.8 L15.8,6.7 M8.2,13.2 L15.8,17.3", listOf(Triple(18f, 5.5f, 2.5f), Triple(6f, 12f, 2.5f), Triple(18f, 18.5f, 2.5f)))
}

@Composable
private fun ShareButton(label: String, icon: ShareIcon, primary: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val path = remember(icon) { PathParser().parsePathString(icon.path).toPath() }
    val tint = if (primary) SHARE_BG else Color.White
    Column(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .alpha(if (enabled) 1f else 0.5f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            Modifier.size(58.dp).clip(CircleShape).background(if (primary) Color(0xFFF3EFE6) else Color(0xFF1C1C20)),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.size(24.dp)) {
                val k = size.minDimension / 24f
                scale(k, k, pivot = Offset.Zero) {
                    val line = Stroke(2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    drawPath(path, tint, style = line)
                    icon.circles.forEach { (x, y, r) -> drawCircle(tint, r, Offset(x, y), style = line) }
                }
            }
        }
        Text(label, color = Color(0xFFD5D8DD), fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * Partilhar um distintivo ganho: a imagem para os stories e três botões (Instagram, Guardar, Mais). A data é a do dia
 * em que foi ganho, ou a de hoje se não se souber.
 */
@Composable
fun BadgeShareDialog(state: BadgeState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val id = state.def.id
    var image by remember(id) { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(id) {
        val date = CadernetaRepository.earnedDate(context, id) ?: LocalDate.now(ZoneId.of("Europe/Lisbon"))
        image = withContext(Dispatchers.Default) { runCatching { BadgeStory.render(context, state, date) }.getOrNull() }
    }

    fun act(block: suspend (Bitmap) -> Unit) {
        val bmp = image ?: return
        if (busy) return
        busy = true
        scope.launch {
            try {
                block(bmp)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Toast.makeText(context, "Não deu para partilhar", Toast.LENGTH_SHORT).show()
            } finally {
                busy = false
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(SHARE_BG).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Fechar", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text("Partilhar", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(state.def.name, color = Color(0xFFB7BBC3), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Box(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp, vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                val bmp = image
                if (bmp == null) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(32.dp))
                } else {
                    val shape = RoundedCornerShape(18.dp)
                    Image(
                        bitmap = remember(bmp) { bmp.asImageBitmap() },
                        contentDescription = "Imagem para os stories: ${state.def.name}",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .aspectRatio(9f / 16f, matchHeightConstraintsFirst = true)
                            .clip(shape)
                            .border(1.dp, Color.White.copy(alpha = 0.08f), shape)
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(26.dp, Alignment.CenterHorizontally)
            ) {
                val ready = image != null && !busy
                ShareButton("Instagram", ShareIcon.STORY, primary = true, enabled = ready) {
                    act { BadgeShare.toInstagram(context, it, id) }
                }
                ShareButton("Guardar", ShareIcon.SAVE, primary = false, enabled = ready) {
                    act {
                        val ok = BadgeShare.save(context, it, id)
                        Toast.makeText(context, if (ok) "Guardada na galeria" else "Não deu para guardar a imagem", Toast.LENGTH_SHORT).show()
                    }
                }
                ShareButton("Mais", ShareIcon.MORE, primary = false, enabled = ready) {
                    act { BadgeShare.toOthers(context, it, id) }
                }
            }
        }
    }
}
