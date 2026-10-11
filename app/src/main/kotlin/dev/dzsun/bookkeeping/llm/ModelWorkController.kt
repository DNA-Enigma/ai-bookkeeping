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
    /**
     * 下载专属：**本次会话**的起点字节（断点续传时 = 已有 .part 的大小）。
     * 速度 = (bytesDone - bytesStart) / 会话用时——不除以总用时，
     * 否则续传后平均速度被之前会话稀释，剩余时间严重低估。
     * 释放路径不设置它（起点恒 0，无歧义）。
     */
    val bytesStart: Long = 0L,
)

/** 后台长任务的种类。[NONE] = 当前没有需要守护的等待。 */
enum class ModelWorkKind { RELEASE, DOWNLOAD, INIT, GENERATE, NONE }

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
 * 一条**已生成、还没被界面取走**的回答。
 *
 * 它是「提问后切后台，回来回答已在」这件事的唯一真源，两条来路都落在这里：
 *  - 进程活着：生成收场时同时写内存这一份和磁盘那一份；
 *  - 进程被杀后重来：磁盘那份由 [ModelWorkController.attach] 播回内存。
 *
 * [answer] 刻意存**成品正文**而不是原始 token 流：成品已经过 ThinkingStripper
 * 剔除与 ReplyStreamFilter 判定，重放时不必（也不该）再跑一遍那些规则。
 *
 * [storedAtMs] 是 `SystemClock.elapsedRealtime()`——单调，不受改系统时间影响。
 * 它从**开机**起算，所以跨进程死亡仍然可比（同一次开机），跨重启就不在一条时间轴上，
 * 那条必须由 [pendingAnswerIsFresh] 拦掉。
 *
 * [ageLine] 是播回那一刻算好的「刚刚 / 2 分钟前」。只有**磁盘播回**的条目带它，
 * 内存里现生成的没有（就是此刻发生的事）——界面据此决定要不要说明这句的来路。
 */
data class PendingAnswer(
    val question: String,
    val answer: String,
    val ok: Boolean = true,
    val storedAtMs: Long? = null,
    val ageLine: String? = null,
    /** 随这条回答一起存下的一块**对 llm 层不透明**的数据（记账卡片等）。见 [TurnResult.payload]。 */
    val payload: String? = null,
)

/**
 * 陈旧判定的**纯函数核**：磁盘上的回答还该不该播回界面。
 *
 * 两条都要拦，缺一都会把假东西端给用户：
 *  - **[ModelWorkStore.CLOCK_INVALID]**：写这条的进程拿不到单调时钟，落盘的是哨兵值。
 *    它不能参与相减——相减会得到一个巨大的正数或负数，取决于开机早晚，表现成
 *    「随机过期」，排查起来最费时间。宁可当它陈旧。
 *  - **[maxAgeMs]**：真实时钟下超过这个年龄的回答不再算「刚答完」。
 *    用户三天后回来，界面上杵着一条三天前的回答，比什么都没有更误导——
 *    他会以为那是刚才那句话的答案。
 *  - **时钟倒退**（`age < 0`）：同一次开机内 elapsedRealtime 不会倒退，倒退等于
 *    这条比机器还老——不可能，判陈旧。不猜是哪一边的锅。
 *
 * [nowMs] 由调用方传（纯函数不自己摸时钟），测试给定值。
 */
internal fun pendingAnswerIsFresh(
    storedAtMs: Long?,
    nowMs: Long,
    maxAgeMs: Long,
): Boolean {
    if (storedAtMs == null || storedAtMs == ModelWorkStore.CLOCK_INVALID) return false
    val age = nowMs - storedAtMs
    if (age < 0L) return false
    return age <= maxAgeMs
}

/**
 * 播回时的年龄文案。与 [pendingAnswerIsFresh] **同一套不可用判据**（哨兵值、
 * 时钟倒退都返回 null）——两处判据必须一致，否则会出现「判为新鲜却说不清几时」的缝。
 */
internal fun pendingAnswerAgeLine(storedAtMs: Long?, nowMs: Long): String? {
    if (storedAtMs == null || storedAtMs == ModelWorkStore.CLOCK_INVALID) return null
    val age = nowMs - storedAtMs
    if (age < 0L) return null
    return when {
        age < 60_000L -> "刚刚"
        age < 60 * 60_000L -> "${age / 60_000L} 分钟前"
        else -> "${age / (60 * 60_000L)} 小时前"
    }
}

