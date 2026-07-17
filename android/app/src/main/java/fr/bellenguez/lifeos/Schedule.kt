package fr.bellenguez.lifeos

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

data class Block(
    val time: String,   // "14h00–16h00"
    val name: String,
    val hat: String,    // casquette
    val nn: Boolean = false,
    val id: Long = 0L,        // id de l'événement source (0 = bloc sans événement)
    val rec: String = "none"  // récurrence de l'événement source
)

data class Goal(val name: String, val quota: Int, val unit: String)
data class Project(val name: String, val hat: String, val alert: Boolean, val next: String)

/** Emploi du temps. Aucun défaut : c'est l'utilisateur qui planifie tout (onglet PLAN ou web). */
object Schedule {

    private val defaultGoals = emptyList<Goal>()
    private val defaultProjects = emptyList<Project>()

    const val DAY_WEEKDAY = "weekday"
    const val DAY_SATURDAY = "saturday"
    const val DAY_SUNDAY = "sunday"

    fun blocksFor(day: String): List<Block> = try {
        Store.rawString("schedule")?.let { raw ->
            val obj = JSONObject(raw)
            if (obj.has(day)) blocksFrom(obj.getJSONArray(day)) else emptyList()
        } ?: emptyList()
    } catch (e: Exception) { emptyList() }

    fun saveBlocks(day: String, blocks: List<Block>) {
        val obj = try { JSONObject(Store.rawString("schedule") ?: "{}") } catch (e: Exception) { JSONObject() }
        val arr = JSONArray()
        blocks.forEach {
            arr.put(
                JSONObject().put("t", it.time).put("n", it.name).put("c", it.hat).put("nn", it.nn)
            )
        }
        obj.put(day, arr)
        Store.putRaw("schedule", obj.toString())
    }

    fun saveGoals(goals: List<Goal>) {
        val arr = JSONArray()
        goals.forEach { arr.put(JSONObject().put("n", it.name).put("q", it.quota).put("u", it.unit)) }
        Store.putRaw("goalsDef", arr.toString())
    }

    fun todayName(): String = when (Calendar.getInstance().get(Calendar.DAY_OF_WEEK)) {
        Calendar.SUNDAY -> DAY_SUNDAY
        Calendar.SATURDAY -> DAY_SATURDAY
        else -> DAY_WEEKDAY
    }

    // ----- Overrides synchronisés (éditables depuis le web) -----

    val goals: List<Goal>
        get() = try {
            Store.rawString("goalsDef")?.let { raw ->
                val arr = JSONArray(raw)
                (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    Goal(o.getString("n"), o.getInt("q"), o.getString("u"))
                }
            } ?: defaultGoals
        } catch (e: Exception) { defaultGoals }

    val projects: List<Project>
        get() = try {
            Store.rawString("projects")?.let { raw ->
                val arr = JSONArray(raw)
                (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    Project(
                        o.getString("n"), o.getString("c"),
                        o.optBoolean("alert", false), o.optString("next", "")
                    )
                }
            } ?: defaultProjects
        } catch (e: Exception) { defaultProjects }

