package com.example.myenglishlearningtool.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.myenglishlearningtool.stt.SpeechTranscriber
import org.json.JSONArray
import org.json.JSONObject

/**
 * 转写记录数据模型
 */
data class TranscriptRecord(
    val id: Long,
    val audioName: String,
    val audioUri: String,
    val transcript: String,
    val segmentCount: Int,
    val modelName: String,
    val segmentsJson: String,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * 语音转文字结果持久化：用系统自带 SQLite 存储（无需额外依赖），
 * 以音频文件名为主键关联，重新识别时覆盖更新。
 */
object TranscriptDb {

    private const val DB_NAME = "transcripts.db"
    private const val TABLE = "transcripts"

    private var helper: Helper? = null

    @Synchronized
    private fun getHelper(context: Context): Helper {
        if (helper == null) helper = Helper(context.applicationContext)
        return helper!!
    }

    private class Helper(context: Context) :
        SQLiteOpenHelper(context, DB_NAME, null, DATABASE_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE (
                    _id INTEGER PRIMARY KEY AUTOINCREMENT,
                    audio_name TEXT NOT NULL UNIQUE,
                    audio_uri TEXT NOT NULL,
                    transcript TEXT NOT NULL,
                    segment_count INTEGER NOT NULL DEFAULT 0,
                    model_name TEXT NOT NULL DEFAULT '',
                    segments_json TEXT NOT NULL DEFAULT '',
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX idx_${TABLE}_name ON $TABLE (audio_name)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE $TABLE ADD COLUMN segments_json TEXT NOT NULL DEFAULT ''")
            }
        }

        companion object {
            private const val DATABASE_VERSION = 2
        }
    }

    /** 保存/覆盖一条转写记录（按音频文件名 upsert），返回记录 id */
    @Synchronized
    fun save(
        context: Context,
        audioName: String,
        audioUri: String,
        transcript: String,
        segmentCount: Int,
        modelName: String,
        segmentsJson: String = ""
    ): Long {
        val db = getHelper(context).writableDatabase
        val now = System.currentTimeMillis()
        val existing = findByName(context, audioName)
        val values = ContentValues().apply {
            put("audio_name", audioName)
            put("audio_uri", audioUri)
            put("transcript", transcript)
            put("segment_count", segmentCount)
            put("model_name", modelName)
            put("segments_json", segmentsJson)
            put("updated_at", now)
        }
        return if (existing != null) {
            db.update(TABLE, values, "audio_name = ?", arrayOf(audioName)).toLong()
            existing.id
        } else {
            values.put("created_at", now)
            db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    /** 按音频文件名查找转写记录 */
    @Synchronized
    fun findByName(context: Context, audioName: String): TranscriptRecord? =
        query(context, "audio_name = ?", arrayOf(audioName)).firstOrNull()

    /** 全部转写记录，按更新时间倒序 */
    @Synchronized
    fun getAll(context: Context): List<TranscriptRecord> =
        query(context, null, null, orderBy = "updated_at DESC")

    /** 删除一条转写记录 */
    @Synchronized
    fun delete(context: Context, audioName: String) {
        getHelper(context).writableDatabase
            .delete(TABLE, "audio_name = ?", arrayOf(audioName))
    }

    /** 把分段列表序列化为 JSON（e = 结束时间；w = 词级时间数组 [词, 起, 止]；旧数据可缺省） */
    fun segmentsToJson(segments: List<SpeechTranscriber.Segment>): String {
        if (segments.isEmpty()) return ""
        return JSONArray().apply {
            segments.forEach { seg ->
                val o = JSONObject()
                    .put("s", seg.startSec.toDouble())
                    .put("e", seg.endSec.toDouble())
                    .put("t", seg.text)
                if (seg.words.isNotEmpty()) {
                    o.put("w", JSONArray().apply {
                        seg.words.forEach { w ->
                            put(JSONArray().put(w.text).put(w.startSec.toDouble()).put(w.endSec.toDouble()))
                        }
                    })
                }
                put(o)
            }
        }.toString()
    }

    /** 解析分段 JSON（兼容旧记录：无 e/w 字段时对应字段缺省，空串或解析失败返回空列表） */
    fun parseSegments(json: String): List<SpeechTranscriber.Segment> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val words = mutableListOf<SpeechTranscriber.WordSpan>()
                if (o.has("w")) {
                    val wa = o.getJSONArray("w")
                    for (j in 0 until wa.length()) {
                        val w = wa.getJSONArray(j)
                        words += SpeechTranscriber.WordSpan(
                            text = w.getString(0),
                            startSec = w.getDouble(1).toFloat(),
                            endSec = w.getDouble(2).toFloat()
                        )
                    }
                }
                SpeechTranscriber.Segment(
                    startSec = o.getDouble("s").toFloat(),
                    endSec = if (o.has("e")) o.getDouble("e").toFloat() else 0f,
                    text = o.getString("t"),
                    words = words
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun query(
        context: Context,
        selection: String?,
        args: Array<String>?,
        orderBy: String = "audio_name ASC"
    ): List<TranscriptRecord> {
        val db = getHelper(context).readableDatabase
        val out = mutableListOf<TranscriptRecord>()
        db.query(
            TABLE, null, selection, args, null, null, orderBy
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out.add(
                    TranscriptRecord(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow("_id")),
                        audioName = cursor.getString(cursor.getColumnIndexOrThrow("audio_name")),
                        audioUri = cursor.getString(cursor.getColumnIndexOrThrow("audio_uri")),
                        transcript = cursor.getString(cursor.getColumnIndexOrThrow("transcript")),
                        segmentCount = cursor.getInt(cursor.getColumnIndexOrThrow("segment_count")),
                        modelName = cursor.getString(cursor.getColumnIndexOrThrow("model_name")),
                        segmentsJson = cursor.getString(cursor.getColumnIndexOrThrow("segments_json")) ?: "",
                        createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                        updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at"))
                    )
                )
            }
        }
        return out
    }
}
