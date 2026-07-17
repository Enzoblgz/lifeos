package fr.bellenguez.lifeos

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * Notes façon Google Keep, en noir et blanc.
 * Clés synchronisées : "notes" (actives), "notes_arch" (archive), "notes_trash" (corbeille).
 *
 * Une note : {id, title, body, ts, pin, att:[{t,u}], items:[{x,c}]}
 *  - pin   : épinglée en haut de la grille
 *  - items : cases à cocher (checklist), en plus du texte libre
 *  - "del" (timestamp) ajouté en corbeille
 * Suppression = corbeille (30 jours), jamais de perte directe.
 * Édition = enregistrement automatique (pas de bouton Enregistrer).
 */

const val TRASH_DAYS = 30
private const val TRASH_MS = TRASH_DAYS * 24L * 3600_000L

object NoteStore {

    private fun read(key: String): MutableList<JSONObject> = try {
        val arr = JSONArray(Store.rawString(key) ?: "[]")
        (0 until arr.length()).map { arr.getJSONObject(it) }.toMutableList()
    } catch (e: Exception) { mutableListOf() }

    private fun write(key: String, list: List<JSONObject>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        Store.putRaw(key, arr.toString())
    }

    fun notes(): List<JSONObject> = read("notes").sortedByDescending { it.optLong("ts") }
    fun archived(): List<JSONObject> = read("notes_arch").sortedByDescending { it.optLong("ts") }
    fun trash(): List<JSONObject> = read("notes_trash").sortedByDescending { it.optLong("del") }

    fun find(id: Long): JSONObject? =
        read("notes").find { it.optLong("id") == id }
            ?: read("notes_arch").find { it.optLong("id") == id }

    fun isArchived(id: Long): Boolean = read("notes_arch").any { it.optLong("id") == id }

    /** Crée ou met à jour une note, là où elle vit (actives ou archive). Renvoie l'id. */
    fun upsert(
        id: Long, title: String, body: String,
        att: List<JSONObject>, items: List<JSONObject>, pin: Boolean
    ): Long {
        val realId = if (id == 0L) System.currentTimeMillis() else id
        val obj = JSONObject()
            .put("id", realId)
            .put("title", title.trim())
            .put("body", body)
            .put("ts", System.currentTimeMillis())
            .put("pin", pin)
            .put("att", JSONArray().also { a -> att.forEach { a.put(it) } })
            .put("items", JSONArray().also { a -> items.forEach { a.put(it) } })
        val arch = read("notes_arch")
        val ai = arch.indexOfFirst { it.optLong("id") == realId }
        if (ai >= 0) { arch[ai] = obj; write("notes_arch", arch); return realId }
        val list = read("notes")
        val idx = list.indexOfFirst { it.optLong("id") == realId }
        if (idx >= 0) list[idx] = obj else list.add(obj)
        write("notes", list)
        return realId
    }

    fun setPin(id: Long, v: Boolean) {
        listOf("notes", "notes_arch").forEach { key ->
            val l = read(key)
            val i = l.indexOfFirst { it.optLong("id") == id }
            if (i >= 0) { l[i].put("pin", v); write(key, l) }
        }
    }

    /** Archiver retire l'épingle, comme Keep. */
    fun archive(id: Long) = move("notes", "notes_arch", id) { it.put("pin", false) }
    fun unarchive(id: Long) = move("notes_arch", "notes", id) { it.put("ts", System.currentTimeMillis()) }

    private fun move(from: String, to: String, id: Long, mutate: (JSONObject) -> JSONObject) {
        val src = read(from)
        val note = src.find { it.optLong("id") == id } ?: return
        write(from, src.filter { it.optLong("id") != id })
        val dst = read(to)
        dst.add(mutate(note))
        write(to, dst)
    }

    /** Suppression = déplacement en corbeille (rien n'est perdu). */
    fun toTrash(id: Long) {
        listOf("notes", "notes_arch").forEach { key ->
            val list = read(key)
            val note = list.find { it.optLong("id") == id } ?: return@forEach
            write(key, list.filter { it.optLong("id") != id })
            val t = read("notes_trash")
            t.add(note.put("del", System.currentTimeMillis()))
            write("notes_trash", t)
        }
    }

