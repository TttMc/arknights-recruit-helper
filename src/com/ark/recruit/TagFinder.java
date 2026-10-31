package com.ark.recruit;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.Log;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 在截图里找公招的标签按钮，再 OCR 出文字。
 *
 * v1.1 关键修复
 * -------------
 * v1.0 用 `Tasks.await(rec.process(...))` 同步等结果 —— 这个方法内部会
 * 等主线程的 Looper 来投递回调，而调用方又在后台线程，一旦 ML Kit 的回调
 * 依赖主线程就会互相等死（或直接抛同线程限制），最终表现为「点球没反应」。
 *
 * 现在改成：纯异步 + CountDownLatch 限时等待，回调跑在 ML Kit 自己的线程，
 * 不依赖主线程 Looper。同时给整体设了超时，绝不会无限挂住。
 */
public final class TagFinder {

    public static final class Result {
        public final List<String> tags = new ArrayList<>();
        public int slots;
        public String note;
        /**
         * 定位置信度低：标签区不是标准的 5 个按钮。
         *
         * 整个定位建立在「标签区是多行、行内等距、同列起点、等高」这个几何假设上，
         * 游戏 UI 改版或分屏/小窗模式下这个假设会失效，此时选出来的组可能是错的，
         * 但我们仍然会照常 OCR 并给结论 —— 所以必须让用户知道「这结果可能不可靠」，
         * 而不是静默给出错误标签。
         */
        public boolean uncertain;
        /**
         * ★ v2.9.2 ★ 整屏截图（src 本身）曾被提交给 ML Kit 且等待超时。
         *
         * Bitmap.createBitmap(src, 0, 0, W, H) 全区域会**直接返回 src 本身**，
         * 所以整屏兜底那条路交出去的其实就是调用方的截图 Bitmap。超时只代表
         * 「我们不等了」，ML Kit 的回调仍可能在后台线程里读它 —— 此时调用方
         * 若 recycle，就是 native 层 use-after-free，表现为随机闪退且无 Java 堆栈。
         *
         * 置位时调用方必须放弃 recycle，交给 GC。
         */
        public boolean srcMayBeInUse;
    }

    private static final String TAG = "TagFinder";

    /**
     * 一次识别的总时间预算（毫秒）。
     *
     * ★ v2.9 修复（原审查第 1 条）★
     * 原来最坏情况是 5 个按钮 × 6 档 × 6 秒 + 整屏兜底 12 秒 ≈ 192 秒，
     * 用户看到的就是「点了球三分钟没反应」。触发条件很现实：OCR 认出了乱码
     * 但 normalize 匹配不到合法标签，tryRead 返回 null 就会一路试完所有档位。
     *
     * 现在给整次识别一个硬预算：每档开跑前先看剩余时间，不够就直接放弃该档，
     * 已经识别到的结果照常返回。20 秒足够正常情况（第 1 档就命中，0.5~1.5 秒）。
     */
    private static final long TOTAL_BUDGET_MS = 20000L;

    /** 单档 OCR 的默认超时上限 */
    private static final long SLOT_TIMEOUT_MS = 6000L;

    /**
     * 最近一次 OCR 的真实失败原因（已格式化成字符串）。
     *
     * 原来 ocr() 里 `catch (Throwable t) { return null; }` 把所有异常吞掉，
     * 用户只能看到「没识别到标签」，看不到是不是 ML Kit 初始化失败 / 模型没下载完。
     * 现在记下来，识别结果为空时拼进 note 一起显示。
     *
     * ★ v2.9.1（交叉审查）：存 String 而不是 Throwable ★
     * 存 Throwable 会让这个静态字段一直持有异常对象及其 cause 链，
     * 而 ML Kit 的异常可能间接引用 Task → InputImage → 那张几十 MB 的截图，
     * 直到下一次 find() 才清 —— 一个诊断字段变成内存放大器。
     */
    private static volatile String lastFailure;

    /**
     * ★ v2.9.2 ★ 本次 find() 里，src 是否被交给 ML Kit 且等待超时。
     * 每次 find() 开头清零，结束时写进 Result.srcMayBeInUse。
     */
    private static volatile boolean srcSubmittedTimeout;

    /**
     * ★ v2.9.2 ★ TextRecognizer 复用。
     *
     * 原来每次 find() 都 getClient(...) 再 close()：中文识别器初始化要
     * 几百毫秒（加载内置模型会话），等于每次点球都白付一次初始化成本。
     * 进程存活期间一直复用，不主动 close（进程退出时系统回收）。
     */
    private static volatile TextRecognizer sharedRecognizer;

