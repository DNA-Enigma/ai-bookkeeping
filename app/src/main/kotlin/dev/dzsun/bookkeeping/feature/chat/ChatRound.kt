package dev.dzsun.bookkeeping.feature.chat

import androidx.compose.runtime.Immutable

/**
 * ============================================================
 *  聊天历史的折叠展示：一轮对话 = 一条用户消息 + 模型这一轮的所有产出。
 *
 *  这里只放**纯逻辑**（分组、标题、展开判定、存档编解码），
 *  渲染在 ChatScreen 的 RoundCard 里。纯函数全部可 JVM 单测，
 *  不依赖 Compose 运行时——分组规则错了要能一眼看出错在哪一条。
 * ============================================================
 */

/**
 * 一轮对话。[items] 是这一轮里上屏的全部消息（含那条用户消息本身，按原顺序）。
 *
 * 标 [Immutable] 是给 Compose 的跳过（skip）信号：内容没换引用时整轮不重组。
 * 折叠态下 body 不参与布局，一个非展开的轮次就应该被整体跳过。
 */
@Immutable
data class ChatRound(
    val id: String,
    /** 触发这一轮的用户原话；开场白那轮没有用户消息，为 null。 */
    val prompt: String?,
    val items: List<ChatMsg>,
    /** 这一轮第一条消息在扁平 messages 里的下标，用来给卡片生成稳定 key。 */
    val firstItemIndex: Int,
) {
    /** 模型这一轮的产出条数（不含用户那句），收起时作为副标题提示「里面有东西」。 */
    val replyCount: Int get() = items.count { !it.isUserTurn }
}

/**
 * 是不是用户自己说的那句。
 *
 * 判据用现成的 [ChatMsg.Text.fromAgent]，不另造字段：EntryCard 永远是模型产出的，
 * 用户消息只有 Text(fromAgent = false) 这一种形状。ChatScreen 分组与渲染都用它。
 */
internal val ChatMsg.isUserTurn: Boolean
    get() = this is ChatMsg.Text && !fromAgent

/**
 * 收起时标题栏右侧的提示文案。
 *
 * 两条都不是可有可无的：
 *  - **0 条回应不写「0 条」**——那句看着像坏了；
 *  - **正在生成必须说一声**。收起最后一轮时打字气泡整块没了，屏幕上没有任何
 *    东西表示「还在跑」，用户只会以为按了没反应（这轮真的可能等上两分钟）。
 *    「生成中」放前面，因为它是此刻唯一需要用户知道的事。
 */
fun roundCollapsedHint(replyCount: Int, live: Boolean = false): String =
    listOfNotNull(
        if (live) "生成中" else null,
        if (replyCount > 0) "$replyCount 条回应" else null,
    ).joinToString(" · ")

/**
 * 把扁平消息流折成轮次。
 *
 * 规则只有一条：**一条用户消息开一轮**，之后的模型产出都归它；
 * 出现在第一条用户消息之前的模型产出（开场白）单独成一轮，prompt 为 null。
 *
 * 用户连着发两句、模型还没答完就又发一句时，会切成两轮，后一句的那轮
 * 把两条回复都收进去。这是有意的：**不做「哪条回复属于哪句提问」的猜测**——
 * 那需要一个配对信号，契约里没有，猜错的代价是用户看不到刚拿到的答案。
 *
 * id 用 `round-{下标}`：消息列表是**只追加**的（没有 clear / removeAt 的调用点），
 * 所以下标稳定，手动折叠的状态不会因为新消息而串到别的轮次上。
 * 哪天有人加了删除消息的能力，这个前提就破了——那时 id 必须换成内容派生或自增序列。
 */
fun groupRounds(messages: List<ChatMsg>): List<ChatRound> {
    if (messages.isEmpty()) return emptyList()
    val out = ArrayList<ChatRound>()
    val current = ArrayList<ChatMsg>()
    var prompt: String? = null
    var start = 0

    fun flush() {
        if (current.isEmpty()) return
        out += ChatRound(
            id = "round-${out.size}",
            prompt = prompt,
            items = current.toList(),
            firstItemIndex = start,
        )
        current.clear()
    }

    messages.forEachIndexed { index, msg ->
        if (msg.isUserTurn) {
            if (current.isNotEmpty()) {
                flush()
                start = index
            }
            prompt = msg.let { (it as? ChatMsg.Text)?.body }
        } else if (current.isEmpty()) {
            start = index
        }
        current += msg
    }
    flush()
    return out
}

/** 标题取前多少个字。超出的部分用 "..." 表示被截断。 */
const val ROUND_TITLE_MAX_CHARS = 20

/**
 * 轮次标题 = 用户消息前 [ROUND_TITLE_MAX_CHARS] 字，截断了才加 "..."。
 *
 * 三条刻意的口径：
 * 1. **先把空白/换行压成单个空格**——原话里带换行时，标题原样渲染就会把卡片
 *    顶栏撑成多行，一轮的高度不再是「一行」，折叠也就没省下地方。
 * 2. 没超过 20 字**不加**省略号，免得「今天」后面跟个「...」看着像话没说完。
 * 3. prompt 为 null（开场白那轮）时退化成第一条模型的**文字**产出（EntryCard 没有
 *    可当标题的话），总得有标题，不能渲染出一个空白可点区；都取不到才用
 *    [ROUND_TITLE_FALLBACK]——**不返回空串**，空标题点不出东西也没法解释为什么。
 */
