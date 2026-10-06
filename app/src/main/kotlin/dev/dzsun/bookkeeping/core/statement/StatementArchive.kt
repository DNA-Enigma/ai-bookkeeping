package dev.dzsun.bookkeeping.core.statement

import java.util.zip.Inflater

/**
 * 打开流水文件，不管它是裸 CSV 还是加密压缩包。
 *
 * 微信/支付宝的账单导出后是**加密 ZIP**（申请后发到邮箱的那种），
 * 而 `java.util.zip` 只解压不解密——`ZipInputStream` 遇到加密条目会直接抛异常。
 * 所以 ZipCrypto（传统 PKWARE 加密）得自己实现，见 [ZipCrypto]。
 */
object StatementArchive {

    fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
            bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()

    /**
     * 取出压缩包里的 CSV 字节。
     *
     * [password] 为 null 表示还没问用户要；加密条目会返回 [ArchiveExtraction.NeedsPassword]。
     */
    fun open(bytes: ByteArray, password: String?): ArchiveExtraction {
        if (!isZip(bytes)) return ArchiveExtraction.NotZip

        var offset = 0
        var sawEncrypted = false
        while (offset + LOCAL_HEADER_SIZE <= bytes.size) {
            if (le32(bytes, offset) != LOCAL_HEADER_SIGNATURE) break
            val flags = le16(bytes, offset + 6)
            val method = le16(bytes, offset + 8)
            val dosTime = le16(bytes, offset + 10)
            val crc32 = le32(bytes, offset + 14)
            val compressedSize = le32(bytes, offset + 18)
            val uncompressedSize = le32(bytes, offset + 22)
            val nameLength = le16(bytes, offset + 26)
            val extraLength = le16(bytes, offset + 28)

            val nameStart = offset + LOCAL_HEADER_SIZE
            val name = runCatching {
                String(bytes, nameStart, nameLength, Charsets.UTF_8)
            }.getOrDefault("")
            val dataStart = nameStart + nameLength + extraLength
            if (dataStart > bytes.size) break

            val encrypted = (flags and FLAG_ENCRYPTED) != 0
            val isCsv = name.endsWith(".csv", ignoreCase = true)
            // 优先挑 csv；不是 csv 的条目（如说明文本）跳过继续找
            if (!isCsv && offset + LOCAL_HEADER_SIZE < bytes.size) {
                if (compressedSize <= 0) break
                offset = dataStart + compressedSize
                if (encrypted) sawEncrypted = true
                continue
            }

            if (encrypted) {
                sawEncrypted = true
                if (password == null) return ArchiveExtraction.NeedsPassword(name)
                val result = decryptEntry(bytes, dataStart, compressedSize, uncompressedSize, method, flags, crc32, dosTime, password)
                return when (result) {
                    null -> ArchiveExtraction.WrongPassword(name)
                    else -> ArchiveExtraction.Extracted(name, result)
                }
            }

            val plain = inflate(bytes, dataStart, compressedSize, uncompressedSize, method)
                ?: return ArchiveExtraction.Failed("压缩包内的条目无法解压（压缩方式 $method）")
            return ArchiveExtraction.Extracted(name, plain)
        }

        return if (sawEncrypted) {
            ArchiveExtraction.NeedsPassword("")
        } else {
            ArchiveExtraction.Failed("压缩包里没有找到 CSV 条目")
        }
    }

    /**
     * 解密一个条目。密码不对时返回 null。
     *
     * 校验方式：ZipCrypto 会在压缩数据前放 12 字节头，其中第 12 字节是校验值——
     * 未置「数据描述符」标志位时它是 CRC32 的最高字节，置位时是 DOS 时间的最高字节。
     * 这是个 1/256 的弱校验，但足以挡住手输错的密码，也是格式本身给的全部。
     */
    private fun decryptEntry(
        bytes: ByteArray,
        dataStart: Int,
        compressedSize: Int,
        uncompressedSize: Int,
        method: Int,
        flags: Int,
        crc32: Int,
        dosTime: Int,
        password: String,
    ): ByteArray? {
        val keys = ZipCrypto(password)
        val headerEnd = dataStart + ZipCrypto.HEADER_BYTES
        if (headerEnd > bytes.size) return null

        val header = ByteArray(ZipCrypto.HEADER_BYTES)
        for (i in 0 until ZipCrypto.HEADER_BYTES) {
            header[i] = keys.decrypt(bytes[dataStart + i])
        }
        val expected = if ((flags and FLAG_DATA_DESCRIPTOR) != 0) {
            (dosTime ushr 8) and 0xff
        } else {
            (crc32 ushr 24) and 0xff
        }
        if ((header[ZipCrypto.HEADER_BYTES - 1].toInt() and 0xff) != expected) return null

        // 压缩长度可能为 0（置了数据描述符的写法），那就多喂一些字节给 inflater——
        // 它解完就停，尾部多余的字节不会有害
        val end = if (compressedSize > 0) {
            (dataStart + ZipCrypto.HEADER_BYTES + compressedSize).coerceAtMost(bytes.size)
        } else {
            bytes.size
        }
        val decrypted = ByteArray(end - headerEnd)
        for (i in decrypted.indices) {
            decrypted[i] = keys.decrypt(bytes[headerEnd + i])
        }
        return inflate(decrypted, 0, decrypted.size, uncompressedSize, method)
    }

