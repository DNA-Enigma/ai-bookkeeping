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
import com.arm.aichat.AiChat
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.dzsun.bookkeeping.core.designsystem.Art
import dev.dzsun.bookkeeping.llm.SparkLlm
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 端侧模型（Spark-X2.5 · llama.cpp）的加载与试跑入口。
 *
 * 为什么放设置页里：这是**工程能力**而不是日常功能——日常记账用不到它，
 * 但评审要看「模型真的在这台设备上跑」，得有个能点、能看结果、能看耗时的地方。
 */
sealed interface OnDeviceUiState {
    data object Idle : OnDeviceUiState
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

@HiltViewModel
class OnDeviceModelViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val engine by lazy { AiChat.getInferenceEngine(context) }
    private val llm by lazy { SparkLlm(engine) }

    private val _state = MutableStateFlow<OnDeviceUiState>(OnDeviceUiState.Idle)
    val state: StateFlow<OnDeviceUiState> = _state.asStateFlow()

    /** 已加载的模型路径；null = 未加载（生成前必须先加载） */
    private var loadedPath: String? = null

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
            _state.value = OnDeviceUiState.Failed(
                "找不到模型文件。先执行：adb push *.gguf " +
                    "/sdcard/Android/data/dev.dzsun.bookkeeping/files/models/"
            )
            return
        }
        viewModelScope.launch {
            _state.value = OnDeviceUiState.Loading(file.absolutePath)
            try {
                llm.init(file.absolutePath)
                loadedPath = file.absolutePath
                _state.value = OnDeviceUiState.Ready(file.absolutePath)
                Log.i(TAG, "model loaded: ${file.absolutePath} (${file.length() / 1024 / 1024} MB)")
            } catch (e: Throwable) {
                loadedPath = null
                _state.value = OnDeviceUiState.Failed("加载失败：${e.message ?: e.javaClass.simpleName}")
                Log.e(TAG, "model load failed", e)
            }
        }
    }

    fun generate(prompt: String) {
        val path = loadedPath
        if (path == null) {
            _state.value = OnDeviceUiState.Failed("模型还没加载，先点「加载模型」")
            return
        }
        if (prompt.isBlank()) return
        viewModelScope.launch {
            val t0 = SystemClock.elapsedRealtime()
            var firstTokenAt = 0L
            val sb = StringBuilder()
            var chunks = 0
            _state.value = OnDeviceUiState.Running(path, "")
            try {
                llm.generate(prompt).collect { piece ->
                    if (firstTokenAt == 0L) firstTokenAt = SystemClock.elapsedRealtime()
                    sb.append(piece)
                    chunks++
                    _state.value = OnDeviceUiState.Running(path, sb.toString())
                }
                val now = SystemClock.elapsedRealtime()
                _state.value = OnDeviceUiState.Done(
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
                _state.value = OnDeviceUiState.Failed("生成失败：${e.message ?: e.javaClass.simpleName}")
                Log.e(TAG, "generate failed", e)
            }
        }
    }

    fun unload() {
        try {
            llm.free()
        } catch (e: Throwable) {
            Log.w(TAG, "unload failed", e)
        }
        loadedPath = null
        _state.value = OnDeviceUiState.Idle
    }

    companion object {
        private const val TAG = "SparkLlm"
    }
}

/* ------------------------------ UI ------------------------------ */

@Composable
fun OnDeviceModelSection(state: OnDeviceUiState, viewModel: OnDeviceModelViewModel) {
    val p = Art.colors
    var prompt by remember { mutableStateOf(DEFAULT_PROMPT) }

    val model = remember { viewModel.findModel() }
    val modelLine = when {
        model != null -> "模型：${model.name}（${model.length() / 1024 / 1024} MB）"
        else -> "模型：未找到 · adb push 到 files/models/"
    }
    Text(modelLine, style = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace), color = p.ink3)
    Spacer(Modifier.height(10.dp))

    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
        ArtActionChip(
            label = when (state) {
                is OnDeviceUiState.Loading -> "加载中…"
                else -> "加载模型"
            },
            enabled = state !is OnDeviceUiState.Loading && state !is OnDeviceUiState.Running,
            onClick = viewModel::load,
        )
        ArtActionChip(
            label = when (state) {
                is OnDeviceUiState.Running -> "生成中…"
                else -> "生成"
            },
            enabled = state !is OnDeviceUiState.Loading && state !is OnDeviceUiState.Running,
            onClick = { viewModel.generate(prompt) },
        )
        ArtActionChip(
            label = "卸载",
            enabled = state !is OnDeviceUiState.Loading && state !is OnDeviceUiState.Running,
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
