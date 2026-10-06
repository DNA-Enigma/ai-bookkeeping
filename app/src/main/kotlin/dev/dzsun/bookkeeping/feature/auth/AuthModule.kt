package dev.dzsun.bookkeeping.feature.auth

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AuthModule {

    @Binds
    @Singleton
    abstract fun bindSessionStore(impl: SharedPrefsSessionStore): SessionStore

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: LocalAuthRepository): AuthRepository
}
