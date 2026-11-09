package com.example.myenglishlearningtool.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * 词汇条目：来自 AI 提问（或手动添加），kind 区分词组/单词。
 * 删除只删本表，录音转写文本（TranscriptDb）完全不受影响。
 */
data class VocabItem(
    val id: Long,
    val audioName: String,
    val kind: String,        // "phrase" / "word"
    val item: String,        // 词组或单词本体
    val meaning: String,     // 中文释义
    val example: String,     // 原文例句
    val level: String,       // "basic" / "important" / "advanced"
    val stars: Int,          // 词组 0~5 星；单词恒为 0
    val createdAt: Long
)

/** 一次 AI 提问的原始回答（JSON 解析失败时保底可见） */
data class LlmAnswer(
    val id: Long,
    val audioName: String,
    val questionTitle: String,
    val questionText: String,
    val answerText: String,
    val createdAt: Long
)

/** 自定义问题（预置 3 条默认，可增删改） */
data class CustomQuestion(
    val id: Long,
    val title: String,
    val prompt: String,
    val isDefault: Boolean,
    val enabled: Boolean
)

/**
 * 词汇笔记持久化（独立 SQLite 库 vocab.db）：
 * - vocab_items：词汇条目，唯一索引 (audio_name, kind, item) 自动去重；
 *   总览笔记 = 全表查询汇总，无需冗余表
 * - llm_answers：原始回答存档
 * - custom_questions：自定义问题（首次创建时播种 3 条默认问题）
 */
object VocabDb {

    const val KIND_PHRASE = "phrase"
    const val KIND_WORD = "word"
    const val KIND_EXPRESSION = "expression"

    private const val DB_NAME = "vocab.db"
    private const val T_ITEMS = "vocab_items"
    private const val T_ANSWERS = "llm_answers"
    private const val T_QUESTIONS = "custom_questions"

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
                CREATE TABLE $T_ITEMS (
                    _id INTEGER PRIMARY KEY AUTOINCREMENT,
                    audio_name TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    item TEXT NOT NULL,
                    meaning TEXT NOT NULL DEFAULT '',
                    example TEXT NOT NULL DEFAULT '',
                    level TEXT NOT NULL DEFAULT 'important',
                    stars INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL,
                    UNIQUE(audio_name, kind, item)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX idx_items_name ON $T_ITEMS (audio_name)")
            db.execSQL("CREATE INDEX idx_items_kind ON $T_ITEMS (kind)")

            db.execSQL(
                """
                CREATE TABLE $T_ANSWERS (
                    _id INTEGER PRIMARY KEY AUTOINCREMENT,
                    audio_name TEXT NOT NULL,
                    question_title TEXT NOT NULL DEFAULT '',
                    question_text TEXT NOT NULL DEFAULT '',
                    answer_text TEXT NOT NULL DEFAULT '',
                    created_at INTEGER NOT NULL
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE $T_QUESTIONS (
                    _id INTEGER PRIMARY KEY AUTOINCREMENT,
                    title TEXT NOT NULL,
                    prompt TEXT NOT NULL,
                    is_default INTEGER NOT NULL DEFAULT 0,
                    enabled INTEGER NOT NULL DEFAULT 1
                )
                """.trimIndent()
            )
            // 播种一键生成问题
            db.insert(
                T_QUESTIONS, null,
                ContentValues().apply {
                    put("title", COMBINED_QUESTION.first)
                    put("prompt", COMBINED_QUESTION.second)
                    put("is_default", 1)
                    put("enabled", 1)
                }
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // v1→v2：补种一键生成问题
            if (oldVersion < 2) {
                db.insert(
                    T_QUESTIONS, null,
                    ContentValues().apply {
                        put("title", COMBINED_QUESTION.first)
                        put("prompt", COMBINED_QUESTION.second)
                        put("is_default", 1)
                        put("enabled", 1)
                    }
                )
            }
        }

        companion object {
            private const val DATABASE_VERSION = 2
        }
    }

