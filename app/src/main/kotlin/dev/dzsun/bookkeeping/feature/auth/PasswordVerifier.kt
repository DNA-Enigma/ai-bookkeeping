package dev.dzsun.bookkeeping.feature.auth

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * 本地账号的**口令校验值**。
 *
 * 这里落盘的是 `PBKDF2` 派生出的**不可逆**校验值，绝不是口令本身——
 * 用户口令常被复用到别处，而 debug 包上 `adb run-as` 就能把
 * SharedPreferences 整份读走。存明文等于把用户别处的口令一起送出去。
 *
 * 为什么不按「Keystore 加密」做：Keystore 加密的口令是**可解密的**，
 * 拿到密钥就能还原明文；而我们只需要回答「口令对不对」，永远不需要读回来。
 * 不可逆校验值在这个用途上严格强于可逆加密。
 *
 * 格式：`pbkdf2-sha256$<迭代数>$<盐 base64>$<派生值 base64>`。
 * 盐每次随机，所以同一个口令两次落盘的值不同，无法用预计算表对撞。
 */
internal object PasswordVerifier {

    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val ITERATIONS = 100_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val PREFIX = "pbkdf2-sha256"
    private const val SEPARATOR = '$'

    fun hash(password: String): String {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val derived = derive(password, salt, ITERATIONS)
        return listOf(PREFIX, ITERATIONS.toString(), encode(salt), encode(derived))
            .joinToString(SEPARATOR.toString())
    }

    /** 校验值缺失、格式不对、算法不符，一律当作**不匹配**——绝不放行。 */
    fun verify(password: String, stored: String?): Boolean {
        val parts = stored?.split(SEPARATOR) ?: return false
        if (parts.size != 4 || parts[0] != PREFIX) return false
        val iterations = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return false
        val salt = decode(parts[2]) ?: return false
        val expected = decode(parts[3]) ?: return false
        return constantTimeEquals(derive(password, salt, iterations), expected)
    }

    private fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /**
     * 定时安全比较：普通的 == 会在第一个不同的字节处提前返回，
     * 泄漏「前缀对上了多少」。校验值本身不该泄漏这类信息。
     */
    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decode(value: String): ByteArray? =
        runCatching { Base64.getDecoder().decode(value) }.getOrNull()
}
