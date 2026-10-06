package dev.dzsun.bookkeeping.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

/**
 * 枚举以名称存成字符串。显式转换而不是依赖 Room 的内建行为，
 * 是为了让"存进去的是什么"在 schema 里一眼可见——用序号存过的枚举，
 * 日后在中间插一个值就会静默错位。
 */
class LedgerConverters {
    @TypeConverter fun accountTypeToString(value: AccountType): String = value.name
    @TypeConverter fun stringToAccountType(value: String): AccountType = AccountType.valueOf(value)

    @TypeConverter fun journalStatusToString(value: JournalStatus): String = value.name
    @TypeConverter fun stringToJournalStatus(value: String): JournalStatus = JournalStatus.valueOf(value)

    @TypeConverter fun journalSourceToString(value: JournalSource): String = value.name
    @TypeConverter fun stringToJournalSource(value: String): JournalSource = JournalSource.valueOf(value)
}

@Database(
    entities = [AccountEntity::class, JournalEntity::class, PostingEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(LedgerConverters::class)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun journalDao(): JournalDao
    abstract fun postingDao(): PostingDao
}
