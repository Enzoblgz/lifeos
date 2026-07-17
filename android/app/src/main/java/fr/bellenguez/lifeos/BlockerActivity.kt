package fr.bellenguez.lifeos

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Écran de verrouillage plein écran. Impossible d'en sortir sans certifier
 * sur l'honneur — le service FocusService rappelle cet écran si on s'échappe.
 */
class BlockerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* pas de sortie par retour */ }
        })
        setContent { BlockerScreen(onCertified = ::unlock) }
    }

    private fun unlock() {
        Store.clearFocus()
        stopService(Intent(this, FocusService::class.java))
        startActivity(
            Intent(this, LauncherActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        finish()
    }
}

private enum class Panel { FOCUS, WHY, CERTIF }

@Composable
private fun BlockerScreen(onCertified: () -> Unit) {
    if (!Store.focusActive()) {
        // focus déjà terminé (relance parasite) — rien à bloquer
        LaunchedEffect(Unit) { onCertified() }
        return
    }
    var panel by remember { mutableStateOf(Panel.FOCUS) }
    val block = Schedule.today().find { it.id == Store.focusId() }
    if (block == null) {
        // le planning a changé sous le focus : plus rien à bloquer, on libère
        LaunchedEffect(Unit) { onCertified() }
        return
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var escapes by remember { mutableStateOf(Store.focusEscapes()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
            escapes = Store.focusEscapes()
        }
    }

    when (panel) {
        Panel.FOCUS -> {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(Bw.Black)
                    .padding(24.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Label("Focus verrouillé", color = Bw.White)
                    Label("Fuites : $escapes", color = if (escapes > 0) Bw.White else Bw.G4)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Label("Tâche en cours")
                    Spacer(Modifier.height(16.dp))
                    Text(
                        block.name.uppercase(),
                        color = Bw.White,
                        fontSize = 42.sp,
                        fontWeight = FontWeight.Black,
                        lineHeight = 44.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(block.time, color = Bw.G5, fontSize = 13.sp, letterSpacing = 3.sp)
                    Spacer(Modifier.height(26.dp))
                    val s = ((now - Store.focusStart()) / 1000).coerceAtLeast(0)
                    val timer = "%02d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
                    Text(
                        timer,
                        color = Bw.G4,
                        fontSize = 15.sp,
                        letterSpacing = 4.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Column {
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    val pm = ctx.packageManager
                    val allowed = remember {
                        Store.whitelist().mapNotNull { pkg ->
                            pm.getLaunchIntentForPackage(pkg)?.let { intent ->
                                Triple(pkg, pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(), intent)
                            }
                        }
                    }
                    var appsOpen by remember { mutableStateOf(false) }
                    if (allowed.isNotEmpty()) {
                        Text(
                            (if (appsOpen) "▾" else "▸") + " APPS AUTORISÉES",
                            color = Bw.G4, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                            modifier = Modifier
                                .clickable { appsOpen = !appsOpen }
                                .padding(vertical = 8.dp)
                        )
                        if (appsOpen) {
                            Spacer(Modifier.height(4.dp))
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                allowed.forEach { (_, label, intent) ->
                                    Text(
                                        label.lowercase(),
                                        color = Bw.G5, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier
                                            .border(1.dp, Bw.G3)
                                            .clickable { ctx.startActivity(intent) }
                                            .padding(horizontal = 12.dp, vertical = 9.dp)
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                    BwButton("Voir pourquoi", ghost = true) { panel = Panel.WHY }
                    Spacer(Modifier.height(12.dp))
                    BwButton("J'ai terminé") { panel = Panel.CERTIF }
                }
            }
        }

        Panel.WHY -> {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(Bw.Black)
            ) {
                Label(
                    "Pourquoi tu fais ça",
                    color = Bw.White,
                    modifier = Modifier.padding(24.dp)
                )
                val items = remember { Store.whyItems() }
                LazyColumn(Modifier.weight(1f)) {
                    if (items.isEmpty()) {
                        item {
                            Text(
                                "Aucun contenu. Retourne au travail,\npuis remplis ton POURQUOI ce soir.",
                                color = Bw.G4,
                                fontSize = 13.sp,
                                lineHeight = 22.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(34.dp)
                            )
                        }
                    }
                    items(items) { WhyItemView(it) }
                }
                BwButton("Retour au travail", modifier = Modifier.padding(0.dp)) { panel = Panel.FOCUS }
            }
        }

        Panel.CERTIF -> {
            var input by remember { mutableStateOf("") }
            Column(
                Modifier
                    .fillMaxSize()
                    .background(Bw.Black)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Label("Certification sur l'honneur")
                Spacer(Modifier.height(14.dp))
                Text(
                    block.name.uppercase(),
                    color = Bw.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "« $OATH »",
                    color = Bw.G5,
                    fontSize = 15.sp,
                    lineHeight = 23.sp,
                    fontStyle = FontStyle.Italic
                )
                Spacer(Modifier.height(20.dp))
                Label("Recopie la phrase exacte pour déverrouiller")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(autoCorrect = false),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Bw.White,
                        unfocusedTextColor = Bw.White,
                        focusedBorderColor = Bw.White,
                        unfocusedBorderColor = Bw.G3,
                        cursorColor = Bw.White,
                        focusedContainerColor = Bw.G1,
                        unfocusedContainerColor = Bw.G1
                    )
                )
                Spacer(Modifier.height(20.dp))
                BwButton("Certifier et déverrouiller", enabled = oathMatches(input)) {
                    Store.setCheck(block.id, true)
                    Thread { Supa.tryPush() }.start()
                    onCertified()
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    "JE N'AI PAS TERMINÉ — RETOUR AU TRAVAIL",
                    color = Bw.G4,
                    fontSize = 10.sp,
                    letterSpacing = 2.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { panel = Panel.FOCUS }
                        .padding(10.dp)
                )
            }
        }
    }
}
