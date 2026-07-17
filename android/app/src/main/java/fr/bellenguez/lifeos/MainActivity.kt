package fr.bellenguez.lifeos

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        setContent { Root() }
    }

    override fun onResume() {
        super.onResume()
        // si un focus est actif, on n'a rien à faire ici : retour au verrou
        if (Store.focusActive()) {
            startActivity(Intent(this, BlockerActivity::class.java))
        }
    }
}

/* ===== Permissions nécessaires au verrouillage ===== */
fun hasUsageAccess(ctx: Context): Boolean {
    val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = ops.unsafeCheckOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName
    )
    return mode == AppOpsManager.MODE_ALLOWED
}

fun canOverlay(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

private enum class Tab(val title: String) {
    NOW("Now"), PLAN("Plan"), WEEK("Sem."), WHY("Pourquoi"), APPS("Apps"), STREAK("Streak")
}

@Composable
fun Root() {
    var logged by remember { mutableStateOf(!Supa.configured() || Store.session() != null) }
    if (logged) App() else AuthScreen(onLogged = { logged = true })
}

@Composable
fun App() {
    var tab by remember { mutableStateOf(Tab.NOW) }
    var refresh by remember { mutableIntStateOf(0) }
    var showPerms by remember { mutableStateOf(false) }

    // Sync : pull au lancement, push (débounce) après chaque édition locale,
    // et poll toutes les 4 s pour rapatrier les changements faits sur l'ordi (< 5 s).
    var lastLocalEdit by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        val pulled = withContext(Dispatchers.IO) { Supa.tryPull() }
        if (pulled) refresh++
    }
    LaunchedEffect(refresh) {
        if (refresh > 0 && lastLocalEdit > 0) {
            delay(1200)
            withContext(Dispatchers.IO) { Supa.tryPush() }
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(4000)
            // pas de pull pendant qu'on édite (le push en attente gagnerait à partir d'abord)
            if (System.currentTimeMillis() - lastLocalEdit > 2500) {
                val changed = withContext(Dispatchers.IO) { Supa.pollAndPull() }
                if (changed) { lastLocalEdit = 0L; refresh++ }
            }
        }
    }
    val markEdit: () -> Unit = { lastLocalEdit = System.currentTimeMillis(); refresh++ }

    Column(
        Modifier
            .fillMaxSize()
            .background(Bw.Black)
    ) {
        Header(refresh)
        Box(Modifier.weight(1f)) {
            when (tab) {
                Tab.NOW -> NowScreen(refresh, onChange = markEdit, onNeedPerms = { showPerms = true }, goPlan = { tab = Tab.PLAN })
                Tab.PLAN -> PlanScreen(onChange = markEdit)
                Tab.WEEK -> WeekScreen(refresh, onChange = markEdit)
                Tab.WHY -> WhyScreen(refresh, onChange = markEdit)
                Tab.APPS -> AppsScreen()
                Tab.STREAK -> StreakScreen(refresh, onChange = markEdit)
            }
            if (showPerms) PermissionsSheet(onDone = { showPerms = false })
        }
        BottomNav(tab, onSelect = { tab = it })
    }
}

@Composable
private fun Header(refresh: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("LIFEOS", color = Bw.White, fontSize = 15.sp, fontWeight = FontWeight.Black, letterSpacing = 5.sp)
        val streak = remember(refresh) { Store.streak }
        Text(
            "J$streak",
            color = Bw.G5,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            modifier = Modifier
                .border(1.dp, Bw.G3)
                .padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
    Divider()
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Bw.G2))
}

