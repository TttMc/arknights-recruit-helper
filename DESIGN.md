# 公招助手 · 视觉与信息层级重设计 v2

目标：把「一张等宽字体的大段文本」变成「一眼能读完的战术面板」。
约束：纯 Java + 原生 View，不引入 AndroidX / ConstraintLayout，不动 TagFinder / Recruit 求解逻辑。

视觉稿：`design/mockup.png`（可缩放源文件 `design/mockup.svg`），像素尺寸直接按本文数值绘制。

---

## 0. 设计基调

三个关键词，落到具体规则：

| 关键词 | 具体规则 |
|---|---|
| 深色 | 背景永远是 `#0A0D0F`~`#1A1F23` 的冷灰黑，绝不用纯黑；叠层靠**亮度差 ≤ 8%** 而不是阴影 |
| 工业感 | 直角为主，只允许 2dp 微圆角 + 对角线切角；所有分割线 1dp；时间/人数一律等宽字体 |
| 橙黄高亮 | 琥珀色 `#FFB020` 是**唯一**的行动色，只给「推荐/可点/当前」；星级色是独立的信息色，不与行动色混用 |

信息层级（从强到弱，5 级，任何一屏只能有一个第 1 级）：

1. **结论** = 前 3 个推荐组合（20sp 数字 + 15sp 标签）
2. **推荐时长** = 等宽 15sp，紧跟组合，视觉重量仅次于结论
3. **保底星级** = 徽章（实心 = 时长保底，虚线 = 命中才有）
4. **明细列表** = 13sp，按星级分组的次要信息
5. **人数 / 上限星 / 时长表** = 11sp caption，可折叠

---

## 1. 色板（`res/values/colors.xml`）

### 底板
| Token | 值 | 用途 |
|---|---|---|
| `ak_void` | `#0A0D0F` | 窗口底、启动页背景 |
| `ak_panel` | `#14181B` | 面板主体 |
| `ak_panel2` | `#1A1F23` | 卡片 / 列表行 |
| `ak_inset` | `#0E1214` | 凹槽：输入框、结论区底、徽章底 |
| `ak_line` | `#262E34` | 常规 1dp 分割线 |
| `ak_line2` | `#333D45` | 组件描边 |
| `ak_line_hi` | `#46525B` | 选中 / 聚焦描边 |

### 文字
| Token | 值 | 对比度(对 `#14181B`) | 用途 |
|---|---|---|---|
| `ak_text` | `#E8EDF0` | 14.2:1 | 标题、标签名、干员名 |
| `ak_text2` | `#9AA7AE` | 7.0:1 | 正文、说明 |
| `ak_text3` | `#5F6B72` | 3.2:1 | 仅用于**装饰性**小字（≥11sp 且非关键信息） |
| `ak_text_dim` | `#3E474D` | 1.9:1 | 禁用、被划掉的组合 |

### 行动色 / 语义
| Token | 值 | 用途 |
|---|---|---|
| `ak_amber` | `#FFB020` | 主行动、推荐标记、时间强调（对比度 9.8:1） |
| `ak_amber_hi` | `#FFC857` | hover / 按下高亮 |
| `ak_amber_lo` | `#B87811` | 琥珀色的暗描边 |
| `ak_amber_dim` | `#FFB020` @ 12% | 琥珀填充背景（`#1F1B12` 等效） |
| `ak_teal` | `#2FD2C4` | **支援机械**专用（特殊但非推荐） |
| `ak_green` | `#34D399` | 「已就绪 / 已授予」状态点 |
| `ak_red` | `#FF5A5A` | 「未开启 / 失败」状态点 |

### 星级色（Arknights 原生语义，勿改）
| 星 | 值 | 说明 |
|---|---|---|
| 1★ | `#7C878E` | 灰 |
| 2★ | `#5FD08A` | 绿 |
| 3★ | `#4E9BFF` | 蓝 |
| 4★ | `#B36BFF` | 紫 |
| 5★ | `#FFE066` | 亮柠檬（刻意比 `ak_amber` 更黄更亮，避免和行动色混淆） |
| 6★ | `#FF7A3C` | 橙红 |

---

## 2. 字体层级

系统字体，不打包字体文件（省 300KB+）。中文走 Noto Sans CJK，数字/时间靠字族制造工业感。

```java
static final Typeface T_DISPLAY = Typeface.create("sans-serif-condensed", Typeface.BOLD); // 大号数字
static final Typeface T_MED     = Typeface.create("sans-serif-medium", Typeface.NORMAL);
static final Typeface T_MONO    = Typeface.MONOSPACE;                                    // 时间码
```