    /**
     * 一键生成：一次请求同时产出词组 + 单词（重要/高阶）+ 地道表达解析，省 API 费用。
     * 评分规则（AI 直接给出，写入 stars 字段）：
     * - 词组/单词 stars 1~5 = 难度与重要程度综合评价（越高越值得记，1 星 = 无足轻重）
     * - 表达 stars 1~5 = 流行/地道程度，UI 用火焰 🔥 展示
     */
    val COMBINED_QUESTION: Pair<String, String> =
        "AI 一键学习（词组+单词+表达）" to (
            "你是专业英语老师。从下面的英语听力文本中完成四项任务，只输出一个 JSON，不要输出任何其他文字：\n" +
                "1) phrases：10~20 个地道英语词组/习语/固定搭配，stars 为 1~5 整数评分" +
                "（难度与重要程度综合评价，越高越值得记，常见搭配打低分）；\n" +
                "2) words：15~30 个核心单词，level 填 \"important\"，stars 为 1~5 整数评分" +
                "（难度与重要程度综合评价，常见且简单的词打 1 星）；\n" +
                "3) words（与第 2 项放同一个数组）：所有 CEFR C1 及以上的高阶/学术/少见词汇，" +
                "level 填 \"advanced\"，stars 按难度与重要程度打 3~5 星；\n" +
                "4) expressions：5~10 个值得学习的地道英语表达方式，meaning 中解析它为什么地道、适用场景，" +
                "flames 为 1~5 整数表示当下英语中的流行/地道程度，越多越流行。\n" +
                "JSON 格式：\n" +
                "{\"phrases\":[{\"phrase\":\"英文词组\",\"meaning\":\"中文释义\",\"example\":\"原文例句\",\"stars\":4}],\n" +
                "\"words\":[{\"word\":\"单词\",\"meaning\":\"中文释义\",\"level\":\"important 或 advanced\",\"stars\":2}],\n" +
                "\"expressions\":[{\"expression\":\"表达方式\",\"meaning\":\"地道之处与使用场景解析\",\"example\":\"原文例句\",\"flames\":5}]}\n\n" +
                "文本：\n"
            )

    // ---------------- 词汇条目 ----------------

