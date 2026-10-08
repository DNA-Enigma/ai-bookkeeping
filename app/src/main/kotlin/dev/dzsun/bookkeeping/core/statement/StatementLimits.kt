package dev.dzsun.bookkeeping.core.statement

/**
 * 账单是**不可信输入**——用户从邮箱拿来的文件，可能被伪造成压缩炸弹或
 * 声明超大解压体积的包。一个几 KB 的文件就能让 App 解压出几个 GB（OOM）
 * 或在解压循环里卡死。
 *
 * 所有上限集中在这里，两个解压入口（[StatementArchive] 与 [XlsxSheet]）共用一份，
 * 免得各写各的、改了一处漏了另一处。
 *
 * 取值依据（实测真实导出）：支付宝 CSV 596 笔、微信 xlsx 89 笔，
 * 压缩后都在几十 KB 量级，最大的也在 1 MiB 以内。下面每一档都留了
 * **几十到上千倍**的余量——正常账单够不着，而炸弹在这种量级上必然越界。
 */
internal object StatementLimits {

    /** 账单文件本身（压缩后）的字节上限。正常账单 < 1 MiB，这里 ~30 倍余量。 */
    const val MAX_ARCHIVE_BYTES = 32 * 1024 * 1024

    /** 压缩包条目数上限。正常账单是 1 个 CSV，或 xlsx 的十来个 XML 部件。 */
    const val MAX_ENTRIES = 64

    /** **单个**条目解压后的字节上限。 */
    const val MAX_ENTRY_BYTES = 16 * 1024 * 1024

    /** 所有条目解压后的**累计**上限——防「多个中等条目叠加」绕过单条上限。 */
    const val MAX_TOTAL_BYTES = 64 * 1024 * 1024

    /** 单条目「解压后 / 压缩后」比例上限。正常 XML/CSV 约 5–20 倍，200 倍只管炸弹。 */
    const val MAX_RATIO = 200L

    /** xlsx 行号上限。正常账单 ~600 行。 */
    const val MAX_ROWS = 20_000

    /** xlsx 列号上限。正常账单 ~12 列。 */
    const val MAX_COLUMNS = 128

    /** 按比例挡掉「头部声称解压出巨量数据」的条目。压缩或解压长度未知时跳过。 */
    fun checkRatio(compressed: Long, uncompressed: Long, label: String) {
        if (compressed <= 0 || uncompressed <= 0) return
        if (uncompressed / compressed > MAX_RATIO) {
            throw StatementLimitExceeded(
                "$label 的压缩比约 ${uncompressed / compressed}:1，超过上限 $MAX_RATIO:1；疑似压缩炸弹，已拒绝导入",
            )
        }
    }

    fun humanSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "${bytes / (1024 * 1024)} MiB"
        bytes >= 1024 -> "${bytes / 1024} KiB"
        else -> "$bytes 字节"
    }
}

/**
 * 触碰上限时抛出。**不静默截断**——截断会让用户以为整份账单导进去了，
 * 而实际少了一半。由 `StatementImporter` 翻译成给用户看的「读不了」原因。
 */
internal class StatementLimitExceeded(val reason: String) : RuntimeException(reason)