| 角色 | 字号 | 字族/字重 | 颜色 | letterSpacing | 用处 |
|---|---|---|---|---|---|
| Display | 20sp | condensed BOLD | `ak_amber` | 0.02 | 序号 01/02/03、大数字 |
| H1 | 15sp | medium | `ak_text` | 0.02 | 面板标题、启动页标题（22sp 同款） |
| H2 | 13sp | medium | `ak_text` | 0 | 组合标签、卡片小标题 |
| Body | 13sp | regular | `ak_text2` | 0 | 干员名、说明（行高 ×1.45 = 19sp） |
| Time | 15sp | **monospace BOLD** | `ak_amber` | 0.06 | `7:40` / `9:00` |
| Chip | 11.5sp | medium | `ak_text` | 0.02 | 标签胶囊内文字 |
| Caption | 11sp | regular | `ak_text2` | 0 | 人数、上限星、注释 |
| Label | 9.5sp | medium | `ak_text3` | **0.14** | 分区标题「结论 / 明细 / 时长参考」 |

> 分区标题刻意用「小字号 + 大字距」，这是工业 HUD 的核心手法，比加粗更有效。

---

## 3. 间距 / 几何

- 基础单位 **4dp**：`2 / 4 / 8 / 12 / 16 / 20 / 24`
- 面板：左右外边距 16dp，**最大宽度 400dp**（平板/折叠屏不拉满），内容内边距 14dp
- **切角规则**：只切对角——左上 + 右下切 10dp（`45°`），另外两个角保留 2dp 微圆角。整个 App 的卡片、按钮、徽章统一遵循这条，形成签名式几何。
- 最小触控 48×48dp；相邻可点元素间距 ≥ 8dp
- 阴影：**不用**。层级靠 `ak_panel2` > `ak_panel` > `ak_inset` 的亮度阶梯 + 1dp 描边表达（悬浮窗上投影会被游戏画面吃掉，等于没有）。

---

## 4. 组件规格

### 4.1 悬浮球（自定义 View `BallView`，60×60dp）

结构（从外到内）：
1. 底盘：`#E61A1F23` 圆，直径 60dp
2. 外环：2dp，`ak_amber` @ 35%，直径 58dp（内缩 1dp）
3. 扇形进度弧：3dp，`ak_amber`，扫过 90°，固定在右侧
4. 中心字形：「招」，22sp，condensed BOLD，`ak_amber`
5. 右下角 6×6dp 小方块（`ak_line_hi`），作为工业标记

状态：

| 状态 | 表现 |
|---|---|
| idle | 外环 35% 透明度；每 3s 一次极慢的 90° 弧旋转（`RotateAnimation` linear infinite，或 onDraw 用 `SystemClock.uptimeMillis()` 算角度，无需动画对象） |
| pressed | 整体 `scale 0.92`，外环透明度 → 100%，120ms |
| busy | 弧从 90° 缩到 30° 并 900ms 转一圈，`Paint.Cap.ROUND`；中心「招」换为「…」 |
| result-ok | 弧一次性从 30° 扫到 360° 再淡出，400ms（`ValueAnimator`） |
| error | 弧变 `ak_red`，闪 2 次（每次 120ms），然后回落 |
| 拖动中 | 外环 100%，`alpha` 0.94 |

拖动松手后**贴边吸附**：距左右边缘 < 24dp 时用 180ms `DecelerateInterpolator` 吸到 12dp 边距；同时把 y 夹在状态栏 +12dp ~ 屏高-72dp。

### 4.2 面板骨架

```
全景遮罩 #000 @ 62%（点它关闭，180ms 淡入）
└ 卡片：宽 min(screenW-32dp, 400dp)，高 wrap_content，最大 78% 屏高
   ├ 顶栏 46dp  ──────────────────────────────
   │  ▌ 3×18dp 琥珀竖条 + 「公招组合预测」15sp + 「5 个标签」caption 右对齐 + ✕ 48dp
   ├ 结论区（ak_inset 凹槽，padding 12dp）
   │  LABEL 结论 ─────────────────
   │  [推荐卡 ×≤3]
   │  [支援机械 特别条]
   │  [不推荐 一行灰字]
   ├ 明细区（独立 ScrollView，weight=1）
   │  LABEL 全部组合 ────────────
   │  分组头 6★ → 组合行 → 分隔……
   ├ 底部 56dp 工具条
   │  [输入框 42dp] [重算 72dp] [重截 44dp] [关闭 44dp]
   └ 
```

