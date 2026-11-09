package com.example.myenglishlearningtool

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import com.example.myenglishlearningtool.audio.AudioExtractor
import com.example.myenglishlearningtool.data.TranscriptDb
import com.example.myenglishlearningtool.data.TranscriptRecord
import com.example.myenglishlearningtool.stt.SpeechTranscriber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToLong

private const val PLAYER_TAG = "ELT_PLAYER"

/** 波形视窗：一屏显示的时间跨度（秒）。屏幕右缘 = 当前窗口结束，不是音频结束 */
private const val WAVE_WINDOW_SEC = 60f

/** 播放头从左向右移动，越过窗口此比例后波形开始滚动（0 = 紧贴左缘） */
private const val WAVE_ANCHOR = 0.3f

/**
 * 波形数据：整条音频的响度归一化到 0..1
 */
class WaveformData(val bars: FloatArray, val durationSec: Float)

/**
 * 波形提取：
 * - 不做重采样、不生成大数组，边解码边累加每根条的峰值，实时丢弃样本
 * - 使用音频原生采样率/声道，速度约为时长的 5%~20%（即 10 分钟音频 30s~2min 内完成，
 *   上一版复用 16kHz 重采样管道要慢一个数量级）
 * - 结果写入磁盘缓存（应用 filesDir/waveform 目录下的 .bin 文件），每条音频一生只解码一次
 */
object WaveformCache {
    private val cache = mutableMapOf<String, WaveformData>()

    @Synchronized
    fun getOrCompute(context: Context, item: AudioExtractor.AudioFileItem): WaveformData {
        cache[item.name]?.let { return it }

        val cacheFile = diskFile(context, item.name)
        if (cacheFile.exists()) {
            runCatching {
                DataInputStream(BufferedInputStream(cacheFile.inputStream())).use { input ->
                    val duration = input.readFloat()
                    val n = input.readInt()
                    val bars = FloatArray(n) { input.readFloat() }
                    val data = WaveformData(bars, duration)
                    cache[item.name] = data
                    return data
                }
            }.onFailure { cacheFile.delete() }
        }

        val data = WaveformExtractor.compute(context, item.uri)
        if (data.bars.isNotEmpty() && data.durationSec > 0f) {
            runCatching {
                cacheFile.parentFile?.mkdirs()
                DataOutputStream(BufferedOutputStream(cacheFile.outputStream())).use { out ->
                    out.writeFloat(data.durationSec)
                    out.writeInt(data.bars.size)
                    data.bars.forEach { out.writeFloat(it) }
                }
            }
        }
        cache[item.name] = data
        return data
    }

    private fun diskFile(context: Context, name: String): File =
        File(File(context.filesDir, "waveform"), name.substringBeforeLast('.') + ".bin")
}

/**
 * 快速波形提取：MediaCodec 解码原生 PCM，每 SAMPLES_PER_BAR 个采样合并为一根条的峰值。
 */
object WaveformExtractor {

    private const val SAMPLES_PER_BAR = 4096
    private const val TIMEOUT_US = 10_000L

    fun compute(context: Context, uri: Uri): WaveformData {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)

            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) {
                throw IllegalStateException("该文件中未找到音频轨")
            }
            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = runCatching { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                .getOrDefault(1)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val bars = ArrayList<Float>(1024)
            var acc = 0f
            var count = 0L
            var totalFrames = 0L
            var sawInputEOS = false
            var sawOutputEOS = false
            val info = MediaCodec.BufferInfo()

            while (!sawOutputEOS) {
                if (!sawInputEOS) {
                    val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val inputBuffer = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(inputBuffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            sawInputEOS = true
                        } else {
                            codec.queueInputBuffer(
                                inIdx, 0, size, extractor.sampleTime, 0
                            )
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIdx >= 0 -> {
                        if (info.size > 0) {
                            val outputBuffer = codec.getOutputBuffer(outIdx)!!
                            outputBuffer.position(info.offset)
                            outputBuffer.limit(info.offset + info.size)
                            val shorts = outputBuffer.order(
                                java.nio.ByteOrder.LITTLE_ENDIAN
                            ).asShortBuffer()
                            val n = shorts.remaining()
                            for (i in 0 until n) {
                                val a = abs(shorts.get(i).toFloat()) / 32768f
                                if (a > acc) acc = a
                                count++
                                if (count >= SAMPLES_PER_BAR) {
                                    bars.add(acc)
                                    acc = 0f
                                    count = 0
                                }
                            }
                            totalFrames += n / channels
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            sawOutputEOS = true
                        }
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = codec.outputFormat
                        sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = runCatching {
                            newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }.getOrDefault(channels)
                    }
                    // INFO_TRY_AGAIN_LATER：继续循环
                }
            }
            if (count > 0) bars.add(acc)

            val durationSec = if (totalFrames > 0) totalFrames / sampleRate.toFloat() else 0f
            var peak = 0f
            for (b in bars) if (b > peak) peak = b
            val out = FloatArray(bars.size) { i ->
                if (peak > 0f) bars[i] / peak else 0f
            }
            return WaveformData(out, durationSec)
        } finally {
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }
}

