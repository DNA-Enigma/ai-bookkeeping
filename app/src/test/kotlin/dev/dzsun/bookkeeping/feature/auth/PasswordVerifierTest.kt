package dev.dzsun.bookkeeping.feature.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 口令校验值的回归。
 *
 * 最关键的一条是 [落盘的校验值不含口令明文]：修复前落盘的就是口令原文
 * （`SessionStore.kt` 的 `password` 键），这条断言在旧实现上必红——
 * 它是「明文进 SharedPreferences 必须消除」这件事的凭证。
 */
class PasswordVerifierTest {

    @Test
    fun `落盘的校验值不含口令明文`() {
        val password = "hunter2-复用口令"
        val stored = PasswordVerifier.hash(password)

        // 修复前 SharedPreferences 里存的就是这个字符串本身
        assertFalse("校验值里不得出现口令原文：$stored", stored.contains(password))
        // 也不该是任何简单变换能还原的形状——只应是「前缀$迭代数$盐$派生值」
        assertTrue("应当是带前缀的结构化校验值：$stored", stored.startsWith("pbkdf2-sha256$"))
    }

    @Test
    fun `口令正确时校验通过`() {
        val stored = PasswordVerifier.hash("correct horse")
        assertTrue(PasswordVerifier.verify("correct horse", stored))
    }

    @Test
    fun `口令错误时校验失败`() {
        val stored = PasswordVerifier.hash("correct horse")
        assertFalse(PasswordVerifier.verify("correct hors", stored))
        assertFalse(PasswordVerifier.verify("", stored))
        assertFalse(PasswordVerifier.verify("Correct Horse", stored))
    }

    @Test
    fun `同一口令两次落盘的值不同——盐是随机的`() {
        val a = PasswordVerifier.hash("123456")
        val b = PasswordVerifier.hash("123456")

        // 值不同，但都能校验通过：预计算表对撞因此失效
        assertNotEquals(a, b)
        assertTrue(PasswordVerifier.verify("123456", a))
        assertTrue(PasswordVerifier.verify("123456", b))
    }

    @Test
    fun `校验值被篡改时失败`() {
        val stored = PasswordVerifier.hash("123456")
        val parts = stored.split('$')
        assertEquals("结构化校验值应有四段", 4, parts.size)

        fun rejoin(derived: String = parts[3], salt: String = parts[2]): String =
            listOf(parts[0], parts[1], salt, derived).joinToString("\$")

        // 改派生值
        assertFalse(PasswordVerifier.verify("123456", rejoin(derived = parts[3].reversed())))
        // 改盐
        assertFalse(PasswordVerifier.verify("123456", rejoin(salt = parts[2].reversed())))
    }

    @Test
    fun `缺失或格式错乱的校验值一律拒绝，不放行`() {
        assertFalse(PasswordVerifier.verify("123456", null))
        assertFalse(PasswordVerifier.verify("123456", ""))
        assertFalse(PasswordVerifier.verify("123456", "123456")) // 修复前的裸口令
        assertFalse(PasswordVerifier.verify("123456", "pbkdf2-sha256\$abc\$\$"))
        assertFalse(PasswordVerifier.verify("123456", "md5\$100\$x\$y"))
        assertFalse(PasswordVerifier.verify("123456", "pbkdf2-sha256\$0\$AAAA\$AAAA"))
    }
}