顶栏琥珀竖条入场动画：宽度 0 → 18dp，260ms `DecelerateInterpolator`。

### 4.3 推荐卡（结论区，≤3 条，每条高 56dp，间距 8dp）

```
┌────────────────────────────────────────────────┐ ← ak_panel2, 切角, 左 3dp 琥珀条
│▌ 01  高级资深干员  资深干员        9:00   [必得6★]│
│      稳住 6★ · 12 人                            │
└────────────────────────────────────────────────┘
```

| 元素 | 规格 |
|---|---|
| 序号 `01` | 20sp condensed BOLD，`ak_amber`；#1 用 100%，#2/#3 用 60% |
| 左强调条 | 3dp 宽，全高；#1 `ak_amber`，#2/#3 `ak_amber_lo` |
| 标签胶囊 | 11.5sp，bg `#22282D`，描边 1dp `ak_line2`，左右 padding 6dp，高 20dp，圆角 2dp；**稀有标签**（资深/高级资深/爆发/控场/召唤/位移）描边与文字改 `ak_amber` |
| 时长 | monospace BOLD 15sp `ak_amber`，右对齐，固定宽 44dp |
| 星级徽章 | 「必得6★」：bg 星级色 @14%，描边 1dp 星级色 @45%，文字星级色 11sp medium，高 20dp，padding 6dp |
| 第二行 caption | 11sp `ak_text2`：`稳住 6★ · 12 人`；`标签未被划才有` 的场景改写成 `稳住 3★ · 命中 5★ · 12 人`，其中「命中 X★」用虚线描边徽章 |
| 高亮 | #1 卡片额外加 12%→0 的琥珀横向渐变背景（layer-list） |

**语义可视化约定（重要）**：
- **实心徽章 = 时长保底**（标签被划也不亏，确定）
- **虚线徽章 = 标签命中才有**（有风险）
- **`必得` = 资深系**（唯一不会被划，用实心 + 100% 色）

这样用户不用读文字就能分辨「这条到底是稳的还是赌的」。

### 4.4 明细区

**分组头**（每组一个，高 26dp）：
```
■ 6★ 必得                     3 组
```
小方块 8×8dp 用星级色，标题 12sp medium `ak_text`，右侧计数 10sp `ak_text3`；下方 1dp 分割线用水平渐变（星级色 30% → 透明，靠右淡出），用 `layer-list` + `gradient` 实现。

**组合行**（高约 56~76dp，padding 10dp 12dp）：

```
│▌ 高级资深干员 + 资深干员          9:00   [必得6★]
│  6★ 银灰 · 能天使 · 星熊                    +3
│  5★ 德克萨斯 · 拉普兰德
```

| 元素 | 规格 |
|---|---|
| 左侧星级条 | 2dp 宽 × 全高，星级色；`<4★` 的组合用 `ak_text_dim` |
| 标签行 | 13sp medium `ak_text`，` + ` 用 `ak_text3` 连接（不用胶囊，节省横向空间） |
| 时间 | mono 13sp `ak_amber`（`<4★` 用 `ak_text3`） |
| 星级徽章 | 同 4.3，缩小为高 18dp / 10sp |
| 干员行 | 前缀星标 `6★`（星级色 12sp medium，固定宽 26dp）+ 名字 13sp `ak_text2`，用 ` · ` 分隔；每行最多 4 个，超出显示 `+N`（10sp `ak_text3`） |
| 低价值行 | 整块 `alpha 0.55`，且排在 4★ 之后 |
| 行点击 | 整行可点 → 展开/收起全部干员名（默认只显示 4 个），点击反馈 bg `#1F262B` |

**排序呈现**：分组顺序 6★ → 5★ → 4★ → **支援机械** → 3★ → 2★ → 1★。支援机械不归属于星级分组，作为一条**通栏特别条**插在 4★ 与 3★ 之间（既符合排序，又完成视觉区分）。

### 4.5 支援机械特别条（通栏，`ak_teal`）

```
┌ 机 ──────────────────────────────────────────┐
│ 支援机械                        1:00  [必得1★] │
│ THRM-EX · Castle-3 · 正义骑士号                │
│ ▸ 寻访拿不到，只能靠公招获得                    │
└──────────────────────────────────────────────┘
```