/**
 * 播放页：波形（红色播放头固定居中、已播放亮/未播放暗）、当前分段字幕、底部播放条。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(item: AudioExtractor.AudioFileItem, onBack: () -> Unit) {
    val context = LocalContext.current

    // 拦截系统返回手势/按键：回应用内主页，而不是退出应用
    BackHandler { onBack() }

    var wave by remember { mutableStateOf<WaveformData?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var record by remember { mutableStateOf<TranscriptRecord?>(null) }
    val segments = remember(record) { TranscriptDb.parseSegments(record?.segmentsJson ?: "") }

    var prepared by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var durationMs by remember { mutableStateOf(0) }
    var positionMs by remember { mutableStateOf(0) }
    // 拖动红线（播放头）中的临时位置（null = 不在拖动）
    var lineDragMs by remember { mutableStateOf<Float?>(null) }
    // 拖动红线期间冻结的波形视窗起点（null = 不在拖动红线）
    var lineDragWindowStart by remember { mutableStateOf<Float?>(null) }
    // 手动平移后的波形视窗起点（null = 视窗自动跟随播放头）
    // 底部滑块拖动时也会写这个状态：滑块 = 波形视窗的滚动条
    var viewStartSec by remember { mutableStateOf<Float?>(null) }
    // 视窗滚动（拖波形/拖滑块）期间，红线固定的屏幕位置比例（0=最左, 1=最右）
    var scrollLineFrac by remember { mutableStateOf<Float?>(null) }
    var playError by remember { mutableStateOf<String?>(null) }

    val displayMs = lineDragMs ?: positionMs.toFloat()
    val displayDurationMs = durationMs.takeIf { it > 0 }
        ?: wave?.durationSec?.times(1000)?.toInt()
        ?: 0

    // 波形视窗起点：拖红线时冻结 > 手动平移/拖滑块 > 自动跟随播放头
    val followStartSec = max(0f, displayMs / 1000f - WAVE_ANCHOR * WAVE_WINDOW_SEC)
    val resolvedViewStart = lineDragWindowStart ?: viewStartSec ?: followStartSec

    // 视窗可滚动范围；音频比一屏短时滑块退化为普通进度条（拖动 = seek）
    val maxScrollSec = max(0f, displayDurationMs / 1000f - WAVE_WINDOW_SEC)
    val sliderFraction = if (maxScrollSec > 0f) {
        (resolvedViewStart / maxScrollSec).coerceIn(0f, 1f)
    } else {
        (displayMs / 1000f / max(1f, displayDurationMs / 1000f)).coerceIn(0f, 1f)
    }

    // 红线当前在屏幕上的位置比例（0=最左, 1=最右），滚动视窗时保持不变
    val lineScreenFrac =
        ((displayMs / 1000f - resolvedViewStart) / WAVE_WINDOW_SEC).coerceIn(0f, 1f)

    // 加载波形 + 转写记录
    LaunchedEffect(item.uri) {
        record = withContext(Dispatchers.IO) { TranscriptDb.findByName(context, item.name) }
        try {
            wave = withContext(Dispatchers.IO) { WaveformCache.getOrCompute(context, item) }
        } catch (e: Exception) {
            Log.e(PLAYER_TAG, "PLAYER: 波形生成失败", e)
            loadError = e.message ?: "波形加载失败"
        }
    }

    // MediaPlayer 生命周期
    val player = remember { MediaPlayer() }
    DisposableEffect(item.uri) {
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            player.setDataSource(context, item.uri)
            player.setOnPreparedListener { mp ->
                durationMs = mp.duration
                prepared = true
            }
            player.setOnCompletionListener { isPlaying = false }
            player.prepareAsync()
        } catch (e: Exception) {
            Log.e(PLAYER_TAG, "PLAYER: 初始化失败", e)
            playError = e.message ?: "无法播放该音频"
        }
        onDispose {
            runCatching { player.release() }
        }
    }

    // 播放进度：每帧用「锚点 + 经过时间」插值，播放头 60fps 平滑移动；
    // 每 200ms 才向 MediaPlayer 查询一次真实位置校准
    var anchorMs by remember { mutableStateOf(0) }
    var anchorNanos by remember { mutableStateOf(0L) }

    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        var lastPoll = 0L
        while (true) {
            withFrameNanos { now ->
                if (now - lastPoll >= 200_000_000L) {
                    runCatching { anchorMs = player.currentPosition }
                    anchorNanos = now
                    lastPoll = now
                }
                val scrubbing = lineDragMs != null
                if (!scrubbing) {
                    positionMs = anchorMs + ((now - anchorNanos) / 1_000_000L).toInt()
                }
            }
        }
    }

    fun seekTo(ms: Float) {
        lineDragMs = null
        lineDragWindowStart = null
        // 注意：不重置视窗。滑块/视窗只由滑块拖动、波形平移和「点播放」控制
        positionMs = ms.toInt().coerceIn(0, displayDurationMs)
        // 重置插值锚点，否则播放中 seek 后会被旧锚点拽回去（最多 200ms）
        anchorMs = positionMs
        anchorNanos = System.nanoTime()
        runCatching { player.seekTo(ms.roundToLong().toInt()) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ---------------- 顶栏：标准 TopAppBar ----------------
        // 自带状态栏避让（内容不再顶到状态栏里）、64dp 标准高度、
        // 48dp+ 导航按钮触控目标，标题单行省略
        TopAppBar(
            title = {
                Text(
                    text = item.name.substringBeforeLast('.'),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            navigationIcon = {
                // 48dp 标准触控目标，图标放大到 28dp 并着主题色，视觉更醒目
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.Filled.Home,
                        contentDescription = "返回主页",
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )

        // ---------------- 交互主区（波形 + 字幕 + 播放条） ----------------
        // 连片屏蔽左右边缘的系统返回手势：拖动跨界不会突然触发返回，
        // 返回功能由左上角主页按钮承担（Android 10+ 生效，系统每侧最多 200dp）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .systemGestureExclusion()
        ) {
        // ---------------- 波形区域 ----------------
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .padding(horizontal = 12.dp)
        ) {
            val w = wave
            if (w == null) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    if (loadError != null) {
                        Text("波形加载失败：$loadError", style = MaterialTheme.typography.bodySmall)
                    } else {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(8.dp))
                        Text("正在生成波形…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                WaveformView(
                    wave = w,
                    posSec = displayMs / 1000f,
                    viewStartSec = resolvedViewStart,
                    // 拖波形：平移视窗，红线钉在屏幕原位（时间随内容滚动）
                    onPanStart = {
                        scrollLineFrac = lineScreenFrac
                    },
                    onViewPan = { deltaSec ->
                        val base = viewStartSec ?: followStartSec
                        val newView = (base + deltaSec)
                            .coerceIn(0f, max(0f, w.durationSec - WAVE_WINDOW_SEC))
                        viewStartSec = newView
                        // 红线屏幕位置不变 → 其对应时间 = 新视窗起点 + 固定比例 × 窗口
                        scrollLineFrac?.let { f ->
                            lineDragMs = ((newView + f * WAVE_WINDOW_SEC) * 1000f)
                                .coerceIn(0f, displayDurationMs.toFloat())
                        }
                    },
                    onPanEnd = {
                        scrollLineFrac = null
                        lineDragMs?.let { seekTo(it) }
                    },
                    // 拖红线：冻结视窗，红线跟手
                    onLineDragStart = {
                        lineDragWindowStart = viewStartSec ?: followStartSec
                        lineDragMs = positionMs.toFloat()
                    },
                    onLineDrag = { sec ->
                        lineDragMs = (sec * 1000f)
                            .coerceIn(0f, displayDurationMs.toFloat())
                    },
                    onLineDragEnd = {
                        // 必须先取出冻结值再 seekTo（seekTo 会清空 lineDragWindowStart）
                        val frozenStart = lineDragWindowStart
                        lineDragMs?.let { seekTo(it) }
                        // 拖完红线：视窗停在拖动期间冻结的位置（滑块不动），
                        // 只有点「播放」才会跳回红线处重新跟随
                        viewStartSec = frozenStart
                        lineDragWindowStart = null
                    },
                    onTapTime = { sec ->
                        seekTo(sec * 1000f)
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // 视窗被手动平移后出现，点击恢复跟随播放头
                if (viewStartSec != null) {
                    TextButton(
                        onClick = { viewStartSec = null },
                        modifier = Modifier.align(Alignment.TopEnd)
                    ) {
                        Text("回到播放位置", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        // ---------------- 字幕区域 ----------------
        TranscriptPane(
            segments = segments,
            posSec = displayMs / 1000f,
            audioName = item.name,
            transcriptText = record?.transcript ?: "",
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )

        // ---------------- 底部播放条 ----------------
        // 滑块 = 波形视窗滚动条：拖到最右 = 音轨末端对齐屏幕右缘；
        // 点播放时视窗跳回红线位置跟随播放
        BottomPlayerBar(
            sliderFraction = sliderFraction,
            bubbleText = formatClock(sliderFraction * displayDurationMs / 1000f),
            currentMs = displayMs.toInt(),
            totalMs = displayDurationMs,
            isPlaying = isPlaying,
            enabled = prepared && playError == null,
            onPlayPause = {
                runCatching {
                    if (isPlaying) {
                        player.pause()
                        isPlaying = false
                    } else {
                        // 播放时视窗跳回红线处，滑块随之跳到红线对应进度
                        viewStartSec = null
                        player.start()
                        isPlaying = true
                    }
                }.onFailure {
                    Log.e(PLAYER_TAG, "PLAYER: 播放控制失败", it)
                    playError = it.message
                }
            },
            onSliderScrubStart = {
                scrollLineFrac = lineScreenFrac
            },
            onSliderScrub = { fraction ->
                if (maxScrollSec > 0f) {
                    // 滑块滚动波形视窗，红线钉在屏幕原位（时间随内容变化）
                    val newView = fraction * maxScrollSec
                    viewStartSec = newView
                    scrollLineFrac?.let { f ->
                        lineDragMs = ((newView + f * WAVE_WINDOW_SEC) * 1000f)
                            .coerceIn(0f, displayDurationMs.toFloat())
                    }
                } else {
                    // 音频短于一屏：滑块退化为 seek
                    lineDragMs = fraction * displayDurationMs
                }
            },
            onSliderScrubEnd = {
                scrollLineFrac = null
                lineDragMs?.let { seekTo(it) }
            },
            error = playError,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        )
        } // 交互主区结束（边缘返回手势屏蔽到此为止）
    }
}

/**
 * 波形（时间窗 + 双手势独立）：
 * - 画布拖动 = 平移视窗（浏览时间轴），完全不改变播放位置
 * - 红线手柄拖动 = 移动播放头（seek），拖动期间视窗冻结、红线跟手
 * - 画布点击 = 播放头跳到该处时间
 * - 红线左亮右暗，随播放从左向右移动，越过窗口锚点后波形平滑滚动
 */
