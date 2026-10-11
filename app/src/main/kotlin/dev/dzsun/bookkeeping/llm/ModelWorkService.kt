package dev.dzsun.bookkeeping.llm

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 端侧模型长任务的前台服务：**用户切走后，通知栏替页面继续说进度**。
 *
 * ## foregroundServiceType 为什么选 `dataSync`
 *
 * Android 14 起前台服务必须声明类型，且每种类型有各自的准入约束：
 *  - `dataSync`：在应用前台发起、且用户明确感知的数据准备/传输类工作——
 *    把内置 1 GB 模型释放到私有目录、把 GGUF 权重读进内存，正是「用户点了
 *    「加载模型」之后跟着走的传输型长任务」；官方类型指引也把「媒体处理/
 *    模型下载」列在 dataSync 的典型场景里。
 *  - `specialUse` 只该在所有标准类型都对不上时用，且要写用途说明——这里
 *    dataSync 对得上，就不该占用 specialUse 的豁免通道。
 *  - `shortService` 上限 3 分钟，覆盖不了实测 267 秒的加载 + 2~3 分钟的生成。
 *
 * 对应权限是 `FOREGROUND_SERVICE` 与 `FOREGROUND_SERVICE_DATA_SYNC`
 * （Manifest 里），缺一在 34+ 上 startForeground 直接抛
 * `MissingForegroundServiceTypeException`。
 *
 * ## 生命周期口径
 *
 *  - **服务不拥有任何模型操作**。它只是 [ModelWorkController] 那份进程级状态的
 *    订阅者 + **进度通知**的渲染器（结果通知由控制器直接投递，见控制器类注释）。
 *    onDestroy 时绝不碰引擎（不 unload、不取消生成）。
 *  - 没有可展示的进度 → 自己 [stopSelf]。startForeground 必须先走
 *    （5 秒硬要求），**然后**才允许停。
 *  - onTaskRemoved 不停服务：用户划掉 App 壳不等于取消生成——引擎是进程级
 *    单例，任务还在跑，通知栏是它唯一的可见出口。
 */
@AndroidEntryPoint
class ModelWorkService : Service() {

    @Inject lateinit var controller: ModelWorkController
    @Inject lateinit var gate: NotificationGate

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        gate.ensureChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 先 startForeground（5 秒硬要求），再看有没有东西可守——顺序反了在
        // Android 12+ 上会 ForegroundServiceDidNotStartInTimeException。
        val (p, r) = controller.progress.value to controller.result.value
        startAsForeground(progressText(p, SystemClock.elapsedRealtime()))

        // onStartCommand 读到的是拉起那一刻的值，真正的生命周期跟着状态流走。
        scope.launch {
            combine(controller.progress, controller.result) { progress, _ -> progress }
                .collectLatest { progress ->
                    val next = progressText(progress, SystemClock.elapsedRealtime())
                    when {
                        // 没有可展示的进度：服务自己收摊（markGuardStopped 让下一次
                        // startGuard 不因状态残留被跳过）。结果通知由控制器直接投递，
                        // 不归这里管——所以只看 progress。
                        next == null -> {
                            controller.markGuardStopped()
                            stopSelf()
                        }
                        // 权限被拒：不崩、不反复尝试，等状态流转到收摊
                        !gate.canNotify() -> Unit
                        else -> notifySafely(buildNotification(next, progress))
                    }
                }
        }
        return START_NOT_STICKY
    }

    /**
     * 通知权限被拒时 `NotificationManagerCompat.notify` 会抛 `SecurityException`——
     * 「拒绝就降级为页面内进度」在这里落成一行：捕住、不再重试。
     */
    private fun notifySafely(notification: Notification) {
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun startAsForeground(pair: Pair<String, String>?) {
        val notification = buildNotification(
            // 拉起瞬间还没有任何真值：用中性占位而不是假的「思考中」——
            // 状态流的第一帧立刻会把它替换成真实阶段。
            pair ?: ("本机模型" to "正在等待模型状态"),
            controller.progress.value,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(pair: Pair<String, String>, progress: ModelWorkProgress): Notification {
        val tap = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName)
                ?: Intent().apply { setPackage(packageName) },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, NotificationGate.CHANNEL_WORK)
            .setSmallIcon(NotificationGate.SMALL_ICON_RES)
            .setContentTitle(pair.first)
            .setContentText(pair.second)
            .setContentIntent(tap)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)

        if (progress.kind == ModelWorkKind.RELEASE) {
            val percent = releasePercent(progress.bytesDone, progress.bytesTotal)
            if (percent != null) {
                // 确定性进度条：分母来自安装器 expectedBytes()，没有分母绝不画条
                builder.setProgress(100, percent, false)
            }
        }
        return builder.build()
    }

    override fun onDestroy() {
        scope.cancel()
        controller.markGuardStopped()
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        // **不碰引擎**：服务被回收只是通知没了，模型操作照常。
        super.onDestroy()
    }

    private companion object {
        /** 进度通知的 ID。结果通知（2002）由控制器直接投递，互不覆盖。 */
        const val NOTIFICATION_ID = 2001
    }
}

