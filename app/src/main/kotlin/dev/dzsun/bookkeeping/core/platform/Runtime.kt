package dev.dzsun.bookkeeping.core.platform

import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject

/**
 * 时间的来源。抽成接口是为了让账目逻辑能在纯 JVM 测试里固定"今天"——
 * 依赖 `LocalDate.now()` 的代码没法测跨月、跨年这些边界。
 */
interface Clock {
    fun nowMillis(): Long
    fun today(): LocalDate
}

/** ID 的来源。同理，测试里需要可预测的 ID 才能断言具体产物。 */
interface IdGenerator {
    fun newId(): String
}

class SystemClock @Inject constructor() : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
    override fun today(): LocalDate = LocalDate.now()
}

class UuidGenerator @Inject constructor() : IdGenerator {
    override fun newId(): String = UUID.randomUUID().toString()
}