    /** Note restée vide : on l'efface sans passer par la corbeille (comme Keep). */
    fun discard(id: Long) {
        listOf("notes", "notes_arch").forEach { key ->
            val list = read(key)
            if (list.any { it.optLong("id") == id }) {
                write(key, list.filter { it.optLong("id") != id })
            }
        }
    }

    fun restore(id: Long) {
        val t = read("notes_trash")
        val note = t.find { it.optLong("id") == id } ?: return
        write("notes_trash", t.filter { it.optLong("id") != id })
        val list = read("notes")
        note.remove("del")
        list.add(note.put("ts", System.currentTimeMillis()))
        write("notes", list)
    }

    /** Suppression définitive : la note ET ses médias créés par LifeOS. */
    fun destroy(ctx: Context, id: Long) {
        val t = read("notes_trash")
        t.find { it.optLong("id") == id }?.let { dropMedia(ctx, it) }
        write("notes_trash", t.filter { it.optLong("id") != id })
    }

    fun emptyTrash(ctx: Context) {
        read("notes_trash").forEach { dropMedia(ctx, it) }
        write("notes_trash", emptyList())
    }

    /** Purge des notes en corbeille depuis plus de 30 jours — appelée au démarrage. */
    fun purgeExpired(ctx: Context) {
        val now = System.currentTimeMillis()
        val t = read("notes_trash")
        val (expired, kept) = t.partition { now - it.optLong("del", now) > TRASH_MS }
        if (expired.isEmpty()) return
        expired.forEach { dropMedia(ctx, it) }
        write("notes_trash", kept)
    }

    private fun dropMedia(ctx: Context, note: JSONObject) {
        attachmentsOf(note).forEach { deleteOwnedMedia(ctx, it.optString("u")) }
    }

    /**
     * Médias orphelins : une note supprimée définitivement depuis le web laisse ses fichiers
     * derrière elle (le web ne touche pas au stockage du téléphone). On ne balaie que les
     * fichiers de plus de 24 h — un média en cours d'ajout n'est pas encore rattaché.
     */
    fun sweepOrphanMedia(ctx: Context) {
        val used = (read("notes") + read("notes_arch") + read("notes_trash"))
            .flatMap { attachmentsOf(it) }
            .map { it.optString("u") }
            .toSet()
        orphanCandidates(ctx, used, System.currentTimeMillis() - 86_400_000L)
    }

    private fun orphanCandidates(ctx: Context, used: Set<String>, olderThan: Long) {
        val dir = java.io.File(ctx.filesDir, "media")
        val files = dir.listFiles() ?: return
        val usedNames = used.mapNotNull { runCatching { android.net.Uri.parse(it).lastPathSegment }.getOrNull() }
            .map { it.substringAfterLast('/') }
            .toSet()
        files.forEach { f ->
            if (f.name !in usedNames && f.lastModified() < olderThan) f.delete()
        }
    }

