package com.example.myenglishlearningtool.llm

import android.content.Context
import android.util.Log
import com.example.myenglishlearningtool.data.VocabDb
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * DeepSeek 对话 API 客户端（OpenAI 兼容协议，HttpURLConnection 零依赖）。
 * - API Key 存 SharedPreferences（不进代码仓库）
 * - 强制 JSON 输出（response_format=json_object），解析失败返回空列表并由调用方存档原文
 * - 转写文本超长自动截断
 */
object DeepSeekClient {

    private const val TAG = "ELT_LLM"
    private const val API_URL = "https://api.deepseek.com/chat/completions"
    private const val MODEL = "deepseek-chat"
    private const val PREFS = "vocab_settings"
    private const val KEY_API = "deepseek_key"
    private const val MAX_TRANSCRIPT_CHARS = 20000

    /** 一次提问的结果：解析出的词汇 + 原始回答文本 */
    data class Reply(
        val parsed: List<com.example.myenglishlearningtool.data.VocabItem>,
        val rawText: String
    )

    // ---------------- API Key ----------------

    fun getApiKey(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_API, "") ?: ""

    fun saveApiKey(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_API, key.trim()).apply()
    }

    fun hasApiKey(context: Context): Boolean = getApiKey(context).isNotBlank()

    // ---------------- 提问 ----------------

    /**
     * 就一段转写文本向 DeepSeek 提问。
     * @param questionPrompt 自定义问题 prompt（末尾会拼接文本）
     * @param transcript 转写全文（超 2 万字符截断）
     */
    fun ask(
        context: Context,
        questionTitle: String,
        questionPrompt: String,
        transcript: String
    ): Result<Reply> {
        val key = getApiKey(context)
        if (key.isBlank()) {
            return Result.failure(IllegalStateException("未配置 DeepSeek API Key"))
        }
        val text = if (transcript.length > MAX_TRANSCRIPT_CHARS) {
            transcript.substring(0, MAX_TRANSCRIPT_CHARS) + "\n…（文本过长已截断）"
        } else {
            transcript
        }
        val body = JSONObject().apply {
            put("model", MODEL)
            put("temperature", 0.3)
            put("response_format", JSONObject().put("type", "json_object"))
            put(
                "messages", JSONArray().put(
                    JSONObject().put("role", "system")
                        .put("content", "你是专业的英语老师，只输出符合要求的 JSON，不要输出多余文字。")
                ).put(
                    JSONObject().put("role", "user").put("content", questionPrompt + text)
                )
            )
        }

        Log.i(TAG, "LLM: 提问 '$questionTitle' 文本长度=${text.length}")
        val start = System.currentTimeMillis()
        return runCatching {
            val conn = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 120000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $key")
            }
            conn.outputStream.use { os ->
                os.write(body.toString().toByteArray(StandardCharsets.UTF_8))
            }
            val code = conn.responseCode
            val respText = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { ins ->
                BufferedReader(InputStreamReader(ins, StandardCharsets.UTF_8)).use { it.readText() }
            } ?: ""
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code: ${respText.take(300)}")
            }

            // 抽取 choices[0].message.content
            val content = JSONObject(respText)
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
            Log.i(TAG, "LLM: 回答完成 耗时=${System.currentTimeMillis() - start}ms 长度=${content.length}")

            // 剥掉可能的 ```json 包裹
            val clean = content.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()
            val parsed = VocabDb.parseReply("", clean)
            Reply(parsed, content)
        }
    }
}
