package fr.bellenguez.lifeos

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Visite guidée « coach marks » : un voile sombre perce un trou autour d'un vrai
 * élément du launcher, avec une bulle d'explication. On apprend l'interface sur
 * l'interface elle-même, pas sur des diapos.
 *
 * Les ancres (`Modifier.tourAnchor`) enregistrent en continu la position des
 * éléments ; l'overlay (`TourOverlay`) n'est monté qu'à la première ouverture.
 */
class TourState {
    val targets: SnapshotStateMap<String, Rect> = mutableStateMapOf()
}

/** LifeOS est-il l'écran d'accueil par défaut ? */
fun isDefaultHome(ctx: android.content.Context): Boolean {
    val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
        .addCategory(android.content.Intent.CATEGORY_HOME)
    val res = ctx.packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
    return res?.activityInfo?.packageName == ctx.packageName
}

/** Ouvre le sélecteur d'écran d'accueil ; repli sur les réglages généraux si indisponible. */
private fun openHomeSettings(ctx: android.content.Context) {
    try {
        ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_HOME_SETTINGS))
    } catch (_: Exception) {
        try { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS)) } catch (_: Exception) {}
    }
}

/** Enregistre la position à l'écran d'un élément pour que la visite puisse le cibler. */
fun Modifier.tourAnchor(state: TourState, id: String): Modifier =
    this.onGloballyPositioned { state.targets[id] = it.boundsInRoot() }

private enum class Kind { INTRO, SPOT, PERMS, HOME, FINAL }

private data class Step(
    val kind: Kind,
    val anchor: String? = null,
    val title: String = "",
    val body: String = ""
)

private val steps = listOf(
    Step(Kind.INTRO, title = "BIENVENUE\nDANS LIFEOS.",
        body = "Ton téléphone t'obéit, pas l'inverse. LifeOS remplace ton écran d'accueil " +
            "par un cockpit nu, et verrouille ton attention quand tu bosses.\n\n" +
            "Deux minutes pour tout comprendre — sur l'écran, pas sur des diapos."),
    Step(Kind.SPOT, anchor = "now", title = "TA TÂCHE DU MOMENT",
        body = "Ici s'affiche le bloc à faire maintenant, et le bouton pour te verrouiller " +
            "dessus en focus. C'est vide pour l'instant : on va planifier ça juste après."),
    Step(Kind.SPOT, anchor = "nav_plan", title = "PLANIFIE ICI",
        body = "PLAN, c'est le point de départ : tes blocs, tes cours, tes rendez-vous de la " +
            "semaine. Ils remontent sur l'accueil, prêts à cocher et à passer en focus."),
    Step(Kind.SPOT, anchor = "nav_notes", title = "TES NOTES",
        body = "Capture tout ce qui passe — texte, listes à cocher, photos, dessins, mémos " +
            "vocaux. Façon Google Keep, en noir et blanc."),
    Step(Kind.SPOT, anchor = "nav_apps", title = "LE FOCUS & TES APPS",
        body = "Quand tu lances un bloc en focus, le téléphone se verrouille dessus. Ici tu " +
            "choisis les rares apps qui restent accessibles malgré tout. Choisis-en peu."),
    Step(Kind.SPOT, anchor = "streak", title = "TON STREAK",
        body = "Chaque jour où tu tiens tes blocs essentiels, ton streak monte. Un jour raté, " +
            "il retombe à zéro. Pas d'excuse, pas de négociation."),
    Step(Kind.PERMS, title = "LE VERROU A\nBESOIN DE TOI.",
        body = "Pour bloquer les autres apps pendant un focus, LifeOS a besoin de deux " +
            "autorisations Android. Tu peux les donner maintenant ou plus tard."),
    Step(Kind.HOME, title = "LIFEOS EST\nTON ACCUEIL.",
        body = "Définis LifeOS comme écran d'accueil par défaut : le cockpit devient la " +
            "première chose que tu vois, et le bouton home y ramène toujours."),
    Step(Kind.FINAL, title = "À TOI.",
        body = "Ta journée est vide. Ajoute ton premier bloc et il apparaîtra sur l'accueil, " +
            "prêt à passer en focus.")
)

@Composable
fun TourOverlay(state: TourState, onOpenPlan: () -> Unit, onFinish: () -> Unit) {
    val ctx = LocalContext.current
    var i by rememberSaveable { mutableIntStateOf(0) }
    var probe by remember { mutableIntStateOf(0) } // relit permissions/launcher au retour des réglages
    androidx.compose.runtime.LaunchedEffect(i) { probe++ }

    val step = steps[i]
    val rect = step.anchor?.let { state.targets[it] }
    val advance: () -> Unit = { if (i < steps.lastIndex) i++ else onFinish() }
    val back: () -> Unit = { if (i > 0) i-- }

    Box(
        Modifier
            .fillMaxSize()
            // avale les taps vers le launcher ; sur les étapes « explication », un tap avance
            .pointerInput(i) {
                detectTapGestures { if (step.kind == Kind.INTRO || step.kind == Kind.SPOT) advance() }
            }
    ) {
        // 1) le voile — troué autour de la cible pour une étape SPOT, plein sinon
        Canvas(Modifier.fillMaxSize()) {
            val scrim = Bw.Black.copy(alpha = 0.86f)
            if (step.kind == Kind.SPOT && rect != null) {
                val pad = 8.dp.toPx()
                val l = (rect.left - pad).coerceAtLeast(0f)
                val t = (rect.top - pad).coerceAtLeast(0f)
                val r = (rect.right + pad).coerceAtMost(size.width)
                val b = (rect.bottom + pad).coerceAtMost(size.height)
                drawRect(scrim, Offset(0f, 0f), Size(size.width, t))
                drawRect(scrim, Offset(0f, b), Size(size.width, (size.height - b).coerceAtLeast(0f)))
                drawRect(scrim, Offset(0f, t), Size(l, (b - t).coerceAtLeast(0f)))
                drawRect(scrim, Offset(r, t), Size((size.width - r).coerceAtLeast(0f), (b - t).coerceAtLeast(0f)))
                drawRect(Bw.White, Offset(l, t), Size((r - l).coerceAtLeast(0f), (b - t).coerceAtLeast(0f)), style = Stroke(width = 2.dp.toPx()))
            } else {
                drawRect(scrim, Offset(0f, 0f), Size(size.width, size.height))
            }
        }

        // 2) la bulle / carte
        if (step.kind == Kind.SPOT && rect != null) {
            SpotBubble(rect, step, i, onNext = advance, onSkip = onFinish)
        } else {
            CenterCard(
                step = step, index = i, ctx = ctx, probe = probe,
                onNext = advance, onBack = back, onSkip = onFinish,
                onOpenPlan = onOpenPlan, onFinish = onFinish
            )
        }
    }
}

