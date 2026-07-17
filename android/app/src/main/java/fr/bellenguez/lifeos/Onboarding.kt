package fr.bellenguez.lifeos

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** LifeOS est-il l'écran d'accueil par défaut ? */
fun isDefaultHome(ctx: Context): Boolean {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    val res = ctx.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
    return res?.activityInfo?.packageName == ctx.packageName
}

/**
 * Onboarding en 5 temps, affiché une seule fois par appareil (flag `dev_onboarded`).
 * Manifeste → fonctionnement → permissions du verrou → écran d'accueil → premier bloc.
 * Les étapes techniques (permissions, launcher) montrent un état vivant : on ne force rien,
 * mais on dit clairement ce qui manque et pourquoi.
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit, onOpenPlan: () -> Unit) {
    val ctx = LocalContext.current
    var step by remember { mutableIntStateOf(0) }
    // recalcul de l'état permissions/launcher au retour des réglages système
    var probe by remember { mutableIntStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(step) { probe++ }

    val steps = 5
    Column(
        Modifier.fillMaxSize().background(Bw.Black).padding(28.dp)
    ) {
        Spacer(Modifier.height(30.dp))
        // progression
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(steps) { i ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(3.dp)
                        .background(if (i <= step) Bw.White else Bw.G3)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Label("Étape ${step + 1} / $steps", color = Bw.G4)

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center
        ) {
            when (step) {
                0 -> StepManifesto()
                1 -> StepHow()
                2 -> StepPermissions(probe)
                3 -> StepLauncher(probe)
                4 -> StepFirstBlock()
            }
        }

        // actions
        val usageOk = remember(probe) { hasUsageAccess(ctx) }
        val overlayOk = remember(probe) { canOverlay(ctx) }
        val homeOk = remember(probe) { isDefaultHome(ctx) }

        when (step) {
            2 -> {
                BwButton(
                    if (usageOk) "1. Accès à l'usage — accordé" else "1. Autoriser l'accès à l'usage",
                    ghost = usageOk
                ) { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                Spacer(Modifier.height(10.dp))
                BwButton(
                    if (overlayOk) "2. Superposition — accordée" else "2. Autoriser la superposition",
                    ghost = overlayOk
                ) {
                    ctx.startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BwButton("Retour", Modifier.weight(1f), ghost = true) { step-- }
                    BwButton(
                        if (usageOk && overlayOk) "Continuer" else "Plus tard",
                        Modifier.weight(1f)
                    ) { step++ }
                }
            }
            3 -> {
                BwButton(
                    if (homeOk) "LifeOS est ton écran d'accueil ✓" else "Définir LifeOS par défaut",
                    ghost = homeOk
                ) { openHomeSettings(ctx) }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BwButton("Retour", Modifier.weight(1f), ghost = true) { step-- }
                    BwButton(if (homeOk) "Continuer" else "Plus tard", Modifier.weight(1f)) { step++ }
                }
            }
            4 -> {
                BwButton("Planifier mon premier bloc") { Store.setOnboarded(); onOpenPlan() }
                Spacer(Modifier.height(10.dp))
                BwButton("Entrer dans LifeOS", ghost = true) { Store.setOnboarded(); onDone() }
            }
            else -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (step > 0) BwButton("Retour", Modifier.weight(1f), ghost = true) { step-- }
                    BwButton("Continuer", Modifier.weight(1f)) { step++ }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

/** Ouvre le sélecteur d'écran d'accueil ; repli sur les réglages généraux si indisponible. */
private fun openHomeSettings(ctx: Context) {
    try {
        ctx.startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
    } catch (_: Exception) {
        try { ctx.startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) {}
    }
}

@Composable
private fun StepManifesto() {
    Text(
        "TON TÉLÉPHONE\nT'OBÉIT.",
        color = Bw.White, fontSize = 38.sp, fontWeight = FontWeight.Black, lineHeight = 42.sp
    )
    Spacer(Modifier.height(20.dp))
    Text(
        "Pas l'inverse.\n\nLifeOS remplace ton écran d'accueil par un cockpit nu : ta journée, " +
            "ton objectif du moment, rien qui distrait. Et quand tu décides de bosser, il verrouille " +
            "le téléphone sur ta tâche — les autres apps deviennent inaccessibles jusqu'à ce que tu aies fini.",
        color = Bw.G5, fontSize = 15.sp, lineHeight = 24.sp
    )
}

