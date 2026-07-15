package fr.bellenguez.lifeos

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Client Supabase minimal (auth + user_state) en REST pur.
 * Remplir URL et ANON avec les clés du projet (Settings → API).
 */
object Supa {

    const val URL = "https://mpivoorbztrqzfbwqlfb.supabase.co"
    const val ANON = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Im1waXZvb3JienRycXpmYndxbGZiIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODM5MzM0NzYsImV4cCI6MjA5OTUwOTQ3Nn0.xV8mzwHgXV6VleCL16lWmw7fissyCZsSu9bLrTUA6hk"

    fun configured(): Boolean = URL.startsWith("https://")

    data class Session(val access: String, val refresh: String, val userId: String)

    private val http = OkHttpClient()
    private val jsonType = "application/json".toMediaType()

    class AuthError(message: String) : Exception(message)

    // ---------- AUTH ----------

    private fun authCall(path: String, body: JSONObject): JSONObject {
        val req = Request.Builder()
            .url("$URL/auth/v1/$path")
            .header("apikey", ANON)
            .post(body.toString().toRequestBody(jsonType))
            .build()
        http.newCall(req).execute().use { r ->
            val txt = r.body?.string() ?: "{}"
            val obj = try { JSONObject(txt) } catch (e: Exception) { JSONObject() }
            if (!r.isSuccessful) {
                throw AuthError(
                    obj.optString("msg", obj.optString("error_description", "Erreur ${r.code}"))
                )
            }
            return obj
        }
    }

    private fun toSession(obj: JSONObject): Session? {
        val access = obj.optString("access_token")
        if (access.isEmpty()) return null
        return Session(
            access = access,
            refresh = obj.optString("refresh_token"),
            userId = obj.getJSONObject("user").getString("id")
        )
    }

    /** null = compte créé mais email à confirmer */
    fun signUp(email: String, pass: String): Session? =
        toSession(authCall("signup", JSONObject().put("email", email).put("password", pass)))

    fun signIn(email: String, pass: String): Session =
        toSession(
            authCall("token?grant_type=password", JSONObject().put("email", email).put("password", pass))
        ) ?: throw AuthError("Connexion impossible")

    fun refresh(session: Session): Session =
        toSession(
            authCall("token?grant_type=refresh_token", JSONObject().put("refresh_token", session.refresh))
        ) ?: throw AuthError("Session expirée — reconnecte-toi")

    // ---------- STATE ----------

    private fun stateReq(session: Session, builder: (Request.Builder) -> Request.Builder): Request =
        builder(
            Request.Builder()
                .header("apikey", ANON)
                .header("Authorization", "Bearer ${session.access}")
        ).build()

    fun pullState(session: Session): JSONObject? {
        val req = stateReq(session) {
            it.url("$URL/rest/v1/user_state?user_id=eq.${session.userId}&select=data")
        }
        http.newCall(req).execute().use { r ->
            if (r.code == 401) return pullState(refreshAndSave(session))
            if (!r.isSuccessful) return null
            val arr = JSONArray(r.body?.string() ?: "[]")
            return if (arr.length() > 0) arr.getJSONObject(0).getJSONObject("data") else null
        }
    }

    /** Horodatage distant de la dernière version connue (poussée ou tirée par cet appareil). */
    @Volatile var lastRemoteStamp: String? = null

    fun pushState(session: Session, data: JSONObject): Boolean {
        val body = JSONArray().put(
            JSONObject().put("user_id", session.userId).put("data", data)
        )
        val req = stateReq(session) {
            it.url("$URL/rest/v1/user_state")
                .header("Prefer", "resolution=merge-duplicates,return=representation")
                .post(body.toString().toRequestBody(jsonType))
        }
        http.newCall(req).execute().use { r ->
            if (r.code == 401) return pushState(refreshAndSave(session), data)
            if (r.isSuccessful) {
                try {
                    val arr = JSONArray(r.body?.string() ?: "[]")
                    if (arr.length() > 0) lastRemoteStamp = arr.getJSONObject(0).optString("updated_at")
                } catch (_: Exception) {}
            }
            return r.isSuccessful
        }
    }

    /** Poll léger : compare updated_at distant, importe si quelqu'un d'autre a modifié.
     *  true = du nouveau a été importé. */
    fun pollAndPull(): Boolean {
        if (!configured()) return false
        val s = Store.session() ?: return false
        return try {
            val req = stateReq(s) {
                it.url("$URL/rest/v1/user_state?user_id=eq.${s.userId}&select=updated_at")
            }
            val stamp = http.newCall(req).execute().use { r ->
                if (r.code == 401) { refreshAndSave(s); return false }
                if (!r.isSuccessful) return false
                val arr = JSONArray(r.body?.string() ?: "[]")
                if (arr.length() == 0) return false
                arr.getJSONObject(0).optString("updated_at")
            }
            if (stamp == lastRemoteStamp) return false
            pullState(Store.session() ?: return false)?.let {
                Store.importState(it)
                lastRemoteStamp = stamp
                true
            } ?: false
        } catch (_: Exception) { false }
    }

    private fun refreshAndSave(old: Session): Session {
        val s = refresh(old)
        Store.saveSession(s)
        return s
    }

    // ---------- HELPERS SYNC ----------

    /** Pousse l'état local. Silencieux en cas d'échec (hors-ligne, etc.). */
    fun tryPush() {
        if (!configured()) return
        val s = Store.session() ?: return
        try { pushState(s, Store.exportState()) } catch (_: Exception) {}
    }

    /** Tire l'état distant et l'importe. true si un état a été importé. */
    fun tryPull(): Boolean {
        if (!configured()) return false
        val s = Store.session() ?: return false
        return try {
            pullState(s)?.let { Store.importState(it); true } ?: false
        } catch (_: Exception) { false }
    }
}