    /** 批量插入（同录音同类同词自动去重；已存在的不覆盖，保护已打星数据） */
    @Synchronized
    fun insertItems(context: Context, audioName: String, items: List<VocabItem>): Int {
        if (items.isEmpty()) return 0
        val db = getHelper(context).writableDatabase
        var inserted = 0
        db.beginTransaction()
        try {
            items.forEach { it0 ->
                val values = ContentValues().apply {
                    put("audio_name", audioName)
                    put("kind", it0.kind)
                    put("item", it0.item)
                    put("meaning", it0.meaning)
                    put("example", it0.example)
                    put("level", it0.level)
                    put("stars", it0.stars)
                    put("created_at", System.currentTimeMillis())
                }
                val rowId = db.insertWithOnConflict(
                    T_ITEMS, null, values, SQLiteDatabase.CONFLICT_IGNORE
                )
                if (rowId != -1L) inserted++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return inserted
    }

    /** 某个录音的词汇（kind 为 null 时返回全部），按类别 + 加入时间排序 */
    @Synchronized
    fun getByAudio(context: Context, audioName: String, kind: String? = null): List<VocabItem> {
        val selection = if (kind == null) "audio_name = ?" else "audio_name = ? AND kind = ?"
        val args = if (kind == null) arrayOf(audioName) else arrayOf(audioName, kind)
        return queryItems(context, selection, args, "kind ASC, _id ASC")
    }

    /** 全部词汇（总览汇总用），kind 为 null 时返回全部 */
    @Synchronized
    fun getAll(context: Context, kind: String? = null): List<VocabItem> {
        return if (kind == null) {
            queryItems(context, null, null, "audio_name ASC, kind ASC, _id ASC")
        } else {
            queryItems(context, "kind = ?", arrayOf(kind), "audio_name ASC, _id ASC")
        }
    }

    /** 打星（1~5；传相同值 = 取消归 0） */
    @Synchronized
    fun setStars(context: Context, id: Long, stars: Int) {
        getHelper(context).writableDatabase.update(
            T_ITEMS,
            ContentValues().apply { put("stars", stars.coerceIn(0, 5)) },
            "_id = ?", arrayOf(id.toString())
        )
    }

    /** 删除单条词汇笔记（不影响录音转写） */
    @Synchronized
    fun deleteItem(context: Context, id: Long) {
        getHelper(context).writableDatabase.delete(T_ITEMS, "_id = ?", arrayOf(id.toString()))
    }

    /** 删除某录音的全部词汇笔记 */
    @Synchronized
    fun deleteByAudio(context: Context, audioName: String) {
        getHelper(context).writableDatabase.delete(T_ITEMS, "audio_name = ?", arrayOf(audioName))
    }

    /** 统计：kind 为 null 时统计全部。返回 Triple(总数, 已标星数, 高阶词数) */
    @Synchronized
    fun stats(context: Context, kind: String? = null): Triple<Int, Int, Int> {
        val db = getHelper(context).readableDatabase
        val where = if (kind == null) "" else "WHERE kind = '$kind'"
        fun count(sql: String): Int =
            db.rawQuery(sql, null).use { c -> c.moveToFirst(); c.getInt(0) }
        val total = count("SELECT COUNT(*) FROM $T_ITEMS $where")
        val starred = count("SELECT COUNT(*) FROM $T_ITEMS $where${if (where.isEmpty()) "WHERE" else " AND"} stars > 0")
        val advanced = count("SELECT COUNT(*) FROM $T_ITEMS $where${if (where.isEmpty()) "WHERE" else " AND"} level = 'advanced'")
        return Triple(total, starred, advanced)
    }

    private fun queryItems(
        context: Context,
        selection: String?,
        args: Array<String>?,
        orderBy: String
    ): List<VocabItem> {
        val db = getHelper(context).readableDatabase
        val out = mutableListOf<VocabItem>()
        db.query(T_ITEMS, null, selection, args, null, null, orderBy).use { c ->
            while (c.moveToNext()) {
                out.add(
                    VocabItem(
                        id = c.getLong(c.getColumnIndexOrThrow("_id")),
                        audioName = c.getString(c.getColumnIndexOrThrow("audio_name")),
                        kind = c.getString(c.getColumnIndexOrThrow("kind")),
                        item = c.getString(c.getColumnIndexOrThrow("item")),
                        meaning = c.getString(c.getColumnIndexOrThrow("meaning")) ?: "",
                        example = c.getString(c.getColumnIndexOrThrow("example")) ?: "",
                        level = c.getString(c.getColumnIndexOrThrow("level")) ?: "",
                        stars = c.getInt(c.getColumnIndexOrThrow("stars")),
                        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"))
                    )
                )
            }
        }
        return out
    }

    // ---------------- 提问回答存档 ----------------

    @Synchronized
    fun saveAnswer(
        context: Context,
        audioName: String,
        questionTitle: String,
        questionText: String,
        answerText: String
    ) {
        getHelper(context).writableDatabase.insert(
            T_ANSWERS, null,
            ContentValues().apply {
                put("audio_name", audioName)
                put("question_title", questionTitle)
                put("question_text", questionText)
                put("answer_text", answerText)
                put("created_at", System.currentTimeMillis())
            }
        )
    }

    @Synchronized
    fun getAnswers(context: Context, audioName: String): List<LlmAnswer> {
        val db = getHelper(context).readableDatabase
        val out = mutableListOf<LlmAnswer>()
        db.query(
            T_ANSWERS, null, "audio_name = ?", arrayOf(audioName),
            null, null, "created_at DESC"
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    LlmAnswer(
                        id = c.getLong(c.getColumnIndexOrThrow("_id")),
                        audioName = c.getString(c.getColumnIndexOrThrow("audio_name")),
                        questionTitle = c.getString(c.getColumnIndexOrThrow("question_title")) ?: "",
                        questionText = c.getString(c.getColumnIndexOrThrow("question_text")) ?: "",
                        answerText = c.getString(c.getColumnIndexOrThrow("answer_text")) ?: "",
                        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"))
                    )
                )
            }
        }
        return out
    }

    /** 所有未评分（stars=0）的条目：老数据一键补分用 */
    @Synchronized
    fun getUnscored(context: Context): List<VocabItem> =
        queryItems(context, "stars = 0", null, "kind ASC, _id ASC")

    // ---------------- 自定义问题 ----------------