/* ================= NOW ================= */
@Composable
private fun NowScreen(refresh: Int, onChange: () -> Unit, onNeedPerms: () -> Unit, goPlan: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); tick++ } }

    val cur = remember(tick, refresh) { Schedule.current() }
    val blocks = Schedule.today()
    val checks = remember(refresh) { Store.checks() }

    if (blocks.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Label("Rien de planifié")
            Spacer(Modifier.height(12.dp))
            Text(
                "TA JOURNÉE EST VIDE.",
                color = Bw.White, fontSize = 30.sp, fontWeight = FontWeight.Black, lineHeight = 32.sp
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "C'est toi qui décides ce que tu fais. Planifie tes blocs, puis exécute.",
                color = Bw.G5, fontSize = 14.sp, lineHeight = 22.sp
            )
            Spacer(Modifier.height(24.dp))
            BwButton("Planifier ma journée") { goPlan() }
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(20.dp)) {
                Spacer(Modifier.height(14.dp))
                Label(
                    when (cur.mode) {
                        "now" -> "Maintenant"
                        "next" -> "Prochain bloc"
                        else -> "Journée terminée"
                    }
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    if (cur.idx >= 0) blocks[cur.idx].name.uppercase() else "REPOS.",
                    color = Bw.White,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Black,
                    lineHeight = 34.sp
                )
                if (cur.idx >= 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${blocks[cur.idx].time}  ·  ${blocks[cur.idx].hat.uppercase()}",
                        color = Bw.G5, fontSize = 12.sp, letterSpacing = 2.sp
                    )
                }
                Spacer(Modifier.height(24.dp))
                BwButton("Entrer en focus", enabled = cur.idx >= 0) {
                    if (!hasUsageAccess(ctx) || !canOverlay(ctx)) {
                        onNeedPerms()
                    } else {
                        Store.startFocus(cur.idx)
                        ctx.startForegroundService(Intent(ctx, FocusService::class.java))
                        ctx.startActivity(Intent(ctx, BlockerActivity::class.java))
                    }
                }
            }
            Divider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Label("Aujourd'hui")
                val pct = if (blocks.isEmpty()) 0 else 100 * checks.size / blocks.size
                Label("$pct %", color = Bw.G5)
            }
            ProgressLine(checks.size.toFloat() / blocks.size.coerceAtLeast(1))
        }
        itemsIndexed(blocks) { i, b ->
            val done = i in checks
            val isCurrent = cur.mode == "now" && cur.idx == i
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (isCurrent) Bw.G1 else Bw.Black)
                    .clickable {
                        Store.setCheck(i, !done)
                        onChange()
                    }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(20.dp)
                        .then(
                            if (done) Modifier.background(Bw.White)
                            else Modifier.border(1.dp, Bw.G4)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (done) Text("✕", color = Bw.Black, fontSize = 12.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.width(14.dp))
                Text(b.time, color = Bw.G4, fontSize = 11.sp, modifier = Modifier.width(76.dp))
                Text(
                    b.name.uppercase(),
                    color = if (done) Bw.G4 else Bw.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = if (done) TextDecoration.LineThrough else null,
                    modifier = Modifier.weight(1f)
                )
                if (b.nn) {
                    Text(
                        "NN", color = Bw.White, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                        modifier = Modifier.border(1.dp, Bw.White).padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
            Divider()
        }
    }
}

@Composable
private fun ProgressLine(fraction: Float) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(2.dp).background(Bw.G2)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(2.dp).background(Bw.White))
    }
}

/* ================= SEMAINE — objectifs par horizon =================
   Objectifs ou casquettes avec tâches régulières hors planning, petites étapes.
   Horizons : semaine / mois / année / long terme. Clé synchronisée : goals2. */

private val HORIZONS = listOf(
    "week" to "CETTE SEMAINE", "month" to "CE MOIS",
    "year" to "CETTE ANNÉE", "life" to "LONG TERME"
)

private fun loadGoals2(): List<JSONObject> = try {
    val arr = org.json.JSONArray(Store.rawString("goals2") ?: "[]")
    (0 until arr.length()).map { arr.getJSONObject(it) }
} catch (e: Exception) { emptyList() }

private fun mutateGoals2(fn: (MutableList<JSONObject>) -> Unit) {
    val list = loadGoals2().toMutableList()
    fn(list)
    val arr = org.json.JSONArray()
    list.forEach { arr.put(it) }
    Store.putRaw("goals2", arr.toString())
}

