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
        BudgetEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
@TypeConverters(LedgerConverters::class)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun journalDao(): JournalDao
    abstract fun postingDao(): PostingDao
    abstract fun journalItemDao(): JournalItemDao
    abstract fun budgetDao(): BudgetDao
    abstract fun ledgerQueryDao(): LedgerQueryDao

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

        /**
         * v2 → v3：冲减的溯源列（退款/撤销冲的是哪一笔）。
         *
         * 同样是加列而非改列，**不用破坏性迁移**——那会清空账本。
         * 老账目的该列为 NULL，含义正是「不是冲减」，与旧数据相符。
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE journal ADD COLUMN reversesJournalId TEXT")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_journal_reversesJournalId " +
                        "ON journal(reversesJournalId)",
                )
            }
        }

        /**
         * v3 → v4：分类的月度预算落表。
         *
         * 纯新增一张表，**不碰任何既有数据**，所以还是不用破坏性迁移。
         * 主键即分类 id，没有索引要建（SQLite 的主键本身就有索引）、没有外键。
         *
         * 注意：预算此前存在 SharedPreferences 里（`PrefsBudgetStore`）。
         * 这次是**换存储而不是搬数据**——旧 prefs 里的预算不会被读进这张表。
         * 试点阶段版本没发出去过，用户重设一次即可；真要兼容得在开库前读一次 prefs。
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS budget (
                        categoryId TEXT NOT NULL,
                        amountMinor INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(categoryId)
                    )
                    """.trimIndent(),
                )
            }
        }
    }
}
