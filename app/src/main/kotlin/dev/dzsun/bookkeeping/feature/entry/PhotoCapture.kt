package dev.dzsun.bookkeeping.feature.entry

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 契约 `MediaUpload` 的 `allowed_mime`：jpeg / png / webp。
 *
 * 部分安卓机型相机默认输出 HEIF，而 HEIF **不在**契约白名单里。
 * 必须在客户端先转成 JPEG——否则要到模型那一跳才失败，那时图片已经传完了。
 */
val CONTRACT_IMAGE_MIMES: Set<String> = setOf("image/jpeg", "image/png", "image/webp")

/** 去掉 `;` 参数、转小写；不在契约白名单就归一成 `image/jpeg`。 */
fun normalizeCaptureMime(rawMime: String?): String {
    val mime = rawMime?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    return mime.takeIf { it in CONTRACT_IMAGE_MIMES } ?: "image/jpeg"
}

/** 这份媒体是否需要重编码成 JPEG（HEIF / 未知格式）。 */
fun requiresJpegReencode(rawMime: String?): Boolean {
    val mime = rawMime?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    return mime !in CONTRACT_IMAGE_MIMES
}

/**
 * 按文件头嗅探真实格式。
 *
 * `ContentResolver.getType` 不可信——FileProvider 按扩展名猜，
 * 拷贝过来的 HEIF 也可能被说成 jpeg。以字节为准。
 */
fun sniffImageMime(bytes: ByteArray): String? {
    if (bytes.size < 12) return null
    // JPEG SOI
    if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) return "image/jpeg"
    // PNG 签名
    if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
        bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
    ) return "image/png"
    // WEBP：RIFF....WEBP
    if (fourCc(bytes, 0) == "RIFF" && fourCc(bytes, 8) == "WEBP") return "image/webp"
    // ISO BMFF：....ftyp.... —— HEIF/HEIC 落在这里
    if (fourCc(bytes, 4) == "ftyp") return "image/heif"
    return null
}

private fun fourCc(bytes: ByteArray, offset: Int): String = buildString {
    for (i in offset until offset + 4) append(bytes[i].toInt().toChar())
}

/**
 * 解码任意受支持的位图格式（含 HEIF）并重编码成 JPEG。
 * 超过 [maxSide] 的边会先缩下来——票据/截图用不着原图像素，上传却要付流量。
 */
fun reencodeToJpeg(bytes: ByteArray, maxSide: Int = MAX_CAPTURE_SIDE): ByteArray {
    val decoded = decodeBitmap(bytes) ?: error("无法解码这张图片，换一张试试")
    val bitmap = decoded.fitInside(maxSide)
    val out = ByteArrayOutputStream()
    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
        error("图片转成 JPEG 失败")
    }
    if (bitmap !== decoded) bitmap.recycle()
    decoded.recycle()
    return out.toByteArray()
}

private fun decodeBitmap(bytes: ByteArray): Bitmap? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        runCatching {
            ImageDecoder.decodeBitmap(
                ImageDecoder.createSource(ByteBuffer.wrap(bytes)),
            ) { decoder, _, _ -> decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE }
        }.getOrNull()?.let { return it }
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}

private fun Bitmap.fitInside(maxSide: Int): Bitmap {
    val longest = maxOf(width, height)
    if (longest <= maxSide) return this
    val scale = maxSide.toFloat() / longest
    val w = (width * scale).toInt().coerceAtLeast(1)
    val h = (height * scale).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(this, w, h, true)
}

/**
 * 读 URI 里的图并压成契约收得下的形状。
 * 以文件头为准判断格式；HEIF/未知一律重编码成 JPEG。
 */
suspend fun loadCapturePayload(context: Context, uri: Uri): Result<ByteArray> =
    withContext(Dispatchers.IO) {
        runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("读不到这张图片")
            val declared = context.contentResolver.getType(uri)
            val sniffed = sniffImageMime(bytes)
            val effective = sniffed ?: declared?.substringBefore(';')?.trim()?.lowercase()
            if (effective != null && effective in CONTRACT_IMAGE_MIMES) {
                bytes
            } else {
                reencodeToJpeg(bytes)
            }
        }
    }

/**
 * 系统相册 + 相机两个入口，产出已归一的图片字节。
 *
 * 相机走 `ACTION_IMAGE_CAPTURE` + FileProvider，不申请 `CAMERA` 权限——
 * 委托给系统相机应用时不需要它。
 */
class PhotoCapture(
    private val onPickGallery: () -> Unit,
    private val onTakePhoto: () -> Unit,
) {
    fun pickFromGallery() = onPickGallery()
    fun takePhoto() = onTakePhoto()
}

@Composable
fun rememberPhotoCapture(
    onPhotoCaptured: (bytes: ByteArray, mime: String) -> Unit,
    onPhotoError: (String) -> Unit,
): PhotoCapture {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cameraUriPath by rememberSaveable { mutableStateOf<String?>(null) }

    fun deliver(uri: Uri?) {
        if (uri == null) return
        scope.launch {
            loadCapturePayload(context, uri).fold(
                onSuccess = { bytes ->
                    // 重编码后必是 JPEG；没动过的字节按嗅探/声明类型走
                    val mime = sniffImageMime(bytes) ?: "image/jpeg"
                    onPhotoCaptured(bytes, normalizeCaptureMime(mime))
                },
                onFailure = { onPhotoError(it.message ?: "图片处理失败") },
            )
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> deliver(uri) }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        val path = cameraUriPath ?: return@rememberLauncherForActivityResult
        cameraUriPath = null
        if (success) deliver(Uri.parse(path))
    }

    return remember(galleryLauncher, cameraLauncher) {
        PhotoCapture(
            onPickGallery = {
                galleryLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            onTakePhoto = {
                val uri = createCameraUri(context)
                cameraUriPath = uri.toString()
                cameraLauncher.launch(uri)
            },
        )
    }
}

private fun createCameraUri(context: Context): Uri {
    val dir = File(context.cacheDir, CAMERA_CACHE_DIR).apply { mkdirs() }
    val file = File(dir, "capture-${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

private const val CAMERA_CACHE_DIR = "camera"
private const val JPEG_QUALITY = 90
private const val MAX_CAPTURE_SIDE = 2048