    private fun inflate(source: ByteArray, offset: Int, length: Int, expectedSize: Int, method: Int): ByteArray? {
        if (method == METHOD_STORED) {
            val size = if (expectedSize > 0) expectedSize else length
            return source.copyOfRange(offset, (offset + size).coerceAtMost(source.size))
        }
        if (method != METHOD_DEFLATE) return null

        val inflater = Inflater(true)
        return try {
            inflater.setInput(source, offset, length)
            val out = java.io.ByteArrayOutputStream(if (expectedSize > 0) expectedSize else DEFAULT_BUFFER)
            val buffer = ByteArray(DEFAULT_BUFFER)
            while (!inflater.finished()) {
                val read = inflater.inflate(buffer)
                if (read == 0) {
                    // 既没产出又没结束：输入不够或数据损坏，再试下去就是死循环
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                }
                out.write(buffer, 0, read)
            }
            out.toByteArray().takeIf { it.isNotEmpty() }
        } catch (e: java.util.zip.DataFormatException) {
            null
        } finally {
            inflater.end()
        }
    }

    private fun le16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun le32(bytes: ByteArray, offset: Int): Int =
        le16(bytes, offset) or (le16(bytes, offset + 2) shl 16)

    private const val LOCAL_HEADER_SIZE = 30
    private const val LOCAL_HEADER_SIGNATURE = 0x04034b50
    private const val FLAG_ENCRYPTED = 0x0001
    private const val FLAG_DATA_DESCRIPTOR = 0x0008
    private const val METHOD_STORED = 0
    private const val METHOD_DEFLATE = 8
    private const val DEFAULT_BUFFER = 16 * 1024
}

/** 打开压缩包的结果。每一种失败都要能对用户说清楚，否则他无从判断该怎么办。 */
sealed interface ArchiveExtraction {
    data class Extracted(val fileName: String, val bytes: ByteArray) : ArchiveExtraction {
        override fun equals(other: Any?): Boolean =
            other is Extracted && other.fileName == fileName && other.bytes.contentEquals(bytes)

        override fun hashCode(): Int = fileName.hashCode() * 31 + bytes.contentHashCode()
    }

    /** 不是压缩包——可能是还没压缩的裸 CSV，调用方应当当普通文件再试一次。 */
    data object NotZip : ArchiveExtraction

    /** 是加密压缩包但还没给密码。 */
    data class NeedsPassword(val fileName: String) : ArchiveExtraction

    /** 密码不对（或者用了 AES 加密，那超出 ZipCrypto 的范围）。 */
    data class WrongPassword(val fileName: String) : ArchiveExtraction

    data class Failed(val reason: String) : ArchiveExtraction
}

/**
 * ZipCrypto（传统 PKWARE 加密）。
 *
 * 算法本身不复杂，但**它是不安全的**——已知明文攻击可以还原密钥。这不影响我们：
 * 这里解的是用户自己导出的账单，密码也在他手上，目的只是把文件打开给人看，
 * 不是保护什么。所以不要因为「实现密码学」而高估它的强度。
 */
internal class ZipCrypto(password: String) {

    private var key0 = 0x12345678
    private var key1 = 0x23456789
    private var key2 = 0x34567890

    init {
        // 密码按 UTF-8 取字节。账单密码通常是身份证后六位或手机号后四位，
        // 纯 ASCII，任何 ASCII 兼容编码结果都一样
        for (byte in password.toByteArray(Charsets.UTF_8)) update(byte)
    }

    fun decrypt(cipherByte: Byte): Byte {
        val plain = (cipherByte.toInt() xor decryptByte()).toByte()
        // 注意：**用的是明文**去更新密钥，不是密文
        update(plain)
        return plain
    }

    private fun decryptByte(): Int {
        val temp = (key2 or 2) and 0xffff
        return ((temp * (temp xor 1)) ushr 8) and 0xff
    }

    private fun update(byte: Byte) {
        key0 = crc32(key0, byte)
        key1 += key0 and 0xff
        key1 = key1 * 134775813 + 1
        key2 = crc32(key2, (key1 ushr 24).toByte())
    }

    private fun crc32(old: Int, byte: Byte): Int =
        (old ushr 8) xor CRC_TABLE[(old xor byte.toInt()) and 0xff]

    companion object {
        const val HEADER_BYTES = 12

        private val CRC_TABLE = IntArray(256) { index ->
            var value = index
            repeat(8) {
                value = if (value and 1 != 0) (value ushr 1) xor 0xEDB88320.toInt() else value ushr 1
            }
            value
        }
    }
}