@Composable
fun WeekScreen(refresh: Int, onChange: () -> Unit) {
    var version by remember { mutableIntStateOf(0) }
    val objectives = remember(refresh, version) { loadGoals2() }
    var newName by remember { mutableStateOf("") }
    var newHorizon by remember { mutableStateOf("week") }
    var stepFor by remember { mutableStateOf<Long?>(null) }
    var stepText by remember { mutableStateOf("") }

    fun bump() { version++; onChange() }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Label("Objectifs — hors planning", modifier = Modifier.padding(20.dp))
            Row(
                Modifier.padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = newName, onValueChange = { newName = it },
                    label = { Text("OBJECTIF / CASQUETTE", fontSize = 9.sp, letterSpacing = 2.sp) },
                    modifier = Modifier.weight(1f), colors = bwFieldColors(),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
                )
                val hLabel = HORIZONS.first { it.first == newHorizon }.second
                Text(
                    hLabel, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                    color = Bw.White,
                    modifier = Modifier
                        .border(1.dp, Bw.G3)
                        .clickable {
                            val keys = HORIZONS.map { it.first }
                            newHorizon = keys[(keys.indexOf(newHorizon) + 1) % keys.size]
                        }
                        .padding(horizontal = 9.dp, vertical = 10.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .border(1.dp, Bw.G3)
                    .clickable(enabled = newName.isNotBlank()) {
                        mutateGoals2 {
                            it.add(
                                JSONObject().put("id", System.currentTimeMillis())
                                    .put("n", newName.trim()).put("h", newHorizon)
                                    .put("steps", org.json.JSONArray())
                            )
                        }
                        newName = ""; bump()
                    }
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("+ OBJECTIF", color = if (newName.isNotBlank()) Bw.White else Bw.G4,
                    fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            }
            Spacer(Modifier.height(6.dp))
        }

        HORIZONS.forEach { (hKey, hLabel) ->
            val group = objectives.filter { it.optString("h", "week") == hKey }
            if (group.isNotEmpty()) {
                item {
                    Label(hLabel, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
                }
                itemsIndexed(group) { _, obj ->
                    val objId = obj.optLong("id")
                    val steps = obj.optJSONArray("steps") ?: org.json.JSONArray()
                    val total = steps.length()
                    val doneCount = (0 until total).count { steps.getJSONObject(it).optBoolean("done") }
                    Column(
                        Modifier
                            .padding(horizontal = 20.dp, vertical = 6.dp)
                            .border(1.dp, Bw.G2)
                            .padding(12.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                obj.optString("n").uppercase(),
                                color = Bw.White, fontSize = 13.sp,
                                fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                                modifier = Modifier.weight(1f)
                            )
                            if (total > 0) {
                                Text("$doneCount / $total", color = Bw.G5, fontSize = 11.sp)
                                Spacer(Modifier.width(10.dp))
                            }
                            Text(
                                "✕", color = Bw.G4, fontSize = 12.sp,
                                modifier = Modifier
                                    .border(1.dp, Bw.G3)
                                    .clickable {
                                        mutateGoals2 { l -> l.removeAll { it.optLong("id") == objId } }
                                        bump()
                                    }
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                        if (total > 0) {
                            Spacer(Modifier.height(8.dp))
                            Box(Modifier.fillMaxWidth().height(2.dp).background(Bw.G2)) {
                                Box(
                                    Modifier
                                        .fillMaxWidth((doneCount.toFloat() / total).coerceIn(0f, 1f))
                                        .height(2.dp).background(Bw.White)
                                )
                            }
                        }
                        for (si in 0 until total) {
                            val step = steps.getJSONObject(si)
                            val done = step.optBoolean("done")
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        mutateGoals2 { l ->
                                            l.find { it.optLong("id") == objId }
                                                ?.optJSONArray("steps")
                                                ?.getJSONObject(si)
                                                ?.put("done", !done)
                                        }
                                        bump()
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier
                                        .size(15.dp)
                                        .then(
                                            if (done) Modifier.background(Bw.White)
                                            else Modifier.border(1.dp, Bw.G4)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (done) Text("✕", color = Bw.Black, fontSize = 8.sp, fontWeight = FontWeight.Black)
                                }
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    step.optString("t"),
                                    color = if (done) Bw.G4 else Bw.G5, fontSize = 13.sp,
                                    textDecoration = if (done) TextDecoration.LineThrough else null,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    "✕", color = Bw.G3, fontSize = 11.sp,
                                    modifier = Modifier
                                        .clickable {
                                            mutateGoals2 { l ->
                                                val o = l.find { it.optLong("id") == objId } ?: return@mutateGoals2
                                                val old = o.optJSONArray("steps") ?: return@mutateGoals2
                                                val kept = org.json.JSONArray()
                                                for (j in 0 until old.length()) if (j != si) kept.put(old.getJSONObject(j))
                                                o.put("steps", kept)
                                            }
                                            bump()
                                        }
                                        .padding(6.dp)
                                )
                            }
                        }
                        if (stepFor == objId) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = stepText, onValueChange = { stepText = it },
                                    label = { Text("ÉTAPE", fontSize = 9.sp, letterSpacing = 2.sp) },
                                    modifier = Modifier.weight(1f), colors = bwFieldColors(),
                                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
                                )
                                Text(
                                    "OK", color = Bw.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .border(1.dp, Bw.White)
                                        .clickable(enabled = stepText.isNotBlank()) {
                                            mutateGoals2 { l ->
                                                l.find { it.optLong("id") == objId }
                                                    ?.optJSONArray("steps")
                                                    ?.put(JSONObject().put("t", stepText.trim()).put("done", false))
                                            }
                                            stepText = ""; stepFor = null; bump()
                                        }
                                        .padding(horizontal = 10.dp, vertical = 10.dp)
                                )
                            }
                        } else {
                            Text(
                                "+ ÉTAPE",
                                color = Bw.G4, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                                modifier = Modifier
                                    .clickable { stepFor = objId; stepText = "" }
                                    .padding(top = 8.dp, bottom = 2.dp)
                            )
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(30.dp)) }
    }
}

