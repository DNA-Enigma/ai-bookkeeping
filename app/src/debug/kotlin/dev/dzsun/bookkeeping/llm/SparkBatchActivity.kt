package dev.dzsun.bookkeeping.llm

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dagger.hilt.android.EntryPointAccessors
import dev.dzsun.bookkeeping.feature.chat.ChatAiEntryPoint
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
 * 进度逐条打到 logcat（tag `SparkBatch`），中途挂掉也能从日志里看出跑到第几条；
 * 界面上同时显示 `n/N` 与当前阶段 + 已用时 —— 单条要跑十几秒，没有进度就只能盯着黑屏。
 */
class SparkBatchActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /** 界面上那几行字。批测是内部工具，用最朴素的 View，不引 Compose。 */
    private lateinit var counterView: TextView
    private lateinit var phaseView: TextView
    private lateinit var hintView: TextView

    @Volatile
    private var total = 0

    @Volatile
    private var done = 0

    /** 本批总起点（`SystemClock.elapsedRealtime()`，不用 currentTimeMillis：改系统时间会跳）。 */
    @Volatile
    private var startedAtMs = 0L

    @Volatile
    private var finished = false

    private val session: SparkSession by lazy {
        EntryPointAccessors.fromApplication(applicationContext, ChatAiEntryPoint::class.java)
            .sparkSession()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val casesRel = intent.getStringExtra(EXTRA_CASES) ?: DEFAULT_CASES
        val outRel = intent.getStringExtra(EXTRA_OUT) ?: DEFAULT_OUT
        val label = intent.getStringExtra(EXTRA_LABEL) ?: "unlabeled"

        setContentView(buildContentView(label))

        // 每秒刷新一次：`n/N` 来自 run() 的计数，阶段与已用时都来自引擎真实状态。
        scope.launch {
            while (isActive && !finished) {
                renderPhase()
                delay(1_000)
            }
        }

        scope.launch {
            try {
                run(casesRel, outRel, label)
            } catch (e: Throwable) {
                Log.e(TAG, "批量评估失败", e)
                writeResult(outRel, BatchResult(label, -1, null, "error: ${e.message}"))
            } finally {
                finished = true
                runOnUiThread { renderPhase() }
                finish()
            }
        }
    }

    private fun buildContentView(label: String): ScrollView {
        val pad = dp(20)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.WHITE)
        }

        fun line(sizeSp: Float, color: Int, topPadDp: Int = 0): TextView =
            TextView(this).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
                setTextColor(color)
                if (topPadDp > 0) setPadding(0, dp(topPadDp), 0, 0)
            }

        column.addView(
            line(15f, Color.BLACK).apply {
                text = "T9 批量评估 · $label"
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            },
        )
        counterView = line(26f, Color.BLACK, topPadDp = 12)
        phaseView = line(15f, Color.DKGRAY, topPadDp = 10)
        hintView = line(13f, Color.GRAY, topPadDp = 6)
        column.addView(counterView)
        column.addView(phaseView)
        column.addView(hintView)

        return ScrollView(this).apply { addView(column) }
    }

    /** 刷新界面上的三行字。计数与阶段都取自真实状态，没有估算出来的百分比。 */
    private fun renderPhase() {
        val elapsed = if (startedAtMs == 0L) null else SystemClock.elapsedRealtime() - startedAtMs
        val phase = phaseOf(session.state.value)
        val counter = when {
            total == 0 -> "准备中…"
            finished -> "已完成 $total/$total"
            else -> "第 ${(done + 1).coerceAtMost(total)}/$total 条"
        }
        val phaseText = if (finished) {
            "全部跑完"
        } else {
            waitHeadline(phase, elapsed)
        }
        runOnUiThread {
            counterView.text = counter
            phaseView.text = phaseText
            hintView.text = if (finished) "" else phaseHint(phase)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private suspend fun run(casesRel: String, outRel: String, label: String) {
        val casesFile = resolve(casesRel)
        if (!casesFile.exists()) {
            Log.e(TAG, "题库不存在: ${casesFile.absolutePath}")
            return
        }
        val cases = json.decodeFromString<List<BatchCase>>(casesFile.readText())
        total = cases.size
        startedAtMs = SystemClock.elapsedRealtime()
        Log.i(TAG, "开始评估 label=$label 共 ${cases.size} 条 → ${casesFile.absolutePath}")

        // 与正式记账**同一个单例解析器、同一份系统提示**（Hilt EntryPoint 取），
        // 否则自己 new 一份会把模型再加载一遍（模拟器实测 65s）
        val parser = EntryPointAccessors
            .fromApplication(applicationContext, ChatAiEntryPoint::class.java)
            .sparkAiParser()
        val rows = ArrayList<BatchRow>(cases.size)

        cases.forEachIndexed { idx, c ->
            val t0 = SystemClock.elapsedRealtime()
            val outcome = parser.parseWithSource(c.text, genTimeoutMs = BATCH_GEN_TIMEOUT_MS)
            val ms = SystemClock.elapsedRealtime() - t0
            val first = outcome.entries.firstOrNull()
            rows += BatchRow(
                id = c.id,
                text = c.text,
                entry = first != null,
                amount = first?.amountText,
                category = first?.categoryName,
                note = first?.note,
                source = outcome.source.kind.name.lowercase(),
                ms = ms,
                raw = outcome.rawModelOutput,
            )
            done = idx + 1
            Log.i(TAG, "[$label] ${idx + 1}/${cases.size} id=${c.id} " +
                "entry=${first != null} cat=${first?.categoryName ?: "-"} " +
                "amt=${first?.amountText ?: "-"} ${ms}ms src=${outcome.source.kind}")
        }

        val totalMs = rows.sumOf { it.ms }
        writeResult(outRel, BatchResult(label, totalMs, rows, null))
        Log.i(TAG, "评估完成 label=$label 条数=${rows.size} 总耗时=${totalMs}ms " +
            "平均=${if (rows.isEmpty()) 0 else totalMs / rows.size}ms → $outRel")
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
