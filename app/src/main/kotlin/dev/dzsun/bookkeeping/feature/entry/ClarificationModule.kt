package dev.dzsun.bookkeeping.feature.entry

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 界面侧自己的 DI：澄清出口的绑定放这里，不占用数据层的 `AppModule`。
 * 接调度层后把 [NoOpClarificationPort] 换成调 `DispatcherClient` 的实现即可。
 */
@Module
@InstallIn(SingletonComponent::class)
object ClarificationModule {

    @Provides
    @Singleton
    fun provideClarificationPort(): ClarificationPort = NoOpClarificationPort
}