@Composable
private fun Stepper(sign: String, onClick: () -> Unit) {
    Box(
        Modifier.size(width = 40.dp, height = 32.dp).border(1.dp, Bw.G3).clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(sign, color = Bw.White, fontSize = 16.sp)
    }
}

/* ================= PLAN (l'utilisateur décide tout) ================= */
private const val DAY_TODAY = "today"
private const val DAY_TOMORROW = "tomorrow"
private val DAY_TABS = listOf(
    DAY_TODAY to "AUJ.",
    DAY_TOMORROW to "DEMAIN",
    Schedule.DAY_WEEKDAY to "LUN–VEN",
    Schedule.DAY_SATURDAY to "SAM",
    Schedule.DAY_SUNDAY to "DIM"
)

@Composable
fun PlanScreen(onChange: () -> Unit) {
    var day by remember { mutableStateOf(DAY_TOMORROW) }
    var blocks by remember(day) {
        mutableStateOf(
            when (day) {
                DAY_TODAY -> Schedule.today()
                DAY_TOMORROW -> Schedule.dayPlan(Store.dateKey(1))
                else -> Schedule.blocksFor(day)
            }
        )
    }
    var goals by remember { mutableStateOf(Schedule.goals) }

    fun saveBlocks(list: List<Block>) {
        blocks = list
        when (day) {
            DAY_TODAY -> Schedule.saveDayPlan(Store.dateKey(0), list)
            DAY_TOMORROW -> Schedule.saveDayPlan(Store.dateKey(1), list)
            else -> Schedule.saveBlocks(day, list)
        }
        onChange()
    }

    fun saveGoals(list: List<Goal>) {
        goals = list
        Schedule.saveGoals(list)
        onChange()
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Label("Plan — tes blocs, tes règles", modifier = Modifier.padding(20.dp))
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DAY_TABS.forEach { (key, label) ->
                    val active = day == key
                    Box(
                        Modifier
                            .weight(1f)
                            .then(
                                if (active) Modifier.background(Bw.White)
                                else Modifier.border(1.dp, Bw.G3)
                            )
                            .clickable { day = key }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                            color = if (active) Bw.Black else Bw.G5
                        )
                    }
                }
            }
            if (day == DAY_TODAY) {
                Text(
                    "Tu réorganises la journée en cours. Un bloc NN ne peut pas être retiré ni dégradé.",
                    color = Bw.G4, fontSize = 12.sp, lineHeight = 19.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                )
            }
            if (day == DAY_TOMORROW) {
                Text(
                    "Routine du soir : planifie demain (${Store.dateKey(1)}). À minuit, ce plan devient ta journée — il prime sur le plan type.",
                    color = Bw.G4, fontSize = 12.sp, lineHeight = 19.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                )
                if (blocks.isEmpty()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .border(1.dp, Bw.G3)
                            .clickable { saveBlocks(Schedule.blocksFor(Schedule.dayNameFor(1))) }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "PARTIR DU PLAN TYPE DE DEMAIN",
                            color = Bw.G5, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        itemsIndexed(blocks) { i, b ->
            Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp).border(1.dp, Bw.G2).padding(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = b.time,
                        onValueChange = { saveBlocks(blocks.toMutableList().also { l -> l[i] = b.copy(time = it) }) },
                        label = { Text("HEURES", fontSize = 9.sp, letterSpacing = 2.sp) },
                        modifier = Modifier.width(140.dp), colors = bwFieldColors(),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
                    )
                    OutlinedTextField(
                        value = b.name,
                        onValueChange = { saveBlocks(blocks.toMutableList().also { l -> l[i] = b.copy(name = it) }) },
                        label = { Text("BLOC", fontSize = 9.sp, letterSpacing = 2.sp) },
                        modifier = Modifier.weight(1f), colors = bwFieldColors(),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = b.hat,
                        onValueChange = { saveBlocks(blocks.toMutableList().also { l -> l[i] = b.copy(hat = it) }) },
                        label = { Text("CASQUETTE", fontSize = 9.sp, letterSpacing = 2.sp) },
                        modifier = Modifier.weight(1f), colors = bwFieldColors(),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
                    )
                    val nnLocked = day == DAY_TODAY && b.nn
                    Text(
                        "NN", fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                        color = if (b.nn) Bw.Black else Bw.G4,
                        modifier = Modifier
                            .then(
                                if (b.nn) Modifier.background(Bw.White)
                                else Modifier.border(1.dp, Bw.G3)
                            )
                            .clickable(enabled = !nnLocked) {
                                saveBlocks(blocks.toMutableList().also { l -> l[i] = b.copy(nn = !b.nn) })
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                    if (!nnLocked) {
                        Text(
                            "✕", fontSize = 13.sp, color = Bw.G4,
                            modifier = Modifier
                                .border(1.dp, Bw.G3)
                                .clickable { saveBlocks(blocks.toMutableList().also { l -> l.removeAt(i) }) }
                                .padding(horizontal = 11.dp, vertical = 7.dp)
                        )
                    }
                }
            }
        }
        item {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp)
                    .border(1.dp, Bw.G3)
                    .clickable { saveBlocks(blocks + Block("09h00–10h00", "", "perso")) }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("+ BLOC", color = Bw.G5, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            }
        }
        item {
            Spacer(Modifier.height(10.dp))
            Divider()
            Label("Quotas de la semaine", modifier = Modifier.padding(20.dp))
        }
        itemsIndexed(goals) { i, g ->
            Row(
                Modifier.padding(horizontal = 20.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = g.name,
                    onValueChange = { saveGoals(goals.toMutableList().also { l -> l[i] = g.copy(name = it) }) },
                    label = { Text("OBJECTIF", fontSize = 9.sp, letterSpacing = 2.sp) },
                    modifier = Modifier.weight(1f), colors = bwFieldColors(),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
                )
                OutlinedTextField(
                    value = if (g.quota == 0) "" else g.quota.toString(),
                    onValueChange = { v ->
                        saveGoals(goals.toMutableList().also { l -> l[i] = g.copy(quota = v.toIntOrNull() ?: 0) })
                    },
                    label = { Text("QUOTA", fontSize = 9.sp, letterSpacing = 2.sp) },
                    modifier = Modifier.width(80.dp), colors = bwFieldColors(),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
                )
                OutlinedTextField(
                    value = g.unit,
                    onValueChange = { saveGoals(goals.toMutableList().also { l -> l[i] = g.copy(unit = it) }) },
                    label = { Text("UNITÉ", fontSize = 9.sp, letterSpacing = 2.sp) },
                    modifier = Modifier.width(90.dp), colors = bwFieldColors(),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
                )
                Text(
                    "✕", fontSize = 13.sp, color = Bw.G4,
                    modifier = Modifier
                        .border(1.dp, Bw.G3)
                        .clickable { saveGoals(goals.toMutableList().also { l -> l.removeAt(i) }) }
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                )
            }
        }
        item {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp)
                    .border(1.dp, Bw.G3)
                    .clickable { saveGoals(goals + Goal("", 1, "x")) }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("+ OBJECTIF", color = Bw.G5, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

/* ================= POURQUOI ================= */
@Composable
fun WhyScreen(refresh: Int, onChange: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var addingText by remember { mutableStateOf(false) }
    val items = remember(refresh) { Store.whyItems() }

    val pickMedia = { type: String ->
        { uri: Uri? ->
            if (uri != null) {
                ctx.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                Store.addWhy(JSONObject().put("type", type).put("uri", uri.toString()))
                onChange()
            }
        }
    }
    val imgLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(), pickMedia("img")
    )
    val vidLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(), pickMedia("vid")
    )

    Column(Modifier.fillMaxSize()) {
        Label("Pourquoi — arsenal de motivation", modifier = Modifier.padding(20.dp))
        LazyColumn(Modifier.weight(1f)) {
            if (items.isEmpty()) {
                item {
                    Text(
                        "Vide. Ajoute ce qui te rappelle pourquoi tu te lèves à 6h15.\nTon futur toi en focus le verra.",
                        color = Bw.G4, fontSize = 13.sp, lineHeight = 22.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(34.dp)
                    )
                }
            }
            itemsIndexed(items) { i, it ->
                WhyItemView(it, onDelete = { Store.removeWhy(i); onChange() })
            }
        }
        if (addingText) {
            var text by remember { mutableStateOf("") }
            Column(Modifier.padding(20.dp)) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Bw.White, unfocusedTextColor = Bw.White,
                        focusedBorderColor = Bw.White, unfocusedBorderColor = Bw.G3,
                        cursorColor = Bw.White,
                        focusedContainerColor = Bw.G1, unfocusedContainerColor = Bw.G1
                    )
                )
                Spacer(Modifier.height(10.dp))
                BwButton("Ajouter", enabled = text.isNotBlank()) {
                    Store.addWhy(JSONObject().put("type", "txt").put("text", text.trim()))
                    addingText = false
                    onChange()
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SmallGhost("+ TEXTE", Modifier.weight(1f)) { addingText = !addingText }
            SmallGhost("+ IMAGE", Modifier.weight(1f)) { imgLauncher.launch(arrayOf("image/*")) }
            SmallGhost("+ VIDÉO", Modifier.weight(1f)) { vidLauncher.launch(arrayOf("video/*")) }
        }
    }
}

