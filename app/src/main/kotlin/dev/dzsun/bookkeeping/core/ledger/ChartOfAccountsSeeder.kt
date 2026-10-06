package dev.dzsun.bookkeeping.core.ledger

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.dzsun.bookkeeping.core.database.AccountEntity
import dev.dzsun.bookkeeping.core.database.AccountType
import dev.dzsun.bookkeeping.core.database.LedgerDatabase
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 首次启动时装入一套默认科目表。
 *
 * 科目表放在 assets 的 JSON 里而不是代码常量里，因为**分类是用户数据，不是规则**：
 * 用户可以改、可以加、可以删；这里给的只是起点。
 */
@Singleton
class ChartOfAccountsSeeder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: LedgerDatabase,
) {

    suspend fun seedIfEmpty() {
        if (database.accountDao().count() > 0) return
        val chart = readChart()
        database.accountDao().upsertAll(
            chart.accounts.map { spec ->
                AccountEntity(
                    id = spec.id,
                    name = spec.name,
                    type = spec.type,
                    currency = chart.currency,
                    sortOrder = spec.sortOrder,
                )
            },
        )
    }

    private fun readChart(): ChartFile {
        val text = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        return json.decodeFromString<ChartFile>(text)
    }

    companion object {
        const val ASSET_NAME = "default_accounts.json"

        private val json = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
internal data class ChartFile(
    val version: Int,
    val currency: String,
    val accounts: List<AccountSpec>,
)

@Serializable
internal data class AccountSpec(
    val id: String,
    val name: String,
    val type: AccountType,
    val sortOrder: Int = 0,
)