    fun attachmentsOf(note: JSONObject): List<JSONObject> {
        val arr = note.optJSONArray("att") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    fun itemsOf(note: JSONObject): List<JSONObject> {
        val arr = note.optJSONArray("items") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }
}

/** Sauvegarde d'une liste de JSONObject dans l'état d'instance (retour caméra, etc.). */
private val JsonListSaver = Saver<List<JSONObject>, String>(
    save = { l -> JSONArray().also { a -> l.forEach { a.put(it) } }.toString() },
    restore = { s ->
        try {
            val arr = JSONArray(s)
            (0 until arr.length()).map { arr.getJSONObject(it) }
        } catch (e: Exception) { emptyList() }
    }
)

private fun noteMatches(n: JSONObject, q: String): Boolean {
    if (q.isBlank()) return true
    val s = q.trim().lowercase()
    if (n.optString("title").lowercase().contains(s)) return true
    if (n.optString("body").lowercase().contains(s)) return true
    return NoteStore.itemsOf(n).any { it.optString("x").lowercase().contains(s) }
}

@Composable
private fun Chip(text: String, active: Boolean = false, onClick: () -> Unit) {
    Text(
        text,
        color = if (active) Bw.White else Bw.G4,
        fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
        modifier = Modifier
            .border(1.dp, if (active) Bw.White else Bw.G3)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 5.dp)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NotesScreen(onChange: () -> Unit) {
    val ctx = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    // -1 = fermé, 0 = nouvelle note. rememberSaveable : l'éditeur survit au passage par la caméra.
    var editingId by rememberSaveable { mutableLongStateOf(-1L) }
    var startAction by rememberSaveable { mutableStateOf("") } // "", "list", "draw"
    var view by rememberSaveable { mutableStateOf("main") }    // main / arch / trash
    var query by rememberSaveable { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<Long?>(null) }    // appui long sur une carte
    var confirmEmpty by remember { mutableStateOf(false) }

    val notes = remember(version) { NoteStore.notes() }
    val archived = remember(version) { NoteStore.archived() }
    val trash = remember(version) { NoteStore.trash() }

    if (editingId >= 0L) {
        NoteEditor(
            id = editingId,
            startAction = startAction,
            onClose = { editingId = -1L; startAction = ""; version++ },
            onChange = onChange
        )
        return
    }

    /* ----- Menu d'une carte (appui long) ----- */
    menuFor?.let { id ->
        val inArchive = view == "arch"
        Column(
            Modifier.fillMaxSize().background(Bw.Black).padding(28.dp),
            verticalArrangement = Arrangement.Center
        ) {
            val n = (if (inArchive) archived else notes).find { it.optLong("id") == id }
            val t = n?.optString("title").orEmpty()
            Label(if (t.isBlank()) "Note" else t, color = Bw.White)
            Spacer(Modifier.height(20.dp))
            if (!inArchive) {
                val pinned = n?.optBoolean("pin") == true
                BwButton(if (pinned) "Désépingler" else "Épingler", ghost = true) {
                    NoteStore.setPin(id, !pinned); menuFor = null; version++; onChange()
                }
                Spacer(Modifier.height(8.dp))
                BwButton("Archiver", ghost = true) {
                    NoteStore.archive(id); menuFor = null; version++; onChange()
                }
            } else {
                BwButton("Désarchiver", ghost = true) {
                    NoteStore.unarchive(id); menuFor = null; version++; onChange()
                }
            }
            Spacer(Modifier.height(8.dp))
            BwButton("Corbeille", ghost = true) {
                NoteStore.toTrash(id); menuFor = null; version++; onChange()
            }
            Spacer(Modifier.height(8.dp))
            BwButton("Annuler") { menuFor = null }
        }
        return
    }

    /* ----- Corbeille ----- */
    if (view == "trash") {
        Column(Modifier.fillMaxSize()) {
            Label("Corbeille — supprimée définitivement après $TRASH_DAYS jours",
                modifier = Modifier.padding(20.dp))
            if (trash.isEmpty()) {
                Text(
                    "Corbeille vide.",
                    color = Bw.G4, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }
            LazyColumn(Modifier.weight(1f).padding(horizontal = 20.dp)) {
                items(trash.size) { i ->
                    val n = trash[i]
                    val id = n.optLong("id")
                    val days = ((System.currentTimeMillis() - n.optLong("del")) / 86_400_000L).toInt()
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).border(1.dp, Bw.G2).padding(12.dp)) {
                        val t = n.optString("title")
                        Text(
                            if (t.isBlank()) "(sans titre)" else t,
                            color = Bw.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            n.optString("body"),
                            color = Bw.G5, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(8.dp))
                        Label("Supprimée il y a ${days}j · reste ${(TRASH_DAYS - days).coerceAtLeast(0)}j")
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BwButton("Restaurer", Modifier.weight(1f), ghost = true) {
                                NoteStore.restore(id); version++; onChange()
                            }
                            BwButton("Supprimer", Modifier.weight(1f), ghost = true) {
                                NoteStore.destroy(ctx, id); version++; onChange()
                            }
                        }
                    }
                }
            }
            Column(Modifier.padding(20.dp)) {
                if (confirmEmpty) {
                    Text(
                        "Vider la corbeille : ${trash.size} note(s) perdue(s) pour de bon.",
                        color = Bw.G5, fontSize = 12.sp, lineHeight = 19.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    BwButton("Oui, tout supprimer") {
                        NoteStore.emptyTrash(ctx); confirmEmpty = false; version++; onChange()
                    }
                    Spacer(Modifier.height(8.dp))
                    BwButton("Annuler", ghost = true) { confirmEmpty = false }
                } else {
                    BwButton("Vider la corbeille", ghost = true, enabled = trash.isNotEmpty()) {
                        confirmEmpty = true
                    }
                }
                Spacer(Modifier.height(8.dp))
                BwButton("Retour aux notes", ghost = true) { view = "main"; confirmEmpty = false }
            }
        }
        return
    }

    /* ----- Archive ----- */
    if (view == "arch") {
        val shown = archived.filter { noteMatches(it, query) }
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Label("Archive")
                Chip("RETOUR AUX NOTES") { view = "main" }
            }
            if (archived.isNotEmpty()) {
                SearchField(query, { query = it }, Modifier.padding(horizontal = 20.dp))
                Spacer(Modifier.height(6.dp))
            }
            if (archived.isEmpty()) {
                Text(
                    "Rien d'archivé. Appui long sur une note → Archiver :\nelle sort de la grille sans partir à la corbeille.",
                    color = Bw.G4, fontSize = 13.sp, lineHeight = 21.sp,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(2),
                modifier = Modifier.weight(1f).padding(horizontal = 14.dp)
            ) {
                items(shown.size) { i ->
                    NoteCard(
                        shown[i],
                        onClick = { editingId = shown[i].optLong("id") },
                        onLongClick = { menuFor = shown[i].optLong("id") },
                        onItemToggle = { idx, v ->
                            toggleItem(shown[i], idx, v); version++; onChange()
                        }
                    )
                }
            }
            Text(
                "Appui long sur une note : désarchiver ou corbeille.",
                color = Bw.G4, fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
        }
        return
    }

    /* ----- Grille principale ----- */
    val shown = notes.filter { noteMatches(it, query) }
    val pinned = shown.filter { it.optBoolean("pin") }
    val others = shown.filter { !it.optBoolean("pin") }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Label("Notes")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("ARCHIVE${if (archived.isEmpty()) "" else " · ${archived.size}"}") { view = "arch" }
                Chip("CORBEILLE${if (trash.isEmpty()) "" else " · ${trash.size}"}") { view = "trash" }
            }
        }

        /* Capture rapide, façon Keep */
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .border(1.dp, Bw.G3),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Prendre une note…",
                color = Bw.G4, fontSize = 14.sp,
                modifier = Modifier
                    .weight(1f)
                    .clickable { editingId = 0L }
                    .padding(horizontal = 14.dp, vertical = 15.dp)
            )
            Text(
                "LISTE",
                color = Bw.G5, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                modifier = Modifier
                    .clickable { startAction = "list"; editingId = 0L }
                    .padding(horizontal = 10.dp, vertical = 15.dp)
            )
            Text(
                "DESSIN",
                color = Bw.G5, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                modifier = Modifier
                    .clickable { startAction = "draw"; editingId = 0L }
                    .padding(horizontal = 10.dp, vertical = 15.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        if (notes.isNotEmpty()) {
            SearchField(query, { query = it }, Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(4.dp))
        }
        if (notes.isEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Aucune note. Capture ce qui te passe par la tête —\n" +
                    "texte, liste à cocher, photo, vidéo, mémo vocal ou dessin.\nTout s'enregistre tout seul.",
                color = Bw.G4, fontSize = 13.sp, lineHeight = 21.sp,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
        } else if (shown.isEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Aucune note ne correspond à « $query ».",
                color = Bw.G4, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
        }
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(2),
            modifier = Modifier.weight(1f).padding(horizontal = 14.dp)
        ) {
            if (pinned.isNotEmpty()) {
                item(span = StaggeredGridItemSpan.FullLine) {
                    Label("Épinglées", modifier = Modifier.padding(start = 6.dp, top = 6.dp, bottom = 2.dp))
                }
                items(pinned.size) { i ->
                    NoteCard(
                        pinned[i],
                        onClick = { editingId = pinned[i].optLong("id") },
                        onLongClick = { menuFor = pinned[i].optLong("id") },
                        onItemToggle = { idx, v ->
                            toggleItem(pinned[i], idx, v); version++; onChange()
                        }
                    )
                }
                if (others.isNotEmpty()) {
                    item(span = StaggeredGridItemSpan.FullLine) {
                        Label("Autres", modifier = Modifier.padding(start = 6.dp, top = 10.dp, bottom = 2.dp))
                    }
                }
            }
            items(others.size) { i ->
                NoteCard(
                    others[i],
                    onClick = { editingId = others[i].optLong("id") },
                    onLongClick = { menuFor = others[i].optLong("id") },
                    onItemToggle = { idx, v ->
                        toggleItem(others[i], idx, v); version++; onChange()
                    }
                )
            }
        }
        Text(
            "Appui long sur une note : épingler, archiver ou corbeille.",
            color = Bw.G4, fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )
    }
}

