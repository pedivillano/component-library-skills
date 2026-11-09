# 技能：音频波形播放组件（Audio Waveform Player）

> 版本：1.0 | 语言：Kotlin + Jetpack Compose (Material3) | 最低 API：26（MediaCodec 建议 21+）

一个开箱即用的 Android 音频播放页面组件：**响度波形 + 红色播放头 + 同步字幕 + 底部滚动条**，
三种手势（拖波形 / 拖红线 / 拖底部滑块）完全独立、逐帧 60fps 平滑跟手。

## 能力清单

| 能力 | 说明 |
|---|---|
| 波形响度图 | MediaCodec 原生采样率流式解码，每 4096 采样取峰值；磁盘缓存（`.bin`）一生只解码一次 |
| 红色播放头 | 已播放亮色 / 未播放暗色；拖动 1:1 跟手（冻结手柄坐标系，杜绝反馈抵消） |
| 时间窗视窗 | 一屏 60 秒（可调 `WAVE_WINDOW_SEC`），开头左对齐，越过 30% 锚点后平滑滚动 |
| 双手势独立 | 拖波形 = 平移视窗（红线钉屏）；拖红线 = seek（视窗/滑块冻结）；互不抢事件 |
| 底部滑块 | = 视窗滚动条（滑到最右 = 音轨末端对齐屏幕右缘），拖动时红线钉屏 |
| 逐帧插值 | 每 200ms 查询播放器锚点 + `withFrameNanos` 每帧推算，播放头丝滑无跳格 |
| 同步字幕 | 按 `TranscriptDb` 分段 JSON 高亮当前句并自动滚动 |

## 集成步骤

1. 复制 `src/PlayerScreen.kt`、`src/TranscriptDb.kt` 到你的模块源码目录（包名按需修改）。
2. `build.gradle.kts` 依赖：

```kotlin
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.xx.xx"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.lifecycle:lifecycle-runtime-compose")
}
```

3. 调用入口：

```kotlin
PlayerScreen(item = audioFileItem, onBack = { /* 返回 */ })
// item: 含 name(文件名) 与 uri(音频 Uri) 的数据类
```

4. 播放页读取 `TranscriptDb` 中按文件名保存的分段转写 JSON（无记录则显示提示，不影响播放）。

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
   所有回调用 `rememberUpdatedState` 包装。
7. **播放头不平滑**：定时轮询播放位置是跳格的 → 锚点 + `withFrameNanos` 逐帧插值。

## 文件结构

```
audio-waveform-player/
├── SKILL.md              ← 本文件
└── src/
    ├── PlayerScreen.kt   ← 播放页全部组件（波形、手势、字幕、播放条）
    └── TranscriptDb.kt   ← 转写记录 SQLite 存储（含分段 JSON 解析）
```