@Composable
private fun StepHow() {
    Text(
        "COMMENT ÇA\nMARCHE.",
        color = Bw.White, fontSize = 34.sp, fontWeight = FontWeight.Black, lineHeight = 38.sp
    )
    Spacer(Modifier.height(22.dp))
    Bullet("LA JOURNÉE", "Tu planifies des blocs (tes tâches, tes rendez-vous). Ils s'affichent sur l'accueil, cochables un par un.")
    Bullet("LE FOCUS", "Tu lances un bloc en focus : plein écran verrouillé sur cette tâche, chrono, compteur de fuites. Pour sortir, tu certifies l'avoir faite.")
    Bullet("LE STREAK", "Chaque jour où tu tiens tes blocs essentiels, ton streak monte. Un jour raté, il retombe à zéro.")
}

@Composable
private fun StepPermissions(probe: Int) {
    Text(
        "LE VERROU A\nBESOIN DE TOI.",
        color = Bw.White, fontSize = 32.sp, fontWeight = FontWeight.Black, lineHeight = 36.sp
    )
    Spacer(Modifier.height(20.dp))
    Text(
        "Pour bloquer les autres apps pendant un focus, LifeOS a besoin de deux autorisations Android :",
        color = Bw.G5, fontSize = 15.sp, lineHeight = 24.sp
    )
    Spacer(Modifier.height(16.dp))
    Bullet("ACCÈS À L'USAGE", "Pour voir quelle app passe au premier plan et la rattraper si elle sort du cadre.")
    Bullet("SUPERPOSITION", "Pour s'afficher par-dessus l'app que tu essaies d'ouvrir pendant un blocage.")
    Spacer(Modifier.height(8.dp))
    Text(
        "Tu peux les accorder maintenant ou plus tard — le verrou ne marchera qu'une fois les deux données.",
        color = Bw.G4, fontSize = 12.sp, lineHeight = 19.sp
    )
}

@Composable
private fun StepLauncher(probe: Int) {
    Text(
        "LIFEOS EST\nTON ACCUEIL.",
        color = Bw.White, fontSize = 34.sp, fontWeight = FontWeight.Black, lineHeight = 38.sp
    )
    Spacer(Modifier.height(20.dp))
    Text(
        "LifeOS n'est pas une app de plus : c'est ton écran d'accueil. Définis-le par défaut pour que le " +
            "cockpit soit la première chose que tu vois — et pour que le bouton home ramène toujours à l'essentiel.",
        color = Bw.G5, fontSize = 15.sp, lineHeight = 24.sp
    )
    Spacer(Modifier.height(12.dp))
    Text(
        "Android va te demander de choisir : sélectionne LifeOS, puis « Toujours ».",
        color = Bw.G4, fontSize = 12.sp, lineHeight = 19.sp
    )
}

@Composable
private fun StepFirstBlock() {
    Text(
        "PRÊT.",
        color = Bw.White, fontSize = 44.sp, fontWeight = FontWeight.Black
    )
    Spacer(Modifier.height(20.dp))
    Text(
        "Une dernière chose : ta journée est vide. Ajoute ton premier bloc — un cours, une séance de sport, " +
            "une session de travail — et il apparaîtra sur ton accueil, prêt à passer en focus.",
        color = Bw.G5, fontSize = 15.sp, lineHeight = 24.sp
    )
}

@Composable
private fun Bullet(title: String, body: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Box(Modifier.padding(top = 5.dp).width(8.dp).height(8.dp).background(Bw.White))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, color = Bw.White, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            Spacer(Modifier.height(3.dp))
            Text(body, color = Bw.G5, fontSize = 14.sp, lineHeight = 21.sp)
        }
    }
}