    private fun blocksFrom(arr: JSONArray): List<Block> =
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Block(o.getString("t"), o.getString("n"), o.optString("c", "perso"), o.optBoolean("nn", false))
        }

    /** Plan daté (routine du soir : "je planifie demain, appliqué à minuit"). */
    fun dayPlan(dateKey: String): List<Block> = try {
        Store.rawString("plan-$dateKey")?.let { blocksFrom(JSONArray(it)) } ?: emptyList()
    } catch (e: Exception) { emptyList() }

    fun saveDayPlan(dateKey: String, blocks: List<Block>) {
        val arr = JSONArray()
        blocks.forEach {
            arr.put(JSONObject().put("t", it.time).put("n", it.name).put("c", it.hat).put("nn", it.nn))
        }
        Store.putRaw("plan-$dateKey", arr.toString())
    }

    fun dayNameFor(offsetDays: Int): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, offsetDays)
        return when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.SUNDAY -> DAY_SUNDAY
            Calendar.SATURDAY -> DAY_SATURDAY
            else -> DAY_WEEKDAY
        }
    }

    /** Un événement a-t-il lieu le jour donné ? Gère la récurrence (daily/weekly/monthly). */
    fun eventOccursOn(e: JSONObject, key: String): Boolean {
        val d = e.optString("d")
        if (d == key) return true
        val rec = e.optString("rec")
        if (rec.isEmpty() || rec == "none" || d > key) return false
        return try {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            val start = Calendar.getInstance().apply { time = fmt.parse(d)!! }
            val target = Calendar.getInstance().apply { time = fmt.parse(key)!! }
            when (rec) {
                "daily" -> true
                "weekly" -> start.get(Calendar.DAY_OF_WEEK) == target.get(Calendar.DAY_OF_WEEK)
                "monthly" -> start.get(Calendar.DAY_OF_MONTH) == target.get(Calendar.DAY_OF_MONTH)
                else -> false
            }
        } catch (ex: Exception) { false }
    }

    /** L'heure a-t-elle une fin explicite ("9h00–10h30") ? */
    fun hasEnd(t: String): Boolean {
        if (!t.contains("–") && !t.contains("-")) return false
        val (a, z) = parseRange(t)
        return z > a
    }

    /**
     * Bloc à attaquer en focus : celui de l'heure s'il n'est pas coché,
     * sinon le premier non coché qui suit — sans se soucier de l'heure.
     * `checks` contient des ids d'événements ; le résultat reste un index d'affichage.
     */
    fun focusTarget(blocks: List<Block>, checks: Set<Long>): Int? {
        if (blocks.isEmpty()) return null
        fun done(i: Int) = blocks[i].id in checks
        val cur = current()
        if (cur.mode == "now" && cur.idx >= 0 && !done(cur.idx)) return cur.idx
        val start = if (cur.idx >= 0) cur.idx else 0
        (start until blocks.size).firstOrNull { !done(it) }?.let { return it }
        return blocks.indices.firstOrNull { !done(it) }
    }

    /**
     * Journée effective = les événements du calendrier du jour donné, rien d'autre.
     * (Plus de blocs ni de plans types — les événements suffisent.)
     * Le tri doit rester STABLE : les coches (`checks-<jour>`) sont indexées sur cette liste.
     */
    fun blocksOn(key: String): List<Block> = Store.events()
        .filter { eventOccursOn(it, key) }
        .map { e ->
            // l'heure reste telle que saisie : "9h00" = début seul (fin libre),
            // "9h00–10h30" = fin explicite si l'utilisateur la précise
            Block(
                time = e.optString("t").trim(),
                name = e.optString("n"),
                hat = "",
                nn = e.optBoolean("nn", false),
                id = e.optLong("id"),
                rec = e.optString("rec", "none")
            )
        }
        .sortedBy { if (it.time.isEmpty()) Int.MAX_VALUE else parseRange(it.time).first }

    fun today(): List<Block> = blocksOn(Store.dateKey(0))

    /** "14h00–16h00" -> minutes depuis minuit (début, fin). Tolère "-" et "14h" sans minutes. */
    fun parseRange(t: String): Pair<Int, Int> {
        val parts = t.replace("-", "–").split("–").map { s ->
            val hm = s.trim().split("h")
            (hm[0].trim().toIntOrNull() ?: 0) * 60 +
                (hm.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0)
        }
        return (parts.getOrNull(0) ?: 0) to (parts.getOrNull(1) ?: 0)
    }

    /** mode : "now" (bloc en cours), "next" (prochain), "done" (journée finie, idx = -1) */
    data class Current(val idx: Int, val mode: String)

    fun current(): Current {
        val blocks = today()
        val cal = Calendar.getInstance()
        val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        // fin explicite contenant l'instant → bloc courant
        blocks.forEachIndexed { i, b ->
            if (hasEnd(b.time)) {
                val (a, z) = parseRange(b.time)
                if (now in a until z) return Current(i, "now")
            }
        }
        // sinon : dernier bloc commencé — sans fin, il court jusqu'à nouvel ordre
        var cand = -1
        blocks.forEachIndexed { i, b ->
            if (b.time.isNotEmpty() && parseRange(b.time).first <= now) cand = i
        }
        if (cand >= 0) {
            val b = blocks[cand]
            val ended = hasEnd(b.time) && now >= parseRange(b.time).second
            if (!ended) return Current(cand, "now")
        }
        blocks.forEachIndexed { i, b ->
            if (b.time.isNotEmpty() && parseRange(b.time).first > now) return Current(i, "next")
        }
        return Current(-1, "done")
    }
}
