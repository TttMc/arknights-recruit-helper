package com.ark.recruit;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 公招数据与组合求解。
 *
 * 规则（决定了怎么算）：
 *  · 一次最多选 3 个标签；
 *  · 一个组合的「必出」= 同时命中全部标签的干员中最低星级；
 *  · 必出 >= 4 才值得选（低于 4 会被 3 星保底拉下水）；
 *  · 高级资深干员必出 6★，资深干员必出 5★。
 */
public final class Recruit {

    public static final int MAX_TAGS = 3;

    public static final class Op {
        public final String name;
        public final int rarity;
        public final String[] tags;
        Op(String n, int r, String[] t) { name = n; rarity = r; tags = t; }
    }

    public static final class Combo {
        public final String[] tags;
        public final int guaranteed;
        public final int ceiling;
        public final int count;
        public final Map<Integer, List<String>> byRarity = new HashMap<>();
        public boolean hasRareTag;
        /** 3~5★ 池为空：该组合在 7:40 以上招不到人，只能用很短时长碰 1~2★ */
        public boolean wastedOnLongRun;
        /** 含「支援机械」标签：必出 1★ 支援机械干员，寻访拿不到，单独排序 */
        public boolean supportMech;
        /** 推荐时长，如 "7:40" / "9:00" */
        public String minTime = "9:00";
        /**
         * 该时长下的「时长保底」—— 即使标签全被划，也至少是这个星级。
         * 这才是真正的保底；标签命中属于运气加成。
         */
        public int timeFloor = 1;