- 背景 `ak_teal` @ 8%，**1dp 虚线描边** `ak_teal` @ 40%（`DashPathEffect`，自定义 `Drawable`）
- 左侧 3dp 实心 `ak_teal` 竖条；左上角一个 16×16dp 的 `ak_teal` 小方块，里面「机」9.5sp `ak_void`
- 标题 13sp medium `ak_teal`；说明行 11sp `ak_teal` @ 80%
- 位置固定在 4★ 组之后、3★ 组之前

### 4.6 时长参考区（可折叠，默认**收起**）

`LABEL 时长参考  ▸` 一行，点击展开（高度 0→auto，180ms）。

展开后每行 24dp，结构：`时间码 | 星级区间条 | 范围文字 | 备注`

```
1:00  ▮▮▮▮□□   1~4★   赌标签
4:00  □▮▮▮▮□   2~5★   下限提到 2★
7:40  □□▮▮▮□   3~5★   锁 3★ 保底 ★推荐
9:00  □□▮▮▮□   3~5★   资深系专用
```

星级区间条：6 个 4×10dp 的小竖条（间隔 2dp），对应 1★~6★。落在区间内的用**该星自身的星级色**，区间外用 `ak_text_dim`。这是整个面板唯一的多色区域，信号极强——一眼看出「这条时长能盖到几星」。

实现：`LinearLayout` 里放 6 个 4×10dp 的 `View`，背景用 6 个色值动态生成即可，不必自定义 View。

### 4.7 底部工具条

- 输入框：高 42dp，bg `ak_inset`，描边 1dp `ak_line2`；聚焦时描边变 `ak_amber` + 底部 2dp 琥珀下划线（`layer-list` selector）
- 提示文字：`手输标签，用、分隔`，11sp `ak_text3`
- 按钮（统一高 40dp，圆角 2dp + 切角）：

| 按钮 | 样式 | 尺寸 |
|---|---|---|
| 重算 | 实心 `ak_amber`，文字 `#0A0D0F` 13sp medium | 宽 72dp |
| 重截 | 描边 `ak_line_hi`，文字 `ak_text` | 宽 56dp |
| 关闭 | 幽灵（无底），文字 `ak_text3` | 宽 44dp；同时保留顶栏 ✕ / 点遮罩 / 返回键三条路径 |

按钮按下：`bg` 变 `ak_amber_hi` / 描边变 `ak_amber`，120ms。

### 4.8 状态页（复用面板骨架）

| 状态 | 呈现 |
|---|---|
| 识别中 | 结论区替换为 3 条骨架行（`ak_panel2` 灰块，45% 透明度，横向渐变扫光 1200ms 循环）；标题右侧「正在识别…」；悬浮球同步转弧 |
| 无标签 | 红条卡片 + 3 条 `·` 原因列表（13sp，行高 20sp）+ 底部「手输兜底」提示；主按钮变为「重截」 |
| 截屏不可用 | 同上，但列出 1/2/3 步复述（复用现有文案，视觉重排为步骤列表：序号方框 + 说明） |
| 无可用组合 | 居中 `ak_text3` 一行 + 仍然渲染时长表（用户可能想自查） |

错误卡片规格：左侧 3dp `ak_red` 竖条，标题 13sp `ak_text`，正文 12sp `ak_text2`，`ak_inset` 底。

### 4.9 启动页（`MainActivity`）

```
[深色 #0A0D0F 全屏]
  ┌ 32dp 顶空 ─────────────────────
  │ ARKNIGHTS / RECRUIT              ← 9.5sp letterSpacing 0.24, ak_amber
  │ 公招助手                          ← 26sp condensed BOLD, ak_text
  │ ▔▔▔▔ 32dp 琥珀线 ────────────────
  │ 点悬浮球 → 自动截屏 → 直接出组合    ← 13sp ak_text2
  │
  │ LABEL 准备步骤 ──────────────
  │ ┌ 01 ─────────────────────────┐
  │ │ 悬浮窗权限          ● 已开启 │  ← 42×42dp 序号方框 + 状态点
  │ │ 允许在其他应用上层显示        │
  │ └─────────────────────────────┘
  │ ┌ 02 ─────────────────────────┐
  │ │ 截屏权限            ○ 未授予 │
  │ │ 系统弹窗必须点「立即开始」     │
  │ └─────────────────────────────┘
  │ ┌ 03 ─────────────────────────┐
  │ │ 启动悬浮球          ○ 未启动 │
  │ │ 启动后 App 会自动退到后台     │
  │ └─────────────────────────────┘
  │
  │ [   启动悬浮球   ]  ← 全宽 52dp 琥珀实心，未就绪时 40% 透明度
  │ [   停止悬浮球   ]  ← 全宽 44dp 幽灵按钮
  │
  │ ▸ 使用说明 / 常见问题（折叠）
```

