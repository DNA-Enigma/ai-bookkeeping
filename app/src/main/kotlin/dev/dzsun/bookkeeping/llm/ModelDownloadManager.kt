package dev.dzsun.bookkeeping.llm

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 下载状态——UI 订阅这一份渲染卡片，与 [ModelWorkController] 的通知进度同源。
 */
sealed interface DownloadUiState {
    /** 没在下载、也没有已完成的模型（或已就绪走别处渲染）。 */
    data object Idle : DownloadUiState

    /**
     * 下载中。[bytesTotal] 为 -1 表示服务器没给长度（没有分母不画百分比）。
     * [sessionBytesStart] 是本次会话起点——速度只按它算。
     */
    data class Running(
        val bytesDone: Long,
        val bytesTotal: Long,
        val sessionBytesStart: Long,
        val startedAtMs: Long,
    ) : DownloadUiState

    /** 已就绪（文件在盘上且校验通过），卡片显示「可以用了」。 */
    data class Ready(val file: File, val bytes: Long) : DownloadUiState

    data class Failed(val message: String, val retried: Boolean = false) : DownloadUiState
}

/**
 * 网络下载的**生命周期管理器**：把 [ModelDownloader] 的执行包进协程，
 * 进度喂给 [ModelWorkController]（通知栏）与自己的 [uiState]（设置页卡片）。
 *
 * ## 为什么要这一层
 *
 * 下载器是纯函数式的（一次调用一个结果），但生命周期是跨调用的：
 *  - 校验不匹配要**自动重下一次**（同一等待，通知不闪「失败」）；
 *  - 进程被杀后 App 重开，要**自动接着下**（.part 还在）；
 *  - 取消要落盘成「.part 保留」，下次从断点走。
 *
 * ## 与 controller 的分工
 *
 * controller 只认识「DOWNLOAD 等待」这个抽象——喂字节、收场。谁发起、
 * 服务器长什么样、要不要重下，都是这里的事。通知文案由 [progressText] 统一渲染，
 * 这里不写一句文案。
 */
@Singleton
class ModelDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controller: ModelWorkController,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 下载目标目录：与应用私有模型目录一致（SparkSession 找模型时第一个查的就是它）。 */
    private val targetDir: File
        get() = File(context.filesDir, "models")

    private val downloader by lazy { ModelDownloader(targetDir) }

    private val _uiState = MutableStateFlow<DownloadUiState>(initialState())
    val uiState: StateFlow<DownloadUiState> = _uiState.asStateFlow()

    private var job: Job? = null
    private var cancelled = false

    /** 下载描述——占位 URL，真实模型文件发布后连 sha 一起换（见 [DEFAULT_SPEC]）。 */
    val spec: ModelDownloadSpec get() = DEFAULT_SPEC

    private fun initialState(): DownloadUiState {
        val target = File(targetDir, DEFAULT_SPEC.fileName)
        return if (target.isFile && target.length() > 0) {
            DownloadUiState.Ready(target, target.length())
        } else {
            DownloadUiState.Idle
        }
    }

    /**
     * 发起/恢复下载。已在下载中时幂等（重复点「下载」不叠加任务）。
     * .part 存在 → 下载器自动从断点继续（Range 头）。
     */
    fun start() {
        if (job?.isActive == true) return
        cancelled = false
        val currentSpec = spec
        val part = downloader.partFile(currentSpec)
        val resumeFrom = if (part.isFile) part.length() else 0L

        // 会话起点在启动时刻定死：之后 controller 的字节流全部相对它算速度
        controller.reportDownloadStart(resumeFrom, -1L)
        _uiState.value = DownloadUiState.Running(
            bytesDone = resumeFrom,
            bytesTotal = -1L,
            sessionBytesStart = resumeFrom,
            startedAtMs = controller.progress.value.startedAtMs ?: 0L,
        )

        job = scope.launch {
            var attempt = 0
            while (true) {
                attempt++
                val result = downloader.download(
                    spec = currentSpec,
                    cancelled = { cancelled },
                ) { done, total ->
                    controller.reportDownloadBytes(done)
                    val current = _uiState.value
                    if (current is DownloadUiState.Running) {
                        _uiState.value = current.copy(bytesDone = done, bytesTotal = total)
                    }
                }
                when (result) {
                    is ModelDownloadResult.Success -> {
                        controller.reportDownloadDone(true, "已下载 ${result.bytes / 1024 / 1024} MB，校验通过")
                        _uiState.value = DownloadUiState.Ready(result.file, result.bytes)
                        Log.i(TAG, "download ok: ${result.file.absolutePath} (${result.bytes} bytes)")
                        return@launch
                    }

                    is ModelDownloadResult.ChecksumMismatch -> {
                        // 破损文件没有续传价值：删 .part 自动重下一次。第二次仍不匹配
                        // 就报失败——重下是给「传输中位翻转」一类事故的，不是死循环。
                        downloader.partFile(currentSpec).delete()
                        if (attempt >= MAX_RESUME_ATTEMPTS) {
                            controller.reportDownloadDone(
                                false,
                                "校验不匹配（可能下载源被改动），已重试仍失败：${result.actual.take(16)}…",
                            )
                            _uiState.value = DownloadUiState.Failed(
                                "下载的文件校验不匹配（可能来源被改动）。已自动重下一次仍失败，请稍后再试或换网络环境",
                                retried = true,
                            )
                            return@launch
                        }
                        Log.w(TAG, "checksum mismatch, retrying (attempt $attempt)")
                        controller.reportDownloadStart(0L, -1L)
                        val running = _uiState.value
                        if (running is DownloadUiState.Running) {
                            _uiState.value = running.copy(
                                bytesDone = 0L,
                                sessionBytesStart = 0L,
                                bytesTotal = -1L,
                            )
                        }
                    }

                    is ModelDownloadResult.Failed -> {
                        if (result.message == ModelDownloader.CANCELLED) {
                            // 用户取消：.part 已保留，回 Idle（重开 App 或再点会续传）
                            controller.reportDownloadDone(false, "下载已暂停，进度已保留")
                            _uiState.value = DownloadUiState.Idle
                            return@launch
                        }
                        controller.reportDownloadDone(false, result.message)
                        _uiState.value = DownloadUiState.Failed(result.message)
                        return@launch
                    }
                }
            }
        }
    }

    /** 取消下载。`.part` 保留——下次 start() 从断点继续。 */
    fun cancel() {
        if (job?.isActive != true) return
        cancelled = true
    }

    /** 重新启用失败卡片上的「重试」：清掉失败态回 Idle。 */
    fun dismissFailure() {
        if (_uiState.value is DownloadUiState.Failed) _uiState.value = DownloadUiState.Idle
    }

    companion object {
        private const val TAG = "ModelDownload"

        /** 校验不匹配后自动重下的次数上限（1 = 重下一次，之后报失败）。 */
        private const val MAX_RESUME_ATTEMPTS = 2

        /**
         * ⚠️ 占位下载源：URL 是任务给的占位地址（实际指向 APK，不是模型文件），
         * expectedSha256 为空 = 跳过校验并明示「未校验」。真实模型发布后**两处一起换**：
         * url 换成模型文件直链，sha 填真实摘要。
         */
        val DEFAULT_SPEC = ModelDownloadSpec(
            url = "https://github.com/DNA-Enigma/ai-bookkeeping/releases/download/v0.21.1/ai-bookkeeping.apk",
            fileName = "Spark-X2.5-1.7B-Q4_K_M.gguf",
            expectedSha256 = "",
        )
    }
}
