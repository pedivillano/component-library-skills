# 技能：音频波形播放组件（Audio Waveform Player）

> 版本：1.2 | 语言：Kotlin + Jetpack Compose (Material3) | 最低 API：26（MediaCodec 建议 21+）

一个开箱即用的 Android 音频播放页面组件：**响度波形 + 红色播放头 + 词级同步字幕 + 底部滚动条**，
三种手势（拖波形 / 拖红线 / 拖底部滑块）完全独立、逐帧 60fps 平滑跟手。
v1.2 新增 **AI 词汇笔记系统**（DeepSeek）：一键生成 / AI 导入 / 自动评分 / 总览汇总。

## 能力清单

| 能力 | 说明 |
|---|---|
| 波形响度图 | MediaCodec 原生采样率流式解码，每 4096 采样取峰值；磁盘缓存（`.bin`）一生只解码一次 |
| 红色播放头 | 已播放亮色 / 未播放暗色；拖动 1:1 跟手（冻结手柄坐标系，杜绝反馈抵消） |
| 时间窗视窗 | 一屏 60 秒（可调 `WAVE_WINDOW_SEC`），开头左对齐，越过 30% 锚点后平滑滚动 |
| 双手势独立 | 拖波形 = 平移视窗（红线钉屏）；拖红线 = seek（视窗/滑块冻结）；互不抢事件 |
| 底部滑块 | = 视窗滚动条（滑到最右 = 音轨末端对齐屏幕右缘），拖动时红线钉屏 |
| 逐帧插值 | 每 200ms 查询播放器锚点 + `withFrameNanos` 每帧推算，播放头丝滑无跳格 |
| 时间轴网格对齐 | 波形条固定在全局时间轴区间上，平移时亚像素级真实滑动（非原地变形） |
| 屏蔽边缘返回 | 交互主区（波形+字幕+播放条）`systemGestureExclusion()` 连片屏蔽，可一路拖到屏幕边缘 |
| 词级同步字幕 | whisper token 时间戳（或能量插值兜底）实现短语级分段 + 卡拉OK式逐词高亮 |
| 双形态字幕 | 「逐句」（1~4s 短语）/「30秒」（聚合大段，词级高亮保留）一键切换 |
| 普通/精细识别 | 普通 30s/块（快）；精细 10s/块（词级时间更准，耗时约 3 倍），识别时可选 |
| AI 词汇笔记 | DeepSeek（OpenAI 兼容）一键生成：单次请求同时产出词组+单词（重要/高阶）+地道表达解析，自动去重入库 |
| 自动评分 | AI 直接打分：词组/单词 ★1~5（难度+重要度），表达 🔥1~5（流行度）；老数据可一键补分（已有评分不动） |
| AI 导入 | 粘贴任意英语内容，AI 自动识别归类（词组/单词/表达，带评分）入库 |
| 总览笔记页 | 全部录音词汇汇总：按来源分组、编号前缀 `001.`、实时统计、按录音贡献计数、1 星单词可开关忽略 |

## 集成步骤

1. 复制 `src/` 下 6 个文件到你的模块源码目录（包名按需修改）：
   `PlayerScreen.kt`（播放页）、`TranscriptDb.kt`（转写存储）、`SpeechTranscriber.kt`（whisper 转写）、
   `VocabDb.kt`（词汇存储）、`DeepSeekClient.kt`（LLM 客户端，放在 `llm/` 子包）、`VocabNotes.kt`（词汇面板+总览页）。
2. `build.gradle.kts` 依赖：

```kotlin
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.xx.xx"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.lifecycle:lifecycle-runtime-compose")
    implementation(files("libs/sherpa-onnx-2.x.x.aar"))  // 词级对齐字幕需要
}
```

3. 调用入口：

```kotlin
PlayerScreen(item = audioFileItem, onBack = { /* 返回 */ })
// item: 含 name(文件名) 与 uri(音频 Uri) 的数据类
```

4. 播放页读取 `TranscriptDb` 中按文件名保存的分段转写 JSON（无记录则显示提示，不影响播放）。
5. AI 词汇功能：首次使用时弹窗输入 DeepSeek API Key（存 SharedPreferences，`platform.deepseek.com` 申请）；
   播放页字幕区切到「词汇」页签 → 「AI 一键生成」；主页顶栏「词汇笔记」看总览；顶栏还有「AI 导入」「一键打分」。
6. 组合切换页面需 `BackHandler { onBack() }` 拦截系统返回（否则返回手势直接退出应用）；
   全屏自绘页面需 `statusBarsPadding()` / `navigationBarsPadding()` 避让系统栏。

## 核心参数（PlayerScreen.kt 顶部）

```kotlin
WAVE_WINDOW_SEC = 60f   // 一屏显示的秒数
WAVE_ANCHOR    = 0.3f   // 播放头滚动锚点位置（占屏宽比例）
grabHalf = 22.dp        // 红线抓取区半宽
```

## 手势模型（设计要点）

```
拖波形区域   → 平移视窗，红线钉在屏幕原位，松手 seek 到红线新对应时间
拖红色竖线   → 视窗/滑块冻结，红线 1:1 跟手，松手 seek
拖底部滑块   → 滚动视窗，红线钉屏；音频短于一屏时退化为普通 seek
点击波形     → 播放头跳到该处时间
点击「播放」 → 视窗跳回红线处跟随播放
```

## 实现时踩过的坑（复用本技能可避免）

