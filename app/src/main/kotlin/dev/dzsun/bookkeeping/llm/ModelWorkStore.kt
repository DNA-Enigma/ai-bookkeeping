package dev.dzsun.bookkeeping.llm

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 磁盘上那条**待交付回答**的形状。
 *
 * [storedAtMs] 原样带出来（不折叠成「新鲜与否」的布尔），是因为调用方要在
 * **判定之后**还要用它：界面得告诉用户这句是「刚刚」答的还是「20 分钟前」答的。
 * 判陈旧是控制器的 [pendingAnswerIsFresh]，这里只负责如实交出内容。
 */
data class StoredPending(
    val question: String,
    val answer: String,
    val ok: Boolean,
    val storedAtMs: Long,
    val payload: String?,
)

/**
 * 一条待交付回答的**字节上限**，只管正文。
 *
 * 为什么必须有上限：SharedPreferences 是把整个文件读进内存再解析的，
 * 一条失控的长回答（模型重复吐同一个 token 是真实发生过的故障形态）会让
 * **每次进程启动**都去解析一个大字符串——而这份数据每次读出来只为显示一句话。
 * [PENDING_MAX_BYTES] 取 16 KB：实测最长回答远不到这个数，超出的必然是失控产物。
 * 超了就**截断而不是丢弃**——留半句真回答比什么都不留有用，界面本来也会把
 * 「这是半截」如实显示出来。
 */
internal const val PENDING_MAX_BYTES = 16 * 1024

/** 一个码点的 UTF-8 字节数。按码点算而不是按 char 算，才不会把一个代理对算成 4+3。 */
private fun utf8Size(codePoint: Int): Int = when {
    codePoint < 0x80 -> 1
    codePoint < 0x800 -> 2
    codePoint < 0x10000 -> 3
    else -> 4
}

/**
 * 超限的正文按 UTF-8 字节数截到 [PENDING_MAX_BYTES] 以内。
 *
 * **按码点走**（`CodePointIterator`），不按 char：一个 emoji 是两个 char（代理对），
 * 按 char 切可能正好落在中间，留下一个**孤高代理**——那串文本之后无论如何都
 * 修不回来，写进 JSON 会炸，显示出来是个豆腐块。切在码点边界上永远是合法字符串。
 *
 * 长度先做一次快判：`String.length`（char 数）是 UTF-8 字节数的**下界**，
 * 它都没超限就一定没超限，不必逐码点走一遍。
 */
internal fun clampPendingAnswer(answer: String): String {
    if (answer.length <= PENDING_MAX_BYTES) return answer
    var bytes = 0
    var i = 0
    while (i < answer.length) {
        val c = answer[i]
        val size: Int
        val step: Int
        if (Character.isHighSurrogate(c) && i + 1 < answer.length &&
            Character.isLowSurrogate(answer[i + 1])
        ) {
            // 完整的代理对 = 一个补充平面码点 = 4 字节，跨 2 个 char
            size = 4
            step = 2
        } else {
            size = utf8Size(c.code)
            step = 1
        }
        if (bytes + size > PENDING_MAX_BYTES) break
        bytes += size
        i += step
    }
    return answer.substring(0, i)
}

