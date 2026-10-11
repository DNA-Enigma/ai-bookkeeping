package dev.dzsun.bookkeeping.llm

import android.content.Context
import android.os.SystemClock
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 一轮**正在跑**的问答。切回来时界面读它接上现场。
 *
 * [draft] 是已经吐出来的正文（可能为空——还在加载模型），[thought] 是思考素材原文。
 * 两者都是**过程量**：这一轮结束就随 [ModelWorkRunner.turn] 一起归 null，
 * 成品正文走 [ModelWorkController.pending] 那条路，不要在这里找。
 */
data class RunningTurn(
    val question: String,
    val draft: String = "",
    val thought: String = "",
    val startedAtMs: Long = 0L,
)

/**
 * 一轮生成的**收场**。
 *
 * [answer] 是给用户看的成品正文（失败时放真实原因）。空串代表这一轮没有产出——
 * [ModelWorkController] 见到空正文**什么都不落**，一句空回答播回界面只会是句怪话。
 *
 * `ok = false` 时界面**照样要显示** [answer]：用户有权知道那句他等了一百秒的话
 * 最后没答上来、以及为什么。切回来只看到初始状态，等于他的等待被静默吞掉了。
 *
 * [payload] 是一块对 `llm` 层**不透明**的数据，由调用方自己编码、自己解读，
 * 随这条回答一起落盘、一起播回。它存在的理由很具体：记账那条路的产出是
 * **一张能点「记下来」的卡片**（金额/分类/备注），纯字符串存不住它——
 * 只恢复文字的话，用户回来看到识别结果却入不了账，那还是丢了一次。
 * 而 `llm` 层不该认识 `feature.entry.ParsedEntry`（单向依赖，AGENTS.md 第一节分工），
 * 所以这里给一个不透明口子，形状归调用方。
 */
data class TurnResult(
    val answer: String,
    val ok: Boolean = true,
    val payload: String? = null,
)

/**
 * 一轮生成往外交吐东西的口。
 *
 * 分成两个方法而不是一条 `Flow<String>`：正文与思考素材在**剔除之后**才分别成形，
 * 混成一条就退化成「每个调用方自己再跑一遍 ThinkingStripper」——那是
 * [SparkLlm] 类注释里明确不要的复制。
 */
interface TurnSink {
    /** 一块**可展示**的正文增量（已剔除思考段与控制 token）。 */
    fun answer(delta: String)

    /** 一块思考素材原文。 */
    fun thought(delta: String)
}

/**
 * 问答轮次的**进程级执行器**：一句话交给它，它在一个不随界面销毁的作用域里跑完。
 *
 * # 现有的 `rememberCoroutineScope()` 为什么不够
 *
 * 面板原先在组合作用域里 `scope.launch { streamTurn(text).collect { … } }`。
 * 那条 Job 的生命周期**等于 Composable 的生命周期**，于是这三种日常操作都会
 * 就地掐掉一轮正在生成的回答：
 *
 *  - 点面板的「收起」（首页 `aiPanel = false`）；
 *  - 从面板跳去对话页/问账页（跳走时面板被收起）；
 *  - 系统在后台把进程杀掉。
 *
 * 前两种尤其常见，而且**没有任何报错**——用户只会觉得「这 AI 答到一半就哑了」。
 * 生成本身不会因为收集方消失而停（引擎跑在自己的单线程 dispatcher 上，见 [SparkLlm]
 * 的线程注释），停掉它的是那个被一起取消的 Job。
 *
 * 所以这里换成 `SupervisorJob() + Dispatchers.Main.immediate` 的**单例作用域**，
 * 与任何页面同寿——页面没了它还在。这一条解决「界面销毁」；「进程被杀」由
 * [ModelWorkStore] 落盘解决，**两者缺一不可**：只做前者，冷启动照样丢答案。
 *
 * # 为什么是 Main 而不是 Default
 *
 * 后台并不冻结主线程：Activity 进后台后主 Looper 照常派发任务，只是不出帧。
 * 而 block 里要写 [TurnSink]，那些状态对象现有的写法与页面里一样是在主线程发生的。
 * 挪到 Default 等于无端引入「跨线程写快照状态」这个新风险，性能一分不赚——
 * 重活本来就在引擎自己的 dispatcher 上。**收集留在主线程，存活靠作用域不靠线程。**
 *
 * # 分工（三条别混）
 *
 *  - 本类**发起**生成，是「轮次的属主」。
 *  - [SparkSession] 仍是**模型与系统提示的唯一属主**：本类只调它已有的 `ensureReady`
 *    与生成入口，那些入口自带挂起锁，两条轮次并发也不会重复 loadModel
 *    ——AGENTS.md 第二节第 6 条要的就是这个，本类没有开第二个属主。
 *  - [ModelWorkController] **不发起**任何模型操作（那是它的边界注释），只观察引擎状态、
 *    挂通知、落待交付回答。本类跑完调 [ModelWorkController.completeGenerate]。
 *    「观察 vs 指挥」的分界因此仍然干净：控制器连一条生成都没发起过。
 *
 * # 为什么 block 由调用方传进来
 *
 * 「一轮问答」的语义——走 MODE_CHAT 还是 MODE_PARSE、输出怎么解读、金额保真怎么校验、
 * 降级话术怎么说——全在 `feature/chat` 那边。这个类在 `llm` 层，不该认识那些。
 * 它只提供「在一个不会死的作用域里跑一个 suspend block，并把过程量透出」这一件事。
 *
 * # 边界（改前先读）
 *
 * 本类**不做超时**：预算与降级话术归调用方（面板那条 `PANEL_TURN_BUDGET_MS`），
 * 免得同一条链出现两份超时预算。也**不做取消传播给引擎**——
 * 引擎的 `stop` 语义与「不可中断阶段」由 [SparkSession] 那边管。
 */
