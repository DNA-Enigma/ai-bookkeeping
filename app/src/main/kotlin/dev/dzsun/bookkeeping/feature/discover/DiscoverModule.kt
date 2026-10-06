package dev.dzsun.bookkeeping.feature.discover

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DiscoverModule {

    @Provides
    @Singleton
    fun provideAdvisor(): Advisor = LocalAdvisor()
}
