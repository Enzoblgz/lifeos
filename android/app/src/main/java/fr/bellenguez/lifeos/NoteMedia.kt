package fr.bellenguez.lifeos

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.Canvas
import androidx.compose.material3.Text
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileOutputStream

/**
 * Pièces jointes des notes : image, vidéo, audio, dessin.
 * Un fichier créé par LifeOS (photo, film, audio, dessin) vit dans filesDir/media
 * et est exposé via le FileProvider ; un fichier choisi dans la galerie garde son
 * content:// d'origine (permission persistée à la sélection).
 *
 * Format stocké dans la note : att: [{"t":"img|vid|aud|drw", "u":"<uri>"}]
 */

const val ATT_IMG = "img"
const val ATT_VID = "vid"
const val ATT_AUD = "aud"
const val ATT_DRW = "drw"

private fun mediaDir(ctx: Context): File =
    File(ctx.filesDir, "media").apply { if (!exists()) mkdirs() }

/** Nouveau fichier média de LifeOS + son uri partageable (caméra, micro, dessin). */
fun newMediaFile(ctx: Context, ext: String): Pair<File, Uri> {
    val f = File(mediaDir(ctx), "${System.currentTimeMillis()}.$ext")
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
    return f to uri
}

/** Supprime le fichier s'il nous appartient (rien à faire pour un média de la galerie). */
fun deleteOwnedMedia(ctx: Context, uri: String) {
    try {
        val u = Uri.parse(uri)
        if (u.authority != "${ctx.packageName}.files") return
        val name = u.lastPathSegment?.substringAfterLast('/') ?: return
        File(mediaDir(ctx), name).delete()
    } catch (_: Exception) {}
}

/* ===== Rendu d'une pièce jointe ===== */

@Composable
fun AttachmentView(type: String, uri: String, grayscale: Boolean = false, modifier: Modifier = Modifier) {
    val filter = if (grayscale)
        ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) else null
    when (type) {
        ATT_IMG, ATT_DRW -> AsyncImage(
            model = Uri.parse(uri),
            contentDescription = null,
            colorFilter = filter,
            contentScale = ContentScale.FillWidth,
            modifier = modifier.fillMaxWidth()
        )
        ATT_VID -> AndroidView(
            factory = { c ->
                VideoView(c).apply {
                    setVideoURI(Uri.parse(uri))
                    setMediaController(MediaController(c))
                    seekTo(1)
                }
            },
            modifier = modifier.fillMaxWidth().aspectRatio(16f / 9f)
        )
        ATT_AUD -> AudioPlayer(uri, modifier)
    }
}

@Composable
private fun AudioPlayer(uri: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var playing by remember(uri) { mutableStateOf(false) }
    val player = remember(uri) { MediaPlayer() }

    DisposableEffect(uri) {
        try {
            player.setDataSource(ctx, Uri.parse(uri))
            player.prepare()
        } catch (_: Exception) {}
        player.setOnCompletionListener { playing = false }
        onDispose { try { player.release() } catch (_: Exception) {} }
    }

    Row(
        modifier
            .fillMaxWidth()
            .clickable {
                try {
                    if (playing) { player.pause(); playing = false }
                    else { player.start(); playing = true }
                } catch (_: Exception) {}
            }
            .padding(horizontal = 14.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (playing) "❚❚" else "▶",
            color = Bw.White, fontSize = 15.sp, fontWeight = FontWeight.Black
        )
        Spacer(Modifier.width(14.dp))
        Text("MÉMO VOCAL", color = Bw.G5, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
    }
}

/* ===== Enregistrement audio ===== */

/** Enregistreur plein écran. onDone(uri) quand l'enregistrement est gardé. */
@Composable
fun AudioRecorderScreen(onDone: (String) -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    var seconds by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }

    val target = remember { newMediaFile(ctx, "m4a") }
    val recorder = remember {
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
    }

    DisposableEffect(Unit) {
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setOutputFile(target.first.absolutePath)
            recorder.prepare()
            recorder.start()
        } catch (e: Exception) {
            failed = true
        }
        onDispose { try { recorder.release() } catch (_: Exception) {} }
    }

    LaunchedEffect(Unit) {
        while (true) { delay(1000); seconds++ }
    }

    /** Arrête proprement : sans stop() le fichier m4a reste illisible. */
    fun stop(): Boolean = try { recorder.stop(); true } catch (e: Exception) { false }

    Column(
        Modifier.fillMaxSize().background(Bw.Black).padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (failed) {
            Label("Micro indisponible", color = Bw.White)
            Spacer(Modifier.height(20.dp))
            BwButton("Fermer") { target.first.delete(); onCancel() }
            return@Column
        }
        Label("Enregistrement en cours")
        Spacer(Modifier.height(16.dp))
        Text(
            "%d:%02d".format(seconds / 60, seconds % 60),
            color = Bw.White, fontSize = 64.sp, fontWeight = FontWeight.Black
        )
        Spacer(Modifier.height(36.dp))
        BwButton("Arrêter et garder") {
            val ok = stop()
            if (ok && target.first.length() > 0) onDone(target.second.toString())
            else { target.first.delete(); onCancel() }
        }
        Spacer(Modifier.height(10.dp))
        BwButton("Annuler", ghost = true) {
            stop()
            target.first.delete()
            onCancel()
        }
    }
}