@Singleton
class ModelWorkRunner @Inject constructor(
    private val controller: ModelWorkController,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _turn = MutableStateFlow<RunningTurn?>(null)

    /** 当前正在跑的那一轮；null = 没有。界面读它把草稿接着上屏。 */
    val turn: StateFlow<RunningTurn?> = _turn.asStateFlow()

    private var job: Job? = null

    /**
     * 发起一轮。**同一句话重复发起会被忽略**（[turn] 已经是这一句了）——
     * 这条防的是「页面重组后把上一条又跑一遍」。模型一轮实测几十秒到三分半，
     * 白跑一次是用户等不起的，所以宁可不跑也不能跑两遍。
     *
     * block 返回的 [TurnResult] 会交给 [ModelWorkController.completeGenerate]：
     * 落待交付回答 + 落盘 + 发通知，三条都在那一处做完。
     */
    fun start(question: String, block: suspend (TurnSink) -> TurnResult) {
        if (_turn.value?.question == question) return
        // 换问题 = 上一轮作废。先取消它，否则两条轮次都会往控制器落待交付回答，
        // 后写完的那条盖掉前一条，用户看到的顺序是反的。
        job?.cancel()
        val started = RunningTurn(question, startedAtMs = SystemClock.elapsedRealtime())
        _turn.value = started
        job = scope.launch {
            // sink 只认**自己这一轮**：旧轮次被取消后，它最后几块 delta 可能才落地，
            // 那时 _turn 已经是新问题的一条了——不认身份就会把上一轮的字写进下一轮。
            val sink = object : TurnSink {
                override fun answer(delta: String) {
                    if (delta.isEmpty()) return
                    _turn.update { if (it?.question == question) it.copy(draft = it.draft + delta) else it }
                }

                override fun thought(delta: String) {
                    if (delta.isEmpty()) return
                    _turn.update { if (it?.question == question) it.copy(thought = it.thought + delta) else it }
                }
            }
            val result = try {
                block(sink)
            } catch (e: CancellationException) {
                // 只有**本类主动取消**（换问题 / cancel / dispose）才会走到这里：
                // 这个 scope 不挂在任何页面上，页面销毁取消不到它。
                // 不落待交付、不发通知——用户自己不要的那一轮不该回来时又冒出来。
                _turn.value = null
                return@launch
            } catch (e: Throwable) {
                TurnResult(answer = e.message ?: e.javaClass.simpleName, ok = false)
            }
            _turn.value = null
            job = null
            controller.completeGenerate(
                ok = result.ok,
                message = result.answer,
                question = question,
                payload = result.payload,
            )
        }
    }

    /**
     * 取消正在跑的这一轮（用户显式点「停止」才该调它）。
     * 只清状态，**不落待交付回答**——主动停掉的东西不该回来时冒出来。
     */
    fun cancel() {
        job?.cancel()
        job = null
        _turn.value = null
    }

    /** 释放（单例一般不用，留口子给测试）。 */
    fun dispose() = cancel()
}

/**
 * 问答链路的 Hilt 入口。
 *
 * 悬浮面板（`feature/chat/AiFloatPanel`）**刻意不挂 ViewModel**，
 * 但要把这两个单例取出来——与 `feature/chat/ChatAiEntryPoint` 同一个套路。
 * 这里另开一个而不是往那个接口里加方法，是为了让改动全落在 `llm` 层内
 * （谁改哪块目录有明确分工，见 AGENTS.md 第一节；那个文件不是我这块）。
 *
 * **不许**用它 new 实例：绕过单例等于绕过「模型与轮次各只有一个属主」那条铁律。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ModelWorkEntryPoint {
    fun runner(): ModelWorkRunner
    fun controller(): ModelWorkController
}

/**
 * 从任意 `LocalContext` 取那两个单例。取不到返回 null，界面**退回改动前的行为**
 * （在组合作用域里跑，切走会断）——宁可用旧行为，也不能因为取不到单例而让面板打不开。
 */
fun modelWorkOf(
    context: Context,
): Pair<ModelWorkRunner, ModelWorkController>? = runCatching {
    val entry = dagger.hilt.android.EntryPointAccessors.fromApplication(
        context.applicationContext,
        ModelWorkEntryPoint::class.java,
    )
    entry.runner() to entry.controller()
}.getOrNull()
