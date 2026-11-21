package com.tprlab.teletube

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity
data class ChannelRecord(
    @PrimaryKey val id: Long,
    val title: String,
    val available: Boolean,
    val archived: Boolean,
)

@Entity
data class ScanSettings(@PrimaryKey val key: Int = 0, val allChannels: Boolean)

@Entity
data class SelectedChannel(@PrimaryKey val channelId: Long)

@Entity
data class HiddenChannel(@PrimaryKey val channelId: Long)

@Entity
data class HiddenVideo(@PrimaryKey val videoId: String, val title: String)

@Entity
data class ScanProgress(
    @PrimaryKey val channelId: Long,
    val newest: Long = 0,
    val pendingHead: Long = 0,
    val pendingCursor: Long = 0,
    val initialCutoff: Long? = null,
    val olderCursor: Long = 0,
    val historyComplete: Boolean = false,
    val lastSynced: Long = 0,
)

@Entity(
    primaryKeys = ["channelId", "messageId", "videoId"],
    indices = [Index("postedAt"), Index("channelId", "postedAt")],
)
data class VideoPost(
    val channelId: Long,
    val messageId: Long,
    val videoId: String,
    val postedAt: Long,
    val url: String,
    val title: String,
)

data class FeedEntry(
    val channelId: Long,
    val messageId: Long,
    val videoId: String,
    val postedAt: Long,
    val url: String,
    val title: String,
    val channelTitle: String,
    val viewed: Boolean,
)

@Entity(indices = [Index("videoId"), Index("playedAt")])
data class Playback(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val videoId: String,
    val url: String,
    val title: String,
    val channelTitle: String,
    val playedAt: Long,
)

@Dao
interface LibraryDao {
    @Query("SELECT * FROM ChannelRecord WHERE available = 1 ORDER BY title COLLATE NOCASE, id")
    fun channels(): Flow<List<ChannelRecord>>

    @Query(
        """
        SELECT c.* FROM ChannelRecord c
        JOIN ScanSettings s ON s.`key` = 0
        WHERE (s.allChannels = 1 OR EXISTS (
            SELECT 1 FROM SelectedChannel sc WHERE sc.channelId = c.id
        )) AND (c.available = 1 OR EXISTS (
            SELECT 1 FROM VideoPost p WHERE p.channelId = c.id
        )) AND NOT EXISTS (
            SELECT 1 FROM HiddenChannel h WHERE h.channelId = c.id
        )
        ORDER BY c.title COLLATE NOCASE, c.id
        """,
    )
    fun browseChannels(): Flow<List<ChannelRecord>>

    @Query(
        """
        SELECT c.* FROM ChannelRecord c
        JOIN HiddenChannel h ON h.channelId = c.id
        ORDER BY c.title COLLATE NOCASE, c.id
        """,
    )
    fun hiddenChannels(): Flow<List<ChannelRecord>>

    @Query("INSERT OR IGNORE INTO HiddenChannel (channelId) SELECT id FROM ChannelRecord WHERE id = :channelId")
    suspend fun hideChannel(channelId: Long)

    @Query("DELETE FROM HiddenChannel WHERE channelId = :channelId")
    suspend fun unhideChannel(channelId: Long)

    @Query("SELECT * FROM HiddenVideo ORDER BY title COLLATE NOCASE, videoId")
    fun hiddenVideos(): Flow<List<HiddenVideo>>

    @Query(
        """
        INSERT OR IGNORE INTO HiddenVideo (videoId, title)
        SELECT :videoId, :title WHERE EXISTS (SELECT 1 FROM ScanSettings WHERE `key` = 0)
        """,
    )
    suspend fun hideVideo(videoId: String, title: String)

    @Query("DELETE FROM HiddenVideo WHERE videoId = :videoId")
    suspend fun unhideVideo(videoId: String)

    @Query("SELECT * FROM ScanSettings WHERE `key` = 0")
    fun settings(): Flow<ScanSettings?>

