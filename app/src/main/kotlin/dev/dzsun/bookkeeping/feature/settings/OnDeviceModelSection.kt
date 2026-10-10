package dev.dzsun.bookkeeping.feature.settings

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.dzsun.bookkeeping.core.designsystem.Art
import dev.dzsun.bookkeeping.llm.ModelInstaller
import dev.dzsun.bookkeeping.llm.ReadyResult
import dev.dzsun.bookkeeping.llm.SessionState
import dev.dzsun.bookkeeping.llm.SparkSession
import dev.dzsun.bookkeeping.llm.issueMessage
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 端侧模型（Spark-X2.5 · llama.cpp）的加载与试跑入口。
 *
 * 为什么放设置页里：这是**工程能力**而不是日常功能——日常记账用不到它，
 * 但评审要看「模型真的在这台设备上跑」，得有个能点、能看结果、能看耗时的地方。
 */
sealed interface OnDeviceUiState {
    data object Idle : OnDeviceUiState
    data class Installing(val progress: Float, val bytes: Long, val totalBytes: Long) : OnDeviceUiState
    data class Loading(val path: String) : OnDeviceUiState
    data class Ready(val path: String) : OnDeviceUiState
    data class Running(val path: String, val output: String) : OnDeviceUiState
    data class Done(
        val path: String,
        val output: String,
        val firstTokenMs: Long,
        val totalMs: Long,
        val chunks: Int,
    ) : OnDeviceUiState

    data class Failed(val message: String) : OnDeviceUiState
}

/** 把打包进 APK 的模型资产暴露成 [ModelAssets]。 */
private class AssetModelAssets(private val context: Context) : dev.dzsun.bookkeeping.llm.ModelAssets {
    override fun names(): List<String> {
        // assets 没有递归 list，只认两层：顶层文件 + 一层子目录
        val out = mutableListOf<String>()
        val roots = context.assets.list("").orEmpty()
        for (r in roots) {
            val children = context.assets.list(r).orEmpty()
            if (children.isEmpty()) out += r else children.forEach { out += "$r/$it" }
        }
        return out
    }

    override fun sizeOf(name: String): Long = try {
        context.assets.openFd(name).declaredLength
    } catch (_: Exception) {
        -1L
    }

    override fun open(name: String) = context.assets.open(name)
}


