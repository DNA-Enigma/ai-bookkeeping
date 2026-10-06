package dev.dzsun.bookkeeping.core.statement

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

/**
 * 从 assets 读格式定义。加了新银行只要改 JSON，这里一行都不用动。
 *
 * 读失败时返回空列表而不是抛——**空列表会让解析报「认不出格式」，那是对的**；
 * 抛异常则会让整个导入在拿到文件之前就崩掉，用户看不到任何可操作的信息。
 */
@Singleton
class StatementFormatCatalog @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    @Volatile
    private var cached: List<StatementFormat>? = null

    fun formats(): List<StatementFormat> = cached ?: synchronized(this) {
        cached ?: load().also { cached = it }
    }

    private fun load(): List<StatementFormat> = runCatching {
        val text = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        json.decodeFromString<StatementFormatFile>(text).formats
    }.getOrDefault(emptyList())

    companion object {
        const val ASSET_NAME = "statement_formats.json"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