/** Coche/décoche un item directement depuis la carte, sans ouvrir la note. */
private fun toggleItem(note: JSONObject, index: Int, checked: Boolean) {
    val items = NoteStore.itemsOf(note).toMutableList()
    if (index !in items.indices) return
    items[index] = JSONObject().put("x", items[index].optString("x")).put("c", checked)
    NoteStore.upsert(
        note.optLong("id"), note.optString("title"), note.optString("body"),
        NoteStore.attachmentsOf(note), items, note.optBoolean("pin")
    )
}

@Composable
private fun SearchField(value: String, onValue: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().border(1.dp, Bw.G2),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValue,
            singleLine = true,
            textStyle = TextStyle(color = Bw.White, fontSize = 13.sp),
            cursorBrush = SolidColor(Bw.White),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text("Rechercher dans les notes…", color = Bw.G4, fontSize = 13.sp)
                    }
                    inner()
                }
            },
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 11.dp)
        )
        if (value.isNotEmpty()) {
            Text(
                "×", color = Bw.G4, fontSize = 16.sp,
                modifier = Modifier.clickable { onValue("") }.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteCard(
    n: JSONObject,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onItemToggle: (Int, Boolean) -> Unit
) {
    val att = NoteStore.attachmentsOf(n)
    val items = NoteStore.itemsOf(n)
    Column(
        Modifier
            .padding(6.dp)
            .border(1.dp, if (n.optBoolean("pin")) Bw.G4 else Bw.G2)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(12.dp)
    ) {
        val t = n.optString("title")
        if (t.isNotBlank() || n.optBoolean("pin")) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (t.isBlank()) "(sans titre)" else t,
                    color = Bw.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (n.optBoolean("pin")) {
                    Text("◆", color = Bw.G5, fontSize = 10.sp, modifier = Modifier.padding(start = 6.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        // aperçu : première image ou dessin de la note
        att.firstOrNull { it.optString("t") == ATT_IMG || it.optString("t") == ATT_DRW }
            ?.let { a ->
                AttachmentView(
                    a.optString("t"), a.optString("u"), grayscale = true,
                    modifier = Modifier.heightIn(max = 110.dp)
                )
                Spacer(Modifier.height(6.dp))
            }
        val body = n.optString("body")
        if (body.isNotBlank()) {
            Text(
                body, color = Bw.G5, fontSize = 12.sp, lineHeight = 18.sp,
                maxLines = if (att.isEmpty() && items.isEmpty()) 8 else 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (items.isNotEmpty()) {
            if (body.isNotBlank()) Spacer(Modifier.height(4.dp))
            val unchecked = items.withIndex().filter { !it.value.optBoolean("c") }
            val checkedCount = items.size - unchecked.size
            unchecked.take(5).forEach { (idx, it) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "☐", color = Bw.G5, fontSize = 13.sp,
                        modifier = Modifier
                            .clickable { onItemToggle(idx, true) }
                            .padding(vertical = 2.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        it.optString("x"), color = Bw.G5, fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (unchecked.size > 5) {
                Text("+ ${unchecked.size - 5} autres", color = Bw.G4, fontSize = 11.sp)
            }
            if (checkedCount > 0) {
                Text("$checkedCount/${items.size} cochés", color = Bw.G4, fontSize = 11.sp)
            }
        }
        if (att.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Label(attSummary(att))
        }
    }
}

private fun attSummary(att: List<JSONObject>): String {
    val n = att.groupingBy { it.optString("t") }.eachCount()
    return listOfNotNull(
        n[ATT_IMG]?.let { "$it image" },
        n[ATT_VID]?.let { "$it vidéo" },
        n[ATT_AUD]?.let { "$it audio" },
        n[ATT_DRW]?.let { "$it dessin" }
    ).joinToString(" · ")
}

/* ===== Éditeur — enregistrement automatique ===== */

@Composable
private fun NoteEditor(id: Long, startAction: String, onClose: () -> Unit, onChange: () -> Unit) {
    val ctx = LocalContext.current
    val existing = remember(id) { if (id == 0L) null else NoteStore.find(id) }

    /* rememberSaveable partout : le launcher peut être détruit pendant que la caméra
       est au premier plan — au retour, la note en cours doit être intacte. */
    var noteId by rememberSaveable(id) { mutableLongStateOf(id) }
    var title by rememberSaveable(id) { mutableStateOf(existing?.optString("title") ?: "") }
    var body by rememberSaveable(id) { mutableStateOf(existing?.optString("body") ?: "") }
    var att by rememberSaveable(id, stateSaver = JsonListSaver) {
        mutableStateOf(existing?.let { NoteStore.attachmentsOf(it) } ?: emptyList())
    }
    var items by rememberSaveable(id, stateSaver = JsonListSaver) {
        mutableStateOf(existing?.let { NoteStore.itemsOf(it) } ?: emptyList())
    }
    var pin by rememberSaveable(id) { mutableStateOf(existing?.optBoolean("pin") ?: false) }
    var archived by rememberSaveable(id) { mutableStateOf(id != 0L && NoteStore.isArchived(id)) }
    var listMode by rememberSaveable(id) { mutableStateOf(startAction == "list") }
    var newItem by rememberSaveable(id) { mutableStateOf("") }
    var saved by rememberSaveable(id) { mutableStateOf(existing != null) }
    var addMenu by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var drawing by rememberSaveable(id) { mutableStateOf(startAction == "draw") }
    var micDenied by remember { mutableStateOf(false) }

    val showList = listMode || items.isNotEmpty()
    val empty = title.isBlank() && body.isBlank() && att.isEmpty() && items.isEmpty()

    /* Enregistrement automatique : 600 ms après la dernière frappe / pièce jointe. */
    LaunchedEffect(title, body, att, items, pin) {
        if (empty) return@LaunchedEffect
        delay(600)
        noteId = NoteStore.upsert(noteId, title, body, att, items, pin)
        saved = true
        onChange()
    }

    fun addAttachment(type: String, uri: String) {
        att = att + JSONObject().put("t", type).put("u", uri)
    }

    fun saveNow() {
        if (!empty) { noteId = NoteStore.upsert(noteId, title, body, att, items, pin); saved = true }
    }

    /* Galerie : on persiste la permission, sinon l'uri devient illisible au prochain lancement. */
    val pickFromGallery = { type: String ->
        { uri: Uri? ->
            if (uri != null) {
                try {
                    ctx.contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {}
                addAttachment(type, uri.toString())
            }
        }
    }
    val imgPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(), pickFromGallery(ATT_IMG)
    )
    val vidPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(), pickFromGallery(ATT_VID)
    )
    val audPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(), pickFromGallery(ATT_AUD)
    )

    /* Caméra : le fichier cible est créé avant le lancement et son chemin survit à la
       destruction de l'activité (rememberSaveable) — sans ça, la photo prise est perdue
       quand Android tue le launcher pendant que la caméra tourne. */
    var pendingPath by rememberSaveable { mutableStateOf("") }
    fun capturedFile(): java.io.File? =
        pendingPath.takeIf { it.isNotEmpty() }?.let { java.io.File(it) }.also { pendingPath = "" }

    val photoCam = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        capturedFile()?.let { f ->
            if (ok && f.exists()) addAttachment(ATT_IMG, mediaUri(ctx, f).toString()) else f.delete()
        }
    }
    val videoCam = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
        capturedFile()?.let { f ->
            if (ok && f.exists()) addAttachment(ATT_VID, mediaUri(ctx, f).toString()) else f.delete()
        }
    }
    val micPerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) recording = true else micDenied = true }

    if (recording) {
        AudioRecorderScreen(
            onDone = { uri -> addAttachment(ATT_AUD, uri); recording = false },
            onCancel = { recording = false }
        )
        return
    }
    if (drawing) {
        DrawScreen(
            onDone = { uri -> addAttachment(ATT_DRW, uri); drawing = false },
            onCancel = { drawing = false }
        )
        return
    }

    if (addMenu) {
        Column(
            Modifier.fillMaxSize().background(Bw.Black).padding(28.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Label("Ajouter à la note", color = Bw.White)
            Spacer(Modifier.height(20.dp))
            listOf<Pair<String, () -> Unit>>(
                "Photo (caméra)" to {
                    val p = newMediaFile(ctx, "jpg"); pendingPath = p.first.absolutePath
                    photoCam.launch(p.second)
                },
                "Image (galerie)" to { imgPicker.launch(arrayOf("image/*")) },
                "Filmer (caméra)" to {
                    val p = newMediaFile(ctx, "mp4"); pendingPath = p.first.absolutePath
                    videoCam.launch(p.second)
                },
                "Vidéo (galerie)" to { vidPicker.launch(arrayOf("video/*")) },
                "Mémo vocal" to {
                    micDenied = false
                    micPerm.launch(android.Manifest.permission.RECORD_AUDIO)
                },
                "Fichier audio" to { audPicker.launch(arrayOf("audio/*")) },
                "Dessin" to { drawing = true }
            ).forEach { (label, action) ->
                BwButton(label, ghost = true) { addMenu = false; action() }
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(6.dp))
            BwButton("Annuler") { addMenu = false }
        }
        return
    }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Chip(if (pin) "◆ ÉPINGLÉE" else "◇ ÉPINGLER", active = pin) { pin = !pin }
            Chip(if (archived) "DÉSARCHIVER" else "ARCHIVER") {
                if (empty) return@Chip
                saveNow()
                if (archived) {
                    NoteStore.unarchive(noteId); archived = false; onChange()
                } else {
                    NoteStore.archive(noteId); pin = false
                    onChange(); onClose()
                }
            }
            Chip("PARTAGER") {
                val text = buildString {
                    if (title.isNotBlank()) appendLine(title.trim())
                    if (body.isNotBlank()) appendLine(body.trim())
                    items.forEach {
                        appendLine("${if (it.optBoolean("c")) "☑" else "☐"} ${it.optString("x")}")
                    }
                }.trim()
                if (text.isNotEmpty()) {
                    ctx.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, text),
                            "Partager la note"
                        )
                    )
                }
            }
        }
        OutlinedTextField(
            value = title, onValueChange = { title = it },
            label = { Text("TITRE", fontSize = 10.sp, letterSpacing = 2.sp) },
            modifier = Modifier.fillMaxWidth(), colors = bwFieldColors(),
            textStyle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold)
        )
        Spacer(Modifier.height(8.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            OutlinedTextField(
                value = body, onValueChange = { body = it },
                label = { Text("NOTE", fontSize = 10.sp, letterSpacing = 2.sp) },
                modifier = Modifier.fillMaxWidth().heightIn(min = if (showList) 90.dp else 180.dp),
                colors = bwFieldColors(),
                textStyle = TextStyle(fontSize = 15.sp, lineHeight = 23.sp)
            )
            if (showList) {
                Spacer(Modifier.height(12.dp))
                ChecklistEditor(
                    items = items,
                    newItem = newItem,
                    onNewItem = { newItem = it },
                    onAdd = {
                        val v = newItem.trim()
                        if (v.isNotEmpty()) {
                            items = items + JSONObject().put("x", v).put("c", false)
                            newItem = ""
                        }
                    },
                    onEdit = { i, v ->
                        items = items.toMutableList().also {
                            it[i] = JSONObject().put("x", v).put("c", it[i].optBoolean("c"))
                        }
                    },
                    onToggle = { i ->
                        items = items.toMutableList().also {
                            it[i] = JSONObject().put("x", it[i].optString("x"))
                                .put("c", !it[i].optBoolean("c"))
                        }
                    },
                    onDelete = { i -> items = items.filterIndexed { j, _ -> j != i } }
                )
            }
            att.forEachIndexed { i, a ->
                Spacer(Modifier.height(10.dp))
                Column(Modifier.fillMaxWidth().border(1.dp, Bw.G2)) {
                    AttachmentView(a.optString("t"), a.optString("u"))
                    Text(
                        "RETIRER",
                        color = Bw.G4, fontSize = 9.sp, letterSpacing = 2.sp,
                        fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                deleteOwnedMedia(ctx, a.optString("u"))
                                att = att.filterIndexed { j, _ -> j != i }
                            }
                            .padding(9.dp)
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        if (micDenied) {
            Text(
                "Micro refusé — autorise l'accès dans les réglages pour un mémo vocal.",
                color = Bw.G5, fontSize = 11.sp, modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Label(if (empty) "Note vide" else if (saved) "Enregistré" else "…")
            if (att.isNotEmpty()) Label(attSummary(att))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!showList) {
                BwButton("+ Liste", Modifier.weight(1f), ghost = true) { listMode = true }
            }
            BwButton("+ Média", Modifier.weight(1f), ghost = true) { addMenu = true }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (saved && noteId != 0L) {
                BwButton("Corbeille", Modifier.weight(1f), ghost = true) {
                    NoteStore.toTrash(noteId)
                    onChange()
                    onClose()
                }
            }
            BwButton("Fermer", Modifier.weight(1f)) {
                // le tour de LaunchedEffect en attente peut être coupé par la fermeture :
                // on force un dernier enregistrement pour ne rien perdre.
                if (!empty) {
                    NoteStore.upsert(noteId, title, body, att, items, pin); onChange()
                } else if (noteId != 0L && saved) {
                    // note redevenue vide : on l'efface, comme Keep
                    NoteStore.discard(noteId); onChange()
                }
                onClose()
            }
        }
    }
}

@Composable
private fun ChecklistEditor(
    items: List<JSONObject>,
    newItem: String,
    onNewItem: (String) -> Unit,
    onAdd: () -> Unit,
    onEdit: (Int, String) -> Unit,
    onToggle: (Int) -> Unit,
    onDelete: (Int) -> Unit
) {
    val done = items.count { it.optBoolean("c") }
    Column(Modifier.fillMaxWidth().border(1.dp, Bw.G2).padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Label("Liste")
            if (items.isNotEmpty()) Label("$done/${items.size}")
        }
        Spacer(Modifier.height(6.dp))
        items.forEachIndexed { i, it ->
            val checked = it.optBoolean("c")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (checked) "☑" else "☐",
                    color = if (checked) Bw.G4 else Bw.White, fontSize = 17.sp,
                    modifier = Modifier
                        .clickable { onToggle(i) }
                        .padding(vertical = 8.dp, horizontal = 2.dp)
                )
                Spacer(Modifier.width(10.dp))
                BasicTextField(
                    value = it.optString("x"),
                    onValueChange = { v -> onEdit(i, v) },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = if (checked) Bw.G4 else Bw.White,
                        fontSize = 14.sp,
                        textDecoration = if (checked) TextDecoration.LineThrough else TextDecoration.None
                    ),
                    cursorBrush = SolidColor(Bw.White),
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "×", color = Bw.G4, fontSize = 16.sp,
                    modifier = Modifier
                        .clickable { onDelete(i) }
                        .padding(vertical = 6.dp, horizontal = 8.dp)
                )
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("＋", color = Bw.G5, fontSize = 15.sp, modifier = Modifier.padding(vertical = 8.dp))
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = newItem,
                onValueChange = onNewItem,
                singleLine = true,
                textStyle = TextStyle(color = Bw.White, fontSize = 14.sp),
                cursorBrush = SolidColor(Bw.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onAdd() }),
                decorationBox = { inner ->
                    Box {
                        if (newItem.isEmpty()) {
                            Text("Ajouter un élément", color = Bw.G4, fontSize = 14.sp)
                        }
                        inner()
                    }
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}
