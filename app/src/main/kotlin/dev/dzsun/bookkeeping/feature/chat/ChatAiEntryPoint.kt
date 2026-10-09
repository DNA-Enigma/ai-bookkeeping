package dev.dzsun.bookkeeping.feature.chat

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * 聊天页用的 Hilt 入口。
 *
 * 聊天页刻意**不挂 ViewModel**（消息流是页面自己的 `remember` 状态），
 * 但要把 [AzhangChat] 这个单例取出来 —— 没有 ViewModel 就用 EntryPoint 取，
 * 比让它自己 new 一个（会绕过单例、导致重复加载模型）可靠。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ChatAiEntryPoint {
    fun azhangChat(): AzhangChat

    /** debug 批量评估入口也从这里取同一个单例，避免它自己 new 一份导致模型重复加载。 */
    fun sparkSession(): dev.dzsun.bookkeeping.llm.SparkSession
    fun sparkAiParser(): dev.dzsun.bookkeeping.feature.entry.SparkAiParser
}
