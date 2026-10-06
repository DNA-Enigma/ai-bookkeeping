package dev.dzsun.bookkeeping

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.dzsun.bookkeeping.core.ledger.ChartOfAccountsSeeder
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class BookkeepingApplication : Application() {

    @Inject lateinit var seeder: ChartOfAccountsSeeder

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch { seeder.seedIfEmpty() }
    }
}