    @Query("SELECT EXISTS (SELECT 1 FROM ScanSettings WHERE `key` = 0)")
    suspend fun hasScanSettings(): Boolean

    @Query("SELECT channelId FROM SelectedChannel")
    fun selected(): Flow<List<Long>>

    @Query("SELECT * FROM ScanProgress")
    fun progress(): Flow<List<ScanProgress>>

    @Query(
        """
        SELECT p.*, c.title AS channelTitle, EXISTS (
            SELECT 1 FROM Playback v WHERE v.videoId = p.videoId
        ) AS viewed
        FROM VideoPost p JOIN ChannelRecord c ON c.id = p.channelId
        JOIN ScanSettings s ON s.`key` = 0
        WHERE (
            s.allChannels = 1 OR EXISTS (
                SELECT 1 FROM SelectedChannel sc WHERE sc.channelId = c.id
            )
        ) AND NOT EXISTS (
            SELECT 1 FROM HiddenChannel h WHERE h.channelId = p.channelId
        ) AND NOT EXISTS (
            SELECT 1 FROM HiddenVideo h WHERE h.videoId = p.videoId
        )
        ORDER BY p.postedAt DESC, p.messageId DESC, p.channelId DESC, p.videoId
        """,
    )
    fun feed(): Flow<List<FeedEntry>>

    @Query("SELECT * FROM Playback ORDER BY playedAt DESC, id DESC")
    fun history(): Flow<List<Playback>>

    @Insert suspend fun savePlayback(playback: Playback)

    @Query("SELECT * FROM ScanProgress WHERE channelId = :channelId")
    suspend fun progressFor(channelId: Long): ScanProgress?

    @Query("SELECT postedAt FROM VideoPost WHERE channelId = :channelId AND messageId = :messageId LIMIT 1")
    suspend fun postDate(channelId: Long, messageId: Long): Long?

    @Upsert suspend fun saveChannel(channel: ChannelRecord)
    @Upsert suspend fun saveSettings(settings: ScanSettings)
    @Upsert suspend fun saveSelection(selection: List<SelectedChannel>)
    @Upsert suspend fun saveProgress(progress: ScanProgress)
    @Upsert suspend fun savePosts(posts: List<VideoPost>)

    @Query("DELETE FROM VideoPost WHERE channelId = :channelId AND messageId = :messageId")
    suspend fun removePost(channelId: Long, messageId: Long)

    @Query("DELETE FROM VideoPost WHERE channelId = :channelId AND messageId IN (:messageIds)")
    suspend fun removePosts(channelId: Long, messageIds: List<Long>)

    @Query("DELETE FROM SelectedChannel")
    suspend fun clearSelection()

    @Query("UPDATE ChannelRecord SET available = 0")
    suspend fun hideAllChannels()
}

@Database(
    entities = [
        ChannelRecord::class, ScanSettings::class, SelectedChannel::class,
        HiddenChannel::class, HiddenVideo::class, ScanProgress::class, VideoPost::class, Playback::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class Library : RoomDatabase() {
    abstract fun dao(): LibraryDao

    companion object {
        private val migration1To2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE HiddenChannel (channelId INTEGER NOT NULL PRIMARY KEY)")
            }
        }

        private val migration2To3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE Playback (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        videoId TEXT NOT NULL,
                        url TEXT NOT NULL,
                        title TEXT NOT NULL,
                        channelTitle TEXT NOT NULL,
                        playedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX index_Playback_videoId ON Playback (videoId)")
                db.execSQL("CREATE INDEX index_Playback_playedAt ON Playback (playedAt)")
            }
        }

        private val migration3To4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE HiddenVideo (videoId TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL)")
            }
        }

        fun open(context: Context, name: String = "teletube.db"): Library =
            Room.databaseBuilder(context, Library::class.java, name)
                .addMigrations(migration1To2, migration2To3, migration3To4)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