@Composable
private fun SmallGhost(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.border(1.dp, Bw.G3).clickable { onClick() }.padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Bw.G5, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
    }
}

/* ================= STREAK ================= */
@Composable
fun StreakScreen(refresh: Int, onChange: () -> Unit) {
    val checks = remember(refresh) { Store.checks() }
    val blocks = Schedule.today()
    val nnOk = blocks.isNotEmpty() && blocks.withIndex().all { (i, b) -> !b.nn || i in checks }
    val validated = remember(refresh) { Store.isValidated() }

    Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(40.dp))
        Text(
            "${Store.streak}", color = Bw.White,
            fontSize = 100.sp, fontWeight = FontWeight.Black
        )
        Label("Jours complets consécutifs")
        Spacer(Modifier.height(30.dp))
        BwButton(
            when {
                validated -> "Journée validée"
                nnOk -> "Valider la journée"
                else -> "Blocs NN incomplets"
            },
            enabled = nnOk && !validated
        ) {
            Store.validateDay()
            onChange()
        }
        Spacer(Modifier.height(24.dp))
        Column(Modifier.fillMaxWidth().border(1.dp, Bw.G2).padding(18.dp)) {
            Text(
                "JOUR COMPLET = tous les blocs NN cochés.\n" +
                    "JOUR RATÉ = streak à zéro, +15 min de renfo le lendemain (cumul max +45).\n" +
                    "Vérification automatique chaque matin : NN d'hier non cochés = streak à 0.\n" +
                    "Reporter un bloc NN à demain = streak à 0 aussi.\n" +
                    "Pas d'excuse. Pas de négociation.",
                color = Bw.G5, fontSize = 13.sp, lineHeight = 23.sp
            )
        }
    }
}