/** 一条待交付回答在磁盘/内存里的有效期。30 分钟：超过它，这句已答的不是用户刚问的事。 */
internal const val PENDING_MAX_AGE_MS = 30 * 60_000L

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
    private val store: ModelWorkStore,
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

    /**
     * **待交付回答**：已经生成完、但界面还没来得及取走的那一句。
     *
     * 界面订阅它做「回来回答已在」；每一次落定都同时写磁盘（[ModelWorkStore]），
     * 因为进程随时可能被系统在后台杀掉——那时这份内存跟着一起没，
     * 只有磁盘那份能把答案救回来。见 [attach]。
     */
    private val _pending = MutableStateFlow<PendingAnswer?>(null)
    val pending: StateFlow<PendingAnswer?> = _pending.asStateFlow()

    /**
     * 进程起来时把磁盘上那条还没被取走的回答播回内存。**必须在 Application.onCreate 调**。
     *
     * 只在内存里还没有待交付项时播——本进程刚刚自己生成过一条的话，它更新、
     * 且已经在 [pending] 里了，用磁盘上的旧一条盖掉等于让回答倒退。
     *
     * **不补发通知**：进程死掉之前那条「回答已生成」已经交给系统了
     * （通知的生命周期不属于应用进程，App 被杀它还在，用户没点就还看得见）。
     * 这里再发一次就是同一件事打扰两遍。
     *
     * [nowMs] 传 `SystemClock.elapsedRealtime()`，默认值只在测试路径成立以外的
     * 场合都可接受——生产调用方就该用真实时钟。
     */
    fun attach(nowMs: Long = SystemClock.elapsedRealtime()) {
        if (_pending.value != null) return
        val stored = store.readPending() ?: return
        if (!pendingAnswerIsFresh(stored.storedAtMs, nowMs, PENDING_MAX_AGE_MS)) {
            // 陈旧的不播，但也别留在盘上：留着的后果是「下次开机它又新鲜了」——
            // elapsedRealtime 归零重来，一条几小时的旧回答会被当成刚答完。
            store.clearPending()
            return
        }
        _pending.value = PendingAnswer(
            question = stored.question,
            answer = stored.answer,
            ok = stored.ok,
            storedAtMs = stored.storedAtMs,
            ageLine = pendingAnswerAgeLine(stored.storedAtMs, nowMs),
        )
    }

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

    // —— 下载（与释放同一口子，见类注释「下载 = 另一种进度源」） ——

    /**
     * 网络下载开始。[bytesStart] 是续传起点（.part 已有字节），总时长含之前会话，
     * 但速度只按本次会话算（见 [ModelWorkProgress.bytesStart]）。
     */
    fun reportDownloadStart(bytesStart: Long, bytesTotal: Long) {
        _progress.value = ModelWorkProgress(
            kind = ModelWorkKind.DOWNLOAD,
            phase = UiPhase.DOWNLOADING,
            startedAtMs = SystemClock.elapsedRealtime(),
            bytesDone = bytesStart,
            bytesTotal = bytesTotal,
            bytesStart = bytesStart,
        )
        notifyGuard()
    }

    /** 下载字节进度——与 [reportReleaseBytes] 同一条去抖路径。 */
    fun reportDownloadBytes(bytesDone: Long) {
        val current = _progress.value
        if (current.kind != ModelWorkKind.DOWNLOAD) return
        _progress.value = current.copy(bytesDone = bytesDone)
    }

    /**
     * 下载收场。校验不匹配自动重下时**不要调它**——那是同一次等待的继续，
     * 调了通知就闪「失败」。真正终态（成功 / 用户取消 / 二次校验仍失败）才调。
     */
    fun reportDownloadDone(ok: Boolean, message: String) {
        if (_progress.value.kind != ModelWorkKind.DOWNLOAD) return
        conclude(ModelWorkResult(ModelWorkKind.DOWNLOAD, ok, message))
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
     *
     * [question] 是这句回答对应的那句提问。**传它**界面才有办法把回答归位
     * （回来时用户问的那句话和答案得对得上，不然回答像凭空冒出来）。
     * 落定后写进 [pending]——见那里的注释「为什么必须是磁盘」。
     *
     * [payload] 跟着一起落盘的一块对 `llm` 层不透明的数据（记账卡片等），
     * 见 [PendingAnswer.payload]。
     */
    fun completeGenerate(
        ok: Boolean,
        message: String,
        question: String = "",
        payload: String? = null,
    ) {
        val current = _progress.value
        if (current.kind == ModelWorkKind.GENERATE) {
            conclude(ModelWorkResult(ModelWorkKind.GENERATE, ok, message))
            recordPending(question, message, ok, payload)
            return
        }
        // 补发分支的去重按**内容**判，不按「有没有过一条 GENERATE 结果」判。
        //
        // 这里原本写的是 `_result.value?.kind == GENERATE → return`，而
        // [consumeResult] 在全仓库**没有任何调用方**——于是第一条回答之后
        // `_result` 永远停在 GENERATE，**第二条及以后的回答被静默丢掉**
        // （既不通知也不落待交付）。这类 bug 的可怕之处在于它一声不响：
        // 用户问第二句时界面毫无反应，而日志里什么都没有。
        //
        // 按内容判的语义也更贴原意——「重复打扰」指的是同一次收场被回报两遍，
        // 不是「本进程以前答过一次」。用户把 pending 取走之后 [_pending] 归 null，
        // 于是同样的问题再答一次照样会通知，这是对的。
        val delivered = _pending.value
        if (delivered != null && delivered.question == question && delivered.answer == message) return
        postResultNotification(ModelWorkResult(ModelWorkKind.GENERATE, ok, message))
        _result.value = ModelWorkResult(ModelWorkKind.GENERATE, ok, message)
        recordPending(question, message, ok, payload)
    }

    /**
     * 落定一条待交付回答：**先写磁盘再更新内存**。
     *
     * 这个顺序是刻意的——写盘之后进程哪怕立刻被杀，答案也已经在磁盘上了；
     * 反过来（先更内存）就有一个「内存里有、盘上没有」的窗口，而进程被杀偏偏
     * 就发生在这种窗口里（内存吃紧时系统优先杀的就是占着 1 GB 模型的后台进程）。
     *
     * 正文为空**什么都不写**：一条没有内容的回答播回界面只会是句怪话。
     * 失败收场照样落一条（[ok] = false），因为用户有权知道那句他等了 100 秒的话
     * 最后没答上来——切回来只看到初始状态，等于他的等待被静默吞掉了。
     */
    private fun recordPending(question: String, answer: String, ok: Boolean, payload: String?) {
        if (answer.isBlank()) return
        val at = runCatching { SystemClock.elapsedRealtime() }
            .getOrDefault(ModelWorkStore.CLOCK_INVALID)
        store.writePending(question, answer, ok, at, payload)
        _pending.value = PendingAnswer(
            question = question,
            answer = answer,
            ok = ok,
            storedAtMs = at,
            payload = payload,
        )
    }

    /**
     * 界面**已经消费**这条回答（已经渲染进消息流/面板）之后调。
     *
     * 内存与磁盘一起清——只清内存的话，下次冷启动 [attach] 会把同一条再播一遍，
     * 用户看到的是「同一句话答了两次」。
     *
     * 顺手把 [_result] 也清掉：那条通道按内容去重之后不再需要它当闸门，
     * 但留着一条陈旧的结果会让**别的可能订阅它的地方**读到过期数据。
     */
    fun consumePending() {
        if (_pending.value == null) return
        _pending.value = null
        store.clearPending()
        if (_result.value?.kind == ModelWorkKind.GENERATE) _result.value = null
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
        ModelWorkKind.DOWNLOAD -> phaseLabel(UiPhase.DOWNLOADING)
        ModelWorkKind.INIT -> "本机模型准备中"
        ModelWorkKind.GENERATE -> "本机模型思考中"
        ModelWorkKind.NONE -> return null
    }
    val elapsed = progress.startedAtMs
        ?.let { start -> nowMs?.let { (it - start).coerceAtLeast(0L) } }
    // 与页面（ModelProgressView）同口径：releasePercent 为 null = 没有真分母，
    // 不画进度条也不造「0 / 共 0 MB」，退回「阶段名 + 已用时」。
    val body = if (progress.kind == ModelWorkKind.DOWNLOAD &&
        releasePercent(progress.bytesDone, progress.bytesTotal) != null
    ) {
        downloadLine(progress.bytesDone, progress.bytesTotal, progress.bytesStart, elapsed)
    } else if (progress.kind == ModelWorkKind.RELEASE &&
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
        ModelWorkKind.DOWNLOAD -> if (result.ok) "模型下载完成" else "模型下载未完成"
        ModelWorkKind.NONE -> return null
    }
    return title to result.message.take(RESULT_SNIPPET_MAX)
}
