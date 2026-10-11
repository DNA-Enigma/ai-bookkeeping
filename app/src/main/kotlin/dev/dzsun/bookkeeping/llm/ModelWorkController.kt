package dev.dzsun.bookkeeping.llm

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.isUninterruptible
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 一次后台长任务（释放 / 加载 / 生成）的**实时进度**。
 *
 * 数字全部来自真实状态，不造：
 *  - [phase] 由引擎状态机经 [phaseOf] 推导，[RELEASE] 只认安装器上报的字节数；
 *  - [bytesDone] / [bytesTotal] 只有 [RELEASE] 有值，其余阶段是 -1 —— **没有分母不造百分比**；
 *  - [startedAtMs] 是 `SystemClock.elapsedRealtime()` 下的**起点**（单调时钟），
 *    不是时长——喂给 [releaseLine] / [waitHeadline] 前必须先和当前时刻相减。
 */
data class ModelWorkProgress(
    val kind: ModelWorkKind = ModelWorkKind.NONE,
    val phase: UiPhase = UiPhase.IDLE,
    val startedAtMs: Long? = null,
    val bytesDone: Long = -1L,
    val bytesTotal: Long = -1L,
)

/** 后台长任务的种类。[NONE] = 当前没有需要守护的等待。 */
enum class ModelWorkKind { RELEASE, INIT, GENERATE, NONE }

/**
 * 一次长任务的**收场**：成功、失败（带真实原因）、或被顶替/取消。
 *
 * 「回答已生成」这条通知的内容就从 [message] 来——它是**真实产出摘要或真实失败原因**，
 * 不是一句没有信息的「完成了」。
 */
data class ModelWorkResult(
    val kind: ModelWorkKind,
    val ok: Boolean,
    val message: String,
)

/**
 * 端侧模型长任务的**后台守护中枢**：谁把一条等待登记进来，前台服务就为它挂通知。
 *
 * ## 为什么状态要在控制器而不是服务里攒
 *
 * 服务随时可能被系统杀掉重建，而「释放到第几 MB / 引擎到了哪个阶段」
 * 的真源本来就在进程里：引擎状态机在 `SparkSession.state`，释放字节数在
 * 安装器回调里。控制器是**进程级单例**，把这两条流合成一份 [StateFlow]，
 * 服务只是它的一个订阅者；用户回到 App 后，页面订阅的是**同一份**状态——
 * 不存在「通知说 80%、页面说加载中」这种两份状态打架。
 *
 * ## 两种通知，两条投递路径（改前先读）
 *
 *  - **进度通知**（CHANNEL_WORK，ongoing）：由 [ModelWorkService] 渲染，
 *    服务活着它就活着，服务死了它跟着没。
 *  - **结果通知**（CHANNEL_RESULT，「回答已生成」）：由**控制器自己**直接投递
 *    （[postResultNotification]），**不经过服务**——收场时 [conclude] 会先停掉
 *    服务，服务 onDestroy 又会 cancel 自己那条进度通知；结果要是也交给服务，
 *    就会在同一瞬间被掐掉。结果通知必须比服务的生命周期更独立。
 *
 * ## 它不做什么（边界，改前先读）
 *
 * 1. **不发起任何模型操作**。加载仍由 `SparkSession.ensureReady()`、生成仍由
 *    各页面的 ViewModel 发起——控制器只**观察**，不指挥。这样「回来不得重新
 *    loadModel」那条铁律不会被这里破坏：属主还是 SparkSession，状态机只有一个。
 * 2. **不解析文件**。释放进度由调用方（设置页那条路）通过 [reportReleaseBytes]
 *    喂进来——那个回调本来就有，挂进来就行。
 * 3. **不判权限、不弹系统对话框**。权限在 [NotificationGate]（UI 层），
 *    控制器只透出 [permissionHint]，投递前查一次 [NotificationGate.canNotify]。
 *
 * ## 「下载 = 另一种进度源」的口子（本期不实现网络部分）
 *
 * [ModelWorkKind] 与 [ModelWorkProgress] 的形状是按进度源设计的：
 * 将来做网络下载时，加一个 `DOWNLOAD` 种类、让下载器往 [reportReleaseBytes]
 * 同一个口子喂「已下 / 共多少字节」即可——阶段、文案、通知、进度条全部复用，
 * 服务一行不用改。
 */