@HiltViewModel
class OnDeviceModelViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    /** 端侧模型的唯一属主：加载、生成、卸载都走它，这里不再自建引擎。 */
    private val session: SparkSession,
) : ViewModel() {

    /**
     * 设置页**自己发起的**动作进度（释放 / 加载 / 生成中）与其结算结果（Done / Failed）。
     * 属主状态还没跟上时由它撑住显示；一旦属主给出权威状态，就交给 [resolveOnDeviceState] 合成。
     */
    private val _action = MutableStateFlow<OnDeviceUiState?>(null)

    /**
     * 对外状态：**属主 [SparkSession.sessionState] + 本地动作合成**，不再自持一份。
     *
     * 背景（软测 TC-03/13/18）：以前这里自己攒 `_state`，功能页把模型加载好了
     * 设置页还显示「未加载」，面板自动重载后又倒回去——两份状态必然漂移。
     * settled 状态（加载成功 / 失败 / 卸载、生成结果）一律跟随属主，
     * 只有用户动作进行中的进度保留本地。
     */
    private val _state = MutableStateFlow<OnDeviceUiState>(OnDeviceUiState.Idle)
    val state: StateFlow<OnDeviceUiState> = _state.asStateFlow()

    private val assets = AssetModelAssets(context)
    private val installer = ModelInstaller(assets, File(context.filesDir, "models"))

    /** 设备上当前能找到的模型文件；释放内置模型后刷新，UI 用它渲染状态行。 */
    private val _modelFile = MutableStateFlow(findModel())
    val modelFile: StateFlow<File?> = _modelFile.asStateFlow()

    init {
        viewModelScope.launch {
            combine(session.sessionState, _action, _modelFile) { owner, action, file ->
                resolveOnDeviceState(owner, action, file?.absolutePath.orEmpty())
            }.collect { _state.value = it }
        }
    }

    /** 找设备上的 GGUF：优先应用私有目录（免权限），再退回外部下载目录。 */
    fun findModel(): File? {
        val dirs = listOfNotNull(
            context.getExternalFilesDir(null)?.let { File(it, "models") },
            File(context.filesDir, "models"),
            context.getExternalFilesDir("Download"),
        )
        for (d in dirs) {
            d.listFiles()?.firstOrNull {
                it.isFile && it.extension.equals("gguf", ignoreCase = true)
            }?.let { return it }
        }
        return null
    }

    fun load() {
        val file = findModel()
        if (file == null) {
            installBundledModel()
            return
        }
        loadFile(file)
    }

    /**
     * 设备上没有模型时的路：把打包进 APK 的那份释放到私有目录，然后接着加载。
     *
     * 这是「装上就能用」的那一步——不指望用户会 `adb push` 一个 1 GB 的文件。
     */
    private fun installBundledModel() {
        viewModelScope.launch {
            val total = withContext(Dispatchers.IO) { installer.expectedBytes() }
            _action.value = OnDeviceUiState.Installing(0f, 0L, total)
            val result = try {
                withContext(Dispatchers.IO) {
                    installer.ensure { p ->
                        // StateFlow 写入线程安全；UI 在主线程 collect，这里直接回写进度
                        val done = if (total > 0) (p * total).toLong() else -1L
                        _action.value = OnDeviceUiState.Installing(p, done, total)
                    }
                }
            } catch (e: Throwable) {
                _action.value = OnDeviceUiState.Failed("释放模型失败：${e.message ?: e.javaClass.simpleName}")
                Log.e(TAG, "install bundled model failed", e)
                return@launch
            }
            _modelFile.value = findModel()
            when {
                result is ModelInstaller.Result.Failed -> {
                    _action.value = OnDeviceUiState.Failed(result.message)
                    Log.w(TAG, "install bundled model failed: ${result.message}")
                }
                _modelFile.value != null -> {
                    Log.i(TAG, "bundled model installed: ${_modelFile.value!!.absolutePath}")
                    loadFile(_modelFile.value!!)
                }
                else -> _action.value = OnDeviceUiState.Failed("释放后仍找不到模型文件")
            }
        }
    }

    /**
     * 加载交给 [SparkSession]：它用**带科目表的系统提示**（全 App 唯一那一份），
     * 这里若自建一份，两套提示词会来回重载模型（模拟器实测每次 60s+）。
     *
     * 「已加载」只在 `session.ensureReady()` 真的成功后才置 [OnDeviceUiState.Ready]。
     */
    private fun loadFile(file: File) {
        viewModelScope.launch {
            _action.value = OnDeviceUiState.Loading(file.absolutePath)
            when (val result = session.ensureReady()) {
                is ReadyResult.Ready -> {
                    // 加载成功是 settled 状态：清掉本地动作，显示交给属主的 Ready
                    _action.value = null
                    Log.i(TAG, "model loaded: ${file.absolutePath} (${file.length() / 1024 / 1024} MB)")
                }

                is ReadyResult.Failed -> {
                    val why = issueMessage(result.issue)
                    _action.value = OnDeviceUiState.Failed(why)
                    Log.e(TAG, "model load failed: $why")
                }
            }
        }
    }

    fun generate(prompt: String) {
        if (prompt.isBlank()) return
        viewModelScope.launch {
            // 先按真实状态确认就绪：没就绪就说没就绪的原因，不显示与状态脱节的旧文案
            val ready = session.ensureReady()
            if (ready is ReadyResult.Failed) {
                val why = issueMessage(ready.issue)
                _action.value = OnDeviceUiState.Failed(why)
                Log.w(TAG, "generate blocked: $why")
                return@launch
            }
            val path = _modelFile.value?.absolutePath
                ?: session.findModel()?.absolutePath.orEmpty()
            val t0 = SystemClock.elapsedRealtime()
            var firstTokenAt = 0L
            val sb = StringBuilder()
            var chunks = 0
            _action.value = OnDeviceUiState.Running(path, "")
            try {
                session.llm.generate(prompt).collect { piece ->
                    if (firstTokenAt == 0L) firstTokenAt = SystemClock.elapsedRealtime()
                    sb.append(piece)
                    chunks++
                    _action.value = OnDeviceUiState.Running(path, sb.toString())
                }
                val now = SystemClock.elapsedRealtime()
                _action.value = OnDeviceUiState.Done(
                    path = path,
                    output = sb.toString(),
                    firstTokenMs = if (firstTokenAt == 0L) 0L else firstTokenAt - t0,
                    totalMs = now - t0,
                    chunks = chunks,
                )
                Log.i(
                    TAG,
                    "generate done: firstToken=${if (firstTokenAt == 0L) -1 else firstTokenAt - t0}ms " +
                        "total=${now - t0}ms chunks=$chunks out=${sb.take(120)}"
                )
            } catch (e: Throwable) {
                _action.value = OnDeviceUiState.Failed("生成失败：${e.message ?: e.javaClass.simpleName}")
                Log.e(TAG, "generate failed", e)
            }
        }
    }

    fun unload() {
        // 交给属主：session 会连同 loadedKey / inFlightPrompt 一起清掉，
        // 这里自己 free 的话 session 还以为模型在，下一次 ensureReady 会直接给错状态。
        session.unload()
        // 卸载同样交给属主：loadedKey 一清，sessionState 随即变 Idle，这里只撤掉本地动作
        _action.value = null
    }

    companion object {
        private const val TAG = "OnDeviceModel"
    }
}

