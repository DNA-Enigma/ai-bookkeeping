package dev.dzsun.bookkeeping.feature.stats

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 收支报表的界面侧 DI。
 *
 * [AiSummarySource] → 走调度层直答通道（`DispatcherAiSummarySource`）。
 * 与 `ClarificationModule` / `ReportModule` 同一个路子：缝留在界面侧自己的模块里，
 * 不占用数据层的 `AppModule`；换实现（比如换成流式输出）只动这一行绑定，
 * `StatsViewModel` 与 `AiSummaryCard` 一行都不用改。
 */
@Module
@InstallIn(SingletonComponent::class)
object StatsModule {

    @Provides
    @Singleton
    fun provideAiSummarySource(impl: DispatcherAiSummarySource): AiSummarySource = impl
}