/* ================= PERMISSIONS ================= */
@Composable
fun PermissionsSheet(onDone: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(
        Modifier.fillMaxSize().background(Bw.Black).padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "AUTORISATIONS DE VERROUILLAGE",
            color = Bw.White, fontSize = 18.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Pour bloquer le téléphone, LifeOS doit :\n\n" +
                "1. Voir quelle app est au premier plan (accès aux données d'utilisation)\n" +
                "2. S'afficher par-dessus les autres apps",
            color = Bw.G5, fontSize = 14.sp, lineHeight = 23.sp
        )
        Spacer(Modifier.height(26.dp))
        BwButton(
            if (hasUsageAccess(ctx)) "1. Accès utilisation — OK" else "1. Accès utilisation",
            ghost = hasUsageAccess(ctx)
        ) {
            ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        Spacer(Modifier.height(12.dp))
        BwButton(
            if (canOverlay(ctx)) "2. Superposition — OK" else "2. Superposition",
            ghost = canOverlay(ctx)
        ) {
            ctx.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${ctx.packageName}")
                )
            )
        }
        Spacer(Modifier.height(12.dp))
        BwButton("Fermer", ghost = true) { onDone() }
    }
}

/* ================= AUTH ================= */
@Composable
fun AuthScreen(onLogged: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun submit(signup: Boolean) {
        busy = true; error = null; notice = null
        scope.launch {
            try {
                val session = withContext(Dispatchers.IO) {
                    if (signup) Supa.signUp(email.trim(), pass) else Supa.signIn(email.trim(), pass)
                }
                if (session == null) {
                    error = "Compte créé — confirme ton email puis connecte-toi."
                } else {
                    Store.saveSession(session)
                    withContext(Dispatchers.IO) { Supa.tryPull() }
                    onLogged()
                }
            } catch (e: Exception) {
                error = e.message ?: "Erreur réseau"
            } finally {
                busy = false
            }
        }
    }

    /** Envoie l'email de réinitialisation ; le nouveau mot de passe se choisit via le lien reçu. */
    fun forgot() {
        if (email.isBlank()) { error = "Entre ton email d'abord."; return }
        busy = true; error = null; notice = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { Supa.recover(email.trim()) }
                notice = "Email envoyé à ${email.trim()}. Ouvre le lien pour choisir un nouveau " +
                    "mot de passe, puis reviens te connecter ici."
            } catch (e: Exception) {
                error = e.message ?: "Erreur réseau"
            } finally {
                busy = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().background(Bw.Black).padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("LIFEOS", color = Bw.White, fontSize = 26.sp, fontWeight = FontWeight.Black, letterSpacing = 8.sp)
        Spacer(Modifier.height(6.dp))
        Label("Ton système. Partout.")
        Spacer(Modifier.height(30.dp))
        val fieldColors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Bw.White, unfocusedTextColor = Bw.White,
            focusedBorderColor = Bw.White, unfocusedBorderColor = Bw.G3,
            cursorColor = Bw.White,
            focusedContainerColor = Bw.G1, unfocusedContainerColor = Bw.G1,
            focusedLabelColor = Bw.G5, unfocusedLabelColor = Bw.G4
        )
        OutlinedTextField(
            value = email, onValueChange = { email = it },
            label = { Text("EMAIL", fontSize = 10.sp, letterSpacing = 2.sp) },
            modifier = Modifier.fillMaxWidth(), colors = fieldColors
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = pass, onValueChange = { pass = it },
            label = { Text("MOT DE PASSE", fontSize = 10.sp, letterSpacing = 2.sp) },
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(), colors = fieldColors
        )
        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Text(error!!, color = Bw.G5, fontSize = 12.sp, lineHeight = 18.sp)
        }
        if (notice != null) {
            Spacer(Modifier.height(12.dp))
            Text(notice!!, color = Bw.White, fontSize = 12.sp, lineHeight = 18.sp)
        }
        Spacer(Modifier.height(22.dp))
        BwButton(if (busy) "…" else "Se connecter", enabled = !busy && email.isNotBlank() && pass.length >= 6) {
            submit(signup = false)
        }
        Spacer(Modifier.height(10.dp))
        BwButton("Créer un compte", ghost = true, enabled = !busy && email.isNotBlank() && pass.length >= 6) {
            submit(signup = true)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Mot de passe oublié ?",
            color = Bw.G5, fontSize = 12.sp, textAlign = TextAlign.Center,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !busy) { forgot() }
                .padding(vertical = 6.dp)
        )
    }
}

