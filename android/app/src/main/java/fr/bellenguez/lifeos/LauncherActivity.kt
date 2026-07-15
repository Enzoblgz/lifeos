package fr.bellenguez.lifeos

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * LifeOS Launcher — l'écran d'accueil est le cockpit :
 * heure, bloc en cours, blocs du jour cochables, agenda, apps, dossiers.
 * Balayage vers la gauche : notifications.
 */
class LauncherActivity : ComponentActivity() {

    companion object {
        /** Incrémenté à chaque appui home : l'UI referme sections et overlays. */
        val homeSignal = androidx.compose.runtime.mutableIntStateOf(0)
        /** Incrémenté quand une app est installée / désinstallée / mise à jour. */
        val packagesVersion = androidx.compose.runtime.mutableIntStateOf(0)
        private const val HOST_ID = 1024
        private const val REQ_BIND = 71
        private const val REQ_CONFIG = 72
    }

    lateinit var widgetHost: android.appwidget.AppWidgetHost
    lateinit var awm: android.appwidget.AppWidgetManager
    val widgetsVersion = androidx.compose.runtime.mutableIntStateOf(0)
    private var pendingWidgetId = -1

    /** La liste des apps suit le système : installée = visible, désinstallée = oubliée partout. */
    private val pkgReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val pkg = i?.data?.schemeSpecificPart
            val replacing = i?.getBooleanExtra(Intent.EXTRA_REPLACING, false) ?: false
            if (i?.action == Intent.ACTION_PACKAGE_REMOVED && pkg != null && !replacing) {
                Store.forgetApp(pkg)
            }
            packagesVersion.intValue++
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        NoteStore.purgeExpired(this)     // corbeille : au-delà de 30 jours, c'est fini
        NoteStore.sweepOrphanMedia(this) // médias des notes détruites depuis le web
        widgetHost = android.appwidget.AppWidgetHost(this, HOST_ID)
        awm = android.appwidget.AppWidgetManager.getInstance(this)
        registerReceiver(
            pkgReceiver,
            android.content.IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
            }
        )
        // migration : l'app Anki épinglée est remplacée par un vrai widget
        if (Store.rawString("anki_widget_migration") == null) {
            Store.removePinnedApp("com.ichi2.anki")
            Store.putRaw("anki_widget_migration", "1")
        }
        // migration : ancien emplacement Anki unique → grille de widgets
        Store.rawString("anki_widget_id")?.toIntOrNull()?.takeIf { it > 0 }?.let { old ->
            if (Store.widgets().isEmpty()) {
                Store.saveWidgets(
                    listOf(JSONObject().put("id", old).put("c", 0).put("r", 0).put("w", 4).put("h", 2))
                )
            }
            Store.putRaw("anki_widget_id", "-1")
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { homeSignal.intValue++ }
        })
        setContent { LauncherScreen() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        homeSignal.intValue++ // geste home → retour à l'accueil nu
    }

    override fun onDestroy() {
        try { unregisterReceiver(pkgReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // retour d'un écran système (désinstallation…) : la liste d'apps peut avoir bougé
        packagesVersion.intValue++
        if (Store.focusActive()) startActivity(Intent(this, BlockerActivity::class.java))
    }

    override fun onStart() {
        super.onStart()
        try { widgetHost.startListening() } catch (_: Exception) {}
    }

    override fun onStop() {
        try { widgetHost.stopListening() } catch (_: Exception) {}
        super.onStop()
    }

    /* ----- Widgets : liaison / placement sur la grille 4×3 ----- */

    fun addWidget(info: android.appwidget.AppWidgetProviderInfo) {
        val id = widgetHost.allocateAppWidgetId()
        pendingWidgetId = id
        if (awm.bindAppWidgetIdIfAllowed(id, info.provider)) {
            configureOrCommit(id)
        } else {
            @Suppress("DEPRECATION")
            startActivityForResult(
                Intent(android.appwidget.AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider),
                REQ_BIND
            )
        }
    }

    private fun configureOrCommit(id: Int) {
        val info = awm.getAppWidgetInfo(id)
        if (info?.configure != null) {
            try {
                widgetHost.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONFIG, null)
            } catch (e: Exception) { commitWidget(id) }
        } else commitWidget(id)
    }

    private fun overlaps(a: JSONObject, c: Int, r: Int, w: Int, h: Int): Boolean {
        val ac = a.optInt("c"); val ar = a.optInt("r")
        val aw = a.optInt("w", 1); val ah = a.optInt("h", 1)
        return ac < c + w && c < ac + aw && ar < r + h && r < ar + ah
    }

    private fun commitWidget(id: Int) {
        val info = awm.getAppWidgetInfo(id)
        val d = resources.displayMetrics.density
        val wCells = ((((info?.minWidth ?: 0) / d) / 90f) + 0.999f).toInt().coerceIn(1, 4)
        val hCells = ((((info?.minHeight ?: 0) / d) / 92f) + 0.999f).toInt().coerceIn(1, 3)
        val list = Store.widgets().toMutableList()
        var place: Pair<Int, Int>? = null
        outer@ for (r in 0..(3 - hCells)) {
            for (c in 0..(4 - wCells)) {
                if (list.none { overlaps(it, c, r, wCells, hCells) }) { place = c to r; break@outer }
            }
        }
        val (c, r) = place ?: (0 to 0)
        list.add(JSONObject().put("id", id).put("c", c).put("r", r).put("w", wCells).put("h", hCells))
        Store.saveWidgets(list)
        widgetsVersion.intValue++
        pendingWidgetId = -1
    }

    fun removeWidget(id: Int) {
        try { widgetHost.deleteAppWidgetId(id) } catch (_: Exception) {}
        Store.saveWidgets(Store.widgets().filter { it.optInt("id") != id })
        widgetsVersion.intValue++
    }

    /** Déplace/redimensionne sur la grille, borné 4×3 (chevauchement autorisé : liberté totale). */
    fun adjustWidget(id: Int, dc: Int, dr: Int, dw: Int, dh: Int) {
        val list = Store.widgets().toMutableList()
        val o = list.find { it.optInt("id") == id } ?: return
        val w = (o.optInt("w", 1) + dw).coerceIn(1, 4)
        val h = (o.optInt("h", 1) + dh).coerceIn(1, 3)
        val c = (o.optInt("c") + dc).coerceIn(0, 4 - w)
        val r = (o.optInt("r") + dr).coerceIn(0, 3 - h)
        o.put("c", c).put("r", r).put("w", w).put("h", h)
        Store.saveWidgets(list)
        widgetsVersion.intValue++
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val id = data?.getIntExtra(
            android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId
        ) ?: pendingWidgetId
        when (requestCode) {
            REQ_BIND ->
                if (resultCode == RESULT_OK) configureOrCommit(id)
                else if (id != -1) widgetHost.deleteAppWidgetId(id)
            REQ_CONFIG ->
                if (resultCode == RESULT_OK) commitWidget(id)
                else if (id != -1) widgetHost.deleteAppWidgetId(id)
        }
    }
}

