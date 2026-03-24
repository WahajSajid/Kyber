package app.secure.kyber.roomdb

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        MessageEntity::class,
        ContactEntity::class,
        GroupMessageEntity::class,
        GroupsEntity::class
    ],
    version = 10,
    exportSchema = false
)
abstract class AppDb : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun contactDao(): ContactDao
    abstract fun groupsMessagesDao(): GroupMessageDao
    abstract fun groupsDao(): GroupDao

    companion object {
        @Volatile
        private var INSTANCE: AppDb? = null

        fun get(context: Context): AppDb =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDb::class.java,
                    "app.db"
                )
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                        MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                        MIGRATION_9_10
                    )
                    .build()
                    .also { INSTANCE = it }
            }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS group_messages (
                        messageId TEXT PRIMARY KEY NOT NULL,
                        group_id TEXT NOT NULL,
                        msg TEXT NOT NULL,
                        senderId TEXT NOT NULL,
                        senderName TEXT NOT NULL,
                        time TEXT NOT NULL,
                        isSent INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS groups (
                        groupId TEXT PRIMARY KEY NOT NULL,
                        groupName TEXT NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE groups ADD COLUMN lastMessage TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE groups ADD COLUMN newMessagesCount INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE groups ADD COLUMN profileImageResId INTEGER")
                database.execSQL("ALTER TABLE groups ADD COLUMN timeSpan INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE groups ADD COLUMN chatTime TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE groups ADD COLUMN createdBy TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE groups ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE group_messages ADD COLUMN type TEXT NOT NULL DEFAULT 'TEXT'")
                database.execSQL("ALTER TABLE group_messages ADD COLUMN uri TEXT")
                database.execSQL("ALTER TABLE messages ADD COLUMN uri TEXT")
                database.execSQL("ALTER TABLE messages ADD COLUMN type TEXT NOT NULL DEFAULT 'TEXT'")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE messages ADD COLUMN ampsJson TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE group_messages ADD COLUMN ampsJson TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE groups ADD COLUMN noOfMembers INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE group_messages ADD COLUMN reaction TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE messages ADD COLUMN reaction TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Migrate contacts: union_id -> onionAddress
                database.execSQL("CREATE TABLE contacts_new (onionAddress TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL)")
                database.execSQL("INSERT INTO contacts_new (onionAddress, name) SELECT union_id, name FROM contacts")
                database.execSQL("DROP TABLE contacts")
                database.execSQL("ALTER TABLE contacts_new RENAME TO contacts")

                // Migrate messages: senderId -> senderOnion
                database.execSQL("""
                    CREATE TABLE messages_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        msg TEXT NOT NULL,
                        senderOnion TEXT NOT NULL,
                        time TEXT NOT NULL,
                        isSent INTEGER NOT NULL,
                        type TEXT NOT NULL DEFAULT 'TEXT',
                        uri TEXT,
                        ampsJson TEXT NOT NULL DEFAULT '',
                        reaction TEXT NOT NULL DEFAULT ''
                    )
                """.trimIndent())
                database.execSQL("""
                    INSERT INTO messages_new (id, msg, senderOnion, time, isSent, type, uri, ampsJson, reaction)
                    SELECT id, msg, senderId, time, isSent, type, uri, ampsJson, reaction FROM messages
                """.trimIndent())
                database.execSQL("DROP TABLE messages")
                database.execSQL("ALTER TABLE messages_new RENAME TO messages")

                // Migrate group_messages: senderId -> senderOnion
                database.execSQL("""
                    CREATE TABLE group_messages_new (
                        messageId TEXT PRIMARY KEY NOT NULL,
                        group_id TEXT NOT NULL,
                        msg TEXT NOT NULL,
                        senderOnion TEXT NOT NULL,
                        senderName TEXT NOT NULL,
                        time TEXT NOT NULL,
                        isSent INTEGER NOT NULL,
                        type TEXT NOT NULL DEFAULT 'TEXT',
                        uri TEXT,
                        ampsJson TEXT NOT NULL DEFAULT '',
                        reaction TEXT NOT NULL DEFAULT ''
                    )
                """.trimIndent())
                database.execSQL("""
                    INSERT INTO group_messages_new (messageId, group_id, msg, senderOnion, senderName, time, isSent, type, uri, ampsJson, reaction)
                    SELECT messageId, group_id, msg, senderId, senderName, time, isSent, type, uri, ampsJson, reaction FROM group_messages
                """.trimIndent())
                database.execSQL("DROP TABLE group_messages")
                database.execSQL("ALTER TABLE group_messages_new RENAME TO group_messages")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE messages ADD COLUMN apiMessageId TEXT")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_messages_apiMessageId ON messages (apiMessageId)")
            }
        }
    }
}