/**
 * 待交付回答的**磁盘落点**——进程被杀之后，「回答已在」这件事唯一的凭据。
 *
 * ## 为什么必须是磁盘，内存单例不够
 *
 * [ModelWorkController] 是 `@Singleton`，进程活着时它的 `StateFlow` 就是真源，
 * 切后台再回来读它就行。但「切后台」这件事本身不保证进程还在：系统在内存吃紧时
 * 会直接杀掉后台进程，**没有回调、没有异常**，进程级单例跟着一起没。
 * 用户回来看到的是 App 冷启动，那条他等了 100 秒的回答如果不曾落盘，就永久丢了
 * ——而界面会显示成一个从没人问过的初始状态。那是最坏的一种「丢了」：无声。
 *
 * 所以生成一收场就写这里，进程起来时由 [ModelWorkController.attach] 播回内存。
 * 内存那份负责实时渲染，磁盘那份负责跨进程死亡；两者内容同源，不会各说各话。
 *
 * ## 为什么用 SharedPreferences 而不是 Room
 *
 * 生命周期是**分钟级、单条、覆盖写**：新的回答顶掉旧的，取走就清。
 * Room 那边要为此加一张表 + 一次迁移，而这条数据不参与任何查询与聚合，
 * 迁移的代价（和账本一起备份、一起升级）全落在没有需要的地方。
 * prefs 是这个形状的数据该去的地方——与
 * `feature/ledger/MonthlyBudgetStore`、`feature/settings/AgentPrefsStore` 同一套路。
 *
 * ## 时钟口径（这里最容易出错，改前先读）
 *
 * 落盘的时间用的是 `SystemClock.elapsedRealtime()`（单调，不受改系统时间影响），
 * 而它**从开机起算**，所以跨「进程被杀后重启」仍然可比，跨**重启手机**就不在一
 * 条时间轴上了。无法拿到该时钟的调用方传 [CLOCK_INVALID]，
 * [ModelWorkController] 会把那条当陈旧——宁可少播一条真回答，
 * 也不播一条不知道什么时候的。判据在 [pendingAnswerIsFresh]。
 *
 * 这条通路只存**用户自己问出去的一句话和模型的回话**，不涉及账目金额，
 * 因此不落敏感数据；MODE_PRIVATE 的私有文件，别的应用读不到。
 */
@Singleton
class ModelWorkStore @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 写入一条待交付回答。覆盖式——**同时只保最新的一条**。
     *
     * 为什么不排队：一轮问答只有一个提问方（就是那个正在等的人），
     * 攒两条等于让界面去猜哪条是他刚问的，猜错的展示比不展示更糟。
     */
    fun writePending(question: String, answer: String, ok: Boolean, storedAtMs: Long, payload: String? = null) {
        prefs.edit()
            .putString(KEY_QUESTION, question)
            .putString(KEY_ANSWER, answer)
            .putBoolean(KEY_OK, ok)
            .putLong(KEY_AT, storedAtMs)
            .putString(KEY_PAYLOAD, payload)
            .apply()
    }

    /**
     * 读回待交付回答。没有就返回 null——**「没写过」和「写坏了」在这里都归 null**，
     * 因为两种情况的处理完全一样：界面上什么都不播。
     * 只有正文缺失才叫真没有；问题或正文为空串的残档也一并当没有（写入不可能产生空正文）。
     */
    fun readPending(): StoredPending? {
        val answer = prefs.getString(KEY_ANSWER, null) ?: return null
        if (answer.isEmpty()) return null
        return StoredPending(
            question = prefs.getString(KEY_QUESTION, "").orEmpty(),
            answer = answer,
            ok = prefs.getBoolean(KEY_OK, true),
            storedAtMs = prefs.getLong(KEY_AT, CLOCK_INVALID),
            payload = prefs.getString(KEY_PAYLOAD, null),
        )
    }

    /** 界面取走之后清掉。**取走即清**，否则重启会把同一条回答再播一次。 */
    fun clearPending() {
        prefs.edit()
            .remove(KEY_QUESTION)
            .remove(KEY_ANSWER)
            .remove(KEY_OK)
            .remove(KEY_AT)
            .remove(KEY_PAYLOAD)
            .apply()
    }

    companion object {
        const val PREFS_NAME = "model_work"
        const val KEY_QUESTION = "pending_question"
        const val KEY_ANSWER = "pending_answer"
        const val KEY_OK = "pending_ok"
        const val KEY_AT = "pending_at_ms"
        const val KEY_PAYLOAD = "pending_payload"

        /**
         * 拿不到单调时钟时的占位值。它**不是 0**：0 是「开机那一刻」，是个合法时刻，
         * 用它当哨兵会让「不知道什么时候」和「刚开机时写的」混成一件事。
         */
        const val CLOCK_INVALID = -1L
    }
}