        Combo(String[] t, int g, int c, Map<Integer, List<String>> byR) {
            tags = t;
            guaranteed = g;
            ceiling = c;
            int n = 0;
            for (List<String> l : byR.values()) n += l.size();
            count = n;
            byRarity.putAll(byR);
        }
        public String label() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < tags.length; i++) {
                if (i > 0) sb.append(" + ");
                sb.append(tags[i]);
            }
            return sb.toString();
        }

        /**
         * 人数文案。
         *
         * ★ v2.9 修复（原审查第 6 条）★
         * wastedOnLongRun 的组合（新手 / 支援机械）在 3~5★ 池里是空的，
         * count 统计的是**全池**人数，直接显示「8 人」会让用户以为是
         * 「拉到长时长后 8 人可选」，实际那个时长根本招不到这些人。
         * 所以这里显式标注范围，避免误读。
         *
         * ★ v2.9.2 ★ 范围改成按实际星级动态生成。原来一律写「仅1~2★」，
         * 但「支援机械」其实全是 1★、「新手」全是 2★，写「1~2★」不准确。
         */
        public String countText() {
            if (wastedOnLongRun) {
                if (guaranteed == ceiling) {
                    return count + " 人（仅" + STAR[guaranteed] + "）";
                }
                return count + " 人（仅" + STAR[guaranteed] + "~" + STAR[ceiling] + "）";
            }
            return count + " 人";
        }
    }

    /** 只用于 UI 高亮的「稀有标签」（含爆发/控场等，这些仍需时长过滤） */
    private static final String[] RARE_TAGS = {
            "高级资深干员", "资深干员", "支援机械", "爆发", "控场", "召唤", "位移"
    };

    /**
     * 真正的「资深系」标签 —— 只有这两个会直接把星级锁死，
     * 且 9:00 下不会被划掉。其余稀有标签（爆发/控场/召唤/位移/支援机械）
     * 必须按时长过滤 3~5★ 池来算必出，否则会被 1★ 支援机械拉低。
     */
    private static boolean isSeniorTag(String[] combo) {
        for (String t : combo) {
            if ("资深干员".equals(t) || "高级资深干员".equals(t)) return true;
        }
        return false;
    }

    public static final String[] STAR = {"", "1★", "2★", "3★", "4★", "5★", "6★"};

    private static List<Op> ops;
    private static Map<String, List<Op>> byTag;

    /** 从 XML 里抽出来的原始行（名字|稀有度|标签...） */
    public static void load(String[] raw) {
        // ★ v2.9.1（交叉审查）★
        // 原来直接往静态字段 ops/byTag 里灌，解析到一半抛异常（某行数据坏了、
        // 稀有度不是数字）就会留下**半初始化**的静态表，而异常在 Service 里被
        // catch 成一行 lastError，之后所有分析都基于残缺数据静默算出错误结论 ——
        // 这比直接崩更难发现。
        //
        // 现在先在局部变量里解析完，全部成功再一次性赋值；任何一行非法就整体放弃，
        // 宁可「数据加载失败」让用户看到，也不给半个数据集。
        List<Op> newOps = new ArrayList<>(raw.length);
        Map<String, List<Op>> newByTag = new HashMap<>();
        for (String line : raw) {
            String[] p = line.split("\\|");
            if (p.length < 3) continue;
            int rarity;
            try {
                rarity = Integer.parseInt(p[1].trim());
            } catch (NumberFormatException e) {
                throw new IllegalStateException("干员数据星级非法: " + line, e);
            }
            // 星级越界会让 STAR[] / STAR_TXT[] 之后越界，这里直接拒收
            if (rarity < 1 || rarity > 6) {
                throw new IllegalStateException("干员数据星级越界(1..6): " + line);
            }
            String[] tags = p[2].split(",");
            Op op = new Op(p[0], rarity, tags);
            newOps.add(op);
            for (String t : tags) {
                List<Op> l = newByTag.get(t);
                if (l == null) { l = new ArrayList<>(); newByTag.put(t, l); }
                l.add(op);
            }
        }
        ops = newOps;
        byTag = newByTag;
    }

    public static boolean isKnownTag(String t) {
        return byTag != null && byTag.containsKey(t);
    }

    /** 同时命中所有 tags 的干员，按星级降序 */
    public static List<Op> match(String[] tags) {
        List<Op> out = new ArrayList<>();
        if (tags == null || tags.length == 0 || byTag == null) return out;

        String[] sorted = tags.clone();
        Arrays.sort(sorted, new Comparator<String>() {
            public int compare(String a, String b) {
                int ca = byTag.containsKey(a) ? byTag.get(a).size() : 0;
                int cb = byTag.containsKey(b) ? byTag.get(b).size() : 0;
                return ca - cb;
            }
        });

        List<Op> pool = null;
        for (String t : sorted) {
            List<Op> l = byTag.get(t);
            if (l == null) return out;
            if (pool == null) {
                pool = new ArrayList<>(l);
            } else {
                List<Op> next = new ArrayList<>();
                for (Op o : pool) {
                    for (String ot : o.tags) {
                        if (ot.equals(t)) { next.add(o); break; }
                    }
                }
                pool = next;
            }
            if (pool.isEmpty()) return out;
        }
        if (pool != null) out.addAll(pool);
        Collections.sort(out, new Comparator<Op>() {
            public int compare(Op a, Op b) {
                if (a.rarity != b.rarity) return b.rarity - a.rarity;
                return a.name.compareTo(b.name);
            }
        });
        return out;
    }

    /** 枚举 1..maxTags 的所有组合并求解 */
    public static List<Combo> analyze(String[] tags, int maxTags) {
        List<String> uniq = new ArrayList<>();
        for (String t : tags) {
            if (t != null && t.length() > 0 && isKnownTag(t) && !uniq.contains(t)) uniq.add(t);
        }
        List<Combo> result = new ArrayList<>();
        int n = uniq.size();
        int kmax = Math.min(maxTags, n);

        for (int k = 1; k <= kmax; k++) {
            for (int[] idx : combinations(n, k)) {
                String[] combo = new String[k];
                for (int i = 0; i < k; i++) combo[i] = uniq.get(idx[i]);
                List<Op> full = match(combo);
                if (full.isEmpty()) continue;

                boolean rare = false;
                for (String t : combo) {
                    for (String rt : RARE_TAGS) if (rt.equals(t)) { rare = true; break; }
                }

                // ★ 关键修复（v1.4）★
                // 「必出」必须按 9:00 的实际可出池算，而不是全池。
                //
                // 原因：9:00 时 1★/2★ 根本刷不出来（官方 recruitRarityTable：
                // 7:40 起下限就是 3★）。旧版直接取全池最低星，
                // 于是「爆发」被 THRM-EX(1★, 支援机械+爆发) 拉成必出 1★，
                // 而实际上爆发在 9:00 只有刻刀(4★)/守林人(5★)/莱恩哈特(5★)，
                // 真实必出是 4★ —— 这正是用户报的那个 bug。
                //
                // 带资深/高级资深的组合单独处理：资深体系按下表的星级必得，
                // 不参与 3~5★ 的过滤（否则 6★ 会被过滤掉）。
                int lo, hi;
                Map<Integer, List<String>> byR = new HashMap<>();

                // 资深系优先：即使同时带支援机械，也按资深锁星级算
                if (isSeniorTag(combo)) {
                    // 资深系：标签直接锁星级，不用时长过滤
                    for (Op o : full) {
                        List<String> names = byR.get(o.rarity);
                        if (names == null) { names = new ArrayList<>(); byR.put(o.rarity, names); }
                        names.add(o.name);
                    }
                    lo = hasTag(combo, "高级资深干员") ? 6 : 5;
                    hi = maxRarity(full);
                } else {
                    // 普通组合：只算 3~5★ 池（9:00 的实际可出范围）
                    List<Op> band = new ArrayList<>();
                    for (Op o : full) {
                        if (o.rarity >= 3 && o.rarity <= 5) band.add(o);
                    }
                    if (band.isEmpty()) {
                        // 3~5★ 池为空：这个组合在 7:40 以上根本招不到人
                        // （典型是「支援机械」「新手」这种只有 1~2★ 的标签）。
                        // 不丢弃，标记出来让用户知道「该标签对 9:00 毫无意义」。
                        for (Op o : full) {
                            List<String> names = byR.get(o.rarity);
                            if (names == null) { names = new ArrayList<>(); byR.put(o.rarity, names); }
                            names.add(o.name);
                        }
                        // ★ v2.9.2 修复（交叉审查）★
                        // 原来 lo 写死 1。对「支援机械」是对的（8 位全是 1★），
                        // 但「新手」命中的 12F/夜刀/巡林者/杜林/黑角全是 2★，
                        // 面板会显示「必出 1★ / 最高 2★」—— 自相矛盾，也违反了
                        // 本类核心定义「必出 = 命中全部标签的干员中最低星」。
                        // 现在按实际成员取最低星。
                        lo = minRarity(full);
                        hi = maxRarity(full);
                        Combo ce = new Combo(combo, lo, hi, byR);
                        ce.hasRareTag = rare;
                        ce.wastedOnLongRun = true;
                        ce.supportMech = !isSeniorTag(combo) && hasTag(combo, "支援机械");
                        ce.minTime = calcMinTime(combo, full, lo);
                        ce.timeFloor = floorOfTime(ce.minTime);
                        result.add(ce);
                        continue;
                    }
                    lo = 99; hi = 0;
                    for (Op o : band) {
                        if (o.rarity < lo) lo = o.rarity;
                        if (o.rarity > hi) hi = o.rarity;
                        List<String> names = byR.get(o.rarity);
                        if (names == null) { names = new ArrayList<>(); byR.put(o.rarity, names); }
                        names.add(o.name);
                    }
                }

                Combo c = new Combo(combo, lo, hi, byR);
                c.hasRareTag = rare;
                c.supportMech = !isSeniorTag(combo) && hasTag(combo, "支援机械");
                c.minTime = calcMinTime(combo, full, lo);
                c.timeFloor = floorOfTime(c.minTime);
                result.add(c);
            }
        }

        Collections.sort(result, new Comparator<Combo>() {
            public int compare(Combo a, Combo b) {
                int ra = rankOf(a), rb = rankOf(b);
                if (ra != rb) return ra - rb;
                if (a.ceiling != b.ceiling) return b.ceiling - a.ceiling;
                if (a.count != b.count) return b.count - a.count;
                return a.tags.length - b.tags.length;
            }
        });
        return result;
    }

    /** 支援机械组合下的干员名（都是 1★） */
    private static String mechNames(Combo c) {
        List<String> all = new ArrayList<>();
        for (List<String> l : c.byRarity.values()) all.addAll(l);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < all.size(); i++) {
            if (i > 0) sb.append("/");
            sb.append(all.get(i));
        }
        return sb.toString();
    }

    private static boolean hasTag(String[] combo, String tag) {
        for (String t : combo) if (tag.equals(t)) return true;
        return false;
    }

    private static int maxRarity(List<Op> ops) {
        int hi = 0;
        for (Op o : ops) if (o.rarity > hi) hi = o.rarity;
        return hi;
    }

    /** 成员里的最低星级。空列表返回 1（调用方已保证非空，这里只是兜底不抛异常） */
    private static int minRarity(List<Op> ops) {
        int lo = 99;
        for (Op o : ops) if (o.rarity < lo) lo = o.rarity;
        return lo == 99 ? 1 : lo;
    }

    /**
     * 推荐时长。
     *
     * ★ 关键认知（2026-10-30 用户纠正）★
     * 玩家选的标签只是「期望」—— 系统有权把它们划掉，
     * 划掉之后你拿到的是「该时长下整个池子」里的随机干员。
     *
     * 所以：「必出 N 星」这个保底，来源只能是时长，不是标签。
     *   · 1:00 → 池子是 1~4★，标签全被划 → 保底 1★
     *   · 4:00 → 池子是 2~5★，标签全被划 → 保底 2★
     *   · 7:40 → 池子是 3~5★，标签全被划 → 保底 3★
     *
     * 标签的作用是「在保底之上再缩小范围」，属于加分项，不算保底。
     * 因此推荐时长的规则就很简单：
     *   · 想靠资深锁 5★/6★ → 必须 9:00（短了标签会被划）
     *   · 其余一律给「覆盖该组合全部成员所需的最短门槛」
     *     —— 只要这个门槛下没有比组合最低星更低的干扰项，
     *        就不必再加时间（7:40 和 9:00 上限都是 5★，加时间不加星）
     *
     * 换句话说：宁可选 7:40 这种「标签被划也不亏」的时长，
     * 而不是 1:00 赌标签全中。
     */
    static String calcMinTime(String[] combo, List<Op> full, int targetGuaranteed) {
        // 资深系：9:00 才不会被划掉
        if (hasTag(combo, "高级资深干员") || hasTag(combo, "资深干员")) return "9:00";

        int[] lows = {1, 2, 3};
        int[] highs = {4, 5, 5};
        String[] labels = {"1:00", "4:00", "7:40"};

        // 找出「能容纳该组合全部成员的最低时长档」，记为起点 needIdx。
        // 语义：取组合里最高星成员也落在该档星级上限内的最早一档；
        // 三档都装不下（例如带 6★）时取最高档 7:40 作为起点。
        int needIdx = 2;
        for (int i = 0; i < 3; i++) {
            boolean covered = true;
            for (Op o : full) {
                if (o.rarity > highs[i]) { covered = false; break; }
            }
            if (covered) { needIdx = i; break; }
        }

        // 从 needIdx 起的门槛里，挑第一个「被划也不亏」的：
        // 即该门槛的池子保底(lows[i]) 已达到 targetGuaranteed
        for (int i = needIdx; i < 3; i++) {
            if (lows[i] >= targetGuaranteed) return labels[i];
        }

        // 保底靠时长达不到目标（只能靠标签）：
        // 那就选「最优性价比」的门槛 —— 7:40 是免费提升（不加星也把 1~2★ 过滤了）
        return "7:40";
    }

    /** 时长 → 该时长下「标签全被划」时的保底星级 */
    static int floorOfTime(String t) {
        if ("9:00".equals(t) || "7:40".equals(t)) return 3;
        if ("4:00".equals(t)) return 2;
        return 1;
    }

    /**
     * 排序权重（数字越小越靠前）。
     *
     * 用户指定：6★ 5★ 4★ 支援机械 3★
     *
     * 支援机械单独夹在 4★ 和 3★ 之间 —— 它是 1★，
     * 但寻访拿不到，只能靠公招，「支援机械」标签是唯一途径，
     * 所以对它来说价值不等于 1★，不该跟 3★ 一起沉底。
     */
    private static int rankOf(Combo c) {
        if (c.supportMech) return 40;          // 夹在 4★(30) 和 3★(50) 之间
        int g = c.guaranteed;
        if (g >= 6) return 0;
        if (g == 5) return 10;
        if (g == 4) return 30;
        if (g == 3) return 50;
        if (g == 2) return 70;
        return 90;                              // 1★ 及以下
    }

    private static List<int[]> combinations(int n, int k) {
        List<int[]> all = new ArrayList<>();
        int[] idx = new int[k];
        for (int i = 0; i < k; i++) idx[i] = i;
        while (true) {
            all.add(idx.clone());
            int pos = k - 1;
            while (pos >= 0 && idx[pos] == n - k + pos) pos--;
            if (pos < 0) break;
            idx[pos]++;
            for (int j = pos + 1; j < k; j++) idx[j] = idx[j - 1] + 1;
        }
        return all;
    }

    /**
     * 招募时长表（官方 gacha_table.json 的 recruitRarityTable 实测值）
     *
     *  1:00:00 起  可能 1~4★
     *  4:00:00 起  可能 2~5★   ← 下限从 1★ 提到 2★
     *  7:40:00 起  可能 3~5★   ← 下限从 2★ 提到 3★（就是常说的「锁 3 星保底」）
     *  9:00:00 且带「资深干员」（不含高级资深）  → 必得 5★
     *  9:00:00 且带「高级资深干员」              → 必得 6★
     *
     * 关键结论：想吃到「必出 3★ 及以上」的保底，时长必须拉满到 7:40 以上；
     * 想拿 5★/6★，只有资深/高级资深标签 + 9:00 才行，时长不够会被划掉。
     */
    public static final int MIN_LEN_BOOST_2 = 4 * 60;    // 4:00 → 下限 2★
    public static final int MIN_LEN_BOOST_3 = 7 * 60 + 40; // 7:40 → 下限 3★
    public static final int MIN_LEN_RARE = 9 * 60;        // 9:00 → 资深系标签生效

    /** 某个「必出星级」需要的最短时长（分钟）；-1 表示仅靠时长做不到 */
    public static int requiredMinutes(int guaranteed) {
        if (guaranteed <= 1) return 60;          // 1:00 就够，但下限仍是 1★
        if (guaranteed == 2) return MIN_LEN_BOOST_2;
        if (guaranteed == 3) return MIN_LEN_BOOST_3;
        return -1;                                // 4★+ 必须靠标签，时长做不到
    }

    /** 分钟 → "7:40" */
    public static String fmtMinutes(int m) {
        if (m < 0) return "—";
        return (m / 60) + ":" + String.format("%02d", m % 60);
    }

    /**
     * 给一个组合算出「应该挂多长时长」和对应说明。
     *
     * 逻辑：
     *  · 有「高级资深干员」→ 9:00（必出 6★）
     *  · 有「资深干员」   → 9:00（必出 5★）
     *  · 否则按必出星级反查最短时长：
     *      必出 3★ → 7:40（否则 1~2★ 会把结果拉下来）
     *      必出 2★ → 4:00
     *      必出 1★ → 1:00 即可，但建议 7:40 保 3★
     */
    public static String advice(Combo c) {
        if (c.wastedOnLongRun) {
            return "该标签在 7:40 以上招不到人，只能短时长碰 1~2★";
        }
        boolean hasHigh = false, hasSenior = false;
        for (String t : c.tags) {
            if ("高级资深干员".equals(t)) hasHigh = true;
            if ("资深干员".equals(t)) hasSenior = true;
        }
        if (hasHigh) return "9:00（必得 6★）";
        if (hasSenior) return "9:00（必得 5★）";

        int need = requiredMinutes(c.guaranteed);
        if (need < 0) {
            // 必出 4★+ 但没有资深标签（如「爆发」「控场」）：时长给不出 4★ 保底，
            // 9:00 与 7:40 的池子上限一样都是 5★，多花时间不加星。
            //
            // ★ v2.9.1（交叉审查）★ 原来这里兜底返回 "9:00"，与 calcMinTime
            // 对同一组合给出的 "7:40" 直接矛盾（而 minTime 才是面板实际显示的值）。
            // 现在统一以 minTime 为准。
            return c.minTime;
        }
        if (c.guaranteed >= 3) return fmtMinutes(need) + "（守住必出 3★）";
        if (c.guaranteed == 2) return fmtMinutes(need);
        return "7:40（建议拉满保 3★）";
    }

    /** 时长表说明，面板底部常驻显示 */
    public static String durationTable() {
        StringBuilder sb = new StringBuilder();
        sb.append("招募时长 → 可能出现星级\n");
        sb.append("  1:00  1~4★\n");
        sb.append("  4:00  2~5★  (下限提到 2★)\n");
        sb.append("  7:40  3~5★  (下限提到 3★)\n");
        sb.append("  9:00  3~5★  ★前提是带资深标签才需要\n");
        sb.append("\n");
        sb.append("要点\n");
        sb.append("  · 标签只是「期望」，系统可能把标签划掉\n");
        sb.append("  · 划掉后按整个池子随机，所以真正的下限来自时长\n");
        sb.append("    例：减速+治疗 挂 1:00，标签保住才出波登可(4★)，\n");
        sb.append("        被划就落回 1~2★；挂 7:40 则稳出 3★ 起\n");
        sb.append("  · 无稀有标签时 7:40 和 9:00 上限都是 5★，\n");
        sb.append("    拉 9:00 只多花龙门币和时间，没必要\n");
        sb.append("  · 资深干员 / 高级资深 必须 9:00，否则标签会被划掉\n");
        sb.append("  · 资深系是唯一「标签不会被划」的情况\n");
        sb.append("    · 资深干员 → 必得 5★\n");
        sb.append("    · 高级资深 → 必得 6★\n");
        return sb.toString();
    }

    /**
     * 推荐用的时长（分钟）。
     *
     * 默认按「满足时长条件」计算 —— 也就是拉到 9:00，
     * 这样 1★/2★ 根本不会出现在池里，必出星级才反映真实上限。
     * 少数情况才降低要求：
     *  · 资深系标签 → 必须 9:00 才生效
     *  · 必出只能到 3★（比如「治疗」「减速」）→ 7:40 就够，不用拉满
     *  · 3~5★ 池为空的标签（新手/支援机械）→ 1:00 碰运气
     */
    public static String recommendTime(Combo c) {
        return c.minTime;
    }

    /**
     * 一行式摘要：组合 + 时长 + 保底 + 瞄准目标
     *
     * 措辞刻意区分两件事：
     *   时间长保底 —— 标签全被划也至少拿到（来自时长，确定）
     *   瞄准 —— 标签没被划时的目标干员（来自标签，有风险）
     */
    public static String summaryLine(Combo c) {
        StringBuilder sb = new StringBuilder();
        sb.append(c.label()).append("  ").append(recommendTime(c));

        if (hasTag(c.tags, "高级资深干员") || hasTag(c.tags, "资深干员")) {
            // 资深系是唯一「标签不会被划」的情况 → 真·必得
            sb.append("  必得").append(STAR[c.guaranteed]);
        } else if (c.timeFloor >= c.guaranteed) {
            // 时长的下限已经盖住目标，标签属于锦上添花
            sb.append("  稳出").append(STAR[c.timeFloor]);
        } else {
            // ★ 关键措辞：标签命中才有高星，被划就掉到时长保底。
            // 不写「必出」，因为系统有权划掉标签。
            sb.append("  稳出").append(STAR[c.timeFloor])
              .append("，标签未被划才有").append(STAR[c.guaranteed]);
        }
        sb.append("  共").append(c.countText());
        return sb.toString();
    }

    /**
     * 生成给人看的报告。
     *
     * 布局（2026-10-30 按用户要求调整）：
     *   ① 顶部结论 —— 只有「组合 + 时长 + 必出」，一眼看完
     *   ② 明细 —— 所有组合按必出星级从高到低排，每个组合内干员也是 6★→1★
     *   ③ 时长表 —— 参考信息放最后
     */
    public static String render(String[] tags, List<Combo> combos, int minGood) {
        List<String> tl = new ArrayList<>();
        for (String t : tags) if (isKnownTag(t)) tl.add(t);

        List<Combo> good = new ArrayList<>();
        List<Combo> bad = new ArrayList<>();
        List<Combo> mech = new ArrayList<>();
        List<Combo> wasted = new ArrayList<>();
        for (Combo c : combos) {
            if (c.supportMech) mech.add(c);              // 支援机械单独成组
            else if (c.wastedOnLongRun) wasted.add(c);
            else if (c.guaranteed >= minGood) good.add(c);
            else bad.add(c);
        }

        // combos 进来时已按「必出降序、最高降序」排好，
        // good / bad 是保序切分，所以各自仍是降序。

        StringBuilder sb = new StringBuilder();

        // ============ ① 顶部结论 ============
        sb.append("【结论】\n");
        if (!good.isEmpty()) {
            int shown = 0;
            for (Combo c : good) {
                if (shown++ >= 3) break;
                sb.append("第").append(shown).append("选  ").append(summaryLine(c)).append("\n");
            }
            if (good.size() > 3) {
                sb.append("     另 ").append(good.size() - 3).append(" 个 4★ 以上组合见下方\n");
            }
        } else {
            Combo top = null;
            if (!bad.isEmpty()) top = bad.get(0);
            if (top != null) {
                sb.append("无 4★ 以上组合。最好的一个是：\n");
                sb.append("     ").append(summaryLine(top)).append("\n");
            } else {
                sb.append("这组标签里没有可用组合。\n");
            }
        }
        if (!mech.isEmpty()) {
            Combo m = mech.get(0);
            // ★ v2.9.1：口径对齐 ★
            // 支援机械不是资深系，标签同样会被划掉，所以不能写「必得」；
            // 时长也用组合自己的 minTime，而不是硬编码 1:00。
            sb.append("※ 支援机械：" + m.label() + " → " + recommendTime(m) + " 目标")
              .append(mechNames(m))
              .append("（1★，寻访拿不到，只能公招）\n");
        }
        if (!wasted.isEmpty()) {
            sb.append("不推荐：");
            for (int i = 0; i < wasted.size(); i++) {
                if (i > 0) sb.append("、");
                sb.append(wasted.get(i).label());
            }
            sb.append("（这些标签在长时长下池里只有 1~2★）\n");
        }
        sb.append("\n");

        // ============ ② 明细（必出星级从高到低）============
        // 标题写的是「全部组合」，那就必须真的全 —— 原来漏了 wasted 类，
        // 只有「新手」这类标签时会先列「不推荐：新手」然后明细写「（无）」，自相矛盾。
        sb.append("【全部组合 · 按必出星级从高到低】\n");
        sb.append("———————————————\n");
        if (!good.isEmpty()) {
            for (Combo c : good) appendCombo(sb, c, 8);
        }
        if (!mech.isEmpty()) {
            for (Combo c : mech) appendCombo(sb, c, 8);
        }
        if (!bad.isEmpty()) {
            for (Combo c : bad) appendCombo(sb, c, 5);
        }
        if (!wasted.isEmpty()) {
            for (Combo c : wasted) appendCombo(sb, c, 5);
        }
        if (good.isEmpty() && mech.isEmpty() && bad.isEmpty() && wasted.isEmpty()) {
            sb.append("（无）\n\n");
        }

        // ============ ③ 参考 ============
        sb.append("【时长参考】\n");
        sb.append(durationTable());
        return sb.toString();
    }

    /**
     * 渲染一个组合。
     *
     * 显示顺序：星级降序（6★ → 1★），因为高星才真正关心，
     * 低星只是「可能被划出来」的陪跑。
     */
    private static void appendCombo(StringBuilder sb, Combo c, int perRarity) {
        sb.append(c.label())
          .append("   ").append(recommendTime(c))
          .append(hasTag(c.tags, "高级资深干员") || hasTag(c.tags, "资深干员")
                  ? "   必得" + STAR[c.guaranteed]
                  : "   稳出" + STAR[c.timeFloor] + " / 未划才有" + STAR[c.guaranteed])
          .append(" / 最高").append(STAR[c.ceiling])
          .append(" / ").append(c.countText())
          .append(c.hasRareTag ? " *" : "").append("\n");

        List<Integer> rs = new ArrayList<>(c.byRarity.keySet());
        Collections.sort(rs);
        Collections.reverse(rs);
        for (int r : rs) {
            List<String> names = c.byRarity.get(r);
            sb.append("   ").append(STAR[r]).append(" ");
            for (int i = 0; i < names.size() && i < perRarity; i++) {
                if (i > 0) sb.append("、");
                sb.append(names.get(i));
            }
            if (names.size() > perRarity) sb.append(" …+").append(names.size() - perRarity);
            sb.append("\n");
        }
        sb.append("\n");
    }
    private Recruit() {}
}
