package dev.dzsun.bookkeeping.core.network

import dev.dzsun.bookkeeping.BuildConfig
import dev.dzsun.bookkeeping.core.platform.IdGenerator
import dev.dzsun.bookkeeping.core.statement.MerchantCategorization
import dev.dzsun.bookkeeping.core.statement.MerchantCategorizer
import dev.dzsun.bookkeeping.core.statement.MerchantSuggestion
import dev.dzsun.bookkeeping.core.statement.UnknownMerchant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

/**
 * 让调度层给陌生商户归类。
 *
 * **一次调用带整份商户列表**（约定 7）。这不是省事，是正确性：让模型逐条分类，
 * 除了贵和慢，还会让同一个商户在不同任务里得到不同分类——批量一次看全，
 * 模型才能给出彼此一致的判断。
 *
 * 归类是**有副作用的读操作**：不改账本，所以不发幂等键；重发一次最多是白花一次钱，
 * 不会写出第二笔账。
 */
@Singleton
class DispatcherMerchantCategorizer @Inject constructor(
    private val client: DispatcherClient,
    private val config: DispatcherConfig,
    private val ids: IdGenerator,
) : MerchantCategorizer {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    override suspend fun categorize(
        merchants: List<UnknownMerchant>,
        categories: List<String>,
    ): MerchantCategorization {
        if (merchants.isEmpty()) return MerchantCategorization.Suggested(emptyList())
        if (categories.isEmpty()) {
            // 没有分类可选时发出去也是白发——模型会从它自己的常识里编一套分类，
            // 而那多半和用户的科目表对不上
            return MerchantCategorization.Unavailable("科目表里还没有分类")
        }

        val outcome = withTimeoutOrNull(TIMEOUT_MS) { run(merchants, categories) }
        return outcome ?: MerchantCategorization.Unavailable("归类超时")
    }

    private suspend fun run(
        merchants: List<UnknownMerchant>,
        categories: List<String>,
    ): MerchantCategorization = try {
        val accepted = client.submitTask(
            TaskEnvelope(
                requestId = ids.newId(),
                identity = Identity(userId = config.userId),
                input = TaskInput(text = requestText(merchants, categories)),
                declared = Declared(intent = INTENT_CATEGORIZE_MERCHANTS, authoritative = true),
                constraints = Constraints(
                    modePreference = Constraints.MODE_SYNC,
                    dataSensitivity = Constraints.SENSITIVITY_FINANCIAL,
                ),
                client = ClientInfo(
                    app = CLIENT_APP,
                    appVersion = BuildConfig.VERSION_NAME,
                    supportsSse = true,
                    supportsLastEventId = true,
                ),
            ),
        )

        val snapshot = awaitTerminal(accepted)
        if (snapshot?.status != TaskSnapshot.STATUS_SUCCEEDED) {
            // snapshot.error.detail 是服务端原文，可能带主机名/上游报错——不进界面。
            return MerchantCategorization.Unavailable(UserFacingErrors.CLASSIFY)
        }

        val parsed = parseMerchantCategories(client.getResult(accepted.taskId))
        if (parsed.isEmpty()) {
            return MerchantCategorization.Unavailable(UserFacingErrors.CLASSIFY)
        }
        MerchantCategorization.Suggested(
            merchants.map { merchant ->
                MerchantSuggestion(
                    payee = merchant.payee,
                    direction = merchant.direction,
                    // 没被提到的商户留空，界面让它保持兜底分类、由用户自己挑
                    categoryName = parsed[merchant.payee],
                )
            },
        )
    } catch (e: CancellationException) {
        // 取消必须往上抛：吞掉它，外层的超时与协程取消就都失效了
        throw e
    } catch (e: Exception) {
        // 连不上、契约缺能力、回包解不出来——对界面都是同一件事：**这次拿不到建议**，
        // 用户手工挑分类即可，不该弹一个他无能为力的错误框。
        // e.message 可能带着地址或上游错误体，只给白名单文案。
        MerchantCategorization.Unavailable(UserFacingErrors.CLASSIFY)
    }

    /**
     * 等到终态。
     *
     * `sync` 之下 `submitTask` 已经把任务跑完才返回，所以通常一次就够；
     * 但路由仲裁可能把它**提升为异步**（契约明写这个行为），那就得轮询。
     */
    private suspend fun awaitTerminal(accepted: TaskAccepted): TaskSnapshot? {
        var snapshot = runCatching { client.getTask(accepted.taskId) }.getOrNull()
        var attempts = 0
        while (snapshot != null && !snapshot.isTerminal && attempts < MAX_POLLS) {
            delay(POLL_INTERVAL_MS)
            snapshot = runCatching { client.getTask(accepted.taskId) }.getOrNull()
            attempts++
        }
        return snapshot
    }

    private fun requestText(merchants: List<UnknownMerchant>, categories: List<String>): String =
        json.encodeToString(
            MerchantCategorizeRequest.serializer(),
            MerchantCategorizeRequest(
                merchants = merchants.map { it.payee },
                categories = categories,
            ),
        )

    private companion object {
        const val CLIENT_APP = "bookkeeping"

        /**
         * 归类是**一批一次**的语言判断，比逐张票据的视觉抽取轻。
         * 给 60 秒：够一次正常的模型往返，又不至于让用户对着转圈干等三分钟。
         */
        const val TIMEOUT_MS = 60_000L

        const val POLL_INTERVAL_MS = 1_500L
        const val MAX_POLLS = 20
    }
}
