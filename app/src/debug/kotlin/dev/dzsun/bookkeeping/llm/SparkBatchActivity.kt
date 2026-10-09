package dev.dzsun.bookkeeping.llm

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import dev.dzsun.bookkeeping.feature.entry.LocalAiParser
import dev.dzsun.bookkeeping.feature.entry.SparkAiParser
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * T9 消融批量评估入口 —— **只进 debug 包**。
 *
 * 为什么要有它：35 条 × 若干档 × 若干设备，靠人点界面要 4 小时，而且界面文案会把
 * 模型原始输出遮掉。这里走的是**和正式记账完全相同的 [SparkAiParser]**，不是另写一套，
 * 所以跑出来的就是产品真实能力。
 *
 * 用法（模型与题库都要先放进 app 私有目录）：
 * ```
 * adb shell run-as dev.dzsun.bookkeeping sh -c 'ls files/'
 * adb shell am start -n dev.dzsun.bookkeeping/.llm.SparkBatchActivity \
 *      --es cases files/t9_cases.json --es out files/t9_results.json --es label 1.7B
 * adb shell run-as dev.dzsun.bookkeeping cat files/t9_results.json > results.json
 * ```
 * 进度逐条打到 logcat（tag `SparkBatch`），中途挂掉也能从日志里看出跑到第几条。
 */
class SparkBatchActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val casesRel = intent.getStringExtra(EXTRA_CASES) ?: DEFAULT_CASES
        val outRel = intent.getStringExtra(EXTRA_OUT) ?: DEFAULT_OUT
        val label = intent.getStringExtra(EXTRA_LABEL) ?: "unlabeled"

        scope.launch {
            try {
                run(casesRel, outRel, label)
            } catch (e: Throwable) {
                Log.e(TAG, "批量评估失败", e)
                writeResult(outRel, BatchResult(label, -1, null, "error: ${e.message}"))
            } finally {
                finish()
            }
        }
    }

    private suspend fun run(casesRel: String, outRel: String, label: String) {
        val casesFile = resolve(casesRel)
        if (!casesFile.exists()) {
            Log.e(TAG, "题库不存在: ${casesFile.absolutePath}")
            return
        }
        val cases = json.decodeFromString<List<BatchCase>>(casesFile.readText())
        Log.i(TAG, "开始评估 label=$label 共 ${cases.size} 条 → ${casesFile.absolutePath}")

        // 与正式记账同一个解析器、同一份系统提示
        val parser = SparkAiParser(SparkSession(this), this)
        val rows = ArrayList<BatchRow>(cases.size)

        cases.forEachIndexed { idx, c ->
            val t0 = System.currentTimeMillis()
            val outcome = parser.parseWithSource(c.text, genTimeoutMs = BATCH_GEN_TIMEOUT_MS)
            val ms = System.currentTimeMillis() - t0
            val first = outcome.entries.firstOrNull()
            rows += BatchRow(
                id = c.id,
                text = c.text,
                entry = first != null,
                amount = first?.amountText,
                category = first?.categoryName,
                note = first?.note,
                source = outcome.source.name.lowercase(),
                ms = ms,
                raw = outcome.rawModelOutput,
            )
            Log.i(TAG, "[$label] ${idx + 1}/${cases.size} id=${c.id} " +
                "entry=${first != null} cat=${first?.categoryName ?: "-"} " +
                "amt=${first?.amountText ?: "-"} ${ms}ms src=${outcome.source}")
        }

        val total = rows.sumOf { it.ms }
        writeResult(outRel, BatchResult(label, total, rows, null))
        Log.i(TAG, "评估完成 label=$label 条数=${rows.size} 总耗时=${total}ms " +
            "平均=${if (rows.isEmpty()) 0 else total / rows.size}ms → $outRel")
    }

    /** 支持传绝对路径或相对 filesDir 的路径。 */
    private fun resolve(rel: String): File =
        if (rel.startsWith("/")) File(rel) else File(filesDir, rel)

    private fun writeResult(rel: String, result: BatchResult) {
        runCatching {
            resolve(rel).writeText(json.encodeToString(result))
            Log.i(TAG, "结果已写入 ${resolve(rel).absolutePath}")
        }.onFailure { Log.e(TAG, "写结果失败", it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) scope.cancel()
    }

    @Serializable
    data class BatchCase(val id: Int, val text: String)

    @Serializable
    data class BatchRow(
        val id: Int,
        val text: String,
        val entry: Boolean,
        val amount: String? = null,
        val category: String? = null,
        val note: String? = null,
        val source: String,
        val ms: Long,
        val raw: String? = null,
    )

    @Serializable
    data class BatchResult(
        val label: String,
        val totalMs: Long?,
        val rows: List<BatchRow>?,
        val error: String? = null,
    )

    companion object {
        private const val TAG = "SparkBatch"
        private const val EXTRA_CASES = "cases"
        private const val EXTRA_OUT = "out"
        private const val EXTRA_LABEL = "label"
        private const val DEFAULT_CASES = "t9_cases.json"
        private const val DEFAULT_OUT = "t9_results.json"

        /**
         * 批量评估的单条生成超时。界面用 90s 是为了不让用户干等，
         * 但批测宁可慢也不能把「模型思考超时」记成降级——那会污染对比表。
         */
        private const val BATCH_GEN_TIMEOUT_MS = 180_000L
    }
}
