package dev.dzsun.bookkeeping.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
    entities = [
        AccountEntity::class,
        JournalEntity::class,
        PostingEntity::class,
        JournalItemEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(LedgerConverters::class)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun journalDao(): JournalDao
    abstract fun postingDao(): PostingDao
    abstract fun journalItemDao(): JournalItemDao

    companion object {
        /**
         * v1 → v2：加外部流水号（跨渠道去重键）、地点（回想锚点）、明细行表。
         *
         * **不用破坏性迁移**——那会清空用户账本。加列而不是改列，所以老数据原样保留；
         * 新列都可空，老行的 `externalRef` 为 null 不会与新导入的流水冲突
         * （SQLite 唯一索引里 NULL 互不相同）。
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE journal ADD COLUMN externalSource TEXT")
                db.execSQL("ALTER TABLE journal ADD COLUMN externalRef TEXT")
                db.execSQL("ALTER TABLE journal ADD COLUMN externalStatus TEXT")
                db.execSQL("ALTER TABLE journal ADD COLUMN place TEXT")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "index_journal_externalSource_externalRef " +
                        "ON journal(externalSource, externalRef)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS journal_item (
                        id TEXT NOT NULL PRIMARY KEY,
                        journalId TEXT NOT NULL,
                        description TEXT NOT NULL,
                        amountMinor INTEGER,
                        sortOrder INTEGER NOT NULL,
                        FOREIGN KEY(journalId) REFERENCES journal(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_journal_item_journalId " +
                        "ON journal_item(journalId)",
                )
            }
        }
    }
}
