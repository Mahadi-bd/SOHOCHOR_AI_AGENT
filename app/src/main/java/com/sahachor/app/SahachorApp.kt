package com.sahachor.app

import android.app.Application
import com.sahachor.app.core.SecretStore
import com.sahachor.app.data.AppDatabase
import com.sahachor.app.llm.KeySetup
import com.sahachor.app.llm.LlmRouter

class SahachorApp : Application() {
    lateinit var secrets: SecretStore; private set
    lateinit var router: LlmRouter; private set
    val keySetup = KeySetup()
    var db: AppDatabase? = null; private set

    override fun onCreate() {
        super.onCreate()
        secrets = SecretStore(this)
        router = LlmRouter(secrets)
        db = AppDatabase.open(this, secrets.databasePassphrase())
    }
}
