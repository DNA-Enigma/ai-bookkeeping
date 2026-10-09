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
import dev.dzsun.bookkeeping.feature.entry.SparkAiParser
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * 刻意不开启 `fallbackToDestructiveMigration`：宁可 schema 变更后启动失败，
     * 也不要静默清空用户账本。所以每次改 schema 都要**显式写出迁移**。
     */
    @Provides
    @Singleton
    fun provideLedgerDatabase(@ApplicationContext context: Context): LedgerDatabase =
        Room.databaseBuilder(context, LedgerDatabase::class.java, "ledger.db")
            .addMigrations(
                LedgerDatabase.MIGRATION_1_2,
                LedgerDatabase.MIGRATION_2_3,
                LedgerDatabase.MIGRATION_3_4,
            )
            .build()

    @Provides
    @Singleton
    fun provideClock(): Clock = SystemClock()

    @Provides
    @Singleton
    fun provideIdGenerator(): IdGenerator = UuidGenerator()

    /**
     * 口语记账解析：**端侧模型为主路径**（赛题硬性要求不走云端）。
     * 模型缺失/超时/输出不合法时，[SparkAiParser] 内部降级到规则版，
     * 不会让用户记不了账——降级与否由 [dev.dzsun.bookkeeping.feature.entry.ParseSource] 标注。
     */
    @Provides
    @Singleton
    fun provideAiParser(parser: SparkAiParser): AiParser = parser
}
