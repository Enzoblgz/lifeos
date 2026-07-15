package fr.bellenguez.lifeos

import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import coil.compose.AsyncImage
import org.json.JSONObject
import java.text.Normalizer

/* ===== Palette — noir et blanc, rien d'autre ===== */
object Bw {
    val Black = Color(0xFF000000)
    val White = Color(0xFFFFFFFF)
    val G1 = Color(0xFF0A0A0A)
    val G2 = Color(0xFF1A1A1A)
    val G3 = Color(0xFF333333)
    val G4 = Color(0xFF666666)
    val G5 = Color(0xFF999999)
}

const val OATH = "Je certifie sur l'honneur avoir accompli cette tâche."

fun normalizeOath(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase()
        .replace('’', '\'')
        .replace(Regex("[.\\s]+"), " ")
        .trim()

fun oathMatches(input: String): Boolean = normalizeOath(input) == normalizeOath(OATH)

/* ===== Composants ===== */

@Composable
fun bwFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Bw.White, unfocusedTextColor = Bw.White,
    focusedBorderColor = Bw.White, unfocusedBorderColor = Bw.G3,
    cursorColor = Bw.White,
    focusedContainerColor = Bw.G1, unfocusedContainerColor = Bw.G1,
    focusedLabelColor = Bw.G5, unfocusedLabelColor = Bw.G4
)

@Composable
fun Label(text: String, color: Color = Bw.G4, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        color = color,
        fontSize = 10.sp,
        letterSpacing = 3.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
    )
}

@Composable
fun BwButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    ghost: Boolean = false,
    onClick: () -> Unit
) {
    val bg = when {
        !enabled -> Bw.G2
        ghost -> Bw.Black
        else -> Bw.White
    }
    val fg = when {
        !enabled -> Bw.G4
        ghost -> Bw.White
        else -> Bw.Black
    }
    Box(
        modifier
            .fillMaxWidth()
            .background(bg)
            .then(if (ghost) Modifier.border(1.dp, Bw.G3) else Modifier)
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 17.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text.uppercase(),
            color = fg,
            fontSize = 13.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 4.sp,
            textAlign = TextAlign.Center
        )
    }
}

/** Un item du POURQUOI — texte, image ou vidéo (rendu en niveaux de gris). */
@Composable
fun WhyItemView(item: JSONObject, onDelete: (() -> Unit)? = null) {
    val grayscale = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 7.dp)
            .border(1.dp, Bw.G2)
    ) {
        when (item.getString("type")) {
            "txt" -> Text(
                item.getString("text"),
                color = Bw.White,
                fontSize = 16.sp,
                lineHeight = 25.sp,
                modifier = Modifier.padding(16.dp)
            )
            "img" -> AsyncImage(
                model = Uri.parse(item.getString("uri")),
                contentDescription = null,
                colorFilter = grayscale,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth()
            )
            "vid" -> AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        setVideoURI(Uri.parse(item.getString("uri")))
                        setMediaController(MediaController(ctx))
                        seekTo(1)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
            )
        }
        if (onDelete != null) {
            Text(
                "SUPPRIMER",
                color = Bw.G4,
                fontSize = 9.sp,
                letterSpacing = 2.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onDelete() }
                    .padding(9.dp)
            )
        }
    }
}