@Singleton
class ModelWorkController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: SparkSession,
    private val gate: NotificationGate,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _progress = MutableStateFlow(ModelWorkProgress())
    val progress: StateFlow<ModelWorkProgress> = _progress.asStateFlow()

    private val _result = MutableStateFlow<ModelWorkResult?>(null)
    val result: StateFlow<ModelWorkResult?> = _result.asStateFlow()

    /**
     * 引擎是否正处在**不可中断的长阶段**（加载权重 / 解码系统提示 / 生成中）。
     *
     * 注意 `isUninterruptible`（引擎包里的扩展属性）**不含 Generating**——它按
     * 「能不能调 stop」定义，而这里的语义是「能不能走开」：生成中走开照样继续，
     * 所以生成要单独补上。
     */
    private val _engineBusy = MutableStateFlow(false)
    val engineBusy: StateFlow<Boolean> = _engineBusy.asStateFlow()

    private var engineJob: Job? = null

    /** 前台服务是否存在（通知栏守护是否活着）。服务自己启停时回写。 */
    private val _guardActive = MutableStateFlow(false)
    val guardActive: StateFlow<Boolean> = _guardActive.asStateFlow()

    init {
        // 引擎状态机是「加载/生成」两类等待的真源：一进生成/加载类状态就登记，
        // 回到稳态（ModelReady / Uninitialized / Error）就结账。
        engineJob = scope.launch {
            session.state.collectLatest { state ->
                _engineBusy.value = state.isUninterruptible ||
                    state is InferenceEngine.State.Generating
                onEngineState(state)
            }
        }
    }

    private fun onEngineState(state: InferenceEngine.State) {
        val phase = phaseOf(state)
        when {
            // 进行中：登记（已登记则原地推进阶段，起点不动）
            phase == UiPhase.LOADING_WEIGHTS ||
                phase == UiPhase.DECODING_PROMPT ||
                phase == UiPhase.STARTING ->
                beginOrUpdate(ModelWorkKind.INIT, phase)

            phase == UiPhase.GENERATING ->
                beginOrUpdate(ModelWorkKind.GENERATE, phase)

            // 稳态：结账 + 清空。**这里之后不再有任何写 _progress 的语句**——
            // 否则会把刚清空的等待又写成幽灵进度。
            else -> finishFromEngine(state, phase)
        }
    }

    private fun beginOrUpdate(kind: ModelWorkKind, phase: UiPhase) {
        val current = _progress.value
        if (current.kind == kind) {
            // 同一种等待的阶段推进（LOADING_WEIGHTS → DECODING_PROMPT）：只改阶段
            if (current.phase != phase && current.kind == ModelWorkKind.INIT) {
                _progress.value = current.copy(phase = phase)
            }
            return
        }
        _progress.value = ModelWorkProgress(
            kind = kind,
            phase = phase,
            startedAtMs = SystemClock.elapsedRealtime(),
        )
        notifyGuard()
    }

    private fun finishFromEngine(state: InferenceEngine.State, phase: UiPhase) {
        val current = _progress.value
        when {
            current.kind == ModelWorkKind.INIT && phase == UiPhase.IDLE &&
                (state is InferenceEngine.State.ModelReady || state is InferenceEngine.State.Error) -> {
                val elapsed = current.startedAtMs
                    ?.let { (SystemClock.elapsedRealtime() - it).coerceAtLeast(0L) } ?: 0L
                conclude(
                    ModelWorkResult(
                        kind = ModelWorkKind.INIT,
                        ok = state is InferenceEngine.State.ModelReady,
                        message = if (state is InferenceEngine.State.Error) {
                            issueMessage(
                                ModelIssue.LoadFailed(
                                    state.exception.message
                                        ?: state.exception.javaClass.simpleName,
                                ),
                            )
                        } else {
                            "模型已就绪（用时 ${formatElapsed(elapsed)}）"
                        },
                    ),
                )
            }

            current.kind == ModelWorkKind.GENERATE && phase == UiPhase.IDLE &&
                (state is InferenceEngine.State.ModelReady || state is InferenceEngine.State.Error) -> {
                // 生成收场的**正文**只有调用方有（最终文本或失败原因）——引擎先回稳态时
                // 静默清空等待，结果通知由调用方随后的 completeGenerate 补发。
                // 在这里替它编一条「完成了」是造假；提前发会挡掉调用方那条有内容的。
                _progress.value = ModelWorkProgress()
                notifyGuard()
            }

            current.kind != ModelWorkKind.NONE && phase == UiPhase.IDLE -> {
                _progress.value = ModelWorkProgress()
                notifyGuard()
            }
        }
    }

    /**
     * 释放内置模型的进度入口——**设置页那条路在安装器回调里调它**。
     *
     * 通知栏的确定性进度条（已释放 X / 1056 MB）吃的就是这个；
     * 页面里的进度条与它同源，不会各说各话。
     *
     * @param bytesTotal 总字节；安装器 `expectedBytes()` 的值
     */
    fun reportReleaseStart(bytesTotal: Long) {
        _progress.value = ModelWorkProgress(
            kind = ModelWorkKind.RELEASE,
            phase = UiPhase.RELEASING,
            startedAtMs = SystemClock.elapsedRealtime(),
            bytesDone = 0L,
            bytesTotal = bytesTotal,
        )
        notifyGuard()
    }

    fun reportReleaseBytes(bytesDone: Long) {
        val current = _progress.value
        if (current.kind != ModelWorkKind.RELEASE) return
        _progress.value = current.copy(bytesDone = bytesDone)
        // 字节进度不触发启停，只是让已活着的服务刷通知（collectLatest 会去抖）
    }

    /**
     * 释放结束：成功传 [ok] = true；失败把安装器的失败信息原样带给通知。
     * 之后若继续加载（设置页的既有流程），[onEngineState] 会接手 INIT 段。
     */
    fun reportReleaseDone(ok: Boolean, message: String) {
        if (_progress.value.kind != ModelWorkKind.RELEASE) return
        conclude(ModelWorkResult(ModelWorkKind.RELEASE, ok, message))
    }

    /**
     * 生成收场的**内容回报口**——调用方（面板/对话页/问账页）拿到最终正文或
     * 失败原因后调它，通知栏的「回答已生成」才有真实内容。
     *
     * 两个时序都正确收敛（谁先到都行）：
     *  - **调用方先**（GENERATE 等待还挂着）：这里收场——清等待、停服务、发结果通知。
     *  - **引擎先**（回稳态已把等待清空，见 [finishFromEngine] 的 GENERATE 分支）：
     *    这里补发结果。迟到的「已生成」发的是真实产出，不误导。
     *  - 等待从未登记（响应极短，StateFlow 合流跳过了 Generating）：
     *    同样落到补发分支——结果照样有通知。
     * 已有一条 GENERATE 结果未消费时忽略本次，避免重复打扰。
     */
    fun completeGenerate(ok: Boolean, message: String) {
        val current = _progress.value
        if (current.kind == ModelWorkKind.GENERATE) {
            conclude(ModelWorkResult(ModelWorkKind.GENERATE, ok, message))
            return
        }
        if (_result.value?.kind == ModelWorkKind.GENERATE) return
        postResultNotification(ModelWorkResult(ModelWorkKind.GENERATE, ok, message))
        _result.value = ModelWorkResult(ModelWorkKind.GENERATE, ok, message)
    }

    /**
     * 收场三件事：**先发结果通知，再写状态，再清等待**。
     * 顺序没有魔法——结果通知只依赖入参，先发能保证即使后续状态写坏了，
     * 用户也已经拿到了那条「回答已生成」。
     */
    private fun conclude(result: ModelWorkResult) {
        postResultNotification(result)
        _result.value = result
        if (_progress.value.kind == result.kind) {
            _progress.value = ModelWorkProgress()
            notifyGuard() // 等待清空 → 这里的分支会走 stopGuard
        }
    }

    /**
     * 结果通知由控制器直接投递（不经服务）——原因见类注释「两种通知」。
     * 权限被拒时安静跳过：降级路径是页面内进度 + [permissionHint]，这里不崩。
     */
    private fun postResultNotification(result: ModelWorkResult) {
        val pair = resultText(result) ?: return
        gate.ensureChannels()
        if (!gate.canNotify()) return
        runCatching {
            val tap = PendingIntent.getActivity(
                context,
                0,
                context.packageManager.getLaunchIntentForPackage(context.packageName)
                    ?: Intent().apply { setPackage(context.packageName) },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, NotificationGate.CHANNEL_RESULT)
                .setSmallIcon(NotificationGate.SMALL_ICON_RES)
                .setContentTitle(pair.first)
                .setContentText(pair.second)
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(RESULT_NOTIFICATION_ID, notification)
        }
    }

    /** 把当前结果标记为已消费（界面展示过后清掉，避免重复入页）。 */
    fun consumeResult() {
        _result.value = null
    }

    /**
     * 通知权限被永久拒绝时的降级提示（界面按需显示）。
     * 空串 = 没有需要提示的。
     */
    val permissionHint: String get() = "通知权限未开启，切到后台时看不到进度提醒；页面内的进度不受影响。"

    // —— 服务启停的桥（服务不存在时调用是安全的：startService 会创建它） ——

    /** 通知服务要活着：登记了等待后调。服务没起就把它拉起来。 */
    fun startGuard() {
        if (_guardActive.value) return
        // 后台启动 FGS 在 12+ 会被直接拒绝（ForegroundServiceStartNotAllowedException）：
        // 捕住降级为页面内进度——等待本身不依赖服务，服务只是它的通知出口。
        runCatching {
            context.startForegroundService(Intent(context, ModelWorkService::class.java))
        }
        _guardActive.value = true
    }

    /** 没有需要守护的等待了：把服务停掉（服务自己也停，这里是主动方的口子）。 */
    fun stopGuard() {
        if (!_guardActive.value) return
        context.stopService(Intent(context, ModelWorkService::class.java))
        _guardActive.value = false
    }

    /** 服务 onDestroy 时回写，让下一次 startGuard 不因状态残留被跳过。 */
    fun markGuardStopped() {
        _guardActive.value = false
    }

    private fun notifyGuard() {
        if (_progress.value.kind == ModelWorkKind.NONE) {
            stopGuard()
        } else {
            startGuard()
        }
    }

    fun dispose() {
        engineJob?.cancel()
        scope.cancel()
    }

    private companion object {
        /** 结果通知的 ID，与服务的进度通知（2001）分开，互不覆盖。 */
        const val RESULT_NOTIFICATION_ID = 2002
    }
}