/* ===== Dessin ===== */

private class Stroke2(val points: MutableList<Offset>, val width: Float)

/** Toile de dessin plein écran (trait blanc sur fond noir), exportée en PNG. */
@Composable
fun DrawScreen(onDone: (String) -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    val strokes = remember { mutableStateListOf<Stroke2>() }
    var current by remember { mutableStateOf<Stroke2?>(null) }
    var width by remember { mutableStateOf(6f) }
    var canvasW by remember { mutableIntStateOf(0) }
    var canvasH by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize().background(Bw.Black).padding(16.dp)) {
        Spacer(Modifier.height(24.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Label("Dessin", color = Bw.White)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("FIN" to 3f, "MOYEN" to 6f, "GROS" to 14f).forEach { (l, w) ->
                    Text(
                        l,
                        color = if (width == w) Bw.White else Bw.G4,
                        fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                        modifier = Modifier
                            .border(1.dp, if (width == w) Bw.White else Bw.G3)
                            .clickable { width = w }
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .border(1.dp, Bw.G3)
        ) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { canvasW = it.width; canvasH = it.height }
                    .pointerInput(width) {
                        detectDragGestures(
                            onDragStart = { p ->
                                current = Stroke2(mutableListOf(p), width)
                            },
                            onDrag = { change, _ ->
                                current?.points?.add(change.position)
                                // force la recomposition du tracé en cours
                                current = current?.let { Stroke2(it.points, it.width) }
                            },
                            onDragEnd = {
                                current?.let { strokes.add(it) }
                                current = null
                            }
                        )
                    }
            ) {
                (strokes + listOfNotNull(current)).forEach { s ->
                    if (s.points.size < 2) {
                        s.points.firstOrNull()?.let {
                            drawCircle(Bw.White, radius = s.width / 2f, center = it)
                        }
                    } else {
                        val path = Path().apply {
                            moveTo(s.points[0].x, s.points[0].y)
                            s.points.drop(1).forEach { lineTo(it.x, it.y) }
                        }
                        drawPath(
                            path, Bw.White,
                            style = Stroke(width = s.width, cap = StrokeCap.Round)
                        )
                    }
                }
            }
            if (strokes.isEmpty() && current == null) {
                Text(
                    "Dessine avec le doigt.",
                    color = Bw.G4, fontSize = 13.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BwButton("Annuler le trait", Modifier.weight(1f), ghost = true, enabled = strokes.isNotEmpty()) {
                strokes.removeLastOrNull()
            }
            BwButton("Tout effacer", Modifier.weight(1f), ghost = true, enabled = strokes.isNotEmpty()) {
                strokes.clear()
            }
        }
        Spacer(Modifier.height(8.dp))
        BwButton("Garder le dessin", enabled = strokes.isNotEmpty()) {
            val uri = exportDrawing(ctx, strokes.toList(), canvasW, canvasH)
            if (uri != null) onDone(uri) else onCancel()
        }
        Spacer(Modifier.height(8.dp))
        BwButton("Fermer", ghost = true) { onCancel() }
        Spacer(Modifier.height(10.dp))
    }
}

private fun exportDrawing(ctx: Context, strokes: List<Stroke2>, w: Int, h: Int): String? {
    if (w <= 0 || h <= 0) return null
    return try {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.drawColor(android.graphics.Color.BLACK)
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            isAntiAlias = true
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
        strokes.forEach { s ->
            paint.strokeWidth = s.width
            if (s.points.size < 2) {
                s.points.firstOrNull()?.let {
                    val fill = android.graphics.Paint(paint).apply {
                        style = android.graphics.Paint.Style.FILL
                    }
                    c.drawCircle(it.x, it.y, s.width / 2f, fill)
                }
            } else {
                val p = android.graphics.Path()
                p.moveTo(s.points[0].x, s.points[0].y)
                s.points.drop(1).forEach { pt -> p.lineTo(pt.x, pt.y) }
                c.drawPath(p, paint)
            }
        }
        val (file, uri) = newMediaFile(ctx, "png")
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        uri.toString()
    } catch (e: Exception) { null }
}