@Composable
fun WaveformView(
    wave: WaveformData,
    posSec: Float,
    viewStartSec: Float,
    onPanStart: () -> Unit,
    onViewPan: (Float) -> Unit,
    onPanEnd: () -> Unit,
    onLineDragStart: () -> Unit,
    onLineDrag: (Float) -> Unit,
    onLineDragEnd: () -> Unit,
    onTapTime: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val playedColor = MaterialTheme.colorScheme.primary
    val restColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    val playheadColor = Color(0xFFE53935)

    // pointerInput(Unit) 只创建一次，用 updatedState 读取最新值
    val currentPosSec by rememberUpdatedState(posSec)
    val currentViewStart by rememberUpdatedState(viewStartSec)
    // 回调同样包装，确保手势触发时拿到的是最新闭包
    val tapState by rememberUpdatedState(onTapTime)
    val panStartState by rememberUpdatedState(onPanStart)
    val panState by rememberUpdatedState(onViewPan)
    val panEndState by rememberUpdatedState(onPanEnd)
    val lineDragStartState by rememberUpdatedState(onLineDragStart)
    val lineDragState by rememberUpdatedState(onLineDrag)
    val lineDragEndState by rememberUpdatedState(onLineDragEnd)

    val grabHalf = 22.dp

    BoxWithConstraints(modifier) {
        val canvasWidthPx = constraints.maxWidth.toFloat()
        val grabHalfPx = with(LocalDensity.current) { grabHalf.toPx() }

        // ---------- 波形画布：平移视窗 + 点击跳转 ----------
        Canvas(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        // 红线抓取区内的点击交给手柄，不触发跳转
                        val lineX = (
                            (currentPosSec - currentViewStart) / WAVE_WINDOW_SEC
                            ).coerceIn(0f, 1f) * size.width
                        if (abs(offset.x - lineX) <= grabHalfPx) return@detectTapGestures
                        if (wave.durationSec > 0f) {
                            // 必须用 currentViewStart（实时值），
                            // 参数 viewStartSec 是 pointerInput 首帧捕获的旧值
                            tapState(
                                (currentViewStart + offset.x / size.width * WAVE_WINDOW_SEC)
                                    .coerceIn(0f, wave.durationSec)
                            )
                        }
                    }
                }
                .pointerInput(Unit) {
                    // 起手点落在红线抓取区内的手势让给红线手柄处理，
                    // 避免两个拖拽检测器抢事件导致红线反复横跳
                    var gestureOnHandle = false
                    detectHorizontalDragGestures(
                        onDragStart = { down ->
                            val lineX = (
                                (currentPosSec - currentViewStart) / WAVE_WINDOW_SEC
                                ).coerceIn(0f, 1f) * size.width
                            gestureOnHandle = abs(down.x - lineX) <= grabHalfPx
                            if (!gestureOnHandle) panStartState()
                        },
                        onDragEnd = { panEndState() },
                        onDragCancel = { panEndState() }
                    ) { change, dragAmount ->
                        if (gestureOnHandle) return@detectHorizontalDragGestures
                        change.consume()
                        // 内容左移 = 时间前进
                        panState(-dragAmount / size.width * WAVE_WINDOW_SEC)
                    }
                }
        ) {
            if (wave.durationSec <= 0f) return@Canvas

            val spacingPx = 6.dp.toPx()
            val barWidthPx = 4.dp.toPx()
            val cornerPx = 2.dp.toPx()
            val amplitudeRange = size.height * 0.84f
            val minBar = 3.dp.toPx()

            // 时间轴网格对齐：每根条固定属于全局时间轴上的一个区间，
            // x 由「条时间 - 视窗起点」换算 → 平移时波形亚像素级连续滑动，
            // 高度跟随时间区间固定不变（不再是在屏幕网格上原地变形）
            val secPerPx = WAVE_WINDOW_SEC / size.width
            val barTimeSpan = spacingPx * secPerPx
            val barsTotal = wave.bars.size
            val srcBarSec = wave.durationSec / barsTotal

            val firstK = floor(viewStartSec / barTimeSpan).toInt()
            val lastK = ceil((viewStartSec + WAVE_WINDOW_SEC) / barTimeSpan).toInt()

            for (k in firstK until lastK) {
                val tStart = k * barTimeSpan
                val tCenter = tStart + barTimeSpan / 2f
                if (tCenter < 0f || tCenter > wave.durationSec) continue
                val x = (tStart - viewStartSec) * size.width / WAVE_WINDOW_SEC
                if (x > size.width || x + spacingPx < 0f) continue
                // 该时间区间覆盖的源波形条，取峰值
                val from = (tStart / srcBarSec).toInt().coerceIn(0, barsTotal - 1)
                val to = (((tStart + barTimeSpan) / srcBarSec).toInt())
                    .coerceAtLeast(from + 1)
                    .coerceAtMost(barsTotal)
                var peak = 0f
                for (j in from until to) {
                    if (wave.bars[j] > peak) peak = wave.bars[j]
                }
                val amp = peak.pow(0.6f)
                val barHeight = (amp * amplitudeRange).coerceAtLeast(minBar)
                val bx = x + (spacingPx - barWidthPx) / 2f
                val y0 = (size.height - barHeight) / 2f
                drawRoundRect(
                    color = if (tCenter <= posSec) playedColor else restColor,
                    topLeft = Offset(bx, y0),
                    size = Size(barWidthPx, barHeight),
                    cornerRadius = CornerRadius(cornerPx)
                )
            }

            // 红色播放头
            val playheadX = ((posSec - viewStartSec) / WAVE_WINDOW_SEC * size.width)
                .coerceIn(0f, size.width)
            drawLine(
                color = playheadColor,
                start = Offset(playheadX, 0f),
                end = Offset(playheadX, size.height),
                strokeWidth = 3.dp.toPx()
            )
        }

        // ---------- 红线拖动手柄：覆盖红线附近一条窄区域 ----------
        val lineFraction = if (wave.durationSec > 0f) {
            ((posSec - viewStartSec) / WAVE_WINDOW_SEC).coerceIn(0f, 1f)
        } else 0f
        val currentLineFraction by rememberUpdatedState(lineFraction)

        // 关键：拖动期间手柄冻结在抓取时的位置。
        // 若手柄跟着红线移动，手指的局部坐标会被手柄自身位移精确抵消，
        // 红线永远不会跟手（这正是之前「跳一下又弹回」的原因）。
        // 手柄本身是隐形的，冻结不影响任何视觉。
        var frozenFraction by remember { mutableStateOf<Float?>(null) }

        Box(
            modifier = Modifier
                .offset(x = maxWidth * (frozenFraction ?: lineFraction) - grabHalf)
                .width(grabHalf * 2)
                .fillMaxHeight()
                .pointerInput(Unit) {
                    var grabTime = 0f
                    var pressX = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { local ->
                            pressX = local.x
                            grabTime = currentPosSec +
                                (local.x - grabHalfPx) / canvasWidthPx * WAVE_WINDOW_SEC
                            frozenFraction = currentLineFraction
                            lineDragStartState()
                        },
                        onDragEnd = {
                            frozenFraction = null
                            lineDragEndState()
                        },
                        onDragCancel = {
                            frozenFraction = null
                            lineDragEndState()
                        }
                    ) { change, _ ->
                        change.consume()
                        lineDragState(
                            grabTime +
                                (change.position.x - pressX) / canvasWidthPx * WAVE_WINDOW_SEC
                        )
                    }
                }
        )
    }
}