1. **Kotlin 嵌套注释**：注释里写 `path/*.bin`，`/*` 会开启新注释 → 整个文件报 Unclosed comment。
2. **`Short` 无 `abs` 重载**：`abs(shorts.get(i).toFloat())`。
3. **手势事件抢占**：重叠的两层手势检测器（红线手柄 vs 画布平移）会互相抢事件导致红线横跳 →
   起手点落在抓取区内时，画布手势直接让位。
4. **坐标反馈抵消**：手柄若跟着红线移动，局部坐标位移与手指位移精确相消，红线永远不跟手 →
   拖动期间冻结手柄位置（手柄是隐形触摸区，冻结无视觉影响）。
5. **状态更新时序**：先 `seekTo`（内部清空冻结状态）再读冻结值 → 读到 null，视窗被意外重置；
   必须先取出到局部变量。
6. **`pointerInput(Unit)` 闭包过期**：手势 lambda 只创建一次，捕获的参数/回调会过期 →
   所有回调用 `rememberUpdatedState` 包装；**注意：手势 lambda 内直接参与计算的参数也要换成
   updatedState 读取**（如点击跳转计算里的 `viewStartSec` 用了旧值，导致滚动后点击失效）。
7. **播放头不平滑**：定时轮询播放位置是跳格的 → 锚点 + `withFrameNanos` 逐帧插值。
8. **seek 后锚点未重置**：插值锚点还停在旧位置，播放中 seek 后红线会被拽回去最多一个轮询周期 →
   `seekTo` 时同步重置锚点。
9. **波形「滑动」变「变形」**：波形条画在屏幕像素网格上（x 固定 6dp 倍数），平移时只有高度变，
   看起来一格一格离散变化 → 改为时间轴网格对齐：每根条固定属于全局时间轴的一个区间，
   `x = (条时间 − 视窗起点) × 像素/秒`，条形随手指亚像素级真实滑动、形状稳定。
10. **边缘返回手势冲突**：波形拖到屏幕边缘被系统返回手势打断 → 波形区加
    `Modifier.systemGestureExclusion()`（放在 `padding` 之前，让排除区覆盖边距；
    系统限制每侧最多排除 200dp，足够覆盖边缘触发区）。
11. **先清状态再读状态**：`seekTo` 内清空冻结视窗后，外层再读该值得到 null →
    视窗被意外重置回跟随模式，每次拖红线都重新锚定、误差累积「越拖越前」→
    先把冻结值存进局部变量再 seek。
12. **sherpa-onnx whisper 词级时间戳默认关闭**：`OfflineRecognizerResult.tokens` 有值但
    `timestamps` 是空数组 → 必须显式设置 `OfflineWhisperModelConfig(enableTokenTimestamps = true,
    enableSegmentTimestamps = true)`。
13. **开了开关时间戳仍为空**（部分模型/版本 native 层不填）→ 兜底方案：
    100ms 帧能量检测出块内语音段（间隔 ≤300ms 合并），token 按字符量加权在语音段内插值，
    **静音不摊时间**（这是主要误差源）；代码保留真实时间戳自动升级路径。
14. **whisper 30s 块是模型原生窗口**：编码器固定处理 30s 频谱图，切块更小模型精度不变、
    单块耗时不变、总耗时线性上涨（10s 块 = 3 倍耗时）→ 只在插值误差可感知时才用精细模式。
15. **Compose 包裹层少一个闭合大括号**：给现有结构包进新 `Column` 时补了一个 `}` 而需要两个 →
    后续所有顶层函数变成「局部函数」，产生几十个 Unresolved 连锁误报 →
    用逐行大括号深度脚本（剥离字符串/注释后计 `{}`）快速定位。
16. **Material3 `TopAppBar` 是实验性 API**：函数上需 `@OptIn(ExperimentalMaterial3Api::class)`。
    顺带收益：自带状态栏避让和 64dp 标准工具栏高度。
17. **Compose 组合函数内：局部状态/函数必须声明在引用它的 lambda 之前**——
    `TopAppBar.actions = { ... }` 引用了后面才声明的 `var` 会报 must be initialized，
    `AlertDialog` 的 onClick 引用后面才声明的 `fun` 会报 Unresolved →
    状态与局部函数统一放组合函数顶部。
18. **material-icons-core 没有 `Outlined` 图标集和 `StarBorder`**：空心星用 `Icons.Filled.Star`
    低透明度表示；火焰评分直接用 emoji 字符串（`"🔥".repeat(n)`）。
19. **SQLite 唯一索引 + `CONFLICT_IGNORE` 做 AI 结果去重**：`(audio_name, kind, item)` 唯一 →
    重复提问/导入不覆盖旧行，天然保护用户已改的评分和打星。
20. **LLM 评分直接在 prompt 里约定 JSON 字段**（`stars`/`flames`），一次请求同时产出多类内容，
    解析时缺字段给兜底值（0=未评分，UI 显示「未评分」而非一排灰星）。

## 文件结构

```
audio-waveform-player/
├── SKILL.md                ← 本文件
└── src/
    ├── PlayerScreen.kt     ← 播放页全部组件（波形、手势、字幕双形态、词汇面板入口、播放条）
    ├── TranscriptDb.kt     ← 转写记录 SQLite 存储（分段 JSON 含词级时间）
    ├── SpeechTranscriber.kt ← whisper 离线转写（词级对齐切分、语音段插值、普通/精细模式）
    ├── VocabDb.kt          ← 词汇笔记存储（条目/评分/原始回答/自定义问题，AI 回复 JSON 解析）
    ├── DeepSeekClient.kt   ← DeepSeek API 客户端（OpenAI 兼容、强制 JSON、Key 管理）
    └── VocabNotes.kt       ← 单录音词汇面板 + 总览笔记页（评分/一键生成/AI 导入/一键补分）
```
