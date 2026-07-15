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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * Notes façon Google Keep, en noir et blanc.
 * Clés synchronisées : "notes" (actives) et "notes_trash" (corbeille).
 *
 * Une note : {id, title, body, ts, att:[{t,u}]} — et "del" (timestamp) en corbeille.
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
    fun trash(): List<JSONObject> = read("notes_trash").sortedByDescending { it.optLong("del") }

    fun find(id: Long): JSONObject? = read("notes").find { it.optLong("id") == id }

    /** Crée ou met à jour une note. Renvoie l'id (généré si la note est neuve). */
    fun upsert(id: Long, title: String, body: String, att: List<JSONObject>): Long {
        val list = read("notes")
        val realId = if (id == 0L) System.currentTimeMillis() else id
        val obj = JSONObject()
            .put("id", realId)
            .put("title", title.trim())
            .put("body", body)
            .put("ts", System.currentTimeMillis())
            .put("att", JSONArray().also { a -> att.forEach { a.put(it) } })
        val idx = list.indexOfFirst { it.optLong("id") == realId }
        if (idx >= 0) list[idx] = obj else list.add(obj)
        write("notes", list)
        return realId
    }

    /** Suppression = déplacement en corbeille (rien n'est perdu). */
    fun toTrash(id: Long) {
        val list = read("notes")
        val note = list.find { it.optLong("id") == id } ?: return
        write("notes", list.filter { it.optLong("id") != id })
        val t = read("notes_trash")
        t.add(note.put("del", System.currentTimeMillis()))
        write("notes_trash", t)
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
        val used = (read("notes") + read("notes_trash"))
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
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NotesScreen(onChange: () -> Unit) {
    val ctx = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    var editingId by remember { mutableStateOf<Long?>(null) } // 0L = nouvelle note
    var showTrash by remember { mutableStateOf(false) }
    var confirmEmpty by remember { mutableStateOf(false) }

    val notes = remember(version) { NoteStore.notes() }
    val trash = remember(version) { NoteStore.trash() }

    if (editingId != null) {
        NoteEditor(
            id = editingId!!,
            onClose = { editingId = null; version++ },
            onChange = onChange
        )
        return
    }

    if (showTrash) {
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
                BwButton("Retour aux notes", ghost = true) { showTrash = false; confirmEmpty = false }
            }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Label("Notes")
            Text(
                "CORBEILLE${if (trash.isEmpty()) "" else " · ${trash.size}"}",
                color = Bw.G4, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                modifier = Modifier
                    .border(1.dp, Bw.G3)
                    .clickable { showTrash = true }
                    .padding(horizontal = 8.dp, vertical = 5.dp)
            )
        }
        BwButton("+ Note", modifier = Modifier.padding(horizontal = 20.dp), ghost = true) {
            editingId = 0L
        }
        Spacer(Modifier.height(10.dp))
        if (notes.isEmpty()) {
            Text(
                "Aucune note. Capture ce qui te passe par la tête —\n" +
                    "texte, photo, vidéo, mémo vocal ou dessin. Tout s'enregistre tout seul.",
                color = Bw.G4, fontSize = 13.sp, lineHeight = 21.sp,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.weight(1f).padding(horizontal = 14.dp)
        ) {
            items(notes.size) { i ->
                val n = notes[i]
                val id = n.optLong("id")
                val att = NoteStore.attachmentsOf(n)
                Column(
                    Modifier
                        .padding(6.dp)
                        .border(1.dp, Bw.G2)
                        .combinedClickable(
                            onClick = { editingId = id },
                            onLongClick = { NoteStore.toTrash(id); version++; onChange() }
                        )
                        .padding(12.dp)
                ) {
                    val t = n.optString("title")
                    if (t.isNotBlank()) {
                        Text(
                            t, color = Bw.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
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
                            maxLines = if (att.isEmpty()) 8 else 3, overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (att.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Label(attSummary(att))
                    }
                }
            }
        }
        Text(
            "Appui long sur une note : direction la corbeille.",
            color = Bw.G4, fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )
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
private fun NoteEditor(id: Long, onClose: () -> Unit, onChange: () -> Unit) {
    val ctx = LocalContext.current
    val existing = remember(id) { if (id == 0L) null else NoteStore.find(id) }

    var noteId by remember(id) { mutableLongStateOf(id) }
    var title by remember(id) { mutableStateOf(existing?.optString("title") ?: "") }
    var body by remember(id) { mutableStateOf(existing?.optString("body") ?: "") }
    var att by remember(id) {
        mutableStateOf(existing?.let { NoteStore.attachmentsOf(it) } ?: emptyList())
    }
    var saved by remember(id) { mutableStateOf(existing != null) }
    var addMenu by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var drawing by remember { mutableStateOf(false) }
    var micDenied by remember { mutableStateOf(false) }

    val empty = title.isBlank() && body.isBlank() && att.isEmpty()

    /* Enregistrement automatique : 600 ms après la dernière frappe / pièce jointe. */
    LaunchedEffect(title, body, att) {
        if (empty) return@LaunchedEffect
        delay(600)
        noteId = NoteStore.upsert(noteId, title, body, att)
        saved = true
        onChange()
    }

    fun addAttachment(type: String, uri: String) {
        att = att + JSONObject().put("t", type).put("u", uri)
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

    // Caméra : le fichier cible est créé avant le lancement, gardé seulement si la capture réussit.
    var pending by remember { mutableStateOf<Pair<java.io.File, Uri>?>(null) }
    val photoCam = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        pending?.let { (f, u) -> if (ok) addAttachment(ATT_IMG, u.toString()) else f.delete() }
        pending = null
    }
    val videoCam = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
        pending?.let { (f, u) -> if (ok) addAttachment(ATT_VID, u.toString()) else f.delete() }
        pending = null
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
                    val p = newMediaFile(ctx, "jpg"); pending = p; photoCam.launch(p.second)
                },
                "Image (galerie)" to { imgPicker.launch(arrayOf("image/*")) },
                "Filmer (caméra)" to {
                    val p = newMediaFile(ctx, "mp4"); pending = p; videoCam.launch(p.second)
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
                modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp), colors = bwFieldColors(),
                textStyle = TextStyle(fontSize = 15.sp, lineHeight = 23.sp)
            )
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
        BwButton("+ Photo · vidéo · audio · dessin", ghost = true) { addMenu = true }
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
                if (!empty) { NoteStore.upsert(noteId, title, body, att); onChange() }
                onClose()
            }
        }
    }
}