/**
 * 中部字幕：显示全部分段，当前播放到的高亮并自动滚动。
 */
@Composable
fun TranscriptPane(
    segments: List<SpeechTranscriber.Segment>,
    posSec: Float,
    audioName: String,
    transcriptText: String,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    // 字幕展示形态：phrase = 短语逐句；chunk30 = 按 30 秒聚合（词级高亮保留）
    var mode by rememberSaveable { mutableStateOf("phrase") }

    // chunk30 形态：把短语按 30s 时间窗分组拼装成大段，词序列原样保留
    val displaySegments = remember(segments, mode) {
        if (mode != "chunk30" || segments.isEmpty()) {
            segments
        } else {
            segments
                .groupBy { maxOf(0, (it.startSec / 30).toInt()) }
                .entries.sortedBy { it.key }
                .map { (_, list) ->
                    SpeechTranscriber.Segment(
                        startSec = list.first().startSec,
                        endSec = list.maxOf { it.endSec },
                        text = list.joinToString(" ") { it.text.trim() },
                        words = list.flatMap { it.words }
                    )
                }
        }
    }

    // 高亮：优先用 [startSec, endSec) 精确匹配（词级对齐的新记录），
    // 旧记录 endSec=0 时退回「最后一个已开始的分段」
    val currentIndex = displaySegments.indexOfFirst { it.endSec > it.startSec && posSec < it.endSec }
        .takeIf { it >= 0 && displaySegments[it].startSec <= posSec + 0.01f }
        ?: displaySegments.indexOfLast { it.startSec <= posSec + 0.01f }

    LaunchedEffect(segments) {
        // 排查：播放页实际拿到的分段数据质量（tag: ELT_SEG）
        Log.i(
            "ELT_SEG",
            "字幕分段=${segments.size} 有endSec=${segments.count { it.endSec > it.startSec }} " +
                "有词级=${segments.count { it.words.isNotEmpty() }} " +
                "总词数=${segments.sumOf { it.words.size }}"
        )
    }

    LaunchedEffect(currentIndex, mode) {
        if (currentIndex >= 0 && mode != "vocab") {
            runCatching { listState.animateScrollToItem(currentIndex) }
            val seg = displaySegments.getOrNull(currentIndex)
            Log.i(
                "ELT_SEG",
                "形态=$mode 高亮段#$currentIndex [${seg?.startSec}..${seg?.endSec}] words=${seg?.words?.size}"
            )
        }
    }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 形态切换
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                FilterChip(
                    selected = mode == "phrase",
                    onClick = { mode = "phrase" },
                    label = { Text("逐句") }
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = mode == "chunk30",
                    onClick = { mode = "chunk30" },
                    label = { Text("30秒") }
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = mode == "vocab",
                    onClick = { mode = "vocab" },
                    label = { Text("词汇") }
                )
            }
        if (mode == "vocab") {
            // 词汇面板：本录音的 AI 词汇笔记（词组可打星，删除不影响转写）
            VocabPanel(
                audioName = audioName,
                transcriptProvider = { transcriptText },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        } else if (displaySegments.isEmpty()) {
            Text(
                text = "暂无转写内容\n请先回到主页对该音频进行「转文字」",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp)
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp, vertical = 12.dp
                )
            ) {
                itemsIndexed(displaySegments) { index, seg ->
                    val current = index == currentIndex
                    // 当前段内逐词高亮：找到 posSec 所在的词（最后一个已开始的词）
                    val wordIdx = if (current && seg.words.isNotEmpty()) {
                        seg.words.indexOfLast { it.startSec <= posSec }
                            .takeIf { idx ->
                                idx >= 0 && posSec < maxOf(
                                    seg.words[idx].endSec,
                                    seg.words[idx].startSec + 0.2f
                                )
                            } ?: -1
                    } else -1

                    val text = if (seg.words.isNotEmpty() && current) {
                        // 当前段：词级高亮的富文本（当前词加底色加粗）
                        buildAnnotatedString {
                            append("[${formatClock(seg.startSec)}] ")
                            seg.words.forEachIndexed { wi, w ->
                                val start = length
                                append(w.text)
                                append(" ")
                                if (wi == wordIdx) {
                                    addStyle(
                                        SpanStyle(
                                            background = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                                            fontWeight = FontWeight.Bold
                                        ),
                                        start,
                                        length
                                    )
                                }
                            }
                        }
                    } else {
                        AnnotatedString("[${formatClock(seg.startSec)}] ${seg.text}")
                    }

                    Text(
                        text = text,
                        style = if (current) {
                            MaterialTheme.typography.bodyLarge
                        } else {
                            MaterialTheme.typography.bodyMedium
                        },
                        fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (current) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                    )
                }
            }
        }
        } // 内层 Column 结束（一个闭合 if/else，一个闭合 Column）
    }
}