/**
 * 属主状态 + 设置页本地动作 → 界面要显示的状态。纯函数，单测直接钉三条判定标准。
 *
 * 合成口径（顺序即优先级）：
 *  1. **用户动作进行中**（释放 / 加载 / 生成中）以动作为准 —— 属主状态还没跟上；
 *  2. 属主 Error → Failed（原因给用户看）；但设置页自己刚报的 Failed 更具体，保留它；
 *  3. 属主 Loading / Generating → 对应的加载中 / 生成中（生成中丢不掉，文本由动作补）；
 *  4. 属主 Ready → 已加载；设置页自己的结算结果（生成耗时 Done、失败原因 Failed）保留；
 *  5. 属主 Idle → 未加载；仅保留设置页自己的 Failed（加载失败的原因不能被 Idle 冲掉）。
 *
 * 三条判定标准就落在这里：
 *  ① 功能页加载后（owner=Ready，无本地动作）→ 设置页显示已加载；
 *  ② 面板/对话页自动重载后（owner=Ready，本地动作是旧的 Idle）→ 不退回未加载；
 *  ③ 设置页卸载后（owner=Idle，本地动作清空）→ 回到未加载。
 */
internal fun resolveOnDeviceState(
    owner: SessionState,
    action: OnDeviceUiState?,
    path: String,
): OnDeviceUiState = when {
    action is OnDeviceUiState.Installing ||
        action is OnDeviceUiState.Loading ||
        action is OnDeviceUiState.Running -> action

    owner is SessionState.Error ->
        if (action is OnDeviceUiState.Failed) action else OnDeviceUiState.Failed(owner.message)

    owner is SessionState.Loading -> OnDeviceUiState.Loading(path)
    owner is SessionState.Generating -> OnDeviceUiState.Running(path, "")

    owner is SessionState.Ready ->
        if (action is OnDeviceUiState.Done || action is OnDeviceUiState.Failed) action
        else OnDeviceUiState.Ready(path)

    // owner Idle
    else -> if (action is OnDeviceUiState.Failed) action else OnDeviceUiState.Idle
}

/* ------------------------------ UI ------------------------------ */

