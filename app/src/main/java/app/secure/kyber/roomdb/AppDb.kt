package app.secure.kyber.roomdb

import android. content.Context
import androidx.room.Database
import androidx.room. Room
import androidx.room.RoomDatabase
import androidx.room. migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        MessageEntity::class,
        ContactEntity::class,
        GroupMessageEntity::class,
        GroupsEntity::class
    ],
    version = 8,
    exportSchema = false
)
abstract class AppDb : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun contactDao(): ContactDao
    abstract fun groupsMessagesDao(): GroupMessageDao
    abstract fun groupsDao(): GroupDao

    companion object {
        @Volatile
        private var INSTANCE:  AppDb? = null

        fun get(context: Context): AppDb =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDb::class. java,
                    "app. db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)  // Add both migrations
                    .build()
                    .also { INSTANCE = it }
            }

        // Migration from version 1 to 2
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Create group_messages table
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

                // Create groups table
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS groups (
                        groupId TEXT PRIMARY KEY NOT NULL,
                        groupName TEXT NOT NULL
                    )
                    """. trimIndent()
                )
            }
        }

        // Migration from version 2 to 3
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add new columns to groups table
                database.execSQL(
                    "ALTER TABLE groups ADD COLUMN lastMessage TEXT NOT NULL DEFAULT ''"
                )
                database.execSQL(
                    "ALTER TABLE groups ADD COLUMN newMessagesCount INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE groups ADD COLUMN profileImageResId INTEGER"
                )
                database. execSQL(
                    "ALTER TABLE groups ADD COLUMN timeSpan INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE groups ADD COLUMN chatTime TEXT NOT NULL DEFAULT ''"
                )
            }
        }


        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add new columns to groups table
                database.execSQL(
                    "ALTER TABLE groups ADD COLUMN createdBy TEXT NOT NULL DEFAULT ''"
                )
                database.execSQL(
                    "ALTER TABLE groups ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add new columns to groups table

                //Alter in 'group_messages' table
                database.execSQL(
                    "ALTER TABLE group_messages ADD COLUMN type TEXT NOT NULL DEFAULT 'TEXT'"
                )
                database.execSQL(
                    "ALTER TABLE group_messages ADD COLUMN uri TEXT INTEGER NOT NULL DEFAULT 0"
                )

                //Alter in 'messages' table
                database.execSQL(
                    "ALTER TABLE messages ADD COLUMN uri TEXT INTEGER NOT NULL DEFAULT 0"
                )

                database.execSQL(
                    "ALTER TABLE messages ADD COLUMN type TEXT NOT NULL DEFAULT 'TEXT'"
                )

            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add new columns to groups table

                //Alter in 'messages' table
                database.execSQL(
                    "ALTER TABLE messages ADD COLUMN ampsJson TEXT INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE group_messages ADD COLUMN ampsJson TEXT INTEGER NOT NULL DEFAULT 0"
                )


            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add new columns to groups table

                //Alter in 'messages' table
                database.execSQL(
                    "ALTER TABLE groups ADD COLUMN noOfMembers INTEGER NOT NULL DEFAULT 0"
                )

            }
        }
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add new columns to groups table

                //Alter in 'messages' table
                database.execSQL(
                    "ALTER TABLE group_messages ADD COLUMN reaction TEXT INTEGER NOT NULL DEFAULT 0"
                )

                database.execSQL(
                    "ALTER TABLE messages ADD COLUMN reaction TEXT INTEGER NOT NULL DEFAULT 0"
                )

            }
        }
    }
}