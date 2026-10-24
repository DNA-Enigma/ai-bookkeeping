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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 下载状态——UI 订阅这一份渲染卡片，与 [ModelWorkController] 的通知进度同源。
 */
sealed interface DownloadUiState {
    /** 没在下载、也没有可用模型。 */
    data object Idle : DownloadUiState

    /**
     * 正在核对盘上已有的文件（算 SHA256，1 GB 要几秒）。核对完转 [Ready] 或 [Idle]。
     *
     * 有这一档是因为**「文件在」不等于「模型可用」**：不核对就直接说「可以用了」，
     * 等于把「我没查」说成「我查过了」。
     */
    data object Verifying : DownloadUiState

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

    /**
     * 已就绪——文件在盘上，**且字节数与 SHA256 都对过**（见 [ModelDownloader.inspectExisting]）。
     * 只有走到这里，界面才允许说「可以用了」。
     */
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
 *  - 启动时要先**核对**盘上已有文件（算 SHA256），验过才说「可以用了」；
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

    /**
     * 初值是 [DownloadUiState.Verifying] 而不是 [DownloadUiState.Idle]：
     * 构造完立刻开始核对，核对之前任何结论都是猜的。
     */
    private val _uiState = MutableStateFlow<DownloadUiState>(DownloadUiState.Verifying)
    val uiState: StateFlow<DownloadUiState> = _uiState.asStateFlow()

    private val _resumableBytes = MutableStateFlow(0L)

    /**
     * 已下但**未校验**的部分文件大小；0 = 没有可续传的进度。
     * 界面用它说「继续下载（已下 X MB）」，而不是笼统的「点此开始」。
     */
    val resumableBytes: StateFlow<Long> = _resumableBytes.asStateFlow()

    /** 启动核对。`start()` 会先 join 它，避免两条协程抢着写同一份状态。 */
    private val verification: Job = scope.launch { verifyExisting() }

    private var job: Job? = null
    private var cancelled = false

    /** 下载描述——真实模型直链与真摘要，见 [DEFAULT_SPEC]。 */
    val spec: ModelDownloadSpec get() = DEFAULT_SPEC

    /**
     * 核对盘上已有的文件，决定初态是 [DownloadUiState.Ready] 还是 [DownloadUiState.Idle]。
     *
     * 判据与 [ModelDownloader.download] 用的是同一个 [ModelDownloader.inspectExisting]，
     * 所以不会出现「卡片说能用、下载器说不对」的分裂。对不上的文件**删掉**——
     * 留着只会让每次启动都白算一遍 1 GB 的哈希。
     */
    private fun verifyExisting() {
        val current = spec
        refreshResumable(current)
        _uiState.value = when (val existing = downloader.inspectExisting(current)) {
            is ExistingModelFile.Verified ->
                DownloadUiState.Ready(existing.file, existing.bytes)

            is ExistingModelFile.Mismatch -> {
                Log.w(TAG, "existing model rejected (${existing.reason}), removing: ${existing.file.name}")
                existing.file.delete()
                DownloadUiState.Idle
            }

            ExistingModelFile.Absent -> DownloadUiState.Idle
        }
    }

    private fun refreshResumable(current: ModelDownloadSpec) {
        val part = downloader.partFile(current)
        _resumableBytes.value = if (part.isFile) part.length() else 0L
    }

    /**
     * 发起/恢复下载。已在下载中时幂等（重复点「下载」不叠加任务）。
     * .part 存在 → 下载器自动从断点继续（Range 头）。
     */
    fun start() {
        if (job?.isActive == true) return
        cancelled = false
        val currentSpec = spec

        job = scope.launch {
            // 核对没结束就开始下，两条协程会抢着写同一份状态；核对通常几百毫秒到几秒
            verification.join()
            if (cancelled) {
                _uiState.value = DownloadUiState.Idle
                return@launch
            }
            refreshResumable(currentSpec)
            val resumeFrom = _resumableBytes.value

            // 会话起点在启动时刻定死：之后 controller 的字节流全部相对它算速度
            controller.reportDownloadStart(resumeFrom, -1L)
            _uiState.value = DownloadUiState.Running(
                bytesDone = resumeFrom,
                bytesTotal = -1L,
                sessionBytesStart = resumeFrom,
                startedAtMs = controller.progress.value.startedAtMs ?: 0L,
            )

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
                        _resumableBytes.value = 0L
                        controller.reportDownloadDone(
                            true,
                            "已下载 ${result.bytes / 1024 / 1024} MB，SHA256 校验通过",
                        )
                        _uiState.value = DownloadUiState.Ready(result.file, result.bytes)
                        Log.i(TAG, "download ok: ${result.file.absolutePath} (${result.bytes} bytes)")
                        return@launch
                    }

                    is ModelDownloadResult.ChecksumMismatch -> {
                        // 破损文件没有续传价值：删 .part 自动重下一次。第二次仍不匹配
                        // 就报失败——重下是给「传输中位翻转」一类事故的，不是死循环。
                        downloader.partFile(currentSpec).delete()
                        _resumableBytes.value = 0L
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
                        // 失败/取消都要把可续传量刷成真实值——界面拿它说「已下 X MB」
                        refreshResumable(currentSpec)
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
         * 真实下载源：镜像站直链 + 真摘要 + 真字节数。
         *
         * 三样都是量出来的，不是估的：
         *  - `expectedBytes = 1107457856`：与仓库外 `~/projects/model-assets/models/`
         *    里那份 GGUF 逐字节相符；
         *  - `expectedSha256`：对同一份文件实算的摘要；
         *  - `url`：`hf-mirror.com` 代理 `XHToken/Spark-X2.5-1.7B-GGUF`，
         *    实测 302 到签名地址后返回 `Content-Length` 与 `Accept-Ranges: bytes`，
         *    所以断点续传（206）在这条链上是可用的。
         *
         * 换模型时**三样一起换**——只换 url 不换 sha，等于让每次下载都判校验失败。
         */
        val DEFAULT_SPEC = ModelDownloadSpec(
            url = "https://hf-mirror.com/XHToken/Spark-X2.5-1.7B-GGUF/resolve/main/Spark-X2.5-1.7B-Q4_K_M.gguf",
            fileName = "Spark-X2.5-1.7B-Q4_K_M.gguf",
            expectedSha256 = "902bde2522394954ac17821b3e5fd0df02defbc6944f122253f2580acf0503f4",
            expectedBytes = 1_107_457_856L,
        )
    }
}
