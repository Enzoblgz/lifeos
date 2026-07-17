package fr.bellenguez.lifeos

import android.content.Context
import android.os.Build
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File

/**
 * Rapport de crash maison, sans SDK externe.
 * Au crash : l'exception est écrite sur disque (rapide, fiable), puis le crash
 * système suit son cours. Au lancement suivant : les rapports en attente sont
 * envoyés dans la table Supabase `crash_reports` (insert-only pour l'anon)
 * puis effacés. SQL de création : `supabase/crash_reports.sql`.
 */
object CrashReporter {

    private const val MAX_KEPT = 5

    private fun dir(ctx: Context) = File(ctx.filesDir, "crashes").apply { mkdirs() }

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try { write(app, thread, e) } catch (_: Exception) {}
            previous?.uncaughtException(thread, e)
        }
    }

    private fun write(ctx: Context, thread: Thread, e: Throwable) {
        val d = dir(ctx)
        // jamais plus de MAX_KEPT rapports en attente (boucle de crash au démarrage…)
        d.listFiles()?.sortedBy { it.name }?.dropLast(MAX_KEPT - 1)?.forEach { it.delete() }
        val version = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        } catch (_: Exception) { "?" }
        val report = JSONObject()
            .put("version", version)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
            .put("stack", "thread ${thread.name}\n" + android.util.Log.getStackTraceString(e))
        File(d, "${System.currentTimeMillis()}.json").writeText(report.toString())
    }

    /** Envoie les rapports en attente — silencieux, jamais bloquant, hors-ligne toléré. */
    fun uploadPending(ctx: Context) {
        val files = dir(ctx).listFiles()?.takeIf { it.isNotEmpty() } ?: return
        Thread {
            val http = OkHttpClient()
            val jsonType = "application/json".toMediaType()
            files.forEach { f ->
                try {
                    val body = f.readText()
                    JSONObject(body) // fichier corrompu → exception → on le garde
                    val req = Request.Builder()
                        .url("${Supa.URL}/rest/v1/crash_reports")
                        .header("apikey", Supa.ANON)
                        .header("Authorization", "Bearer ${Supa.ANON}")
                        .post(body.toRequestBody(jsonType))
                        .build()
                    http.newCall(req).execute().use { r -> if (r.isSuccessful) f.delete() }
                } catch (_: Exception) {}
            }
        }.start()
    }
}