@Composable
fun OnDeviceModelSection(state: OnDeviceUiState, viewModel: OnDeviceModelViewModel) {
    val p = Art.colors
    var prompt by remember { mutableStateOf(DEFAULT_PROMPT) }

    val model by viewModel.modelFile.collectAsStateWithLifecycle()
    val modelLine = when {
        model != null -> "模型：${model!!.name}（${model!!.length() / 1024 / 1024} MB）"
        else -> "模型：内置 · 点「加载模型」会自动释放到应用目录（首次约 1 GB）"
    }
    Text(modelLine, style = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace), color = p.ink3)
    Spacer(Modifier.height(10.dp))

    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
        ArtActionChip(
            label = when (state) {
                is OnDeviceUiState.Installing -> "释放中 ${(state.progress * 100).toInt()}%"
                is OnDeviceUiState.Loading -> "加载中…"
                else -> "加载模型"
            },
            enabled = state !is OnDeviceUiState.Installing &&
                state !is OnDeviceUiState.Loading &&
                state !is OnDeviceUiState.Running,
            onClick = viewModel::load,
        )
        ArtActionChip(
            label = when (state) {
                is OnDeviceUiState.Running -> "生成中…"
                else -> "生成"
            },
            enabled = state !is OnDeviceUiState.Installing &&
                state !is OnDeviceUiState.Loading &&
                state !is OnDeviceUiState.Running,
            onClick = { viewModel.generate(prompt) },
        )
        ArtActionChip(
            label = "卸载",
            enabled = state !is OnDeviceUiState.Installing &&
                state !is OnDeviceUiState.Loading &&
                state !is OnDeviceUiState.Running,
            onClick = viewModel::unload,
        )
    }

    Spacer(Modifier.height(14.dp))
    BasicTextField(
        value = prompt,
        onValueChange = { prompt = it },
        textStyle = TextStyle(fontSize = 13.sp, color = p.ink, lineHeight = 20.sp),
        cursorBrush = SolidColor(p.accent),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(p.radiusL))
            .background(p.surface)
            .border(1.dp, p.line, RoundedCornerShape(p.radiusL))
            .padding(12.dp),
    )

    Spacer(Modifier.height(14.dp))
    when (state) {
        is OnDeviceUiState.Idle ->
            StatusText("未加载 · 点「加载模型」", p.ink3)
        is OnDeviceUiState.Installing -> {
            val pct = if (state.totalBytes > 0) "${(state.progress * 100).toInt()}%" else "…"
            val mb = if (state.totalBytes > 0) {
                "${state.bytes / 1024 / 1024} / ${state.totalBytes / 1024 / 1024} MB · "
            } else ""
            StatusText("正在释放内置模型 $mb$pct（首次约 1 GB，稍等）", p.accent)
        }
        is OnDeviceUiState.Loading ->
            StatusText("正在加载模型（首次读 1GB+ 文件，耐心等）…", p.accent)
        is OnDeviceUiState.Ready ->
            StatusText("已加载 · 可以生成了", p.accent)
        is OnDeviceUiState.Running ->
            StatusText("生成中…", p.accent)
        is OnDeviceUiState.Failed ->
            StatusText(state.message, p.warn)
        is OnDeviceUiState.Done -> {
            StatusText(
                "首 token ${state.firstTokenMs} ms · 全程 ${state.totalMs} ms · " +
                    "${state.chunks} 块 · ${(state.output.length)} 字",
                p.accent,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                state.output,
                style = TextStyle(fontSize = 13.5.sp, lineHeight = 22.sp, fontFamily = FontFamily.Monospace),
                color = p.ink,
            )
        }
    }
}

@Composable
private fun StatusText(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(text, style = TextStyle(fontSize = 12.5.sp, lineHeight = 19.sp), color = color)
}

@Composable
private fun ArtActionChip(label: String, enabled: Boolean, onClick: () -> Unit) {
    val p = Art.colors
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .border(1.dp, p.line, RoundedCornerShape(999.dp))
            .background(if (enabled) p.surface else p.bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.Medium),
            color = if (enabled) p.accent else p.ink3,
        )
    }
}

private const val DEFAULT_PROMPT = "用一句话说明记一笔「打车28元」该怎么分类。"
