package dev.dzsun.bookkeeping.feature.entry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoCaptureTest {

    // —— 归一化：契约只收 jpeg/png/webp，其他一律当 JPEG ——

    @Test
    fun `契约白名单原样通过`() {
        assertEquals("image/jpeg", normalizeCaptureMime("image/jpeg"))
        assertEquals("image/png", normalizeCaptureMime("image/png"))
        assertEquals("image/webp", normalizeCaptureMime("image/webp"))
    }

    @Test
    fun `HEIF 与未知类型归一成 jpeg`() {
        assertEquals("image/jpeg", normalizeCaptureMime("image/heic"))
        assertEquals("image/jpeg", normalizeCaptureMime("image/heif"))
        assertEquals("image/jpeg", normalizeCaptureMime("image/avif"))
        assertEquals("image/jpeg", normalizeCaptureMime(null))
        assertEquals("image/jpeg", normalizeCaptureMime(""))
    }

    @Test
    fun `MIME 参数与大小写不影响判定`() {
        assertEquals("image/jpeg", normalizeCaptureMime("IMAGE/JPEG"))
        assertEquals("image/png", normalizeCaptureMime("image/png; charset=utf-8"))
        assertTrue(requiresJpegReencode("image/heic"))
        assertFalse(requiresJpegReencode("image/JPEG; foo=bar"))
    }

    // —— 文件头嗅探：ContentResolver 的类型不可信，以字节为准 ——

    @Test
    fun `嗅探 JPEG PNG WEBP 与 HEIF 的文件头`() {
        assertEquals("image/jpeg", sniffImageMime(soi(0xFF, 0xD8)))
        assertEquals("image/png", sniffImageMime(pngSignature()))
        assertEquals("image/webp", sniffImageMime(riffWebp()))
        assertEquals("image/heif", sniffImageMime(ftypHeic()))
    }

    @Test
    fun `过短或不认识的字节不猜`() {
        assertNull(sniffImageMime(ByteArray(4)))
        assertNull(sniffImageMime("not an image at all....".toByteArray()))
    }

    @Test
    fun `HEIF 文件头需要重编码`() {
        val mime = sniffImageMime(ftypHeic())
        assertTrue(requiresJpegReencode(mime))
    }

    private fun soi(b0: Int, b1: Int): ByteArray = byteArrayOf(b0.toByte(), b1.toByte()) + ByteArray(10)

    private fun pngSignature(): ByteArray =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) + ByteArray(8)

    private fun riffWebp(): ByteArray =
        "RIFF".toByteArray() + ByteArray(4) + "WEBP".toByteArray()

    private fun ftypHeic(): ByteArray {
        val bytes = ByteArray(16)
        // offset 4..7 = "ftyp"
        val ftyp = "ftyp".toByteArray()
        ftyp.copyInto(bytes, 4)
        "heic".toByteArray().copyInto(bytes, 8)
        return bytes
    }
}
