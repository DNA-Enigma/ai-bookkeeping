package dev.dzsun.bookkeeping.feature.importer

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.dzsun.bookkeeping.core.network.DispatcherMerchantCategorizer
import dev.dzsun.bookkeeping.core.statement.MerchantCategorizer
import javax.inject.Singleton

/**
 * 陌生商户归类的绑定。与 `ClarificationModule` 同一个路子：
 * 界面侧自己的出口绑在这里，不占用数据层的 `AppModule`。
 *
 * 目前只有一条路——走调度层。**没有本地兜底**：约定 4 明写客户端不做关键词匹配，
 * 而一份写死的商户→分类映射表迟早和用户的科目表对不上（`LocalAiParser` 踩过这个坑）。
 * 拿不到建议时界面如实说「暂时无法智能归类」，用户手工挑分类即可。
 */
@Module
@InstallIn(SingletonComponent::class)
object MerchantCategorizationModule {

    @Provides
    @Singleton
    fun provideMerchantCategorizer(impl: DispatcherMerchantCategorizer): MerchantCategorizer = impl
}

