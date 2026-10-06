package dev.dzsun.bookkeeping.core.platform

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.dzsun.bookkeeping.core.database.LedgerDatabase
import dev.dzsun.bookkeeping.feature.entry.AiParser
import dev.dzsun.bookkeeping.feature.entry.LocalAiParser
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * 刻意不开启 `fallbackToDestructiveMigration`：宁可 schema 变更后启动失败，
     * 也不要静默清空用户账本。开发期改 schema 时手动卸载重装即可。
     */
    @Provides
    @Singleton
    fun provideLedgerDatabase(@ApplicationContext context: Context): LedgerDatabase =
        Room.databaseBuilder(context, LedgerDatabase::class.java, "ledger.db").build()

    @Provides
    @Singleton
    fun provideClock(): Clock = SystemClock()

    @Provides
    @Singleton
    fun provideIdGenerator(): IdGenerator = UuidGenerator()

    @Provides
    @Singleton
    fun provideAiParser(): AiParser = LocalAiParser()
}
