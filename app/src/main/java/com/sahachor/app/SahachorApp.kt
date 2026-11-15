package com.sahachor.app

import android.app.Application
import com.sahachor.app.core.SecretStore
import com.sahachor.app.data.AppDatabase
import com.sahachor.app.llm.KeySetup
import com.sahachor.app.llm.LlmRouter
import com.sahachor.app.reminders.Notifier
import com.sahachor.app.reminders.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

class SahachorApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var secrets: SecretStore; private set
    lateinit var router: LlmRouter; private set
    lateinit var scheduler: ReminderScheduler; private set
    /** The encrypted DB opens in the background (SQLCipher key derivation is slow); null means it could not be opened. */
    lateinit var dbDeferred: Deferred<AppDatabase?>; private set
    val keySetup = KeySetup()

    override fun onCreate() {
        super.onCreate()
        secrets = SecretStore(this)
        router = LlmRouter(secrets)
        scheduler = ReminderScheduler(this)
        Notifier.ensureChannel(this)
        dbDeferred = appScope.async { AppDatabase.open(this@SahachorApp, secrets.databasePassphrase()) }
    }
}
