package dev.dzsun.bookkeeping.feature.entry

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.dzsun.bookkeeping.core.network.DispatcherClient
import javax.inject.Singleton

/**
 * 界面侧自己的 DI：澄清出口的绑定放这里，不占用数据层的 `AppModule`。
 * 拿到 [DispatcherClient] 就走调度层，拿不到才退回 [NoOpClarificationPort]。
 */
@Module
@InstallIn(SingletonComponent::class)
object ClarificationModule {

    @Provides
    @Singleton
    fun provideClarificationPort(client: DispatcherClient): ClarificationPort =
        DispatcherClarificationPort(client)
}