/* ----- Notifications : ouverture du volet + sourdine ----- */

@SuppressLint("WrongConstant", "PrivateApi")
private fun expandNotifications(ctx: Context) {
    try {
        val service = ctx.getSystemService("statusbar")
        Class.forName("android.app.StatusBarManager")
            .getMethod("expandNotificationsPanel")
            .invoke(service)
    } catch (_: Exception) {}
}

private fun isSilenced(ctx: Context): Boolean {
    val nm = ctx.getSystemService(NotificationManager::class.java)
    return nm.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL
}

private fun toggleSilence(ctx: Context) {
    val nm = ctx.getSystemService(NotificationManager::class.java)
    if (!nm.isNotificationPolicyAccessGranted) {
        ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        return
    }
    nm.setInterruptionFilter(
        if (isSilenced(ctx)) NotificationManager.INTERRUPTION_FILTER_ALL
        else NotificationManager.INTERRUPTION_FILTER_PRIORITY
    )
}

/* ----- Fonctions Android standard sur une app (ce qu'un launcher normal propose) ----- */

private fun newTask(i: Intent) = i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** Désinstallation : c'est le système qui demande confirmation. */
private fun uninstallApp(ctx: Context, pkg: String) {
    try {
        ctx.startActivity(newTask(Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:$pkg"))))
    } catch (e: Exception) {
        appInfo(ctx, pkg)
    }
}

/** Infos de l'app : permissions, stockage, notifications, désactivation d'une app système. */
private fun appInfo(ctx: Context, pkg: String) {
    try {
        ctx.startActivity(
            newTask(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:$pkg")
                )
            )
        )
    } catch (_: Exception) {}
}

private fun openStore(ctx: Context, pkg: String) {
    try {
        ctx.startActivity(newTask(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$pkg"))))
    } catch (e: Exception) {
        try {
            ctx.startActivity(
                newTask(
                    Intent(
                        Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://play.google.com/store/apps/details?id=$pkg")
                    )
                )
            )
        } catch (_: Exception) {}
    }
}

/** Une app système ne se désinstalle pas : on renvoie vers Infos (désactiver / désinstaller la MAJ). */
private fun isSystemApp(ctx: Context, pkg: String): Boolean = try {
    val f = ctx.packageManager.getApplicationInfo(pkg, 0).flags
    (f and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 &&
        (f and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
} catch (e: Exception) { false }

/** Raccourcis de l'app (appui long façon Android) — réservé au launcher par défaut. */
private fun shortcutsOf(ctx: Context, pkg: String): List<android.content.pm.ShortcutInfo> = try {
    val la = ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE) as android.content.pm.LauncherApps
    val q = android.content.pm.LauncherApps.ShortcutQuery()
        .setPackage(pkg)
        .setQueryFlags(
            android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
        )
    la.getShortcuts(q, android.os.Process.myUserHandle()).orEmpty()
} catch (e: Exception) { emptyList() }

private fun startShortcut(ctx: Context, s: android.content.pm.ShortcutInfo) {
    try {
        val la = ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE) as android.content.pm.LauncherApps
        la.startShortcut(s.`package`, s.id, null, null, android.os.Process.myUserHandle())
    } catch (_: Exception) {}
}

private fun shortcutLabel(s: android.content.pm.ShortcutInfo): String =
    (s.longLabel ?: s.shortLabel ?: s.id).toString()

/* Layout épinglé : [{"type":"app","pkg":"..."} | {"type":"folder","name":"...","apps":["pkg",...]}] */
private fun layout(): JSONArray = try { JSONArray(Store.launcherLayout()) } catch (e: Exception) { JSONArray() }
private fun saveLayout(arr: JSONArray) = Store.saveLauncherLayout(arr.toString())

private val FR_DAYS = listOf("L", "M", "M", "J", "V", "S", "D")

private fun keyOf(cal: Calendar): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)

private fun frDate(key: String): String = try {
    val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(key)!!
    SimpleDateFormat("EEE d MMM", Locale.FRANCE).format(d).uppercase()
} catch (e: Exception) { key }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LauncherScreen() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val pm = ctx.packageManager

    val pkgVersion = LauncherActivity.packagesVersion.intValue
    val allApps = remember(pkgVersion) {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }
    val labelOf = remember(allApps) { allApps.toMap() }

    var logged by remember { mutableStateOf(!Supa.configured() || Store.session() != null) }
    if (!logged) {
        AuthScreen(onLogged = { logged = true })
        return
    }

    var tick by remember { mutableIntStateOf(0) }
    var showAll by remember { mutableStateOf(false) }
    var showAnkiPicker by remember { mutableStateOf(false) }
    var showClockPicker by remember { mutableStateOf(false) }
    var blockMenu by remember { mutableStateOf<Int?>(null) } // appui long sur un bloc du jour
    var editWidget by remember { mutableStateOf<Int?>(null) } // ✎ sur un widget
    var showPerms by remember { mutableStateOf(false) }
    var section by remember { mutableStateOf<String?>(null) } // plan / week / notes / why / streak
    var search by remember { mutableStateOf("") }
    var openFolder by remember { mutableStateOf<String?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var shortcutsFor by remember { mutableStateOf<String?>(null) } // raccourcis d'une app
    var widgetPkg by remember { mutableStateOf<String?>(null) }    // widgets d'une app seulement
    var pickFolderFor by remember { mutableStateOf<String?>(null) }
    var newFolderName by remember { mutableStateOf("") }
    var layoutVersion by remember { mutableIntStateOf(0) }
    var dataVersion by remember { mutableIntStateOf(0) }

    // geste home (ou retour) → tout refermer, accueil nu
    val homeSig = LauncherActivity.homeSignal.intValue
    LaunchedEffect(homeSig) {
        section = null; showAll = false; showAnkiPicker = false; showClockPicker = false
        menuFor = null; pickFolderFor = null; showPerms = false; blockMenu = null; editWidget = null
        shortcutsFor = null; widgetPkg = null
    }

    LaunchedEffect(Unit) { while (true) { delay(10_000); tick++ } }
    // sync : rapatrie les changements faits sur l'ordi (< 5 s)
    LaunchedEffect(Unit) {
        while (true) {
            delay(5000)
            val changed = withContext(Dispatchers.IO) { Supa.pollAndPull() }
            if (changed) dataVersion++
        }
    }

    fun pushAsync() = Thread { Supa.tryPush() }.start()

    fun launch(pkg: String) {
        pm.getLaunchIntentForPackage(pkg)?.let { ctx.startActivity(it) }
    }

    fun pinApp(pkg: String) {
        val arr = layout()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("type") == "app" && o.optString("pkg") == pkg) return
        }
        arr.put(JSONObject().put("type", "app").put("pkg", pkg))
        saveLayout(arr); layoutVersion++
    }

    fun unpin(pkg: String) {
        val arr = layout(); val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("type") == "app" && o.optString("pkg") == pkg) continue
            if (o.optString("type") == "folder") {
                val apps = o.getJSONArray("apps"); val kept = JSONArray()
                for (j in 0 until apps.length()) if (apps.getString(j) != pkg) kept.put(apps.getString(j))
                o.put("apps", kept)
            }
            out.put(o)
        }
        saveLayout(out); layoutVersion++
    }

    fun addToFolder(pkg: String, folderName: String) {
        val arr = layout()
        var folder: JSONObject? = null
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("type") == "folder" && o.optString("name") == folderName) folder = o
        }
        if (folder == null) {
            folder = JSONObject().put("type", "folder").put("name", folderName).put("apps", JSONArray())
            arr.put(folder)
        }
        val apps = folder!!.getJSONArray("apps")
        for (j in 0 until apps.length()) if (apps.getString(j) == pkg) return
        apps.put(pkg)
        saveLayout(arr); layoutVersion++
    }

    fun removeFolder(name: String) {
        val arr = layout(); val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("type") == "folder" && o.optString("name") == name) continue
            out.put(o)
        }
        saveLayout(out); layoutVersion++
        if (openFolder == name) openFolder = null
    }

    // pkgVersion : une app désinstallée disparaît aussi des épingles et des dossiers
    val pinned = remember(layoutVersion, pkgVersion) { layout() }
    val folderNames = remember(layoutVersion, pkgVersion) {
        (0 until pinned.length())
            .map { pinned.getJSONObject(it) }
            .filter { it.optString("type") == "folder" }
            .map { it.optString("name") }
    }

    // homeSig : retour de focus/certification → relecture immédiate des coches
    val blocks = remember(tick, dataVersion, homeSig) { Schedule.today() }
    val checks = remember(tick, dataVersion, homeSig) { Store.checks() }
    val target = remember(tick, dataVersion, homeSig) { Schedule.focusTarget(blocks, checks) }

    fun startFocusOn(idx: Int) {
        if (!hasUsageAccess(ctx) || !canOverlay(ctx)) {
            showPerms = true
        } else {
            Store.startFocus(idx)
            ctx.startForegroundService(Intent(ctx, FocusService::class.java))
            ctx.startActivity(Intent(ctx, BlockerActivity::class.java))
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Bw.Black)
            .pointerInput(Unit) {
                var drag = 0f
                detectHorizontalDragGestures(
                    onDragStart = { drag = 0f },
                    onHorizontalDrag = { _, amount -> drag += amount },
                    onDragEnd = { if (drag > 140f) expandNotifications(ctx) }
                )
            }
            .padding(horizontal = 28.dp)
    ) {

        /* ----- Tête : heure + streak + sourdine ----- */
        Spacer(Modifier.height(56.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            val now = remember(tick) { Date() }
            Column {
                Text(
                    SimpleDateFormat("HH:mm", Locale.FRANCE).format(now),
                    color = Bw.White, fontSize = 52.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp,
                    modifier = Modifier.combinedClickable(
                        onClick = {
                            val pkg = Store.rawString("clock_shortcut")
                            if (pkg == null) showClockPicker = true else launch(pkg)
                        },
                        onLongClick = { showClockPicker = true }
                    )
                )
                Text(
                    SimpleDateFormat("EEEE d MMMM", Locale.FRANCE).format(now).uppercase(),
                    color = Bw.G4, fontSize = 11.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Bold
                )
            }
            val streak = remember(tick, dataVersion, homeSig) { Store.streak }
            Text(
                "STREAK : $streak",
                color = Bw.White, fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp,
                modifier = Modifier
                    .border(1.dp, Bw.G4)
                    .clickable { section = "streak" }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            when {
                blocks.isEmpty() -> "RIEN DE PLANIFIÉ — PLANIFIER"
                target != null -> "› ${blocks[target].name.uppercase()}"
                else -> "JOURNÉE TERMINÉE"
            },
            color = if (target != null) Bw.White else Bw.G4,
            fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
            modifier = Modifier.clickable { section = "plan" }
        )
        if (target != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                "ENTRER EN FOCUS",
                color = Bw.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Bw.White)
                    .clickable { startFocusOn(target) }
                    .padding(vertical = 12.dp)
            )
        }
        Spacer(Modifier.height(20.dp))

        /* À venir : uniquement l'inhabituel — événements NON récurrents, strictement futurs,
           3 max, ordre chronologique. (Aujourd'hui = déjà des blocs cochables du plan.) */
        val upcoming = remember(dataVersion, tick) {
            Store.events()
                .filter { it.optString("rec", "none") == "none" && it.optString("d") > Store.dateKey(0) }
                .sortedWith(compareBy({ it.optString("d") }, { it.optString("t") }))
                .take(3)
        }

        /* ----- Blocs du jour + agenda + apps ----- */
        LazyColumn(Modifier.weight(1f)) {
            if (blocks.isNotEmpty()) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Label("Aujourd'hui")
                        Label("${checks.size} / ${blocks.size}", color = Bw.G5)
                    }
                    Box(Modifier.fillMaxWidth().height(2.dp).background(Bw.G2)) {
                        Box(
                            Modifier
                                .fillMaxWidth((checks.size.toFloat() / blocks.size).coerceIn(0f, 1f))
                                .height(2.dp)
                                .background(Bw.White)
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }
                itemsIndexed(blocks) { i, b ->
                    val done = i in checks
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {
                                    if (!Store.focusActive()) {
                                        Store.setCheck(i, !done)
                                        dataVersion++
                                        pushAsync()
                                    }
                                },
                                onLongClick = { blockMenu = i }
                            )
                            .padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(16.dp)
                                .then(
                                    if (done) Modifier.background(Bw.White)
                                    else Modifier.border(1.dp, Bw.G4)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (done) Text("✕", color = Bw.Black, fontSize = 9.sp, fontWeight = FontWeight.Black)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(b.time, color = Bw.G4, fontSize = 10.sp, modifier = Modifier.width(72.dp))
                        Text(
                            b.name.uppercase(),
                            color = if (done) Bw.G4 else Bw.White,
                            fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            textDecoration = if (done) TextDecoration.LineThrough else null,
                            modifier = Modifier.weight(1f)
                        )
                        if (b.nn) {
                            Text(
                                "NN", color = Bw.White, fontSize = 8.sp, fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                                modifier = Modifier.border(1.dp, Bw.White).padding(horizontal = 3.dp)
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(18.dp)) }
            }

            if (upcoming.isNotEmpty()) {
                item {
                    Label("À venir", modifier = Modifier.padding(bottom = 4.dp))
                }
                items(upcoming) { e ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { section = "plan" }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            frDate(e.optString("d")),
                            color = Bw.G4, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp, modifier = Modifier.width(86.dp)
                        )
                        Text(
                            e.optString("t"),
                            color = Bw.G4, fontSize = 10.sp, modifier = Modifier.width(48.dp)
                        )
                        Text(
                            e.optString("n").uppercase(),
                            color = Bw.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        if (e.optBoolean("nn")) {
                            Text(
                                "NN", color = Bw.White, fontSize = 8.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.border(1.dp, Bw.White).padding(horizontal = 3.dp)
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(18.dp)) }
            }

            /* ----- Widgets : grille libre 4×3, n'importe quelle app ----- */
            item {
                val act = ctx as LauncherActivity
                val wVersion = act.widgetsVersion.intValue
                val widgets = remember(wVersion) { Store.widgets() }
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Label("Widgets")
                    Text(
                        "+ AJOUTER",
                        color = Bw.G4, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                        modifier = Modifier
                            .border(1.dp, Bw.G3)
                            .clickable { showAnkiPicker = true }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                if (widgets.isEmpty()) {
                    Text("Aucun widget. Touche + AJOUTER.", color = Bw.G4, fontSize = 12.sp)
                } else {
                    // Compaction : on ne réserve que les lignes réellement occupées.
                    // Le bloc se cale en haut (on retire les lignes vides au-dessus) et
                    // s'arrête à la dernière ligne utilisée — pas de place gaspillée.
                    val rowStart = widgets.minOf { it.optInt("r") }
                    val rowEnd = widgets.maxOf { it.optInt("r") + it.optInt("h", 1) }
                    val usedRows = (rowEnd - rowStart).coerceAtLeast(1)
                    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val cellW = maxWidth / 4
                        val cellH = 92.dp
                        Box(Modifier.fillMaxWidth().height(cellH * usedRows)) {
                            widgets.forEach { wo ->
                                val id = wo.optInt("id")
                                val info = act.awm.getAppWidgetInfo(id) ?: return@forEach
                                Box(
                                    Modifier
                                        .offset(
                                            x = cellW * wo.optInt("c"),
                                            y = cellH * (wo.optInt("r") - rowStart)
                                        )
                                        .width(cellW * wo.optInt("w", 1))
                                        .height(cellH * wo.optInt("h", 1))
                                        .padding(3.dp)
                                ) {
                                    AndroidView(
                                        factory = { c ->
                                            act.widgetHost.createView(c.applicationContext, id, info)
                                        },
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    Text(
                                        "✎",
                                        color = Bw.G4, fontSize = 11.sp,
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .background(Bw.Black)
                                            .border(1.dp, Bw.G3)
                                            .clickable { editWidget = id }
                                            .padding(horizontal = 7.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
            }

            /* ----- Épinglés + dossiers ----- */
            items((0 until pinned.length()).map { pinned.getJSONObject(it) }) { item ->
                when (item.optString("type")) {
                    "app" -> {
                        val pkg = item.optString("pkg")
                        Text(
                            (labelOf[pkg] ?: pkg).lowercase(),
                            color = Bw.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = { launch(pkg) },
                                    onLongClick = { menuFor = pkg }
                                )
                                .padding(vertical = 9.dp)
                        )
                    }
                    "folder" -> {
                        val name = item.optString("name")
                        val open = openFolder == name
                        Text(
                            (if (open) "▾ " else "▸ ") + name.lowercase(),
                            color = Bw.G5, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = { openFolder = if (open) null else name },
                                    onLongClick = { removeFolder(name) }
                                )
                                .padding(vertical = 9.dp)
                        )
                        if (open) {
                            val apps = item.getJSONArray("apps")
                            for (j in 0 until apps.length()) {
                                val pkg = apps.getString(j)
                                Text(
                                    (labelOf[pkg] ?: pkg).lowercase(),
                                    color = Bw.White, fontSize = 19.sp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            onClick = { launch(pkg) },
                                            onLongClick = { menuFor = pkg }
                                        )
                                        .padding(start = 26.dp, top = 7.dp, bottom = 7.dp)
                                )
                            }
                        }
                    }
                }
            }
            if (pinned.length() == 0) {
                item {
                    Text(
                        "APPS → appui long pour épingler une app ici.\nCAL pour noter un rendez-vous. Balaye à gauche : notifications.",
                        color = Bw.G4, fontSize = 13.sp, lineHeight = 21.sp
                    )
                }
            }
        }

        /* ----- Pied : toutes les sections, directement depuis l'accueil ----- */
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(
                "PLAN" to { section = "plan" },
                "SEM." to { section = "week" },
                "NOTES" to { section = "notes" },
                "PQ?" to { section = "why" },
                "APPS" to { showAll = true; search = "" },
                "STREAK" to { section = "streak" },
            ).forEach { (label, act) ->
                Text(
                    label,
                    color = Bw.G5, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { act() }
                        .padding(vertical = 18.dp)
                )
            }
        }
        Spacer(Modifier.height(10.dp))
    }

    /* ----- Sections plein écran (ex-app, désormais dans le launcher) ----- */
    section?.let { s ->
        Column(Modifier.fillMaxSize().background(Bw.Black)) {
            Spacer(Modifier.height(34.dp))
            Box(Modifier.weight(1f)) {
                val onChange: () -> Unit = { dataVersion++; pushAsync() }
                when (s) {
                    "plan" -> PlanCalScreen(onChange = onChange)
                    "week" -> WeekScreen(dataVersion, onChange = onChange)
                    "notes" -> NotesScreen(onChange = onChange)
                    "why" -> WhyScreen(dataVersion, onChange = onChange)
                    "streak" -> StreakScreen(dataVersion, onChange = onChange)
                }
            }
            Text(
                "FERMER",
                color = Bw.G4, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { section = null }
                    .padding(vertical = 14.dp)
            )
        }
    }

    if (showPerms) {
        PermissionsSheet(onDone = { showPerms = false })
    }

    /* ----- Menu bloc du jour (appui long) ----- */
    blockMenu?.let { i ->
        val b = blocks.getOrNull(i)
        if (b == null) { blockMenu = null } else {
            val done = i in checks
            Column(
                Modifier.fillMaxSize().background(Bw.Black).padding(28.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Label(b.time)
                Text(
                    b.name.uppercase(),
                    color = Bw.White, fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp
                )
                Spacer(Modifier.height(24.dp))
                if (!done) {
                    BwButton("Entrer en focus dessus") { blockMenu = null; startFocusOn(i) }
                    Spacer(Modifier.height(10.dp))
                }
                BwButton(if (done) "Décocher" else "Cocher", ghost = true) {
                    if (!Store.focusActive()) {
                        Store.setCheck(i, !done)
                        dataVersion++; pushAsync()
                    }
                    blockMenu = null
                }
                Spacer(Modifier.height(10.dp))
                BwButton("Éditer dans le plan", ghost = true) { blockMenu = null; section = "plan" }
                Spacer(Modifier.height(10.dp))
                BwButton("Annuler", ghost = true) { blockMenu = null }
            }
        }
    }

    /* ----- Overlay : raccourci de l'horloge ----- */
    if (showClockPicker) {
        Column(Modifier.fillMaxSize().background(Bw.Black).padding(24.dp)) {
            Spacer(Modifier.height(30.dp))
            Label("Raccourci de l'horloge — quelle app ouvrir ?", color = Bw.White)
            Spacer(Modifier.height(10.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(allApps) { (pkg, label) ->
                    Text(
                        label.lowercase(),
                        color = Bw.G5, fontSize = 17.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                Store.putRaw("clock_shortcut", pkg)
                                showClockPicker = false
                            }
                            .padding(vertical = 9.dp)
                    )
                }
            }
            BwButton("Fermer", ghost = true) { showClockPicker = false }
            Spacer(Modifier.height(14.dp))
        }
    }

    /* ----- Overlay : choix d'un widget (toutes les apps, ou une seule via le menu app) ----- */
    if (showAnkiPicker) {
        val act = ctx as LauncherActivity
        val filter = widgetPkg
        val providers = remember(filter) {
            act.awm.installedProviders
                .filter { filter == null || it.provider.packageName == filter }
                .sortedBy { it.loadLabel(pm).lowercase() }
        }
        Column(Modifier.fillMaxSize().background(Bw.Black).padding(24.dp)) {
            Spacer(Modifier.height(30.dp))
            Label(
                if (filter == null) "Choisir un widget"
                else "Widgets — ${(labelOf[filter] ?: filter).lowercase()}",
                color = Bw.White
            )
            Spacer(Modifier.height(10.dp))
            if (providers.isEmpty()) {
                Text("Cette app ne fournit aucun widget.", color = Bw.G4, fontSize = 13.sp)
            }
            LazyColumn(Modifier.weight(1f)) {
                items(providers) { info ->
                    Text(
                        info.loadLabel(pm).lowercase() + "  ·  " + info.provider.packageName.substringAfterLast('.'),
                        color = Bw.G5, fontSize = 16.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                act.addWidget(info); showAnkiPicker = false; widgetPkg = null
                            }
                            .padding(vertical = 10.dp)
                    )
                }
            }
            BwButton("Fermer") { showAnkiPicker = false; widgetPkg = null }
            Spacer(Modifier.height(14.dp))
        }
    }

    /* ----- Overlay : déplacer / redimensionner / retirer un widget ----- */
    editWidget?.let { wid ->
        val act = ctx as LauncherActivity
        act.widgetsVersion.intValue // recomposition à chaque ajustement
        val label = act.awm.getAppWidgetInfo(wid)?.loadLabel(pm)?.lowercase() ?: "widget"
        Column(
            Modifier.fillMaxSize().background(Bw.Black).padding(28.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Label("Ajuster — $label", color = Bw.White)
            Spacer(Modifier.height(20.dp))
            Label("Déplacer")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "←" to intArrayOf(-1, 0), "→" to intArrayOf(1, 0),
                    "↑" to intArrayOf(0, -1), "↓" to intArrayOf(0, 1)
                ).forEach { (s, d) ->
                    Text(
                        s, color = Bw.White, fontSize = 18.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .weight(1f)
                            .border(1.dp, Bw.G3)
                            .clickable { act.adjustWidget(wid, d[0], d[1], 0, 0) }
                            .padding(vertical = 12.dp)
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Label("Taille (largeur · hauteur)")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "L−" to intArrayOf(-1, 0), "L+" to intArrayOf(1, 0),
                    "H−" to intArrayOf(0, -1), "H+" to intArrayOf(0, 1)
                ).forEach { (s, d) ->
                    Text(
                        s, color = Bw.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .weight(1f)
                            .border(1.dp, Bw.G3)
                            .clickable { act.adjustWidget(wid, 0, 0, d[0], d[1]) }
                            .padding(vertical = 13.dp)
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            BwButton("Retirer le widget", ghost = true) {
                act.removeWidget(wid); editWidget = null
            }
            Spacer(Modifier.height(10.dp))
            BwButton("Terminé") { editWidget = null }
        }
    }

    /* ----- Overlay : toutes les apps ----- */
    if (showAll) {
        Column(Modifier.fillMaxSize().background(Bw.Black).padding(20.dp)) {
            OutlinedTextField(
                value = search, onValueChange = { search = it },
                label = { Text("CHERCHER", fontSize = 10.sp, letterSpacing = 2.sp) },
                modifier = Modifier.fillMaxWidth(), colors = bwFieldColors(),
                textStyle = TextStyle(fontSize = 15.sp)
            )
            Spacer(Modifier.height(6.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(allApps.filter { search.isBlank() || it.second.contains(search, ignoreCase = true) }) { (pkg, label) ->
                    Text(
                        label.lowercase(),
                        color = Bw.White, fontSize = 19.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { launch(pkg); showAll = false },
                                onLongClick = { menuFor = pkg }
                            )
                            .padding(vertical = 9.dp)
                    )
                }
            }
            Text(
                "FERMER",
                color = Bw.G4, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showAll = false }
                    .padding(vertical = 14.dp)
            )
        }
    }

    /* ----- Menu app (appui long) : LifeOS + tout ce qu'Android sait faire sur une app ----- */
    menuFor?.let { pkg ->
        val shortcuts = remember(pkg) { shortcutsOf(ctx, pkg) }
        val system = remember(pkg) { isSystemApp(ctx, pkg) }
        Column(
            Modifier
                .fillMaxSize()
                .background(Bw.Black)
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(28.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(Modifier.height(40.dp))
            Text(
                (labelOf[pkg] ?: pkg).uppercase(),
                color = Bw.White, fontSize = 20.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp
            )
            Text(pkg, color = Bw.G4, fontSize = 11.sp)
            Spacer(Modifier.height(20.dp))

            Label("LifeOS")
            Spacer(Modifier.height(8.dp))
            val inWhitelist = pkg in Store.whitelist()
            BwButton(
                if (inWhitelist) "Autorisée en focus — retirer" else "Autoriser pendant le focus",
                ghost = true
            ) { Store.toggleWhitelist(pkg); menuFor = null }
            Spacer(Modifier.height(10.dp))
            BwButton("Épingler à l'accueil", ghost = true) { pinApp(pkg); menuFor = null }
            Spacer(Modifier.height(10.dp))
            BwButton("Mettre dans un dossier", ghost = true) { pickFolderFor = pkg; menuFor = null }
            Spacer(Modifier.height(10.dp))
            BwButton("Retirer de l'accueil", ghost = true) { unpin(pkg); menuFor = null }

            Spacer(Modifier.height(22.dp))
            Label("Android")
            Spacer(Modifier.height(8.dp))
            if (shortcuts.isNotEmpty()) {
                BwButton("Raccourcis de l'app · ${shortcuts.size}", ghost = true) {
                    shortcutsFor = pkg; menuFor = null
                }
                Spacer(Modifier.height(10.dp))
            }
            BwButton("Widgets de cette app", ghost = true) {
                widgetPkg = pkg; showAnkiPicker = true; menuFor = null
            }
            Spacer(Modifier.height(10.dp))
            BwButton("Infos de l'app", ghost = true) { appInfo(ctx, pkg); menuFor = null }
            Spacer(Modifier.height(10.dp))
            BwButton("Voir dans le store", ghost = true) { openStore(ctx, pkg); menuFor = null }
            Spacer(Modifier.height(10.dp))
            if (system) {
                Text(
                    "App système : Android interdit la désinstallation. Passe par Infos de l'app " +
                        "pour la désactiver.",
                    color = Bw.G4, fontSize = 11.sp, lineHeight = 18.sp
                )
            } else {
                BwButton("Désinstaller") {
                    uninstallApp(ctx, pkg)
                    menuFor = null // le retour dans le launcher rafraîchit la liste
                }
            }

            Spacer(Modifier.height(18.dp))
            BwButton("Annuler", ghost = true) { menuFor = null }
            Spacer(Modifier.height(40.dp))
        }
    }

    /* ----- Overlay : raccourcis d'une app (appui long façon Android) ----- */
    shortcutsFor?.let { pkg ->
        val shortcuts = remember(pkg) { shortcutsOf(ctx, pkg) }
        Column(Modifier.fillMaxSize().background(Bw.Black).padding(24.dp)) {
            Spacer(Modifier.height(30.dp))
            Label("Raccourcis — ${(labelOf[pkg] ?: pkg).lowercase()}", color = Bw.White)
            Spacer(Modifier.height(10.dp))
            if (shortcuts.isEmpty()) {
                Text("Cette app n'expose aucun raccourci.", color = Bw.G4, fontSize = 13.sp)
            }
            LazyColumn(Modifier.weight(1f)) {
                items(shortcuts) { s ->
                    Text(
                        shortcutLabel(s).lowercase(),
                        color = Bw.White, fontSize = 17.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { startShortcut(ctx, s); shortcutsFor = null }
                            .padding(vertical = 10.dp)
                    )
                }
            }
            BwButton("Fermer", ghost = true) { shortcutsFor = null }
            Spacer(Modifier.height(14.dp))
        }
    }

    /* ----- Choix / création de dossier ----- */
    pickFolderFor?.let { pkg ->
        Column(
            Modifier.fillMaxSize().background(Bw.Black).padding(28.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Label("Dans quel dossier ?")
            Spacer(Modifier.height(16.dp))
            folderNames.forEach { name ->
                BwButton(name, ghost = true) { addToFolder(pkg, name); pickFolderFor = null }
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = newFolderName, onValueChange = { newFolderName = it },
                label = { Text("NOUVEAU DOSSIER", fontSize = 10.sp, letterSpacing = 2.sp) },
                modifier = Modifier.fillMaxWidth(), colors = bwFieldColors()
            )
            Spacer(Modifier.height(10.dp))
            BwButton("Créer et ajouter", enabled = newFolderName.isNotBlank()) {
                addToFolder(pkg, newFolderName.trim()); newFolderName = ""; pickFolderFor = null
            }
            Spacer(Modifier.height(10.dp))
            BwButton("Annuler", ghost = true) { pickFolderFor = null }
        }
    }
}