/**
 * 底部播放条：滑块 = 波形视窗滚动条（拖到最右 = 音轨末端对齐屏幕右缘），
 * 与红线（播放头）解耦；圆球拖动时变大并显示气泡；两侧时间标签仍显示播放头时间。
 */
@Composable
fun BottomPlayerBar(
    sliderFraction: Float,
    bubbleText: String,
    currentMs: Int,
    totalMs: Int,
    isPlaying: Boolean,
    enabled: Boolean,
    onPlayPause: () -> Unit,
    onSliderScrubStart: () -> Unit,
    onSliderScrub: (Float) -> Unit,
    onSliderScrubEnd: () -> Unit,
    error: String?,
    modifier: Modifier = Modifier
) {
    var dragging by remember { mutableStateOf(false) }
    val fraction = sliderFraction.coerceIn(0f, 1f)
    // 回调用 updatedState 包装，确保手势触发时拿到最新闭包
    val scrubStartState by rememberUpdatedState(onSliderScrubStart)
    val scrubState by rememberUpdatedState(onSliderScrub)
    val scrubEndState by rememberUpdatedState(onSliderScrubEnd)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (error != null) {
            Text(
                text = "播放出错：$error",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        // 滑块 + 气泡
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val ballSize by animateDpAsState(if (dragging) 28.dp else 18.dp, label = "ball")
            val ballColor = MaterialTheme.colorScheme.primary
            val fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
            val trackColor = MaterialTheme.colorScheme.surfaceVariant

            // 拖动时圆球上方显示气泡
            if (dragging) {
                val bubbleWidth = 64.dp
                Text(
                    text = bubbleText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .offset(
                            x = (maxWidth * fraction - bubbleWidth / 2)
                                .coerceIn(0.dp, maxWidth - bubbleWidth)
                        )
                        .background(ballColor, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }

            // 滑轨
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(trackColor, RoundedCornerShape(2.dp))
            )
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth(fraction)
                    .height(4.dp)
                    .background(fillColor, RoundedCornerShape(2.dp))
            )
            // 圆球
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = maxWidth * fraction - ballSize / 2)
                    .size(ballSize)
                    .background(ballColor, CircleShape)
            )

            // 手势层：点击跳转 + 横向拖动连续调节
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .pointerInput(enabled) {
                        if (!enabled) return@pointerInput
                        fun frac(x: Float) = (x / size.width.toFloat()).coerceIn(0f, 1f)
                        detectTapGestures { offset ->
                            scrubStartState()
                            scrubState(frac(offset.x))
                            scrubEndState()
                        }
                    }
                    .pointerInput(enabled) {
                        if (!enabled) return@pointerInput
                        fun frac(x: Float) = (x / size.width.toFloat()).coerceIn(0f, 1f)
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                dragging = true
                                scrubStartState()
                                scrubState(frac(offset.x))
                            },
                            onDragEnd = {
                                dragging = false
                                scrubEndState()
                            },
                            onDragCancel = {
                                dragging = false
                                scrubEndState()
                            }
                        ) { change, _ ->
                            change.consume()
                            scrubState(frac(change.position.x))
                        }
                    }
            )
        }

        // 时间 + 播放按钮
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formatClock(currentMs / 1000f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(56.dp)
            )
            Spacer(modifier = Modifier.weight(1f))

            // 播放/暂停大圆钮
            androidx.compose.material3.FilledIconButton(
                onClick = onPlayPause,
                enabled = enabled,
                modifier = Modifier.size(64.dp)
            ) {
                if (isPlaying) {
                    // 自绘暂停图标（两根竖条），避免依赖扩展图标库
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Box(
                            Modifier
                                .size(width = 6.dp, height = 26.dp)
                                .background(
                                    MaterialTheme.colorScheme.onPrimary,
                                    RoundedCornerShape(2.dp)
                                )
                        )
                        Box(
                            Modifier
                                .size(width = 6.dp, height = 26.dp)
                                .background(
                                    MaterialTheme.colorScheme.onPrimary,
                                    RoundedCornerShape(2.dp)
                                )
                        )
                    }
                } else {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = "播放",
                        modifier = Modifier.size(34.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = formatClock(totalMs / 1000f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.width(56.dp)
            )
        }
    }
}

private fun formatClock(sec: Float): String {
    val total = sec.toInt().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
