package com.sahachor.app

import android.app.Application
import com.sahachor.app.core.AppSettings
import com.sahachor.app.core.SecretStore
import com.sahachor.app.core.SessionLock
import com.sahachor.app.vault.VaultCrypto
import com.sahachor.app.vault.VaultKeys
import com.sahachor.app.vault.VaultStore
import com.sahachor.app.data.AppDatabase
import com.sahachor.app.docs.DocumentStore
import com.sahachor.app.llm.KeySetup
import com.sahachor.app.llm.LlmRouter
import com.sahachor.app.reminders.Notifier
import com.sahachor.app.reminders.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

class SahachorApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var secrets: SecretStore; private set
    lateinit var router: LlmRouter; private set
    lateinit var scheduler: ReminderScheduler; private set
    /** The encrypted DB opens in the background (SQLCipher key derivation is slow); null means it could not be opened. */
    lateinit var dbDeferred: Deferred<AppDatabase?>; private set
    val keySetup = KeySetup()
    val documents by lazy { DocumentStore(this) }
    val settings by lazy { AppSettings(this) }
    val session = SessionLock()
    /** null if the Keystore is unusable on this phone; the Vault screen then explains it. */
    val vault: VaultStore? by lazy { try { VaultStore(this, VaultCrypto(VaultKeys.keystoreKey())) } catch (e: Exception) { null } }

    override fun onCreate() {
        super.onCreate()
        secrets = SecretStore(this)
        router = LlmRouter(secrets)
        scheduler = ReminderScheduler(this)
        Notifier.ensureChannel(this)
        appScope.launch { try { java.io.File(cacheDir, "vault_tmp").deleteRecursively() } catch (e: Exception) { /* nothing to clean */ } } // decrypted temp copies never survive a restart
        dbDeferred = appScope.async { AppDatabase.open(this@SahachorApp, secrets.databasePassphrase()) }
    }
}