    @Synchronized
    fun getQuestions(context: Context): List<CustomQuestion> {
        val db = getHelper(context).readableDatabase
        val out = mutableListOf<CustomQuestion>()
        db.query(
            T_QUESTIONS, null, "enabled = 1", null,
            null, null, "is_default DESC, _id ASC"
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    CustomQuestion(
                        id = c.getLong(c.getColumnIndexOrThrow("_id")),
                        title = c.getString(c.getColumnIndexOrThrow("title")),
                        prompt = c.getString(c.getColumnIndexOrThrow("prompt")),
                        isDefault = c.getInt(c.getColumnIndexOrThrow("is_default")) == 1,
                        enabled = c.getInt(c.getColumnIndexOrThrow("enabled")) == 1
                    )
                )
            }
        }
        return out
    }

    @Synchronized
    fun addQuestion(context: Context, title: String, prompt: String): Long {
        return getHelper(context).writableDatabase.insert(
            T_QUESTIONS, null,
            ContentValues().apply {
                put("title", title)
                put("prompt", prompt)
                put("is_default", 0)
                put("enabled", 1)
            }
        )
    }

    @Synchronized
    fun deleteQuestion(context: Context, id: Long) {
        getHelper(context).writableDatabase
            .delete(T_QUESTIONS, "_id = ? AND is_default = 0", arrayOf(id.toString()))
    }

    // ---------------- AI 回复 JSON 解析 ----------------

    /** 解析批量打分回复 {"scores":[{"kind":"...","item":"...","stars":1-5}]} → 键为 "kind|item"（小写） */
    fun parseScores(json: String): Map<String, Int> {
        val out = mutableMapOf<String, Int>()
        runCatching {
            val clean = json.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()
            val arr = JSONObject(clean).getJSONArray("scores")
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                val kind = e.optString("kind", "").trim().lowercase()
                val item = e.optString("item", "").trim().lowercase()
                val stars = e.optInt("stars", 0)
                if (item.isEmpty() || stars !in 1..5) continue
                if (kind.isNotEmpty()) out["$kind|$item"] = stars else out[item] = stars
            }
        }
        return out
    }

    /** 解析 DeepSeek 回复 JSON → 词汇条目列表（phrase + word 两种数组，兼容只有其一） */
    fun parseReply(audioName: String, json: String): List<VocabItem> {
        val out = mutableListOf<VocabItem>()
        runCatching {
            val o = JSONObject(json)
            if (o.has("phrases")) {
                val arr = o.getJSONArray("phrases")
                for (i in 0 until arr.length()) {
                    val p = arr.getJSONObject(i)
                    val phrase = p.optString("phrase").trim()
                    if (phrase.isEmpty()) continue
                    out.add(
                        VocabItem(
                            id = 0, audioName = audioName, kind = KIND_PHRASE,
                            item = phrase,
                            meaning = p.optString("meaning").trim(),
                            example = p.optString("example").trim(),
                            level = "important",
                            stars = p.optInt("stars", 3).coerceIn(1, 5),
                            createdAt = 0
                        )
                    )
                }
            }
            if (o.has("words")) {
                val arr = o.getJSONArray("words")
                for (i in 0 until arr.length()) {
                    val w = arr.getJSONObject(i)
                    val word = w.optString("word").trim()
                    if (word.isEmpty()) continue
                    out.add(
                        VocabItem(
                            id = 0, audioName = audioName, kind = KIND_WORD,
                            item = word,
                            meaning = w.optString("meaning").trim(),
                            example = "",
                            level = w.optString("level", "important").ifBlank { "important" },
                            stars = w.optInt("stars", 0).coerceIn(0, 5),
                            createdAt = 0
                        )
                    )
                }
            }
            if (o.has("expressions")) {
                val arr = o.getJSONArray("expressions")
                for (i in 0 until arr.length()) {
                    val e = arr.getJSONObject(i)
                    val expr = e.optString("expression").trim()
                    if (expr.isEmpty()) continue
                    // 表达的流行度（flames 1~5）复用 stars 字段存储，UI 用火焰展示
                    out.add(
                        VocabItem(
                            id = 0, audioName = audioName, kind = KIND_EXPRESSION,
                            item = expr,
                            meaning = e.optString("meaning").trim(),
                            example = e.optString("example").trim(),
                            level = "important",
                            stars = e.optInt("flames", 3).coerceIn(1, 5),
                            createdAt = 0
                        )
                    )
                }
            }
        }
        return out
    }
}