/** 通知上收场正文的长度上限——再长也只取摘要，全文回页面里看。 */
private const val RESULT_SNIPPET_MAX = 120

/**
 * 进度通知文案的**纯函数核**——服务只是把它渲染出来，文案在这里逐字可测。
 *
 *  - 标题：RELEASE → 阶段名；INIT →「本机模型准备中」；GENERATE →「本机模型思考中」。
 *  - 正文：RELEASE 是 `已释放 X / 共 Y MB · Z%`（复用 [releaseLine]，口径与页面一致）；
 *    其余是 `<阶段名> · 已用 <时长>`。
 *  - [nowMs] 传 `SystemClock.elapsedRealtime()`：纯函数不自己摸时钟，测试给定值。
 *    [ModelWorkProgress.startedAtMs] 是**起点**不是时长，这里负责相减。
 *
 * 没有可展示的进行中等待 → 返回 null（调用方据此停服务）。
 */
internal fun progressText(progress: ModelWorkProgress, nowMs: Long?): Pair<String, String>? {
    if (progress.kind == ModelWorkKind.NONE || progress.phase == UiPhase.IDLE) return null
    val title = when (progress.kind) {
        ModelWorkKind.RELEASE -> phaseLabel(UiPhase.RELEASING)
        ModelWorkKind.INIT -> "本机模型准备中"
        ModelWorkKind.GENERATE -> "本机模型思考中"
        ModelWorkKind.NONE -> return null
    }
    val elapsed = progress.startedAtMs
        ?.let { start -> nowMs?.let { (it - start).coerceAtLeast(0L) } }
    // 与页面（ModelProgressView）同口径：releasePercent 为 null = 没有真分母，
    // 不画进度条也不造「0 / 共 0 MB」，退回「阶段名 + 已用时」。
    val body = if (progress.kind == ModelWorkKind.RELEASE &&
        releasePercent(progress.bytesDone, progress.bytesTotal) != null
    ) {
        releaseLine(progress.bytesDone, progress.bytesTotal, elapsed)
    } else {
        waitHeadline(progress.phase, elapsed)
    }
    return title to body
}

/**
 * 结果通知文案的**纯函数核**：按种类和成败给标题，正文是真实摘要/失败原因。
 * [ModelWorkKind.NONE] 不是一次收场 → null。
 */
internal fun resultText(result: ModelWorkResult): Pair<String, String>? {
    val title = when (result.kind) {
        ModelWorkKind.GENERATE -> if (result.ok) "回答已生成" else "生成没有完成"
        ModelWorkKind.INIT -> if (result.ok) "模型已就绪" else "模型加载失败"
        ModelWorkKind.RELEASE -> if (result.ok) "模型资产释放完成" else "模型资产释放失败"
        ModelWorkKind.NONE -> return null
    }
    return title to result.message.take(RESULT_SNIPPET_MAX)
}