- 序号方框：40×40dp，1dp `ak_line2` 描边，数字 15sp condensed `ak_text3`；**已完成**时描边和数字变 `ak_amber`，右侧状态点变 `ak_green` 实心（带 1.6s 呼吸，用 `ObjectAnimator` alpha 1↔0.4）
- 每步整行可点（右侧留 `chevron`），点击即触发原逻辑
- 主题：`android:Theme.Material.NoActionBar` + `windowBackground #0A0D0F` + `statusBarColor #0A0D0F`（API 23+ 用 `windowLightStatusBar=false`），**不要**再硬编码 `#F7F7F9`
- 语义色：红 = 未完成且必须做；绿 = 已完成；`ak_text3` = 未轮到

---

## 5. 交互与动效

| 场景 | 参数 |
|---|---|
| 面板入场 | 220ms：`alpha 0→1` + `translationY 24→0`，`DecelerateInterpolator(2.0)`；顶栏竖条 0→18dp 稍后 60ms 启动 |
| 面板退场 | 140ms：`alpha 1→0` + `scale 0.98`（`pivot = center`），`AccelerateInterpolator` |
| 遮罩 | 180ms `alpha 0→0.62` |
| 列表行入场 | 不做逐行 stagger（无 RecyclerView，成本不划算），改为整块 `translationY 12→0` |
| 折叠区 | 180ms，用 `ValueAnimator` 改 `LayoutParams.height` + `requestLayout()` |
| 骨架扫光 | 1200ms 循环，`LinearGradient` 平移 |
| 悬浮球按压 | 120ms `scale 0.92`；松手 `OvershootInterpolator(1.6)` 回 1.0 |
| 悬浮球识别中 | 弧 900ms/圈，`AnimationUtils.linearInterpolator()` |
| 触觉 | 触发识别、出结果各一次 `HapticFeedbackConstants.VIRTUAL_KEY`（`ball.performHapticFeedback`）；不要用 `LONG_PRESS` |
| 减少动效 | `Settings.Global.ANIMATOR_DURATION_SCALE == 0` 时全部动画时长直接置 0（悬浮窗场景下这很常见，必须处理） |

---

## 6. 落地实现映射

### 6.1 资源文件（新增）

```
res/values/colors.xml      ← 第 1 节全部 token
res/values/dimens.xml      ← 第 3 节全部尺寸
res/values/styles.xml      ← Theme.ArkRecruit（启动页）+ 文字样式 AR.Text.H1/H2/Body/Caption/Label
res/drawable/
  bg_panel.xml             ← layer-list：ak_panel + 1dp ak_line2 描边（切角用代码 Drawable，见下）
  bg_card.xml              ← ak_panel2 + 描边
  bg_inset.xml             ← ak_inset + 描边
  bg_chip.xml              ← 2dp 圆角 + ak_line2 描边
  bg_chip_rare.xml         ← 琥珀描边
  bg_badge_floor.xml       ← 星级色填充（实心，代码 setColor 换色）
  bg_btn_primary.xml       ← selector：ak_amber / ak_amber_hi / ak_amber_lo
  bg_btn_ghost.xml         ← selector 描边
  bg_input.xml             ← selector：常态 ak_line2 / 聚焦 ak_amber + 底部 2dp 线
  div_fade_amber.xml       ← 水平渐变 1dp 线
  bg_pick_hero.xml         ← 琥珀 12%→0 横向渐变
  bg_overlay.xml           ← #000 @ 62%
  ic_close.xml / ic_recap.xml / ic_chevron.xml / ic_check.xml
  mipmap 保持不动
```

### 6.2 需要自定义 `onDraw` 的（4 个类，都很小）

| 类 | 为什么 XML 做不到 | 行数 |
|---|---|---|
| `BallView` | 环形 + 旋转弧 + 中心字，需要角度插值 | ~110 |
| `BevelDrawable extends Drawable` | `shape` 只能全圆角；对角切角需要 `Path`（也可换成 `layer-list` 叠两个旋转 45° 的方块，但描边会断，不推荐） | ~70 |
| `DashBorderDrawable extends Drawable` | 虚线描边（支援机械条） | ~50 |
| `SkeletonView` | 扫光渐变位移（可选，用普通 View + `ValueAnimator` 改 `background` 也行） | ~40 |

其余全部用 XML shape / selector / vector 完成。

