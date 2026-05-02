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
        GroupsEntity::class,
        KeyEntity::class
    ],
    version = 28,
    exportSchema = false
)
abstract class AppDb : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun contactDao(): ContactDao
    abstract fun groupsMessagesDao(): GroupMessageDao
    abstract fun groupsDao(): GroupDao
    abstract fun keyDao(): KeyDao

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
                        MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15,
                        MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19,
                        MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23,
                        MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28
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

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Recreate messages table to add messageId with NOT NULL and UNIQUE constraints
                // Removed DEFAULT values as they are not present in Room's expected schema for MessageEntity
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `messages_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                        `messageId` TEXT NOT NULL, 
                        `apiMessageId` TEXT, 
                        `msg` TEXT NOT NULL, 
                        `senderOnion` TEXT NOT NULL, 
                        `time` TEXT NOT NULL, 
                        `isSent` INTEGER NOT NULL, 
                        `type` TEXT NOT NULL, 
                        `uri` TEXT, 
                        `ampsJson` TEXT NOT NULL, 
                        `reaction` TEXT NOT NULL
                    )
                """.trimIndent())

                // Copy data, using 'id' as a fallback for 'messageId' to ensure uniqueness and NOT NULL for existing rows
                database.execSQL("""
                    INSERT INTO `messages_new` (`id`, `messageId`, `apiMessageId`, `msg`, `senderOnion`, `time`, `isSent`, `type`, `uri`, `ampsJson`, `reaction`)
                    SELECT `id`, CAST(`id` AS TEXT), `apiMessageId`, `msg`, `senderOnion`, `time`, `isSent`, `type`, `uri`, `ampsJson`, `reaction` FROM `messages`
                """.trimIndent())

                database.execSQL("DROP TABLE `messages`")
                database.execSQL("ALTER TABLE `messages_new` RENAME TO `messages`")
                
                // Re-create indices as defined in MessageEntity
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_messages_apiMessageId` ON `messages` (`apiMessageId`)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_messages_messageId` ON `messages` (`messageId`)")
            }

        }
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `messages` ADD COLUMN `updatedAt` TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `messages` ADD COLUMN `isRequest` INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `messages` ADD COLUMN `uploadState` TEXT NOT NULL DEFAULT 'done'")
                database.execSQL("ALTER TABLE `messages` ADD COLUMN `downloadState` TEXT NOT NULL DEFAULT 'done'")
                database.execSQL("ALTER TABLE `messages` ADD COLUMN `uploadProgress` INTEGER NOT NULL DEFAULT 100")
                database.execSQL("ALTER TABLE `messages` ADD COLUMN `downloadProgress` INTEGER NOT NULL DEFAULT 100")
                database.execSQL("ALTER TABLE `messages` ADD COLUMN `localFilePath` TEXT")
                database.execSQL("ALTER TABLE `messages` ADD COLUMN `remoteMediaId` TEXT")
                database.execSQL("ALTER TABLE `messages` ADD COLUMN `mediaDurationMs` INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE `messages` ADD COLUMN `mediaSizeBytes` INTEGER NOT NULL DEFAULT 0")
            }
        }


        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `messages` ADD COLUMN `thumbnailPath` TEXT")
            }
        }

        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `user_keys` (
                        `keyId` TEXT NOT NULL, 
                        `publicKey` TEXT NOT NULL, 
                        `createdAt` INTEGER NOT NULL, 
                        `activatedAt` INTEGER NOT NULL, 
                        `expiresAt` INTEGER NOT NULL, 
                        `status` TEXT NOT NULL, 
                        PRIMARY KEY(`keyId`)
                    )
                """.trimIndent())
                database.execSQL("ALTER TABLE `contacts` ADD COLUMN `publicKey` TEXT")
                database.execSQL("ALTER TABLE `contacts` ADD COLUMN `keyVersion` TEXT")
                database.execSQL("ALTER TABLE `contacts` ADD COLUMN `lastKeyUpdate` INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE `messages` ADD COLUMN `keyFingerprint` TEXT")
            }
        }

        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `user_keys` ADD COLUMN `privateKeyEncrypted` TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE `messages` ADD COLUMN `iv` TEXT")
            }
        }

        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `groups` ADD COLUMN `isAnonymous` INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `group_messages` ADD COLUMN `uploadState` TEXT NOT NULL DEFAULT 'done'")
                database.execSQL("ALTER TABLE `group_messages` ADD COLUMN `downloadState` TEXT NOT NULL DEFAULT 'done'")
                database.execSQL("ALTER TABLE `group_messages` ADD COLUMN `uploadProgress` INTEGER NOT NULL DEFAULT 100")
                database.execSQL("ALTER TABLE `group_messages` ADD COLUMN `downloadProgress` INTEGER NOT NULL DEFAULT 100")
                database.execSQL("ALTER TABLE `group_messages` ADD COLUMN `localFilePath` TEXT")
                database.execSQL("ALTER TABLE `group_messages` ADD COLUMN `remoteMediaId` TEXT")
                database.execSQL("ALTER TABLE `group_messages` ADD COLUMN `mediaDurationMs` INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE `group_messages` ADD COLUMN `mediaSizeBytes` INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `messages` ADD COLUMN `expiresAt` INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE `group_messages` ADD COLUMN `expiresAt` INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `groups` ADD COLUMN `groupExpiresAt` INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `group_messages` ADD COLUMN `thumbnailPath` TEXT")
            }
        }

        private val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `groups` ADD COLUMN `anonymousAliases` TEXT NOT NULL DEFAULT '{}'")
            }
        }

        private val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `messages` ADD COLUMN `replyToText` TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE `group_messages` ADD COLUMN `replyToText` TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `contacts` ADD COLUMN `shortId` TEXT")
            }
        }

        private val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `contacts` ADD COLUMN `isContact` INTEGER NOT NULL DEFAULT 1")
            }
        }

        private val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `messages` ADD COLUMN `deliveredAt` INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE `messages` ADD COLUMN `seenAt` INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `messages` ADD COLUMN `uploadedChunkIndices` TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE `messages` ADD COLUMN `downloadedChunkIndices` TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE `messages` ADD COLUMN `totalChunksExpected` INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE `messages` ADD COLUMN `uploadAttemptCount` INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE `messages` ADD COLUMN `lastUploadAttemptTime` INTEGER NOT NULL DEFAULT 0")
            }
        }


    }
}