    public static Result find(Bitmap src, Context ctx) {
        Result r = new Result();
        lastFailure = null;
        srcSubmittedTimeout = false;
        // 预算从这里开始计：连定位（findDarkBars 的逐像素连通域）一起算在内，
        // 否则「定位很慢 + OCR 也慢」时总耗时会突破 20 秒。
        final long deadline = android.os.SystemClock.elapsedRealtime() + TOTAL_BUDGET_MS;
        List<Rect> boxes = findDarkBars(src);
        r.slots = boxes.size();


        TextRecognizer rec = recognizer();
        try {
            // 最多处理前 12 个候选，避免极端情况下耗时过长
            // ★ 先定位「标签区」，再只读区内的按钮（2026-10-30 修）★
            //
            // 原来直接按全图检测顺序读前 12 个候选，但公招界面里
            // 「招募时限」的上下箭头、顶部状态栏图标、确认按钮，
            // 尺寸和标签按钮几乎一模一样，会先把 12 个名额占满 ——
            // 结果标签只能读到前 1~2 个，第二行完全没机会。
            // 这正是「只能识别第一行」「费用回复识别不到」的真因。
            //
            // 标签区的强特征是「多行 × 多列，且列起点对齐」：
            //   第一行 x=636, 840, 1046
            //   第二行 x=636, 840
            // 两行共享相同的 x 起点，这在界面其他区域不出现。
            List<Rect> group = pickTagGroup(boxes);
            // 标签区正常就是 5 个按钮（3+2）。数量对不上说明几何假设可能失效。
            if (group.size() != 5) r.uncertain = true;

            // ★ v2.9.6 修复（真机反馈：多个 4 字标签时误报「识别不准确」）★
            //
            // 根因有两层，都在「每按钮独立归一化 + 结果去重」这个做法上：
            //
            //  ① 去重吞标签。公招 5 个标签**必然互不相同**，所以去重本身没错；
            //     但 bug 在于：某按钮的残片（如「辅助」）被 normalize 成完整标签
            //     （「辅助干员」）后，会和另一个真按钮的识别结果撞车。此时
            //     `!tags.contains(tag)` 把后到的那个丢掉 → 只剩 4 个 → uncertain。
            //     真实场景：5 个里有 4 个是 4 字标签时，这种撞车概率显著上升。
            //
            //  ② 静默错认。4 字标签只认出前 2 字时，normalize 的「唯一命中」
            //     规则会把它放大成某个 4 字标签，但可能放错：
            //       「医疗」→ 治疗（丢了干员）、「快速」→ 减速
            //     这类错误不触发 uncertain，用户看到的是一个**错的但自信**的结论。
            //
            // 修法：改成「先收集所有候选，再做全局最优分配」——
            //   · 每个按钮收集多个候选（不够就用 VALID 里所有合理候选，
            //     按与原始文本的相似度排序）；
            //   · 然后贪心分配：**同一个标签只能用一次**（互不相同约束）；
            //   · 每个按钮取「自己还没被用掉的最佳候选」。
            // 这样撞车不再丢信息，而是让第二个按钮退而求其次选它的次优候选。
            List<List<String>> cands = new ArrayList<>();
            for (Rect b : group) {
                if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                    r.uncertain = true;
                    break;
                }
                Rect safe = safeCropRect(b, boxes);
                List<String> c = readButtonCandidates(src, safe, rec, deadline);
                cands.add(c);
            }
            assignTags(cands, r.tags);

            // 读到 4 个、只差一个，通常是最后一个按钮 OCR 失败，
            // 此时结论仍可用，但要让用户知道少了一个标签。
            if (r.tags.size() != 5 && group.size() == 5) r.uncertain = true;

            if (r.tags.size() < 3) {
                // 兜底：整屏识别，从文本里挑合法标签。
                //
                // 注意这里不能只做 whole.contains(v) 精确匹配 ——
                // OCR 整屏识别错字率更高，必须同样过一遍 normalize 的
                // 编辑距离容错，否则「位移」认成「佗移」时还是漏。
                long left = deadline - android.os.SystemClock.elapsedRealtime();
                // ★ v2.9.1：阈值从 800ms 提到 2500ms ★
                // 全屏中文 OCR 在真机上普遍要 1~4 秒，800ms 几乎必然超时，结果是
                // 白等一次 + 那张全屏 Bitmap（3008×1880 ≈ 22MB）走了「不 recycle」路径
                // 白泄给 GC。阈值太小反而比不设更糟。剩余预算不足 2.5 秒时再跳过。
                long wholeTimeout = Math.max(0L, Math.min(12000L, left));
                String whole = wholeTimeout < 2500 ? null
                        : ocr(src, new Rect(0, 0, src.getWidth(), src.getHeight()),
                              rec, 1f, wholeTimeout);
                if (whole == null) {
                    r.note = wholeTimeout < 2500
                            ? "时间预算不足，跳过整屏兜底"
                            : "整屏 OCR 返回空";
                } else {
                    // 只做精确包含。
                    // 不要在这里用滑窗 + 编辑距离 —— 实测会误匹配：
                    // 「近战位治疗」里跨词的「位治」会被判成「位移」，
                    // 凭空多出一个标签，结论就错了。宁可少认，不能认错。
                    for (String v : VALID) {
                        if (whole.contains(v) && !r.tags.contains(v)) r.tags.add(v);
                    }
                }
            }
        } catch (Throwable t) {
            r.note = "OCR 异常: " + t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.w(TAG, "find failed", t);
        } finally {
            // 识别结果为空时，把底层真实原因（ML Kit 初始化失败 / 模型未下载 /
            // 回调异常 / 超时）补进 note，否则面板只会写「没识别到标签」，无从排查。
            //
            // ★ v2.9.1：允许覆盖占位文案 ★
            // 原来条件是「note 为空才补」，但整屏兜底那段会先写一句
            // 「整屏 OCR 返回空」，把真正的 Exception 挡在外面，诊断信息就丢了。
            // 只有 find() 自己 catch 到的异常写下的 note 才值得保留。
            if (r.tags.isEmpty() && lastFailure != null) {
                r.note = "OCR 底层失败: " + lastFailure;
            }
            lastFailure = null;
            // ★ v2.9.2 ★ 不再 close 共享识别器（复用，见 sharedRecognizer 注释）；
            // 但要告诉调用方：整屏截图可能还在 ML Kit 手里，别 recycle。
            r.srcMayBeInUse = srcSubmittedTimeout;
            srcSubmittedTimeout = false;
        }
        return r;
    }

    /** 取（或首次创建）进程级共享的识别器 */
    private static TextRecognizer recognizer() {
        TextRecognizer r = sharedRecognizer;
        if (r == null) {
            synchronized (TagFinder.class) {
                r = sharedRecognizer;
                if (r == null) {
                    r = TextRecognition.getClient(
                            new ChineseTextRecognizerOptions.Builder().build());
                    sharedRecognizer = r;
                }
            }
        }
        return r;
    }

    /**
     * 从所有候选矩形里挑出「标签按钮组」。
     *
     * 公招界面的标签是 3+2 的两行网格，两行共享相同的列起点。
     * 界面上其他深色元素（招募时限的上下箭头、顶部图标、确认按钮）
     * 虽然尺寸相似，但不会形成「多行同列对齐」的规律。
     *
     * 算法：
     *  1. 按行聚类（同一行的 y 差值小于按钮高度的一半）
     *  2. 找出所有「≥2 行」且「行内 ≥2 个」的簇
     *  3. 在这些簇里找「列起点重合最多」的那个 → 就是标签区
     *  4. 兜底：找不到成组规律时，退回按面积/数量取靠下的一组
     */
    private static List<Rect> pickTagGroup(List<Rect> boxes) {
        if (boxes.size() <= 1) return boxes;

        // ① 按行聚类（y 差值 < 较小高度的一半算同一行）
        List<Rect> sorted = new java.util.ArrayList<>(boxes);
        java.util.Collections.sort(sorted, new java.util.Comparator<Rect>() {
            public int compare(Rect a, Rect b) {
                if (a.top != b.top) return a.top - b.top;
                return a.left - b.left;
            }
        });

        List<List<Rect>> rows = new java.util.ArrayList<>();
        for (Rect r : sorted) {
            boolean placed = false;
            for (List<Rect> row : rows) {
                Rect f = row.get(0);
                int tol = Math.max(8, Math.min(f.height(), r.height()) / 2);
                if (Math.abs(r.top - f.top) <= tol) { row.add(r); placed = true; break; }
            }
            if (!placed) {
                List<Rect> nr = new java.util.ArrayList<>();
                nr.add(r);
                rows.add(nr);
            }
        }

        // ② 只保留「≥2 个」且「行内等距」的行
        //
        // 真实界面数据（一加15 截图实测）：
        //   y=154  x=[464,642,844,1538] 间距 178/202/694 → 不等距，剔除
        //   y=334  x=[638,840]          间距 202，等距但只有 2 个
        //   y=442  x=[636,840,1046]     间距 204/206   → 标签第一行
        //   y=530  x=[636,840]          间距 204       → 标签第二行
        List<List<Rect>> good = new java.util.ArrayList<>();
        for (List<Rect> row : rows) {
            if (row.size() < 2) continue;
            // 先剔除行内离群元素，再判断等距 —— 不能因为混进一个干扰元素
            // 就把整行丢掉（平板上「点击刷新标签」文字会混进标签第二行）。
            List<Rect> clean = dropOutliers(row);
            if (clean.size() < 2) continue;
            if (!evenlySpaced(clean)) continue;
            java.util.Collections.sort(clean, new java.util.Comparator<Rect>() {
                public int compare(Rect a, Rect b) { return a.left - b.left; }
            });
            good.add(clean);
        }
        if (good.isEmpty()) {
            List<Rect> best = new java.util.ArrayList<>();
            for (List<Rect> row : rows) if (row.size() > best.size()) best = row;
            return best.isEmpty() ? boxes : best;
        }

        // ③ 找「列起点对齐」的连续行组，取元素总数最多的
        int bestScore = -1;
        List<Rect> bestGroup = new java.util.ArrayList<>();
        for (int i = 0; i < good.size(); i++) {
            List<Rect> group = new java.util.ArrayList<>(good.get(i));
            int prevTop = good.get(i).get(0).top;
            int prevLeft = good.get(i).get(0).left;

            for (int j = i + 1; j < good.size(); j++) {
                List<Rect> row = good.get(j);
                // 列起点要对得上（容差 8px）
                if (Math.abs(row.get(0).left - prevLeft) > 8) break;
                // 行间距要合理
                int gap = row.get(0).top - prevTop;
                if (gap <= 0 || gap > Math.max(60, row.get(0).height() * 4)) break;
                // ★ 高度必须一致（容差 15%）★
                //
                // 实测：标签区两行按钮高都是 56（采样值），
                // 而「招募时限」那行的箭头高 68 —— 差 21%，
                // 靠这条规则就能把它排除，不会混进标签组。
                int h1 = group.get(0).height(), h2 = row.get(0).height();
                int hmin = Math.min(h1, h2), hmax = Math.max(h1, h2);
                if (hmin <= 0 || (hmax - hmin) / (float) hmin > 0.15f) break;

                group.addAll(row);
                prevTop = row.get(0).top;
                prevLeft = row.get(0).left;
            }

            int score = group.size() * 100 + rowsOf(group);
            if (score > bestScore) { bestScore = score; bestGroup = group; }
        }

        return bestGroup.isEmpty() ? boxes : bestGroup;
    }

    /**
     * 剔除行内的离群元素。
     *
     * 实测场景（红米 KPad2 3008×1880）：
     *   标签第二行 x=[885, 1275, 2615]
     *   885→1275 间距 390（正常），1275→2615 间距 1340（x=2615 是「点击刷新标签」）
     *   旧逻辑判定整行不等距 → 整行丢弃 → 第二行永远识别不到。
     *
     * 现在改成：按间距把元素连成「间距相近的链」，保留最长的那条链。
     */
    private static List<Rect> dropOutliers(List<Rect> row) {
        if (row.size() <= 2) return row;

        List<Rect> s = new java.util.ArrayList<>(row);
        java.util.Collections.sort(s, new java.util.Comparator<Rect>() {
            public int compare(Rect a, Rect b) { return a.left - b.left; }
        });

        // 算所有相邻间距
        java.util.List<Integer> gaps = new java.util.ArrayList<>();
        for (int i = 1; i < s.size(); i++) gaps.add(s.get(i).left - s.get(i - 1).left);
        if (gaps.isEmpty()) return s;

        // 取间距中位数作为「正常间距」
        java.util.List<Integer> sortedGaps = new java.util.ArrayList<>(gaps);
        java.util.Collections.sort(sortedGaps);
        // 取下中位数：偶数长度时取前一个。
        // 反例：间距 [390, 1340]，若取 index 1 会得到 1340，
        // 把正常的 390 判成离群，整行就选错起点了。
        int median = sortedGaps.get((sortedGaps.size() - 1) / 2);
        if (median <= 0) return s;

        // 从每个元素出发向右连链，间距与 median 差 < 35% 才算同一链
        int bestStart = 0, bestLen = 1;
        for (int i = 0; i < s.size(); i++) {
            int len = 1;
            for (int j = i + 1; j < s.size(); j++) {
                int gap = s.get(j).left - s.get(j - 1).left;
                if (Math.abs(gap - median) <= median * 0.35) len++;
                else break;
            }
            if (len > bestLen) { bestLen = len; bestStart = i; }
        }

        List<Rect> out = new java.util.ArrayList<>();
        for (int i = bestStart; i < s.size() && out.size() < bestLen; i++) out.add(s.get(i));
        return out;
    }

    /** 行内元素是否近似等距（间距标准差 < 12%） */
    private static boolean evenlySpaced(List<Rect> row) {
        if (row.size() < 2) return false;
        List<Rect> s = new java.util.ArrayList<>(row);
        java.util.Collections.sort(s, new java.util.Comparator<Rect>() {
            public int compare(Rect a, Rect b) { return a.left - b.left; }
        });
        if (s.size() == 2) {
            // 两个元素无法判断是否等距，只要间距合理即可
            int gap = s.get(1).left - (s.get(0).left + s.get(0).width());
            return gap >= 0 && gap < s.get(0).width() * 2;
        }
        double sum = 0;
        int n = 0;
        for (int i = 1; i < s.size(); i++) {
            sum += s.get(i).left - s.get(i - 1).left;
            n++;
        }
        if (n == 0) return false;
        double avg = sum / n;
        if (avg <= 0) return false;
        double var = 0;
        for (int i = 1; i < s.size(); i++) {
            double d = (s.get(i).left - s.get(i - 1).left) - avg;
            var += d * d;
        }
        double sd = Math.sqrt(var / n);
        return sd / avg < 0.12;
    }

    /** 统计 group 里横跨多少行 */
    private static int rowsOf(List<Rect> group) {
        java.util.Set<Integer> tops = new java.util.HashSet<>();
        for (Rect r : group) tops.add(Math.round(r.top / 16f));
        return tops.size();
    }
    /**
     * 计算安全的裁剪矩形：向外留边，但**绝不越过相邻按钮**。
     *
     * 这是「费用回复识别不到」的真正修法。
     *
     * 公招标签按钮的间距只有约 20px，而按钮高约 108px。
     * 原来固定留边 高度/6=18px，裁「费用回复」时会切进紧邻的「群攻」，
     * OCR 拿到的是两按钮拼接的脏图 → 稳定失败。
     *
     * 现在的做法：对每个方向单独算「到最近邻居的距离」，留边取
     * min(按钮高/12, 邻居距离/2 - 1)，保证裁剪框始终落在自己按钮附近。
     */
    private static Rect safeCropRect(Rect box, List<Rect> all) {
        int base = Math.max(2, box.height() / 12);

        int padL = base, padT = base, padR = base, padB = base;

        for (Rect o : all) {
            if (o == box) continue;
            boolean vOverlap = box.top < o.bottom && box.bottom > o.top;
            boolean hOverlap = box.left < o.right && box.right > o.left;

            if (vOverlap) {
                // 左右邻居
                if (o.left >= box.right) {
                    int gap = o.left - box.right;
                    padR = Math.min(padR, Math.max(1, gap / 2 - 1));
                } else if (o.right <= box.left) {
                    int gap = box.left - o.right;
                    padL = Math.min(padL, Math.max(1, gap / 2 - 1));
                }
            }
            if (hOverlap) {
                // 上下邻居
                if (o.top >= box.bottom) {
                    int gap = o.top - box.bottom;
                    padB = Math.min(padB, Math.max(1, gap / 2 - 1));
                } else if (o.bottom <= box.top) {
                    int gap = box.top - o.bottom;
                    padT = Math.min(padT, Math.max(1, gap / 2 - 1));
                }
            }
        }

        return new Rect(box.left - padL, box.top - padT,
                box.right + padR, box.bottom + padB);
    }

    /**
     * 读单个按钮，返回**候选列表**（按置信度从高到低），而不是只返回一个值。
     *
     * ★ v2.9.6 新增 ★
     *
     * 原来 readButtonTag 命中第一档就返回单值，导致两个问题：
     *   · 两个按钮归一化撞成同一个标签时，后一个被去重丢掉（信息全丢）；
     *   · 只认出前 2 字时，若「唯一命中」规则放错（快速复活→减速），无从纠正。
     *
     * 现在：
     *   · 每档 OCR 只要产出文本，就把 normalize 的结果按顺序记进候选；
     *   · 如果所有档都失败或候选太少，再加一批「跟原始文本相似的历史候选」
     *     （用于 resolveCandidates 的兜底），让全局分配有得选。
     */
    private static List<String> readButtonCandidates(Bitmap src, Rect box, TextRecognizer rec,
                                                     long deadline) {
        List<String> out = new ArrayList<>();
        String firstRaw = null;

        // 4 字标签在 2x 下容易截断，所以 3x / 4x 也放进主循环，
        // 且**只有精确命中才允许提前收工**（见下方 exact 判断）。
        float[] plain = {2f, 3f, 1.5f, 4f};
        for (float s : plain) {
            long left = deadline - android.os.SystemClock.elapsedRealtime();
            if (left < 600) return out;
            String raw = ocr(src, box, rec, s, Math.min(SLOT_TIMEOUT_MS, left), false);
            if (raw == null) continue;
            if (firstRaw == null) firstRaw = raw;

            boolean exact = isExactTag(raw);
            String tag = normalize(raw);
            if (tag != null) {
                if (exact) {
                    // ★ 精确命中的候选必须排到最前 ★
                    // 「医疗」会被纠错成「治疗」，而 3x 才给出真正的「医疗干员」。
                    // 沿用"先到先得"的顺序，正确的那个就永远排在后面、分配时取不到。
                    out.remove(tag);
                    out.add(0, tag);
                } else if (!out.contains(tag)) {
                    out.add(tag);
                }
            }

            // 只有「真读到了完整标签」才值得停止重试；
            // 靠纠错规则猜出来的（4 字被截成前缀）应继续试更大倍率。
            if (exact) return out;
        }
        if (!out.isEmpty()) return out;

        // 第二轮：反色增强（深底白字转白底黑字，对长标签更稳）
        float[] boost = {2f, 3f};
        for (float s : boost) {
            long left = deadline - android.os.SystemClock.elapsedRealtime();
            if (left < 600) return out;
            String raw = ocr(src, box, rec, s, Math.min(SLOT_TIMEOUT_MS, left), true);
            if (raw == null) continue;
            if (firstRaw == null) firstRaw = raw;
            String tag = normalize(raw);
            if (tag != null) {
                if (isExactTag(raw)) {
                    out.remove(tag);
                    out.add(0, tag);
                    return out;
                }
                if (!out.contains(tag)) out.add(tag);
            }
        }
        if (!out.isEmpty()) return out;

        // 第三轮：还是没候选，但有原始文本（比如「医疗」「快速」这种被 normalize
        // 判为不唯一的残片）→ 列出所有「它明显是哪个标签」，交给全局分配裁决。
        //
        // 注：原「4x 兜底」那一轮已经并入主循环的 plain 数组，不再重复跑。
        if (firstRaw != null) {
            out.addAll(plausibleTags(firstRaw));
        }
        return out;
    }

    /**
     * 原始 OCR 文本（清理空白与括号后）是否**恰好等于**某个合法标签。
     *
     * 用来区分「真读到了完整标签」和「靠纠错规则猜出来的」：
     * 前者可以直接采信并停止重试，后者应该继续尝试更大倍率（4 字标签尤其如此）。
     */
    private static boolean isExactTag(String raw) {
        if (raw == null) return false;
        String t = raw.replaceAll("[\\s\\u00A0]", "")
                .replace("（", "").replace("）", "")
                .replace("(", "").replace(")", "");
        if (t.isEmpty()) return false;
        for (String v : VALID) if (v.equals(t)) return true;
        return false;
    }

    /**
     * 给一段 OCR 原始文本，列出所有「它可能是哪个合法标签」的候选，
     * 按相似度从高到低排序。
     *
     * ★ 严格限定候选范围 ★
     *
     * 第一版把「有序子序列」「编辑距离 ≤2」都算作可能，结果残片「辅助」能
     * 匹配出一大堆无关标签（位移、生存……），全局分配取次优时就编出了假标签。
     *
     * 现在只接受三种**强相关**：
     *   1. 完全相等；
     *   2. 原文是某个合法标签的**真前缀**（如「辅助」→「辅助干员」、
     *      「费用回」→「费用回复」）—— OCR 截断的典型形态；
     *   3. 与某个合法标签等长且编辑距离 ≤1（错一个字）。
     *
     * 这样残片只能指向「它明显是哪个标签」，不会扩散到无关词。
     */
    private static List<String> plausibleTags(String raw) {
        List<String> res = new ArrayList<>();
        if (raw == null) return res;
        String t = raw.replaceAll("[\\s\\u00A0]", "")
                .replace("（", "").replace("）", "")
                .replace("(", "").replace(")", "");
        if (t.length() < 2) return res;

        final String tt = t;
        List<String> pool = new ArrayList<>();
        for (String v : VALID) {
            if (tt.equals(v)) { pool.add(v); continue; }
            // 真前缀：OCR 截断了尾部
            if (v.length() > tt.length() && v.startsWith(tt)) { pool.add(v); continue; }
            // 等长且只错一个字
            if (v.length() == tt.length() && editDistance(tt, v) <= 1) { pool.add(v); }
        }
        // 按「候选长度升序」（越短越保守）+ 字典序，保证结果确定
        Collections.sort(pool, new Comparator<String>() {
            public int compare(String a, String b) {
                if (a.length() != b.length()) return a.length() - b.length();
                return a.compareTo(b);
            }
        });
        return pool;
    }

    /**
     * 全局分配：把「每按钮的候选列表」摊成「尽量互不相同」的标签集合。
     *
     * ★ v2.9.6 新增，解决真机反馈的误报 ★
     *
     * 核心洞察：**公招同一次出现的 5 个标签一定互不相同**。这个约束原来完全
     * 没利用，反而被 `!tags.contains(tag)` 拿去当去重规则用 —— 结果是「撞车就
     * 丢掉后一个」，造成只有 4 个标签、弹出「识别不准确」。
     *
     * 做法是轮转匹配：每轮让还没着落的按钮去取「自己候选里第一个未被占用」的；
     * 某一轮全员都没有新进展就停。
     *
     * ★ 铁律：绝不为了凑满 5 个而编造 ★
     *
     * 第一版实现有个严重错误：候选全被占的按钮，直接拿 cands[i][0]（哪怕撞车）
     * 硬塞进结果。实测场景「辅助干员残片撞上真辅助标签」时，它把「位移」造了
     * 出来 —— 结果 5 个标签看起来正常、uncertain=false，但有一个是假的，
     * 用户会照着去选一个根本不保底的组合。这比"少认一个、提示不可靠"糟糕得多。
     *
     * 所以这里宁可返回 4 个（调用方会置 uncertain），也不编第 5 个。
     */
    private static void assignTags(List<List<String>> cands, List<String> out) {
        out.clear();
        if (cands.isEmpty()) return;

        int n = cands.size();
        int[] ptr = new int[n];
        boolean[] satisfied = new boolean[n];
        Set<String> used = new LinkedHashSet<>();

        for (int round = 0; round < VALID.length + 2; round++) {
            boolean progress = false;
            for (int i = 0; i < n; i++) {
                if (satisfied[i]) continue;
                List<String> c = cands.get(i);
                while (ptr[i] < c.size() && used.contains(c.get(ptr[i]))) {
                    ptr[i]++;
                }
                if (ptr[i] < c.size()) {
                    used.add(c.get(ptr[i]));
                    satisfied[i] = true;
                    progress = true;
                }
            }
            if (!progress) break;
        }

        // 不再有「兜底硬塞」这一步。没拿到的就是没识别出来，
        // 由调用方置 uncertain 让用户知道结果偏保守。
        out.addAll(used);
    }

    /**
     * 按钮区域的 OCR 预处理：灰度 → 反色 → 对比度拉伸。
     *
     * 为什么需要：公招标签是「深灰底 + 白字」，ML Kit 对这类低对比小字
     * 的识别率明显低于「白底黑字」。实测「位移」在两字词里经常被认成
     * 「佗移」「位秽」，反色增强后能稳定认出。
     *
     * 用 getPixels/setPixels 批量处理，不要逐像素 getPixel（慢 100 倍）。
     */
    private static Bitmap enhanceForOcr(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);

        // 第一遍：算灰度并统计直方图，用于自动找拉伸区间
        int minL = 255, maxL = 0;
        int[] lum = new int[w * h];
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
            int l = (r * 299 + g * 587 + b * 114) / 1000;
            lum[i] = l;
            if (l < minL) minL = l;
            if (l > maxL) maxL = l;
        }
        // 对比度拉伸：把 [minL,maxL] 映射到 [0,255]，再反色
        int span = Math.max(1, maxL - minL);
        for (int i = 0; i < px.length; i++) {
            int v = (lum[i] - minL) * 255 / span;
            if (v < 0) v = 0;
            if (v > 255) v = 255;
            v = 255 - v;   // 反色
            px[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, w, 0, 0, w, h);
        return out;
    }

    /** 异步 OCR + 限时等待，不依赖主线程 Looper */
    private static String ocr(Bitmap src, Rect box, TextRecognizer rec,
                              float scale, long timeoutMs) {
        return ocr(src, box, rec, scale, timeoutMs, false);
    }

    /**
     * 带预处理的 OCR。
     *
     * enhance=true 时做三件事，专门对付「深底白字 + 小字号」：
     *  1. 转灰度（消除彩色背景干扰，比如按钮的渐变/高光）
     *  2. 反色（白字黑底 → 黑字白底，ML Kit 对这种更准）
     *  3. 拉对比度（把灰蒙蒙的边缘压成纯黑纯白，笔画更清晰）
     */
    private static String ocr(Bitmap src, Rect box, TextRecognizer rec,
                              float scale, long timeoutMs, boolean enhance) {
        Bitmap crop = null, scaled = null, processed = null;
        // 是否已经把 Bitmap 交给 ML Kit（process() 一旦提交就可能被回调读取）
        boolean submitted = false;
        // await 是否正常结束（成功/失败都算结束）；超时或异常时视为未结束
        boolean done = false;
        // crop 是否就是调用方传进来的 src 本身（整屏兜底路径）。要在 finally 里用，
        // 所以必须声明在 try 之外。
        boolean cropIsSrc = false;
        try {
            // ★ 裁剪留边必须很小（2026-10-30 修）★
            //
            // 原来用 高度/6（约 18px），但公招标签按钮之间的间距只有 20px。
            // 结果裁「费用回复」时往右多扩 18px，直接切进紧邻的「群攻」，
            // OCR 看到的是两张按钮拼起来的脏图 → 稳定识别失败。
            // 留边已经在 safeCropRect 里按「到邻居的距离」算过了，
            // 这里不再二次扩张，否则会把刚才省下来的间隙又吃掉。
            Rect rr = new Rect(box);
            if (rr.left < 0) rr.left = 0;
            if (rr.top < 0) rr.top = 0;
            if (rr.right > src.getWidth()) rr.right = src.getWidth();
            if (rr.bottom > src.getHeight()) rr.bottom = src.getHeight();
            if (rr.width() < 8 || rr.height() < 8) return null;

            crop = Bitmap.createBitmap(src, rr.left, rr.top, rr.width(), rr.height());
            // ★ v2.9.2 ★ 区域等于整图时 createBitmap 直接返回 src 本身（不是副本）。
            // 这条路径只有整屏兜底会走到，此时「临时 Bitmap」其实就是调用方的截图，
            // 超时后必须让调用方知道不能 recycle。
            cropIsSrc = (crop == src);
            Bitmap base = crop;
            if (enhance) {
                processed = enhanceForOcr(crop);
                base = processed;
            }
            scaled = (scale == 1f) ? base : Bitmap.createScaledBitmap(base,
                    Math.max(1, (int) (base.getWidth() * scale)),
                    Math.max(1, (int) (base.getHeight() * scale)), true);

            final CountDownLatch latch = new CountDownLatch(1);
            final StringBuilder sb = new StringBuilder();
            final Throwable[] err = new Throwable[1];

            submitted = true;
            // ★ v2.9.1（交叉审查修正）★
            // addOnSuccessListener(listener) 单参版默认跑在**主线程**（TaskExecutors.MAIN_THREAD），
            // 不是 ML Kit 自己的线程。等 latch 的是后台线程，所以不会死锁；
            // 但主线程一旦被面板构建卡住，回调会被推迟 → 我们这边超时放弃 → 白等 + 白泄漏。
            // 显式传直接执行的 executor，让回调在 ML Kit 的线程上立即跑完。
            final java.util.concurrent.Executor direct = new java.util.concurrent.Executor() {
                public void execute(Runnable r) { r.run(); }
            };
            rec.process(InputImage.fromBitmap(scaled, 0))
                    .addOnSuccessListener(direct, t -> {
                        try {
                            for (Text.TextBlock blk : t.getTextBlocks()) {
                                // 每行之间补分隔符：直接首尾相接会把相邻两行拼成一个词
                                // （「近战」+「位移」→「近战位移」，contains 判「近战位」就成了假阳性）
                                if (sb.length() > 0) sb.append('\n');
                                for (Text.Line ln : blk.getLines()) {
                                    if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
                                        sb.append('\n');
                                    }
                                    sb.append(ln.getText());
                                }
                            }
                        } finally {
                            latch.countDown();
                        }
                    })
                    .addOnFailureListener(direct, e -> {
                        err[0] = e;
                        latch.countDown();
                    });

            // ★ v2.9 关键修复（原审查第 2 条）：超时绝不能 recycle ★
            //
            // latch.await 超时只代表「我们不等了」，ML Kit 的异步回调
            // 可能仍在后台线程里读这个 Bitmap。此时 recycle 会让 native 层
            // 访问已释放内存 → 直接 SIGSEGV，Java 层连堆栈都没有，
            // 表现为 App 无征兆闪退，且日志里查不到原因。
            //
            // 所以：只有「确认回调已经结束」（成功或失败）才回收；
            // 超时的那一份交给 GC 自己处理（几 MB 的临时 Bitmap，
            // 由 ART 在下次 GC 时回收，代价远小于崩溃）。
            done = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            if (!done) {
                // ★ v2.9.2 ★ 整屏兜底时 crop 就是源截图本身：把它记下来，
                // 由 find() 通过 Result.srcMayBeInUse 告知调用方不可 recycle。
                if (cropIsSrc) srcSubmittedTimeout = true;
                lastFailure = "OCR 超时(" + timeoutMs + "ms)";
                return null;
            }
            if (err[0] != null) {
                // 失败原因不再静默吞掉：整次识别失败时 note 会带上这条
                lastFailure = err[0].getClass().getSimpleName() + ": " + err[0].getMessage();
                return null;
            }
            return sb.length() == 0 ? null : sb.toString();
        } catch (Throwable t) {
            // 不再无声返回 null：记下最近一次真实异常，供面板提示使用
            lastFailure = t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.w(TAG, "ocr failed: " + t, t);
            return null;
        } finally {
            // ★ v2.9.1（交叉审查修正）★
            // 回收条件必须同时看 submitted：只要 process() 已经提交过，
            // 而 await 没等到结果（超时 / InterruptedException / 其他异常），
            // 回调就可能正在读这些 Bitmap，此时 recycle 会崩在 native 层。
            // 原来只靠 reclaimable 单一标志，异常路径（catch）没有置 false，漏洞仍在。
            if (!submitted || done) {
                if (scaled != null && scaled != crop && scaled != processed) {
                    try { scaled.recycle(); } catch (Throwable ignored) {}
                }
                if (processed != null) { try { processed.recycle(); } catch (Throwable ignored) {} }
                // ★ v2.9.2 ★ crop == src 时绝不能 recycle：那是调用方的截图，
                // 即便本轮 OCR 正常结束，后面还有整屏兜底/其他档位要用它。
                if (crop != null && !cropIsSrc) { try { crop.recycle(); } catch (Throwable ignored) {} }
            }
        }
    }

    // ------------------------------------------------------------ 找深色长条
    private static List<Rect> findDarkBars(Bitmap src) {
        int W = src.getWidth(), H = src.getHeight();
        // 采样率按短边算：保证横竖屏下按钮在采样图上的像素尺寸一致，
        // 阈值才有可比性。目标是把短边压到约 320。
        int shortPx = Math.min(W, H);
        int sw = Math.max(1, shortPx / 320);
        int mw = Math.max(1, W / sw), mh = Math.max(1, H / sw);

        int[] px = new int[mw * mh];
        // ★ v2.9.1 性能修复（交叉审查）★
        //
        // 原来逐点 src.getPixel(sx, sy)：3008×1880 的截图约 22.6 万次 JNI 调用，
        // 大屏机上实测可占几百毫秒到 1 秒，而这步发生在时间预算的检查之外，
        // 会让「20 秒预算是硬上限」的承诺打折扣。
        //
        // 改成按行取：先取整行（宽 W 的 int[]），再从行里按步长挑采样点 ——
        // JNI 次数从 22.6 万降到 mh 次（约 470 次），同时复用同一块行缓冲。
        int[] row = new int[W];
        for (int y = 0; y < mh; y++) {
            int sy = Math.min(y * sw, H - 1);
            src.getPixels(row, 0, W, 0, sy, W, 1);
            int base = y * mw;
            for (int x = 0; x < mw; x++) {
                int sx = Math.min(x * sw, W - 1);
                px[base + x] = row[sx];
            }
        }

        boolean[] dark = new boolean[mw * mh];
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = (p >> 16) & 0xFF, gg = (p >> 8) & 0xFF, b = p & 0xFF;
            int lum = (r * 299 + gg * 587 + b * 114) / 1000;
            dark[i] = lum < 95;
        }

        int[] label = new int[mw * mh];
        int[] stack = new int[mw * mh];
        int cur = 0;
        List<Rect> out = new ArrayList<>();

        // ★ 阈值改为相对屏幕短边计算（2026-10-30 修）★
        //
        // 旧写法的两个 bug：
        //  1. 采样率按「宽度/320」算，横屏时宽度变大 → 降采样更狠，
        //     按钮在采样图上只剩 20 多像素，直接被 minW=40 拒掉；
        //  2. 高度阈值用 mh/40，横屏 mh 很小 → 范围窄到 [12..19]，
        //     实际按钮高度对不上，全部漏过。
        // 结果就是横屏下永远「找不到标签按钮」。
        //
        // 现在改成：采样率按短边算（保证精度一致），
        // 阈值按 min(短边) 的比例给宽松区间，再靠长宽比和填充率筛。
        // 阈值按短边比例给。经 6 种分辨率验算（手机/平板 × 横竖屏）：
        //   竖屏时按钮在采样图上只有 42~43px 宽，原来的 shortSide/8=45 会漏检，
        //   所以放宽到 /10（40px 仍能进），上限也放宽确保大按钮不被误杀。
        int shortSide = Math.min(mw, mh);
        int minW = Math.max(6, shortSide / 10);      // 按钮宽度 ≥ 短边 1/10
        int maxW = (int) (shortSide * 0.85f);        // ≤ 短边 85%
        int minH = Math.max(4, shortSide / 45);      // 高度 ≥ 短边 1/45
        int maxH = Math.max(8, shortSide / 4);       // ≤ 短边 1/4

        for (int s = 0; s < dark.length; s++) {
            if (!dark[s] || label[s] != 0) continue;
            cur++;
            int sp = 0;
            stack[sp++] = s;
            label[s] = cur;
            int minx = mw, miny = mh, maxx = 0, maxy = 0, cnt = 0;
            while (sp > 0) {
                int idx = stack[--sp];
                int x = idx % mw, y = idx / mw;
                cnt++;
                if (x < minx) minx = x;
                if (y < miny) miny = y;
                if (x > maxx) maxx = x;
                if (y > maxy) maxy = y;
                if (x > 0) { int n = idx - 1; if (dark[n] && label[n] == 0) { label[n] = cur; stack[sp++] = n; } }
                if (x < mw - 1) { int n = idx + 1; if (dark[n] && label[n] == 0) { label[n] = cur; stack[sp++] = n; } }
                if (y > 0) { int n = idx - mw; if (dark[n] && label[n] == 0) { label[n] = cur; stack[sp++] = n; } }
                if (y < mh - 1) { int n = idx + mw; if (dark[n] && label[n] == 0) { label[n] = cur; stack[sp++] = n; } }
            }
            int bw = maxx - minx + 1, bh = maxy - miny + 1;
            if (bw < minW || bw > maxW) continue;
            if (bh < minH || bh > maxH) continue;
            double aspect = bw / (double) bh;
            if (aspect < 1.4 || aspect > 7.0) continue;
            double fill = cnt / (double) (bw * bh);
            if (fill < 0.50) continue;
            out.add(new Rect(minx * sw, miny * sw, (maxx + 1) * sw, (maxy + 1) * sw));
        }

        List<Rect> kept = new ArrayList<>();
        for (Rect a : out) {
            boolean dup = false;
            for (Rect b : kept) {
                Rect i = new Rect(a);
                if (i.intersect(b)) {
                    double ov = i.width() * (double) i.height()
                            / (a.width() * (double) a.height());
                    if (ov > 0.7) { dup = true; break; }
                }
            }
            if (!dup) kept.add(a);
        }
        java.util.Collections.sort(kept, new java.util.Comparator<Rect>() {
            public int compare(Rect a, Rect b) {
                int ay = a.top / 40, by = b.top / 40;
                if (ay != by) return ay - by;
                return a.left - b.left;
            }
        });
        return kept;
    }

    // ------------------------------------------------------------ 文本纠正
    /**
     * 公招界面可能出现、且干员数据里存在的标签（29 个）。
     *
     * 来源核对（2026-10-30）：官方 gacha_table.json 的 gachaTags 有 31 个，
     * 但其中「男性干员」「女性干员」只存在于 tag 表，从未出现在
     * recruitDetail（实际招募池说明）里，也不会在公招界面作为可选标签出现。
     * MAA 干员数据同样没有使用这两个标签。
     *
     * 所以这里只保留真正会出现的 29 个 —— 少两个标签能让匹配更准：
     * 编辑距离容错的候选池更小，误判概率更低。
     */
    public static final String[] VALID = {
            // 职业（8）
            "近卫干员", "狙击干员", "重装干员", "医疗干员", "辅助干员",
            "术师干员", "特种干员", "先锋干员",
            // 部署位置（2）
            "近战位", "远程位",
            // 特性标签（16）
            "控场", "爆发", "治疗", "支援", "费用回复", "输出", "生存",
            "群攻", "防护", "减速", "削弱", "快速复活", "位移", "召唤",
            "支援机械", "元素",
            // 干员资历（3）
            "新手", "资深干员", "高级资深干员"
    };

    /**
     * ★ v2.9.2 修复（交叉审查）：按行分段匹配 ★
     *
     * ocr() 刻意用 '\n' 分隔识别出来的行（见那里的注释），目的是防止
     * 「近战」+「位移」两行被拼成「近战位移」而 contains 出假阳性「近战位」。
     * 但这里的 `replaceAll("[\\s\\u00A0]","")` 会把 '\n' 一起抹掉 ——
     * 防拼接的努力在下游被抵消了。
     *
     * 现在先按换行切段，逐段做原来的单段匹配（normalizeOne），
     * 取第一个能对上的段；这样跨行的拼接不再可能发生。
     * 只有一段时行为与旧版完全一致。
     */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String[] lines = raw.split("[\\r\\n]+");
        int nonEmpty = 0;
        for (String ln : lines) if (ln != null && ln.trim().length() > 0) nonEmpty++;
        if (nonEmpty <= 1) return normalizeOne(raw);
        for (String ln : lines) {
            if (ln == null) continue;
            String hit = normalizeOne(ln);
            if (hit != null) return hit;
        }
        return null;
    }

    /** 单段（不含换行）的规整 + 纠错 */
    private static String normalizeOne(String raw) {
        if (raw == null) return null;
        String t = raw.replaceAll("[\\s\\u00A0]", "")
                .replace("（", "").replace("）", "")
                .replace("(", "").replace(")", "");
        if (t.isEmpty()) return null;

        // ① 完全相等
        for (String v : VALID) if (t.equals(v)) return v;

        // ② 等长 + 编辑距离 ≤1 —— 必须排在 contains 之前
        //
        // 反例：OCR 把「支援机械」认成「支援机槭」（4 字对 3 个），
        // 若先跑 contains 会命中 2 字的「支援」并直接返回，长标签被短标签吃掉。
        String bestSame = null;
        int bestSameDist = 99;
        for (String v : VALID) {
            if (v.length() != t.length()) continue;
            int d = editDistance(t, v);
            if (d < bestSameDist) { bestSameDist = d; bestSame = v; }
        }
        if (bestSameDist <= 1 && bestSame != null) return bestSame;

        // ③ 整串包含候选标签（如「位移干员」→「位移」）
        //
        // 这里必须先挑「最长」的命中，不能用 VALID 的声明顺序。
        // 反例：「费用回复治疗」同时包含「费用回复」和「治疗」，
        // 按声明顺序会先命中「治疗」，把 4 字标签错判成 2 字。
        String longest = null;
        for (String v : VALID) {
            if (t.length() > v.length() && t.contains(v)) {
                if (longest == null || v.length() > longest.length()) longest = v;
            }
        }
        if (longest != null) return longest;

        // ④ 候选标签包含整串（OCR 只认出了标签的一部分，如「费用回」）
        //
        // ★ v2.9.1 修复（交叉审查发现的假阳性）★
        // 原来这里同样取「最长」，于是 2 字残片会被放大成最长的长标签：
        //   t="干员" → 同时命中「近卫干员」「高级资深干员」… → 取最长 → 「高级资深干员」
        //   t="资深" → 「高级资深干员」
        // 结果是裁到半个按钮、或识别只剩公共尾缀时，凭空给出 6★ 结论 ——
        // 这比「没认出来」严重得多（会让用户去选一个根本不保底的组合）。
        //
        // 现在要求「唯一命中」：只要有两个以上候选能容纳它，就说明信息不足，
        // 直接放弃（返回 null），交给后面的规则或整屏兜底。
        // 这与本文件既有的原则一致：宁可少认，不能认错。
        String longest2 = null;
        int hit2 = 0;
        for (String v : VALID) {
            if (t.length() >= 2 && t.length() < v.length() && v.contains(t)) {
                hit2++;
                if (longest2 == null || v.length() > longest2.length()) longest2 = v;
            }
        }
        if (hit2 == 1) return longest2;

        // ⑤ 编辑距离 ≤1 的近似匹配
        String bestEdit = null;
        int bestDist = 99;
        for (String v : VALID) {
            if (Math.abs(t.length() - v.length()) > 1) continue;
            int d = editDistance(t, v);
            if (d < bestDist) { bestDist = d; bestEdit = v; }
        }
        if (bestDist <= 1 && bestEdit != null) return bestEdit;

        // ⑥ ★ 子序列匹配（2026-10-30 加，专治「费用回复」）★
        //
        // OCR 读 4 字长标签时，最容易「抽掉中间一个字」：
        //   费用回复 → 「费回」/「用复」/「费复」
        // 这类输出编辑距离是 2，上面所有规则都够不到，直接返回 null。
        // 但它们的字符是原文的**有序子序列**，这个特征很稳定。
        // 限定：目标标签 ≥3 字、输入 ≥2 字、且输入字符全部按序出现在标签里。
        //
        // ★ v2.9.1：同样要求唯一命中 ★
        // 「干员」「位」这类公共残片能同时是多个标签的子序列，取最长同样会
        // 凭空造出「高级资深干员」。命中不唯一就放弃。
        if (t.length() >= 2) {
            String bestSub = null;
            int hitSub = 0;
            for (String v : VALID) {
                if (v.length() < 3) continue;
                if (v.length() <= t.length()) continue;
                if (isSubsequence(t, v)) {
                    hitSub++;
                    if (bestSub == null || v.length() > bestSub.length()) bestSub = v;
                }
            }
            if (hitSub == 1) return bestSub;
        }

        // ⑦ 兜底：字符重合度
        String best = null;
        double bestScore = 0;
        for (String v : VALID) {
            double s = jaccard(t, v) - Math.abs(t.length() - v.length()) * 0.04;
            if (s > bestScore) { bestScore = s; best = v; }
        }
        return bestScore >= 0.55 ? best : null;
    }

    /** a 是否为 b 的有序子序列（允许跳过中间字符） */
    private static boolean isSubsequence(String a, String b) {
        int i = 0;
        for (int j = 0; j < b.length() && i < a.length(); j++) {
            if (b.charAt(j) == a.charAt(i)) i++;
        }
        return i == a.length();
    }
    /** 编辑距离（Levenshtein），用于纠正 OCR 的单字误识 */
    private static int editDistance(String a, String b) {
        int n = a.length(), m = b.length();
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;
        for (int i = 1; i <= n; i++) {
            cur[0] = i;
            for (int j = 1; j <= m; j++) {
                int sub = prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                cur[j] = Math.min(Math.min(prev[j] + 1, cur[j - 1] + 1), sub);
            }
            int[] tmp = prev; prev = cur; cur = tmp;
        }
        return prev[m];
    }

    private static double jaccard(String a, String b) {
        Set<Character> sa = new LinkedHashSet<>(), sb = new LinkedHashSet<>();
        for (char c : a.toCharArray()) sa.add(c);
        for (char c : b.toCharArray()) sb.add(c);
        int inter = 0;
        for (char c : sa) if (sb.contains(c)) inter++;
        int uni = sa.size() + sb.size() - inter;
        return uni == 0 ? 0 : inter / (double) uni;
    }

    private TagFinder() {}
}
