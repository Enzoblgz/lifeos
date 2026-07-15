package fr.bellenguez.lifeos

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * État persistant — SharedPreferences local + export/import JSON pour la sync Supabase.
 * Clés locales (jamais synchronisées) : focus_*, sess_*, wl_*.
 */
object Store {

    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        if (!::sp.isInitialized) {
            sp = ctx.applicationContext.getSharedPreferences("lifeos", Context.MODE_PRIVATE)
        }
    }

    /** Clé de date locale, décalable : dateKey(1) = demain. */
    fun dateKey(offsetDays: Int = 0): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, offsetDays)
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
    }

    private fun dayKey(): String = dateKey(0)

    private fun weekKey(): String {
        val cal = Calendar.getInstance()
        val shift = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 // lundi = début de semaine
        cal.add(Calendar.DAY_OF_YEAR, -shift)
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
    }

    fun rawString(key: String): String? = sp.getString(key, null)
    fun putRaw(key: String, value: String) = sp.edit().putString(key, value).apply()

    // ----- Launcher (local à l'appareil) -----
    fun launcherLayout(): String = sp.getString("wl_launcher", "[]")!!
    fun saveLauncherLayout(json: String) = sp.edit().putString("wl_launcher", json).apply()

    // ----- Widgets (local) : [{id, c, r, w, h}] sur une grille 4×3 -----
    fun widgets(): List<JSONObject> = try {
        val arr = JSONArray(sp.getString("widgets_grid", "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it) }
    } catch (e: Exception) { emptyList() }

    fun saveWidgets(list: List<JSONObject>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        sp.edit().putString("widgets_grid", arr.toString()).apply()
    }

    // ----- Calendrier (synchronisé) : [{id, d:"YYYY-MM-DD", t:"14h00", n:"Meeting"}] -----
    fun events(): List<JSONObject> = try {
        val arr = JSONArray(sp.getString("events", "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it) }
    } catch (e: Exception) { emptyList() }

    fun addEvent(date: String, time: String, name: String, nn: Boolean = false, rec: String = "none") {
        val arr = JSONArray(sp.getString("events", "[]"))
        arr.put(
            JSONObject()
                .put("id", System.currentTimeMillis())
                .put("d", date).put("t", time).put("n", name).put("nn", nn).put("rec", rec)
        )
        putRaw("events", arr.toString())
    }

    /** Retire une app épinglée de l'accueil (les dossiers sont nettoyés aussi). */
    fun removePinnedApp(pkg: String) {
        val arr = try { JSONArray(launcherLayout()) } catch (e: Exception) { JSONArray() }
        val out = JSONArray()
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
        saveLauncherLayout(out.toString())
    }

    fun updateEvent(id: Long, date: String, time: String, name: String, nn: Boolean, rec: String) {
        val out = JSONArray()
        events().forEach {
            if (it.optLong("id") == id) {
                out.put(
                    JSONObject().put("id", id)
                        .put("d", date).put("t", time).put("n", name).put("nn", nn).put("rec", rec)
                )
            } else out.put(it)
        }
        putRaw("events", out.toString())
    }

    fun removeEvent(id: Long) {
        val out = JSONArray()
        events().forEach { if (it.optLong("id") != id) out.put(it) }
        putRaw("events", out.toString())
    }

    // ----- Blocs du jour -----
    fun checks(): Set<Int> =
        sp.getStringSet("checks-${dayKey()}", emptySet())!!.map { it.toInt() }.toSet()

    fun setCheck(idx: Int, done: Boolean) {
        val s = checks().toMutableSet()
        if (done) s.add(idx) else s.remove(idx)
        sp.edit().putStringSet("checks-${dayKey()}", s.map { it.toString() }.toSet()).apply()
    }

    fun isValidated(): Boolean = sp.getBoolean("valid-${dayKey()}", false)
    fun validateDay() {
        sp.edit().putBoolean("valid-${dayKey()}", true).apply()
        streak = streak + 1
    }

    var streak: Int
        get() = sp.getInt("streak", 0)
        set(v) = sp.edit().putInt("streak", v).apply()

    // ----- Focus (local à l'appareil) -----
    fun focusActive(): Boolean = sp.getBoolean("focus_active", false)
    fun focusIdx(): Int = sp.getInt("focus_idx", -1)
    fun focusStart(): Long = sp.getLong("focus_start", 0L)
    fun focusEscapes(): Int = sp.getInt("focus_escapes", 0)

    fun startFocus(idx: Int) {
        sp.edit()
            .putBoolean("focus_active", true)
            .putInt("focus_idx", idx)
            .putLong("focus_start", System.currentTimeMillis())
            .putInt("focus_escapes", 0)
            .apply()
    }

    fun incEscapes() {
        sp.edit().putInt("focus_escapes", focusEscapes() + 1).apply()
    }

    fun clearFocus() {
        sp.edit().putBoolean("focus_active", false).apply()
    }

    // ----- Liste blanche du focus (local à l'appareil) -----
    fun whitelist(): Set<String> = sp.getStringSet("wl_apps", emptySet())!!

    fun toggleWhitelist(pkg: String) {
        val s = whitelist().toMutableSet()
        if (!s.remove(pkg)) s.add(pkg)
        sp.edit().putStringSet("wl_apps", s).apply()
    }

    /** App désinstallée : on efface toute trace (épingle, dossier, focus, horloge). */
    fun forgetApp(pkg: String) {
        removePinnedApp(pkg)
        val s = whitelist().toMutableSet()
        if (s.remove(pkg)) sp.edit().putStringSet("wl_apps", s).apply()
        if (rawString("clock_shortcut") == pkg) sp.edit().remove("clock_shortcut").apply()
    }

    // ----- Objectifs hebdo -----
    fun goalsDone(): IntArray {
        val raw = sp.getString("goals-${weekKey()}", null)
            ?: return IntArray(Schedule.goals.size)
        return raw.split(",").map { it.toInt() }.toIntArray()
    }

    fun bumpGoal(i: Int, delta: Int) {
        val g = goalsDone().copyOf(Schedule.goals.size)
        g[i] = maxOf(0, g[i] + delta)
        sp.edit().putString("goals-${weekKey()}", g.joinToString(",")).apply()
    }

    // ----- Pourquoi -----
    fun whyItems(): List<JSONObject> {
        val arr = JSONArray(sp.getString("why", "[]"))
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    fun addWhy(obj: JSONObject) {
        val arr = JSONArray(sp.getString("why", "[]"))
        arr.put(obj)
        sp.edit().putString("why", arr.toString()).apply()
    }

    fun removeWhy(index: Int) {
        val arr = JSONArray(sp.getString("why", "[]"))
        val out = JSONArray()
        for (i in 0 until arr.length()) if (i != index) out.put(arr.getJSONObject(i))
        sp.edit().putString("why", out.toString()).apply()
    }

    // ----- Session Supabase (locale à l'appareil) -----
    fun session(): Supa.Session? {
        val a = sp.getString("sess_access", null) ?: return null
        return Supa.Session(
            access = a,
            refresh = sp.getString("sess_refresh", "")!!,
            userId = sp.getString("sess_uid", "")!!
        )
    }

    fun saveSession(s: Supa.Session) {
        sp.edit()
            .putString("sess_access", s.access)
            .putString("sess_refresh", s.refresh)
            .putString("sess_uid", s.userId)
            .apply()
    }

    fun clearSession() {
        sp.edit().remove("sess_access").remove("sess_refresh").remove("sess_uid").apply()
    }

    // ----- Export / import pour la sync -----
    private fun isLocalKey(k: String) =
        k.startsWith("focus_") || k.startsWith("sess_") || k.startsWith("wl_")

    fun exportState(): JSONObject {
        val out = JSONObject()
        for ((k, v) in sp.all) {
            if (isLocalKey(k)) continue
            when (v) {
                is Int -> out.put(k, v)
                is Long -> out.put(k, v)
                is Boolean -> out.put(k, v)
                is String -> out.put(k, v)
                is Set<*> -> out.put(k, JSONArray(v.map { it.toString() }))
            }
        }
        return out
    }

    fun importState(data: JSONObject) {
        val ed = sp.edit()
        for (k in data.keys()) {
            if (isLocalKey(k)) continue
            when (val v = data.get(k)) {
                is JSONArray -> ed.putStringSet(k, (0 until v.length()).map { v.getString(it) }.toSet())
                is Boolean -> ed.putBoolean(k, v)
                is Int -> ed.putInt(k, v)
                is Long -> ed.putLong(k, v)
                else -> ed.putString(k, v.toString())
            }
        }
        ed.apply()
    }
}
