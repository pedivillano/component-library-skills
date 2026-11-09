package com.example.myenglishlearningtool

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.myenglishlearningtool.data.VocabDb
import com.example.myenglishlearningtool.data.VocabItem
import com.example.myenglishlearningtool.llm.DeepSeekClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 编号前缀：001. 002. … 用来直观计算总数 */
internal fun vocabPrefix(index: Int): String = "%03d.".format(index + 1)

/**
 * 评分展示（AI 识别时已自动评分，可手动调整）：
 * - 词组/单词：星星 1~5（难度 + 重要程度综合评价）
 * - 表达：火焰 🔥 1~5（流行/地道程度，只读）
 */
@Composable
private fun ScoreRow(item: VocabItem, onSet: ((Int) -> Unit)? = null) {
    if (item.kind == VocabDb.KIND_EXPRESSION) {
        Text(
            text = "🔥".repeat(item.stars.coerceIn(0, 5)),
            style = MaterialTheme.typography.bodySmall
        )
    } else if (item.stars <= 0) {
        // 0 分 = 未评分（老数据/兜底），不显示一排灰星
        Text(
            text = "未评分",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    } else {
        Row {
            (1..5).forEach { n ->
                IconButton(
                    onClick = { onSet?.invoke(if (item.stars == n) 0 else n) },
                    modifier = Modifier.size(28.dp)
                ) {
                    // 空心星用同款 Star 低透明度表示（icons-core 无 StarBorder）
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = "$n 星",
                        tint = if (n <= item.stars) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
                        },
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/** 单词列表过滤：1 星单词（AI 评为常见无足轻重）默认忽略，可开关放开显示 */
private fun filterIgnoredWords(kind: String, items: List<VocabItem>, showOneStar: Boolean): List<VocabItem> =
    if (kind == VocabDb.KIND_WORD && !showOneStar) items.filter { it.stars != 1 } else items

/**
 * 单录音词汇面板：字幕区「词汇」形态的内容。
 * - 词组/单词两个子页签，编号前缀 + 星级（词组）+ 删除（只删笔记，不动转写）
 * - 底部「向 AI 提问」：选择问题 → 调 DeepSeek → 结果入库并刷新
 */
@Composable
fun VocabPanel(
    audioName: String,
    transcriptProvider: () -> String,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var kind by remember { mutableStateOf(VocabDb.KIND_PHRASE) }
    var items by remember { mutableStateOf(listOf<VocabItem>()) }
    var asking by remember { mutableStateOf(false) }
    var askStatus by remember { mutableStateOf("") }
    var showKeyDialog by remember { mutableStateOf(false) }

    fun reload() {
        items = VocabDb.getByAudio(context, audioName, kind)
    }
    LaunchedEffect(kind, asking) { reload() }

    // 一键生成：一次请求同时产出 词组 + 单词（重要/高阶）+ 地道表达解析，省 API 费用
    fun startAsk() {
        val (title, prompt) = VocabDb.COMBINED_QUESTION
        asking = true
        askStatus = "AI 正在生成 词组 + 单词 + 地道表达…（单次请求，约 10~30 秒）"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                DeepSeekClient.ask(context, title, prompt, transcriptProvider())
            }
            asking = false
            result.fold(
                onSuccess = { reply ->
                    VocabDb.saveAnswer(context, audioName, title, prompt, reply.rawText)
                    val byKind = reply.parsed.groupBy { it.kind }
                    val n = byKind.values.sumOf { VocabDb.insertItems(context, audioName, it) }
                    askStatus = "完成：词组 ${byKind[VocabDb.KIND_PHRASE]?.size ?: 0} · " +
                        "单词 ${byKind[VocabDb.KIND_WORD]?.size ?: 0} · " +
                        "表达 ${byKind[VocabDb.KIND_EXPRESSION]?.size ?: 0}，新增入库 $n 条"
                },
                onFailure = { askStatus = "失败：${it.message}" }
            )
        }
    }

    Column(modifier = modifier) {
        // 词组 / 单词 / 表达 子页签 + 一键生成入口
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            FilterChip(
                selected = kind == VocabDb.KIND_PHRASE,
                onClick = { kind = VocabDb.KIND_PHRASE },
                label = { Text("词组") }
            )
            Spacer(modifier = Modifier.width(6.dp))
            FilterChip(
                selected = kind == VocabDb.KIND_WORD,
                onClick = { kind = VocabDb.KIND_WORD },
                label = { Text("单词") }
            )
            Spacer(modifier = Modifier.width(6.dp))
            FilterChip(
                selected = kind == VocabDb.KIND_EXPRESSION,
                onClick = { kind = VocabDb.KIND_EXPRESSION },
                label = { Text("表达") }
            )
            Spacer(modifier = Modifier.weight(1f))
            OutlinedButton(onClick = {
                if (DeepSeekClient.hasApiKey(context)) startAsk() else showKeyDialog = true
            }, enabled = !asking) {
                Text(if (asking) "生成中…" else "AI 一键生成", style = MaterialTheme.typography.labelMedium)
            }
        }

        if (asking || askStatus.isNotEmpty()) {
            Text(
                text = askStatus,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }

        // 1 星单词默认忽略，可点 chip 放开显示
        var showOneStar by remember { mutableStateOf(false) }
        val displayItems = filterIgnoredWords(kind, items, showOneStar)
        if (kind == VocabDb.KIND_WORD) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 2.dp)
            ) {
                FilterChip(
                    selected = showOneStar,
                    onClick = { showOneStar = !showOneStar },
                    label = {
                        Text(if (showOneStar) "1 星单词：显示中" else "忽略 1 星单词（${items.size - displayItems.size}）")
                    }
                )
            }
        }

        if (items.isEmpty() && !asking) {
            Text(
                text = "还没有词汇，点右上角「AI 一键生成」\n一次请求生成 词组 + 单词 + 地道表达",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp)
            )
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                itemsIndexed(displayItems) { index, it ->
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Text(
                            text = vocabPrefix(index),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .width(40.dp)
                                .padding(top = 4.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = it.item,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (it.meaning.isNotBlank()) {
                                Text(
                                    text = it.meaning,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (it.example.isNotBlank()) {
                                Text(
                                    text = "\"${it.example}\"",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (it.kind != VocabDb.KIND_EXPRESSION) {
                                ScoreRow(item = it, onSet = { n ->
                                    VocabDb.setStars(context, it.id, n)
                                    reload()
                                })
                            } else {
                                ScoreRow(item = it)
                            }
                        }
                        IconButton(onClick = {
                            VocabDb.deleteItem(context, it.id)
                            reload()
                        }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "删除",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // 首次配置 API Key
    if (showKeyDialog) {
        var key by remember { mutableStateOf(DeepSeekClient.getApiKey(context)) }
        AlertDialog(
            onDismissRequest = { showKeyDialog = false },
            title = { Text("配置 DeepSeek API Key") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "在 platform.deepseek.com 创建 API Key 后粘贴到这里，仅保存在本机。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it },
                        singleLine = true,
                        placeholder = { Text("sk-…") }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    DeepSeekClient.saveApiKey(context, key)
                    showKeyDialog = false
                    if (key.isNotBlank()) startAsk()
                }) { Text("保存并生成") }
            },
            dismissButton = {
                TextButton(onClick = { showKeyDialog = false }) { Text("取消") }
            }
        )
    }
}

/**
 * 总览词汇笔记页：所有录音的词汇汇总。
 * - 词组/单词两个 tab，标题带实时总数
 * - 按来源录音分组，每组头显示录音名 + 贡献条数
 * - 统计头：总数 / 已标星 / 高阶词
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VocabOverviewScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current

    // 拦截系统返回手势/按键：回应用内主页
    BackHandler { onBack() }
    var kind by remember { mutableStateOf(VocabDb.KIND_PHRASE) }
    var items by remember { mutableStateOf(listOf<VocabItem>()) }
    var version by remember { mutableStateOf(0) }
    var onlyStarred by remember { mutableStateOf(false) }

    LaunchedEffect(kind, version) {
        items = VocabDb.getAll(context, kind)
    }

    val scope = rememberCoroutineScope()
    var scoring by remember { mutableStateOf(false) }
    var scoreMsg by remember { mutableStateOf("") }
    var showScoreConfirm by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var importMsg by remember { mutableStateOf("") }
    // 未评分条目数（随 version 刷新），为 0 时按钮置灰、点击给提示
    val unscoredCount = remember(version) { VocabDb.getUnscored(context).size }

    // 一键补分：把没有评分（0 星）的老词组/老单词/老表达收集起来，单次请求让 AI 打分；
    // 已有评分的条目完全不碰
    fun batchScore() {
        if (scoring) return
        if (!DeepSeekClient.hasApiKey(context)) {
            scoreMsg = "未配置 API Key，请先在播放页「AI 一键生成」里配置"
            return
        }
        scoring = true
        scoreMsg = "正在收集未评分条目…"
        scope.launch {
            val unscored = withContext(Dispatchers.IO) { VocabDb.getUnscored(context) }
            if (unscored.isEmpty()) {
                scoring = false
                scoreMsg = "所有条目都已有评分，无需补分"
                return@launch
            }
            scoreMsg = "正在让 AI 为 ${unscored.size} 条老数据打分…（单次请求）"
            val listing = unscored.mapIndexed { i, it ->
                "${i + 1}. [${it.kind}] ${it.item}"
            }.joinToString("\n")
            val prompt = "你是专业英语老师。给下面的英语学习条目打分，只输出一个 JSON，不要任何其他文字：\n" +
                "- kind 为 phrase 或 word：stars 为 1~5 整数（难度与重要程度综合评价，常见且简单的打 1 星，越高越值得记）\n" +
                "- kind 为 expression：stars 为 1~5 整数（当下英语中的流行/地道程度，越多越流行）\n" +
                "JSON 格式：{\"scores\":[{\"kind\":\"phrase\",\"item\":\"条目原文\",\"stars\":3}]}\n" +
                "item 必须与条目原文完全一致。\n\n条目列表：\n" + listing
            val result = withContext(Dispatchers.IO) {
                DeepSeekClient.ask(context, "一键打分", prompt, "")
            }
            scoring = false
            result.fold(
                onSuccess = { reply ->
                    val scores = VocabDb.parseScores(reply.rawText)
                    var applied = 0
                    unscored.forEach { u ->
                        val key = "${u.kind}|${u.item.trim().lowercase()}"
                        val s = scores[key] ?: scores[u.item.trim().lowercase()]
                        if (s != null) {
                            VocabDb.setStars(context, u.id, s)
                            applied++
                        }
                    }
                    scoreMsg = "补分完成：$applied/${unscored.size} 条（已有评分的未改动）"
                    version++
                },
                onFailure = { scoreMsg = "打分失败：${it.message}" }
            )
        }
    }

    val (total, starred, advanced) = VocabDb.stats(context, kind)
    // 三个页签各自统计各自的数量（而不是都显示当前选中类型的数量）
    val phraseTotal = VocabDb.stats(context, VocabDb.KIND_PHRASE).first
    val wordTotal = VocabDb.stats(context, VocabDb.KIND_WORD).first
    val exprTotal = VocabDb.stats(context, VocabDb.KIND_EXPRESSION).first
    // 1 星单词默认忽略，可开关放开；再叠加已标星筛选
    var showOneStar by rememberSaveable { mutableStateOf(false) }
    val base = filterIgnoredWords(kind, items, showOneStar)
    val display = if (onlyStarred) base.filter { it.stars > 0 } else base
    val grouped = display.groupBy { it.audioName }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        // 顶栏（TopAppBar 自带状态栏避让；标题下挂实时统计）
        TopAppBar(
            title = {
                Column {
                    Text(
                        text = "词汇笔记",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "共 $total 条 · 已标星 $starred · 高阶词 $advanced",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            actions = {
                TextButton(onClick = { showImportDialog = true }, enabled = !importing && !scoring) {
                    Text(if (importing) "导入中…" else "AI 导入")
                }
                TextButton(
                    onClick = {
                        if (scoring) return@TextButton
                        if (unscoredCount == 0) {
                            // 没有需要打分的：点击给提示
                            scoreMsg = "所有条目都已有评分，无需补分"
                        } else {
                            showScoreConfirm = true
                        }
                    },
                    enabled = !scoring
                ) {
                    Text(
                        if (scoring) "打分中…" else "一键打分",
                        color = if (unscoredCount == 0 && !scoring) {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        } else {
                            androidx.compose.ui.graphics.Color.Unspecified
                        }
                    )
                }
            }
        )

        if (scoreMsg.isNotEmpty() || importMsg.isNotEmpty()) {
            Text(
                text = listOf(scoreMsg, importMsg).filter { it.isNotEmpty() }.joinToString("　"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
        }

        // AI 导入：用户粘贴任意内容，AI 自动识别归类（词组/单词/表达）并带评分入库，挂在「AI 导入」名下
        fun aiImport(content: String) {
            if (importing) return
            if (!DeepSeekClient.hasApiKey(context)) {
                importMsg = "未配置 API Key，请先在播放页「AI 一键生成」里配置"
                return
            }
            importing = true
            importMsg = "AI 正在识别并导入…"
            scope.launch {
                val prompt = "你是专业英语老师。从下面的内容中提取值得学习的英语知识点，分成三类：\n" +
                    "- phrases：地道词组/习语/固定搭配\n" +
                    "- words：重要核心单词或高阶/学术词汇（level 填 important 或 advanced）\n" +
                    "- expressions：地道英语表达方式（meaning 中解析地道之处与使用场景）\n" +
                    "内容由你自行判断适合哪一类或哪几类，不适用的类别返回空数组；宁多勿漏但不要凑数。\n" +
                    "评分规则：词组/单词 stars 为 1~5 整数（难度与重要程度综合，常见简单的打 1 星）；" +
                    "表达 flames 为 1~5 整数（当下流行/地道程度）。\n" +
                    "只输出 JSON，不要任何其他文字：\n" +
                    "{\"phrases\":[{\"phrase\":\"...\",\"meaning\":\"...\",\"example\":\"...\",\"stars\":4}],\n" +
                    "\"words\":[{\"word\":\"...\",\"meaning\":\"...\",\"level\":\"important\",\"stars\":2}],\n" +
                    "\"expressions\":[{\"expression\":\"...\",\"meaning\":\"...\",\"example\":\"...\",\"flames\":5}]}\n\n内容：\n"
                val result = withContext(Dispatchers.IO) {
                    DeepSeekClient.ask(context, "AI 导入", prompt, content)
                }
                importing = false
                result.fold(
                    onSuccess = { reply ->
                        val parsed = VocabDb.parseReply("AI 导入", reply.rawText)
                        val byKind = parsed.groupBy { it.kind }
                        val n = byKind.values.sumOf { VocabDb.insertItems(context, "AI 导入", it) }
                        importMsg = "导入完成：词组 ${byKind[VocabDb.KIND_PHRASE]?.size ?: 0} · " +
                            "单词 ${byKind[VocabDb.KIND_WORD]?.size ?: 0} · " +
                            "表达 ${byKind[VocabDb.KIND_EXPRESSION]?.size ?: 0}，新增入库 $n 条"
                        version++
                    },
                    onFailure = { importMsg = "导入失败：${it.message}" }
                )
            }
        }

        // AI 导入弹窗
        if (showImportDialog) {
            var importText by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { if (!importing) showImportDialog = false },
                title = { Text("AI 导入") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "粘贴一段英语内容（文章、台词、词表…），AI 自动识别其中的词组/单词/表达，带评分入库。",
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedTextField(
                            value = importText,
                            onValueChange = { importText = it },
                            enabled = !importing,
                            minLines = 4,
                            maxLines = 8,
                            placeholder = { Text("粘贴内容…") }
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val content = importText
                            showImportDialog = false
                            aiImport(content)
                        },
                        enabled = importText.isNotBlank() && !importing
                    ) { Text("导入") }
                },
                dismissButton = {
                    TextButton(onClick = { if (!importing) showImportDialog = false }) { Text("取消") }
                }
            )
        }

        // 一键打分二次确认
        if (showScoreConfirm) {
            AlertDialog(
                onDismissRequest = { showScoreConfirm = false },
                title = { Text("确认一键打分") },
                text = {
                    Text(
                        "将为 $unscoredCount 条未评分的老词组/单词/表达调用一次 DeepSeek API 补分，" +
                            "已有评分的条目不会改动。"
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showScoreConfirm = false
                        batchScore()
                    }) { Text("开始打分") }
                },
                dismissButton = {
                    TextButton(onClick = { showScoreConfirm = false }) { Text("取消") }
                }
            )
        }

        // 词组 / 单词 tab + 星级筛选
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            FilterChip(
                selected = kind == VocabDb.KIND_PHRASE,
                onClick = { kind = VocabDb.KIND_PHRASE },
                label = { Text("词组 $phraseTotal") }
            )
            Spacer(modifier = Modifier.width(6.dp))
            FilterChip(
                selected = kind == VocabDb.KIND_WORD,
                onClick = { kind = VocabDb.KIND_WORD },
                label = { Text("单词 $wordTotal") }
            )
            Spacer(modifier = Modifier.width(6.dp))
            FilterChip(
                selected = kind == VocabDb.KIND_EXPRESSION,
                onClick = { kind = VocabDb.KIND_EXPRESSION },
                label = { Text("表达 $exprTotal") }
            )
            Spacer(modifier = Modifier.weight(1f))
            if (kind == VocabDb.KIND_PHRASE) {
                FilterChip(
                    selected = onlyStarred,
                    onClick = { onlyStarred = !onlyStarred },
                    label = { Text("★ 已标星") }
                )
            }
            if (kind == VocabDb.KIND_WORD) {
                Spacer(modifier = Modifier.width(6.dp))
                FilterChip(
                    selected = showOneStar,
                    onClick = { showOneStar = !showOneStar },
                    label = { Text(if (showOneStar) "1 星显示中" else "含 1 星") }
                )
            }
        }

        if (display.isEmpty()) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = "还没有笔记\n在播放页切到「词汇」点「AI 一键生成」",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .navigationBarsPadding(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
            ) {
                grouped.entries.forEach { (audioName, list) ->
                    item(key = "header_$audioName") {
                        Text(
                            text = "▶ $audioName（${list.size} 条）",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                        )
                    }
                    itemsIndexed(list, key = { _, it -> it.id }) { index, it ->
                        Row(
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Text(
                                text = vocabPrefix(index),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .width(40.dp)
                                    .padding(top = 4.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = it.item,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (it.meaning.isNotBlank()) {
                                    Text(
                                        text = it.meaning,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (it.kind != VocabDb.KIND_EXPRESSION) {
                                    ScoreRow(item = it, onSet = { n ->
                                        VocabDb.setStars(context, it.id, n)
                                        version++
                                    })
                                } else {
                                    ScoreRow(item = it)
                                }
                            }
                            IconButton(onClick = {
                                VocabDb.deleteItem(context, it.id)
                                version++
                            }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "删除",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
