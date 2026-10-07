package dev.dzsun.bookkeeping.feature.report

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 报表与预算的界面侧 DI。
 *
 * 三个绑定都是**接口 → 当前实现**，换实现只动这里：
 * - [MonthlyReportSource] → 适配数据层的 `LedgerRepository.observeMonthlyReport`
 * - [BudgetStore] → `budget` 表（数据层的 `LedgerRepository`）
 * - [BudgetSettings] → 阈值长期留在界面侧（那是用户偏好，不是账本数据）
 */
@Module
@InstallIn(SingletonComponent::class)
object ReportModule {

    @Provides
    @Singleton
    fun provideMonthlyReportSource(impl: LedgerMonthlyReportSource): MonthlyReportSource = impl

    @Provides
    @Singleton
    fun provideBudgetStore(impl: RepositoryBudgetStore): BudgetStore = impl

    @Provides
    @Singleton
    fun provideBudgetSettings(impl: PrefsBudgetSettings): BudgetSettings = impl
}