fun roundTitle(prompt: String?, items: List<ChatMsg> = emptyList()): String {
    val source = prompt ?: items.firstNotNullOfOrNull { msg ->
        if (msg.isUserTurn) null else (msg as? ChatMsg.Text)?.body
    }
    val flat = source?.whitespaced()?.trim().orEmpty()
    if (flat.isEmpty()) return ROUND_TITLE_FALLBACK
    return if (flat.length <= ROUND_TITLE_MAX_CHARS) flat
    else flat.take(ROUND_TITLE_MAX_CHARS) + ROUND_TITLE_ELLIPSIS
}

const val ROUND_TITLE_FALLBACK = "对话"
const val ROUND_TITLE_ELLIPSIS = "..."

/** 换行/连续空白压成一个空格，标题因此恒为一行。 */
private fun String.whitespaced(): String = replace(WS_RUN, " ")

private val WS_RUN = Regex("[\\s\\u00A0]+")

/**
 * 默认展开最近几轮。更早的收起——多轮对话占满屏幕就是这次要解决的问题。
 * 这里是**默认值**，不是硬上限：用户点开的轮次会记进 overrides，不受它约束。
 */
const val DEFAULT_EXPANDED_ROUNDS = 3

/**
 * 这一轮默认展开吗。倒数 [recent] 轮展开，更早的收起。
 *
 * 取 `total - recent` 为界而不是「index < 3 收起」：新轮次追加时，
 * 一个从没被用户碰过的轮次只会在**滑出窗口**的那一刻收起一次，
 * 不会随着新消息在展开/收起之间来回跳。
 */
fun isRoundExpandedByDefault(index: Int, total: Int, recent: Int = DEFAULT_EXPANDED_ROUNDS): Boolean {
    if (index < 0 || total <= 0) return false
    return index >= total - recent
}

/**
 * 这一轮到底展不展开。**用户点过的一定算数**，没表过态才轮得到默认值和 live。
 *
 *  - `overrides[id]` 有值 → 直接用。手动点必须有点到的效果，
 *    「按钮永远点不动」这类卡死在本项目里已经记过不止一次。
 *  - 没表过态时：[live]（这一轮正在出东西）无条件展开。否则一轮刚开始就被
 *    默认规则收起，用户对着一个字都看不见的标题等 20 秒。
 *  - 再否则走 [isRoundExpandedByDefault]。
 *
 * 反过来写（`live || overrides[...]`）会让「收起正在生成的那轮」变成点不动，
 * 而生成的那轮又永远是最后一轮——正好是最常被点的那一个。
 */
fun resolveRoundExpanded(
    index: Int,
    total: Int,
    roundId: String,
    overrides: Map<String, Boolean>,
    live: Boolean,
    recent: Int = DEFAULT_EXPANDED_ROUNDS,
): Boolean = overrides[roundId] ?: (live || isRoundExpandedByDefault(index, total, recent))

/**
 * 只留下还存在的轮次的状态。
 *
 * 每次手动切换都过一次：存档字符串不会随着历史变长而无限膨胀，
 * 进程被杀后恢复（此时 messages 是空的）也不会留下指向不存在轮次的键。
 */
fun pruneExpanded(liveIds: Collection<String>, overrides: Map<String, Boolean>): Map<String, Boolean> {
    if (overrides.isEmpty()) return overrides
    val keep = liveIds.toSet()
    return overrides.filterKeys { it in keep }
}

/* ---------------- 存档编解码 ---------------- */

/**
 * 折叠状态 ↔ 一行字符串，为了走 [androidx.compose.runtime.saveable.rememberSaveable]
 * 的默认 String 通道——`Map<String, Boolean>` 不能直接进 Bundle，
 * 自己写 Saver 要操心 ArrayList/Serializable 那条分支，编码成字符串就没这些讲究。
 *
 * 形状：`round-0=1;round-2=0`。id 由分组生成（`round-数字`），不含 `=` 和 `;`，
 * 所以不需要转义。解不出来的片段**丢掉**，不抛异常——
 * 折叠状态丢了最多是回到默认展开，不该把整个聊天页弄崩。
 */
fun encodeExpanded(overrides: Map<String, Boolean>): String =
    overrides.entries.joinToString(";") { (k, v) -> "$k=${if (v) '1' else '0'}" }

fun decodeExpanded(raw: String): Map<String, Boolean> {
    if (raw.isEmpty()) return emptyMap()
    val out = LinkedHashMap<String, Boolean>()
    raw.split(';').forEach { part ->
        val at = part.lastIndexOf('=')
        if (at <= 0 || at == part.length - 1) return@forEach
        val value = part.substring(at + 1)
        if (value == "1" || value == "0") out[part.substring(0, at)] = value == "1"
    }
    return out
}

/**
 * 已提交入账的卡片 key ↔ 一行字符串。
 *
 * 为什么要把「按过确认」存起来而不是让 EntryBubble 自己 remember：
 * 卡片在收起的轮次里**不参与组合**，remember 会被丢掉。原来那版
 * `var done by remember { mutableStateOf(false) }` 一遇到折叠再展开，
 * 「已提交」就变回可点的「确认入账」——那是在邀请用户把同一笔记两遍。
 */
fun encodeConfirmed(keys: List<String>): String = keys.joinToString(";")

fun decodeConfirmed(raw: String): Set<String> =
    if (raw.isEmpty()) emptySet() else raw.split(';').filter { it.isNotEmpty() }.toSet()

/**
 * 一张待确认卡片在扁平消息流里的稳定 key。
 *
 * 下标只增不减（没有删除调用点），所以同一个下标永远指着同一张卡。
 * 用下标而不是内容当 key 是有意的：两张一模一样的卡（同金额同备注，用户连按两次
 * 同样的话）内容完全相同，按内容做 key 会把第二次那张也标成「已提交」——
 * 而它其实还没入账，按钮却是灰的，用户没法记第二笔。
 */
fun entryCardKey(absoluteIndex: Int): String = "card-$absoluteIndex"