/* ================= APPS (liste blanche du focus) ================= */
@Composable
fun AppsScreen() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val apps = remember {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .filter { it.first != ctx.packageName }
            .sortedBy { it.second.lowercase() }
    }
    val whitelist = remember(refresh) { Store.whitelist() }

    Column(Modifier.fillMaxSize()) {
        Label("Apps autorisées pendant le focus", modifier = Modifier.padding(20.dp))
        Text(
            "Coche ce qui doit rester accessible même verrouillé. Chaque coche est une porte de sortie — choisis peu.",
            color = Bw.G4, fontSize = 12.sp, lineHeight = 19.sp,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(apps) { _, (pkg, label) ->
                val allowed = pkg in whitelist
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            Store.toggleWhitelist(pkg)
                            refresh++
                        }
                        .padding(horizontal = 20.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(20.dp)
                            .then(
                                if (allowed) Modifier.background(Bw.White)
                                else Modifier.border(1.dp, Bw.G4)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (allowed) Text("✕", color = Bw.Black, fontSize = 12.sp, fontWeight = FontWeight.Black)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            label, color = if (allowed) Bw.White else Bw.G5,
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                        )
                        Text(pkg, color = Bw.G4, fontSize = 10.sp)
                    }
                    if (allowed) {
                        Text(
                            "LIBRE", color = Bw.White, fontSize = 9.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                            modifier = Modifier.border(1.dp, Bw.White).padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
                Divider()
            }
        }
    }
}

/* ================= NAV ================= */
@Composable
private fun BottomNav(current: Tab, onSelect: (Tab) -> Unit) {
    Divider()
    Row(Modifier.fillMaxWidth().background(Bw.Black)) {
        Tab.entries.forEach { t ->
            Box(
                Modifier
                    .weight(1f)
                    .clickable { onSelect(t) }
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    t.title.uppercase(),
                    color = if (t == current) Bw.White else Bw.G4,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
            }
        }
    }
}