/** Bulle d'explication posée près de l'élément mis en évidence. */
@Composable
private fun SpotBubble(rect: Rect, step: Step, index: Int, onNext: () -> Unit, onSkip: () -> Unit) {
    val density = LocalDensity.current
    val screenH = LocalConfiguration.current.screenHeightDp
    val below = with(density) { rect.bottom.toDp() }.value < screenH * 0.55f
    val yDp = with(density) { if (below) (rect.bottom).toDp() + 16.dp else (rect.top).toDp() - 210.dp }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .offset(y = yDp)
    ) {
        Column(
            Modifier
                .widthIn(max = 360.dp)
                .background(Bw.G1)
                .border(1.dp, Bw.White)
                .padding(18.dp)
        ) {
            StepDots(index)
            Spacer(Modifier.height(10.dp))
            Text(step.title, color = Bw.White, fontSize = 15.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
            Spacer(Modifier.height(8.dp))
            Text(step.body, color = Bw.G5, fontSize = 13.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "PASSER",
                    color = Bw.G4, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                    modifier = Modifier
                        .weight(1f)
                        .border(1.dp, Bw.G3)
                        .clickableNoRipple(onSkip)
                        .padding(vertical = 12.dp),
                )
                Text(
                    "SUIVANT",
                    color = Bw.Black, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp,
                    modifier = Modifier
                        .weight(1f)
                        .background(Bw.White)
                        .clickableNoRipple(onNext)
                        .padding(vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun CenterCard(
    step: Step, index: Int, ctx: android.content.Context, probe: Int,
    onNext: () -> Unit, onBack: () -> Unit, onSkip: () -> Unit,
    onOpenPlan: () -> Unit, onFinish: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center
    ) {
        StepDots(index)
        Spacer(Modifier.height(16.dp))
        Text(step.title, color = Bw.White, fontSize = 34.sp, fontWeight = FontWeight.Black, lineHeight = 38.sp)
        Spacer(Modifier.height(18.dp))
        Text(step.body, color = Bw.G5, fontSize = 15.sp, lineHeight = 24.sp)
        Spacer(Modifier.height(28.dp))

        when (step.kind) {
            Kind.PERMS -> {
                val usageOk = remember(probe) { hasUsageAccess(ctx) }
                val overlayOk = remember(probe) { canOverlay(ctx) }
                BwButton(if (usageOk) "1. Accès à l'usage — accordé" else "1. Autoriser l'accès à l'usage", ghost = usageOk) {
                    ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
                Spacer(Modifier.height(10.dp))
                BwButton(if (overlayOk) "2. Superposition — accordée" else "2. Autoriser la superposition", ghost = overlayOk) {
                    ctx.startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:${ctx.packageName}")
                        )
                    )
                }
                Spacer(Modifier.height(16.dp))
                NavRow(onBack, if (usageOk && overlayOk) "Continuer" else "Plus tard", onNext)
            }
            Kind.HOME -> {
                val homeOk = remember(probe) { isDefaultHome(ctx) }
                BwButton(if (homeOk) "LifeOS est ton écran d'accueil ✓" else "Définir LifeOS par défaut", ghost = homeOk) {
                    openHomeSettings(ctx)
                }
                Spacer(Modifier.height(16.dp))
                NavRow(onBack, if (homeOk) "Continuer" else "Plus tard", onNext)
            }
            Kind.FINAL -> {
                BwButton("Planifier mon premier bloc") { onOpenPlan() }
                Spacer(Modifier.height(10.dp))
                BwButton("Entrer dans LifeOS", ghost = true) { onFinish() }
            }
            else -> { // INTRO
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BwButton("Passer", Modifier.weight(1f), ghost = true) { onSkip() }
                    BwButton("Commencer la visite", Modifier.weight(1f)) { onNext() }
                }
            }
        }
    }
}

@Composable
private fun NavRow(onBack: () -> Unit, nextLabel: String, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BwButton("Retour", Modifier.weight(1f), ghost = true) { onBack() }
        BwButton(nextLabel, Modifier.weight(1f)) { onNext() }
    }
}

@Composable
private fun StepDots(index: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(steps.size) { k ->
            Box(
                Modifier
                    .height(3.dp)
                    .then(if (k == index) Modifier.widthIn(min = 18.dp) else Modifier.widthIn(min = 6.dp))
                    .background(if (k <= index) Bw.White else Bw.G3)
            )
        }
    }
}

/** Clic sans effet visuel material (l'overlay gère son propre style). */
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.pointerInput(onClick) { detectTapGestures { onClick() } }