/**
 * 通知权限门：渠道创建 + `POST_NOTIFICATIONS` 判定 + 请求启动。
 *
 * 权限请求必须从 UI 发起（ActivityResultLauncher 只能挂在 Activity 上），
 * 所以这个类只做三件事：建渠道、判「要不要请求」「能不能发」、
 * 给 MainActivity 一个**不进 Compose 树的** launcher 停靠点。
 *
 * 被拒绝时**不崩、不静默**：[denied] 变 true，界面按需显示
 * [ModelWorkController.permissionHint]；服务与控制器在投递前都会查
 * [canNotify]，查了还发（竞态窗口）也有 runCatching 兜底。
 */
@Singleton
class NotificationGate @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val _denied = kotlinx.coroutines.flow.MutableStateFlow(false)
    val denied: kotlinx.coroutines.flow.StateFlow<Boolean> = _denied

    /** MainActivity 在 onCreate 里把 launcher 塞进来；服务路径用不到它。 */
    @Volatile
    var permissionLauncher: ((Unit) -> Unit)? = null

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val work = android.app.NotificationChannel(
            CHANNEL_WORK,
            context.getString(dev.dzsun.bookkeeping.R.string.notif_channel_work_name),
            android.app.NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(dev.dzsun.bookkeeping.R.string.notif_channel_work_desc)
            setShowBadge(false)
        }
        val done = android.app.NotificationChannel(
            CHANNEL_RESULT,
            context.getString(dev.dzsun.bookkeeping.R.string.notif_channel_result_name),
            android.app.NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(dev.dzsun.bookkeeping.R.string.notif_channel_result_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(work)
        manager.createNotificationChannel(done)
    }

    /** 现在能不能发通知：渠道在、权限有（33+ 才检查运行时权限）。 */
    fun canNotify(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** 进相关流程时调：没权限且有 launcher 就发起系统请求（一次）。 */
    fun maybeRequest() {
        if (canNotify()) {
            _denied.value = false
            return
        }
        val launcher = permissionLauncher
        if (launcher != null) {
            launcher(Unit)
        } else {
            // 没有 UI 途径（比如纯后台路径）：标记降级，不崩、不静默
            _denied.value = true
        }
    }

    /** MainActivity 的 launcher 回调里回写。 */
    fun onPermissionResult(granted: Boolean) {
        _denied.value = !granted
    }

    companion object {
        // 渠道名与图标 id 都不用 const：SMALL_ICON_RES 引用 R 常量，const 会让
        // 增量编译的 IR 常量折叠在 stub 上炸出 AssertionError（已实测两次）。
        const val CHANNEL_WORK = "model_work"
        const val CHANNEL_RESULT = "model_result"

        /** 通知小图标：直接复用 launcher 前景（白账本轮廓），不另引一套资源。 */
        val SMALL_ICON_RES = dev.dzsun.bookkeeping.R.drawable.ic_launcher_foreground
    }
}