### 6.3 代码改造点（**不动算法**）

| 文件 | 改动 |
|---|---|
| `Recruit.java` | **一行不改**。新 UI 只读取 `Combo` 的 public 字段：`tags / guaranteed / ceiling / count / byRarity / minTime / timeFloor / supportMech / wastedOnLongRun / hasRareTag`，以及 `Combo.label()`、`Recruit.STAR[]`、`Recruit.durationTable()`（改成结构化数组即可新增 `STATIC String[][] DURATION_ROWS`，属于展示数据，不涉及求解）。 |
| `OverlayService.addBall()` | 换成 `new BallView(this)`，尺寸 60dp，保留 `BallTouch` 拖拽逻辑 + 加松手吸附。 |
| `OverlayService.buildPanel()` | 拆成 `PanelBuilder.build(List<Combo>, List<String> tags, Callback)`；`showPanel(String)` 保留为错误/提示专用分支（`buildMessagePanel`）。 |
| 新增 `PanelBuilder.java` | 结构化渲染：结论区 / 明细区 / 支援机械条 / 时长表。**不拼字符串**。 |
| 新增 `Ui.java` | `dp/sp`、`TypedValue.applyDimension`、`typeface()`、`chip(ctx, text, rare)`、`badge(ctx, text, rarity, dashed)` 等工厂方法，避免 600 行重复 `setTextSize/setPadding`。 |
| `MainActivity.onCreate()` | 整段重写为第 4.9 节结构；`refresh()` 改成更新 3 个状态点 + CTA 可用态，而不是拼一段带 ✅ 的字符串。 |
| `AndroidManifest.xml` | `android:theme` 改为 `@style/Theme.ArkRecruit`。 |

### 6.4 关于 AndroidX / ConstraintLayout

**不引入。** 代价与收益：

- `androidx.appcompat` ≈ +1.1MB dex，且强制全 App 继承 `Theme.AppCompat`，还要重配 `windowActionMode`、`statusBar` 等，纯负收益。
- `androidx.constraintlayout` 单独 ≈ +300KB，收益仅是把面板做成百分比约束——而本方案的布局是**严格纵向的 LinearLayout + weight**，`ConstraintLayout` 无收益。
- 用 `LinearLayout(weight)` + `FrameLayout` 做遮罩/居中 + 自定义 `onDraw`，能 100% 覆盖本设计所需的全部视觉。

### 6.5 兼容性要点

- `letterSpacing` 需 API 21+，minSdk 24 安全。
- `setBackground(Drawable)` API 16+，直接用，弃用 `setBackgroundDrawable`。
- 悬浮窗内自定义 View 的 `onDraw` 在 `TYPE_APPLICATION_OVERLAY` 上正常，但**不要**用 `RenderEffect`/`BlurMaskFilter`（部分机型 GPU 上会变糊或崩溃）。
- `densityDpi` 差异：所有尺寸走 `dp()`/`sp()`，禁止硬编码 px。
- 面板高度上限取 `min(0.78 * screenH, content)`，避免小屏（720p）时被顶出屏幕。

---

## 7. 可访问性 / 性能

- 正文对比度全部 ≥ 7:1；`ak_text3` 仅用于装饰性 11sp 及以下非关键文字，关键信息绝不用（≥11sp 且非关键，符合 WCAG AA 的 large-text 例外范围）
- 触控目标：悬浮球 60dp、✕ 48dp、按钮 40dp 高但左右 padding 保证 48dp 宽
- 内容描述：悬浮球 `setContentDescription("识别招募标签")`；面板卡片 `setAccessibilityPaneTitle("公招组合预测")`（API 28+，用 `if (Build.VERSION.SDK_INT >= 28)` 包裹）
- 性能：面板一次性 inflate 完成（组合数量最多 25 个，纯 View 完全够）；避免在 `onDraw` 里 `new Paint()`；星级色做成 `static final int[] RARITY_COLOR` 数组，避免重复 `Color.parseColor`

---

## 8. 建议落地顺序（每步都能单独出 APK 验证）

1. `colors.xml` / `dimens.xml` / `styles.xml` + 启动页重做（独立验证，风险最低）
2. `BallView` + 松手吸附 + 三态动效
3. `PanelBuilder` 骨架（顶栏 + 结论区三卡 + 底部工具条，明细暂用旧文本）
4. 明细区分组 + 组合行 + 展开交互
5. 支援机械条 + 时长区间条 + 折叠
6. 状态页（骨架/错误/空）与全局动效打磨
