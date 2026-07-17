package fr.bellenguez.lifeos

import android.app.Application

/** Point d'entrée du process : le crash reporting couvre toutes les activités et services. */
class LifeOSApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        CrashReporter.install(this)
        CrashReporter.uploadPending(this)
    }
}
