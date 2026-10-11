package dev.dzsun.bookkeeping.core.platform

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 登陆令牌的落盘处。
 *
 * 存三件东西：令牌本身、**过期时刻**、以及账号邮箱。存过期时刻是因为
 * 「重开 App 要不要直接进首页」这个问题**必须能离线回答**——启动时先联网问一次
 * 「我这枚令牌还有效吗」，在没网的地铁里就变成了转圈等到超时，而这正是
 * 令牌（而不是每次重新登陆）存在的理由。服务端也在响应里同时给了
 * `expires_in` 与 `expires_at`，就是为这一步准备的。
 *
 * **只存令牌，不存口令。** 口令交给服务端校验，本地不留任何形态的副本——
 * 连不可逆的校验值也不留：它在这里没有任何用途，留着只是多一份可被
 * `adb run-as` 读走的材料。
 */
@Singleton
class TokenStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        purgeLegacyCredentials()
    }

    /**
     * 存入一次登陆会话。三项**同一次提交**写入。
     *
     * 分开写会留下一个"令牌已更新、过期时刻还是上一枚的"的中间态，
     * 而它恰好表现为"刚登陆完就被判成过期"或反之——最难查的那种。
     */
    fun save(token: String, expiresAtSeconds: Long, email: String) {
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putLong(KEY_EXPIRES_AT, expiresAtSeconds)
            .putString(KEY_EMAIL, email)
            .apply()
    }

    /** 当前令牌；没登陆过为 null。 */
    fun token(): String? = prefs.getString(KEY_TOKEN, null)

    /** 当前账号邮箱。令牌缺失时为 null——邮箱是跟着会话走的，不单独成立。 */
    fun email(): String? = if (token() == null) null else prefs.getString(KEY_EMAIL, null)

    /**
     * 今天（[nowSeconds] 秒级 Unix 时间）这枚令牌还能不能用。
     *
     * 判据只有一件事：过期时刻**已知**且还没到（留 [TokenValidity.EXPIRY_SKEW_S] 余量）。
     * 过期时刻缺失一律算不能用——见 [TokenValidity.isUsable] 里为什么不能"乐观放行"。
     */
    fun hasUsableToken(nowSeconds: Long): Boolean =
        TokenValidity.isUsable(
            expiresAtSeconds = if (token() == null) null else prefs.getLong(KEY_EXPIRES_AT, MISSING),
            nowSeconds = nowSeconds,
        )

    /** 退出登陆：把会话清干净。 */
    fun clear() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_EXPIRES_AT).remove(KEY_EMAIL).apply()
    }

    /**
     * 抹掉上一版留在同一份 prefs 里的本地账号材料。
     *
     * 上一版是**纯本地**账号：`email` 之外还写了 `password`（明文口令）与
     * `password_hash`（PBKDF2 校验值）。账号体系改到服务端之后这两个键再没有
     * 读取点，但**不主动删就会一直躺在磁盘上**——升级只改写入路径的话，
     * 那份明文会跟着这份安装活到卸载为止。所以这里在构造时无条件清一次。
     */
    private fun purgeLegacyCredentials() {
        val legacy = listOf(KEY_LEGACY_PLAINTEXT_PASSWORD, KEY_LEGACY_PASSWORD_HASH)
            .filter { prefs.contains(it) }
        if (legacy.isNotEmpty()) {
            prefs.edit().apply { legacy.forEach { remove(it) } }.apply()
        }
    }

    companion object {
        /** 与上一版共用同一个文件名，这样 [purgeLegacyCredentials] 才够得着那些旧键。 */
        const val PREFS_NAME = "auth_session"

        const val KEY_TOKEN = "token"
        const val KEY_EXPIRES_AT = "expires_at_seconds"
        const val KEY_EMAIL = "email"

        /** 上一版的键，仅用于清除，不再有写入点。 */
        const val KEY_LEGACY_PLAINTEXT_PASSWORD = "password"
        const val KEY_LEGACY_PASSWORD_HASH = "password_hash"

        /** `getLong` 的"没这个键"哨兵。令牌缺失时这个值不会被读到。 */
        const val MISSING = Long.MIN_VALUE
    }
}

/**
 * 令牌有效性的判定。**抽成纯函数**是为了能在 JVM 测试里钉住边界——
 * 有效期的错判两种方向都很贵：判宽了会让用户进到首页、然后每个请求都 401；
 * 判严了会让还有效的令牌被丢掉，用户莫名其妙要重新登陆一次。
 */
object TokenValidity {

    /**
     * 提前量：把"还剩不到一分钟"也算作过期。
     *
     * 两件事同时被它挡住。一是**设备时钟与服务端的偏差**——判定用的是本地时钟，
     * 而令牌的到期时刻是服务端时钟算的，两者差几分钟很常见，卡在边界上就会出现
     * "本地说还有效、服务端说已过期"，表现是进首页后满屏 401。二是**请求本身的耗时**：
     * 令牌在请求发出时有效、到达服务端时刚好过期，同样是一个 401。
     * 提前一分钟失效，代价是用户偶尔多登陆一次，比上面两种好得多。
     */
    const val EXPIRY_SKEW_S: Long = 60L

    /**
     * @param expiresAtSeconds 服务端给的到期时刻（**秒**）；null 表示不知道。
     */
    fun isUsable(
        expiresAtSeconds: Long?,
        nowSeconds: Long,
        skewSeconds: Long = EXPIRY_SKEW_S,
    ): Boolean {
        // 不知道到期时刻 —— **不能**当成"大概没事"放行。放行的后果是拿一枚
        // 可能早就过期的令牌进首页，然后每个请求各自失败，而用户看到的是
        // "界面坏了"而不是"该重新登陆了"。宁可让他登陆一次。
        val expiresAt = expiresAtSeconds ?: return false
        if (expiresAt <= 0L) return false
        return nowSeconds + skewSeconds < expiresAt
    }
}
