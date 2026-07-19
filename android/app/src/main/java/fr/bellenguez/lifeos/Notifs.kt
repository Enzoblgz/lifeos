package fr.bellenguez.lifeos

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.Calendar

/**
 * Rappels de bloc : à l'heure de début d'un bloc de la journée, LifeOS notifie
 * « c'est l'heure » avec un bouton « Entrer en focus ». L'app devient proactive
 * au lieu d'attendre qu'on l'ouvre.
 *
 * Planification via AlarmManager (exact si autorisé). Reprogrammée à l'ouverture,
 * à chaque changement de planning, au démarrage du téléphone et chaque minuit.
 */
object Notifs {

    const val CHANNEL = "blocks"
    private const val EXTRA_ID = "block_id"
    private const val EXTRA_NAME = "block_name"
    private const val EXTRA_NN = "block_nn"
    const val EXTRA_FOCUS_ID = "lifeos_focus_id" // lu par LauncherActivity
    private const val MIDNIGHT_CODE = 999_001

    /** Rappels activés ? (device-local, défaut oui) */
    fun enabled(): Boolean = Store.rawString("dev_notif") != "0"
    fun setEnabled(on: Boolean) {
        Store.putRaw("dev_notif", if (on) "1" else "0")
    }

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Rappels de bloc", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Prévient quand un bloc de ta journée commence."
            }
        )
    }

    private fun alarmIntent(ctx: Context, id: Long, name: String, nn: Boolean): PendingIntent {
        val i = Intent(ctx, BlockAlarmReceiver::class.java).apply {
            putExtra(EXTRA_ID, id); putExtra(EXTRA_NAME, name); putExtra(EXTRA_NN, nn)
        }
        return PendingIntent.getBroadcast(
            ctx, id.hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** (Re)programme les rappels des blocs restants d'aujourd'hui + le réveil de minuit. */
    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // réveil de minuit : reprogrammer pour le nouveau jour même app fermée
        val cal = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 1)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val midnight = PendingIntent.getBroadcast(
            ctx, MIDNIGHT_CODE, Intent(ctx, BlockAlarmReceiver::class.java).putExtra("midnight", true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        setAlarm(am, cal.timeInMillis, midnight)

        if (!enabled()) return
        val now = System.currentTimeMillis()
        Schedule.today().forEach { b ->
            if (b.time.isBlank()) return@forEach
            val start = Schedule.parseRange(b.time).first
            val when0 = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, start / 60); set(Calendar.MINUTE, start % 60)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            if (when0 > now) setAlarm(am, when0, alarmIntent(ctx, b.id, b.name, b.nn))
        }
    }

    private fun setAlarm(am: AlarmManager, at: Long, pi: PendingIntent) {
        try {
            val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (_: Exception) {
            try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) } catch (_: Exception) {}
        }
    }

    fun notifyBlock(ctx: Context, id: Long, name: String, nn: Boolean) {
        ensureChannel(ctx)
        // tap → ouvre LifeOS
        val open = PendingIntent.getActivity(
            ctx, id.hashCode(),
            Intent(ctx, LauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // action → ouvre LifeOS en demandant le focus sur ce bloc
        val focus = PendingIntent.getActivity(
            ctx, id.hashCode() + 1,
            Intent(ctx, LauncherActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_FOCUS_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle(if (nn) "$name — NON NÉGOCIABLE" else name)
            .setContentText("C'est l'heure. Entre en focus dessus.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_lock_lock, "Entrer en focus", focus)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(id.hashCode(), notif)
        } catch (_: SecurityException) { /* POST_NOTIFICATIONS non accordée */ }
    }
}

/** Reçoit les alarmes : réveil de minuit → reprogramme ; sinon → poste la notif du bloc. */
class BlockAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Store.init(ctx)
        if (intent.getBooleanExtra("midnight", false)) {
            Notifs.schedule(ctx)
            return
        }
        val id = intent.getLongExtra("block_id", 0L)
        val name = intent.getStringExtra("block_name") ?: return
        val nn = intent.getBooleanExtra("block_nn", false)
        Notifs.notifyBlock(ctx, id, name, nn)
    }
}

/** Au redémarrage du téléphone, les alarmes sont perdues : on les reprogramme. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Store.init(ctx)
            Notifs.ensureChannel(ctx)
            Notifs.schedule(ctx)
        }
    }
}
