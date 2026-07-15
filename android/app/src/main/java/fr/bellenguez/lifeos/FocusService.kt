package fr.bellenguez.lifeos

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Service de verrouillage : tant que le focus est actif, surveille l'app au premier plan.
 * Si autre chose que LifeOS passe devant → rappel immédiat de BlockerActivity + fuite comptée.
 */
class FocusService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastForeground: String? = null

    /** Le launcher et l'UI système doivent rester praticables, sinon
     *  impossible de naviguer vers les apps de la liste blanche. */
    private val systemAllowed: Set<String> by lazy {
        val home = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_HOME)
        val launcher = packageManager
            .resolveActivity(home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
        setOfNotNull(launcher, "com.android.systemui")
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (!Store.focusActive()) {
                stopSelf()
                return
            }
            if (Store.focusIdx() !in Schedule.today().indices) {
                // le bloc verrouillé n'existe plus (planning modifié) : on libère
                Store.clearFocus()
                stopSelf()
                return
            }
            val fg = foregroundPackage()
            if (fg != null && fg != packageName && fg !in systemAllowed && fg !in Store.whitelist()) {
                if (lastForeground != fg) Store.incEscapes()
                startActivity(
                    Intent(this@FocusService, BlockerActivity::class.java)
                        .addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        )
                )
            }
            lastForeground = fg
            handler.postDelayed(this, 800L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("focus", "Focus", NotificationManager.IMPORTANCE_LOW)
        )
        val notif: Notification = Notification.Builder(this, "focus")
            .setContentTitle("FOCUS VERROUILLÉ")
            .setContentText("Le téléphone est bloqué jusqu'à certification.")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()
        startForeground(1, notif)
        handler.post(watchdog)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Dernier package passé au premier plan (nécessite l'accès aux données d'utilisation). */
    private fun foregroundPackage(): String? {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 5000, now)
        var pkg: String? = null
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) pkg = e.packageName
        }
        return pkg
    }
}
