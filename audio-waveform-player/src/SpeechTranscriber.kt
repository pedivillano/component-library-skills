package com.example.myenglishlearningtool.stt

import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File
import kotlin.math.ceil

/**
 * 语音转文字：调用 sherpa-onnx 的 Whisper 模型，完全离线。
 * 按 30 秒窗口分块识别（Whisper 原生窗口），支持进度回调。
 */
object SpeechTranscriber {

    private const val TAG = "ELT_STT"

    /** 词级对齐问题排查专用 tag（logcat 过滤 ELT_SEG） */
    private const val SEG_TAG = "ELT_SEG"

    data class Segment(
        val startSec: Float,
        val endSec: Float = 0f,
        val text: String,
        val words: List<WordSpan> = emptyList()
    )

    /** 词级时间戳：segment 内某个词的起止时刻（用于播放时逐词高亮） */
    data class WordSpan(
        val text: String,
        val startSec: Float,
        val endSec: Float
    )

    /**
     * @param pcm 16kHz 单声道 PCM（[-1, 1] 归一化）
     * @param fineMode 精细模式：识别块从 30s 降到 10s，词级时间插值误差约降 3 倍，
     *   但转写耗时约 3 倍（whisper 编码器固定处理 30s 窗口，块数增加即总耗时增加）
     * @param onProgress (0f..1f)
     */
    fun transcribe(
        spec: ModelManager.ModelSpec,
        modelDir: File,
        pcm: FloatArray,
        sampleRate: Int,
        fineMode: Boolean = false,
        onProgress: (Float) -> Unit
    ): List<Segment> {
        val config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = File(modelDir, spec.encoderFile).absolutePath,
                    decoder = File(modelDir, spec.decoderFile).absolutePath,
                    language = spec.language,
                    task = "transcribe",
                    // 词级对齐：打开 token/段时间戳（默认关闭，不打开则 result.timestamps 为空）
                    enableTokenTimestamps = true,
                    enableSegmentTimestamps = true,
                ),
                tokens = File(modelDir, spec.tokensFile).absolutePath,
                modelType = "whisper",
                numThreads = 2,
                provider = "cpu",
            ),
        )

        val encoderPath = File(modelDir, spec.encoderFile).absolutePath
        val decoderPath = File(modelDir, spec.decoderFile).absolutePath
        val tokensPath = File(modelDir, spec.tokensFile).absolutePath
        Log.i(
            TAG,
            "ASR: 初始化识别器 model=${spec.repo} encoder=${File(encoderPath).length()}B " +
                "decoder=${File(decoderPath).length()}B tokens=${File(tokensPath).length()}B " +
                "language='${spec.language}' pcmSamples=${pcm.size} pcmDuration=${"%.1f".format(pcm.size / sampleRate.toFloat())}s"
        )

        val initStart = System.currentTimeMillis()
        val recognizer = OfflineRecognizer(assetManager = null, config = config)
        Log.i(TAG, "ASR: 识别器初始化完成，耗时 ${System.currentTimeMillis() - initStart}ms")
        try {
            val chunkSec = if (fineMode) 10 else 30
            val chunkSize = chunkSec * sampleRate
            val totalChunks = ceil(pcm.size / chunkSize.toFloat()).toInt().coerceAtLeast(1)
            Log.i(
                TAG,
                "ASR: 共 $totalChunks 个识别块，每块 ${chunkSec}s（${if (fineMode) "精细" else "普通"}模式）"
            )
            val segments = mutableListOf<Segment>()
            val asrStart = System.currentTimeMillis()

            for (i in 0 until totalChunks) {
                val from = i * chunkSize
                val to = minOf(pcm.size, from + chunkSize)
                if (from >= to) break

                val chunk = pcm.copyOfRange(from, to)
                val chunkStart = System.currentTimeMillis()
                val stream = recognizer.createStream()
                stream.acceptWaveform(chunk, sampleRate)
                recognizer.decode(stream)
                val result = recognizer.getResult(stream)
                stream.release()

                val text = result.text.trim()
                Log.i(
                    TAG,
                    "ASR: 块 ${i + 1}/$totalChunks [${from / sampleRate}s-${to / sampleRate}s] " +
                        "耗时=${System.currentTimeMillis() - chunkStart}ms " +
                        "rawText='${result.text.take(120)}'"
                )
                if (text.isNotBlank()) {
                    // 词级对齐：token 时间戳不可用时，按字符量在语音段内插值估算（跳过静音）
                    val chunkStartSec = from.toFloat() / sampleRate
                    val runs = speechRuns(chunk, sampleRate, chunkStartSec)
                    val chunkSegments = splitByTokenTimestamps(result, chunkStartSec, runs)
                    Log.i(
                        SEG_TAG,
                        "块${i + 1}: tokens=${result.tokens.size} ts=${result.timestamps.size} " +
                            "dur=${result.durations.size} → 切成 ${chunkSegments.size} 段"
                    )
                    chunkSegments.take(3).forEach { s ->
                        Log.i(
                            SEG_TAG,
                            "  [${"%.2f".format(s.startSec)}..${"%.2f".format(s.endSec)}] " +
                                "words=${s.words.size} '${s.text.take(40)}'"
                        )
                    }
                    segments += chunkSegments
                }
                onProgress((i + 1) / totalChunks.toFloat())
            }
            Log.i(
                TAG,
                "ASR: 识别结束 共 ${segments.size} 段非空文本 总耗时=${System.currentTimeMillis() - asrStart}ms"
            )
            return segments
        } finally {
            recognizer.release()
        }
    }

    /** 把识别结果格式化为 "[mm:ss] 文本" 的多行文本 */
    fun formatTranscript(segments: List<Segment>): String {
        return segments.joinToString("\n") { seg ->
            "[${formatClock(seg.startSec)}] ${seg.text}"
        }
    }

    /** 句末标点：强制换段 */
    private val sentenceEnders = ".!?。！？…"
    /** 短语边界标点：段长超过阈值时才换段 */
    private val phraseEnders = ",，、;；:："
    /** 段长上限：无任何标点的长句按词边界强切 */
    private const val MAX_SEG_SEC = 8f
    /** 短语标点换段的最小段长 */
    private const val MIN_PHRASE_SEC = 2.5f

    /**
     * 词级对齐切分：sherpa-onnx 的 whisper 结果携带每个 token 的时刻
     * （timestamps）与时长（durations）。两步：
     * 1) token → 词：以空格开头的 token 开新词，词起止 = 首/末 token 时刻±时长；
     * 2) 切段：句末标点强制切；短语标点在段长 > 2.5s 时切；超 8s 按词边界强切。
     * 时间戳相对当前 30s 块起点，加上块起始时间得到绝对时间。
     * 若结果不含时间戳：按 token 字符量在语音段（speechRuns，静音不摊时间）内插值估算。
     */
    private fun splitByTokenTimestamps(
        result: OfflineRecognizerResult,
        chunkStartSec: Float,
        speechRuns: List<Pair<Float, Float>>
    ): List<Segment> {
        val tokens = result.tokens
        if (tokens.isEmpty()) {
            Log.w(SEG_TAG, "块@$chunkStartSec 无 token，跳过")
            return emptyList()
        }
        val timestamps = result.timestamps
        val durations = result.durations
        val n = tokens.size

        // 每 token 的起止时刻：
        // - 有真实时间戳：直接用（最优精度）
        // - 没有：按 token 字符量加权，在块内语音活动窗口中线性插值（估算，相对顺序准确）
        val tStart = FloatArray(n)
        val tEnd = FloatArray(n)
        if (timestamps.size >= n && timestamps.isNotEmpty()) {
            for (i in 0 until n) {
                tStart[i] = chunkStartSec + timestamps[i]
                tEnd[i] = tStart[i] +
                    (if (i < durations.size && durations[i] > 0f) durations[i] else 0f)
            }
            Log.i(SEG_TAG, "块@$chunkStartSec 使用真实 token 时间戳")
        } else {
            val weights = IntArray(n) { maxOf(1, tokens[it].trim().length) }
            val totalW = weights.sum().toFloat()
            val totalSpeech = speechRuns.sumOf { (s, e) -> (e - s).toDouble() }.toFloat()
            val chunkDur = speechRuns.last().second - speechRuns.first().first
            // 语音时间（把所有语音段首尾相接后的偏移）→ 墙钟时间
            fun mapSt(st: Float): Float {
                var rem = st
                for (r in speechRuns) {
                    val d = r.second - r.first
                    if (rem <= d) return r.first + rem
                    rem -= d
                }
                return speechRuns.last().second
            }
            var cum = 0f
            for (i in 0 until n) {
                tStart[i] = mapSt(totalSpeech * cum / totalW)
                cum += weights[i]
                tEnd[i] = mapSt(totalSpeech * cum / totalW)
            }
            Log.i(
                SEG_TAG,
                "块@$chunkStartSec 无真实时间戳，按字符量在 ${speechRuns.size} 个语音段" +
                    "（语音 ${"%.1f".format(totalSpeech)}s / 块长 ${"%.1f".format(chunkDur)}s）内插值估算，静音不摊时间"
            )
        }

        // 1) 组词：以空格开头的 token 开新词
        val words = mutableListOf<WordAcc>()
        for (i in 0 until n) {
            val tok = tokens[i]
            if (i == 0 || tok.startsWith(" ") || words.isEmpty()) {
                words += WordAcc(StringBuilder(), tStart[i], tEnd[i], i)
            }
            val w = words.last()
            w.sb.append(tok.trimStart())
            w.end = maxOf(w.end, tEnd[i])
            w.lastIdx = i
        }

        // 2) 切段
        val out = mutableListOf<Segment>()
        val buf = StringBuilder()
        var segStartIdx = 0
        var segStartSec = tStart[0]
        var prevEndSec = tStart[0]
        var wordCursor = 0

        fun closeSegment(endTokenIdx: Int) {
            val text = buf.toString().trim()
            if (text.isNotBlank()) {
                val segWords = mutableListOf<WordSpan>()
                while (wordCursor < words.size && words[wordCursor].lastIdx <= endTokenIdx) {
                    val w = words[wordCursor++]
                    if (w.sb.isNotBlank()) {
                        segWords += WordSpan(
                            text = w.sb.toString(),
                            startSec = w.start,
                            endSec = maxOf(w.end, w.start + 0.05f)
                        )
                    }
                }
                out += Segment(
                    startSec = segStartSec,
                    endSec = maxOf(prevEndSec, segStartSec + 0.5f),
                    text = text,
                    words = segWords
                )
            }
            buf.clear()
            segStartIdx = endTokenIdx + 1
            segStartSec = if (segStartIdx < n) tStart[segStartIdx] else prevEndSec
        }

        for (i in 0 until n) {
            buf.append(tokens[i])
            prevEndSec = maxOf(prevEndSec, tEnd[i])

            val tok = tokens[i]
            when {
                tok.any { it in sentenceEnders } -> closeSegment(i)
                i == n - 1 -> closeSegment(i)
                tok.any { it in phraseEnders } && prevEndSec - segStartSec > MIN_PHRASE_SEC ->
                    closeSegment(i)
                prevEndSec - segStartSec > MAX_SEG_SEC -> closeSegment(i)
            }
        }
        return out
    }

    /**
     * 检测块内的语音段（绝对时间区间列表）：
     * 100ms 帧能量，超过块内峰值能量 -40dB 视为语音；间隔 ≤ 300ms 的段合并。
     * 无真实时间戳时，token 按字符量在这些语音段内插值（静音不摊时间）。
     */
    private fun speechRuns(
        pcm: FloatArray,
        sampleRate: Int,
        chunkStartSec: Float
    ): List<Pair<Float, Float>> {
        if (pcm.isEmpty()) return listOf(chunkStartSec to chunkStartSec + 1f)
        val frameSize = maxOf(1, sampleRate / 10)
        val frameCount = (pcm.size + frameSize - 1) / frameSize
        val energies = FloatArray(frameCount)
        var maxE = 0f
        for (f in 0 until frameCount) {
            var sum = 0.0
            val st = f * frameSize
            val en = minOf(pcm.size, st + frameSize)
            for (j in st until en) sum += pcm[j] * pcm[j]
            energies[f] = (sum / (en - st)).toFloat()
            if (energies[f] > maxE) maxE = energies[f]
        }
        val threshold = maxE * 1e-4f
        val runs = mutableListOf<Pair<Float, Float>>()
        var curStart = -1
        var curEnd = -1
        fun frameTime(f: Int) = chunkStartSec + f * frameSize / sampleRate.toFloat()
        for (f in 0 until frameCount) {
            if (energies[f] > threshold) {
                if (curStart < 0) {
                    curStart = f
                } else if (curEnd in 0 until f && f - curEnd > 3) {
                    // 静音超过 300ms：收段，另起新段
                    runs += frameTime(curStart) to frameTime(curEnd + 1)
                    curStart = f
                }
                curEnd = f
            }
        }
        if (curStart >= 0 && curEnd >= curStart) {
            runs += frameTime(curStart) to frameTime(curEnd + 1)
        }
        if (runs.isEmpty()) {
            runs += chunkStartSec to chunkStartSec + pcm.size / sampleRate.toFloat()
        }
        return runs
    }

    /** 组词过程中的临时累积器 */
    private class WordAcc(
        val sb: StringBuilder,
        var start: Float,
        var end: Float,
        var lastIdx: Int
    )

    private fun formatClock(sec: Float): String {
        val total = sec.toInt()
        return "%02d:%02d".format(total / 60, total % 60)
    }
}
