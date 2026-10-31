package com.ark.recruit;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 结果面板构建器。
 *
 * 严格按 DESIGN.md 的层级：
 *   ① 顶栏     标题 + 标签数 + ✕
 *   ② 结论区   凹槽内 ≤3 张推荐卡 + 支援机械条 + 不推荐一行
 *   ③ 明细区   按星级分组的组合行（可滚动，weight=1）
 *   ④ 底栏     输入框 + 重算/重截/关闭
 *
 * 语义可视化（不用读字就能分辨稳/赌）：
 *   实心徽章 = 时长保底（标签被划也不亏）
 *   虚线徽章 = 标签命中才有（有风险）
 *   「必得」   = 资深系（唯一不会被划）
 */
class PanelBuilder {

    /** 公招界面的标签槽位数量（固定 5 个） */
    static final int SLOT_COUNT = 5;

    interface Callback {
        void onRecalc(String manualTags);
        void onRecapture();
        void onClose();
    }

    private final Context ctx;
    private final Callback cb;
    private final List<Recruit.Combo> combos;
    private final List<String> tags;

    /**
     * 识别可靠性提示（可为空）。
     *
     * ★ v2.9 新增（原审查第 4 条）★
     * 定位全靠「多行等距、同列起点、等高」这套几何假设，UI 改版或分屏下
     * 假设失效时，pickTagGroup 仍会挑出一组送进 OCR —— 结果可能是错的，
     * 但面板照常显示「第1选 XX」。这是最危险的情况：错得不明显。
     * 所以 TagFinder 一旦判定 uncertain / 有 note，就在这里显式警告。
     */
    private final String warning;

    /** 明细区里哪些组合已展开（key 用 label） */
    private final java.util.Set<String> expanded = new java.util.HashSet<>();

    PanelBuilder(Context ctx, List<Recruit.Combo> combos, List<String> tags, Callback cb) {
        this(ctx, combos, tags, cb, null);
    }

    PanelBuilder(Context ctx, List<Recruit.Combo> combos, List<String> tags,
                 Callback cb, String warning) {
        this.ctx = ctx;
        this.combos = combos;
        this.tags = tags;
        this.cb = cb;
        this.warning = warning;
    }

    /** 当前是否横屏（游戏界面是横屏的） */
    private boolean landscape() {
        android.util.DisplayMetrics m = ctx.getResources().getDisplayMetrics();
        return m.widthPixels > m.heightPixels;
    }

    View build() {
        LinearLayout root = Ui.col(ctx);
        root.setBackground(new BevelDrawable(Ui.PANEL, Ui.LINE2,
                Ui.dp(ctx, 1), Ui.dp(ctx, 10), Ui.dp(ctx, 2)));

        // 顶栏与底栏固定，中间整体可滚动。
        //
        // ★ v2.9.1（交叉审查）★
        // 原来是「顶栏固定 + 结论固定 + 明细 weight=1 内嵌 ScrollView + 时长固定 +
        // 底栏固定」，只在明细区留弹性。横屏时卡片 maxH 只有屏高的 88%（1080 高的
        // 手机上约 345dp），结论+时长+底栏这些固定部分已经超过 400dp，明细被压成 0、
        // 底栏「重算/重截/关闭」直接被裁到卡片外面看不见 —— 面板等于没法用。
        //
        // 现在只固定顶栏和底栏，其余全部塞进一个外层 ScrollView：
        // 内容再多也只是滚动，任何屏幕高度都不会丢按钮。
        root.addView(buildTopBar());
        root.addView(Ui.divider(ctx, Ui.LINE));

        ScrollView outer = new ScrollView(ctx);
        outer.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        outer.setVerticalScrollBarEnabled(false);

        LinearLayout body = Ui.col(ctx);
        if (warning != null && warning.length() > 0) body.addView(buildWarningBar());
        body.addView(buildConclusion());

        // 明细（不再内嵌 ScrollView：外层已经能滚，嵌套滚动反而更难操作）
        LinearLayout detail = Ui.col(ctx);
        detail.setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 10), Ui.dp(ctx, 14), Ui.dp(ctx, 6));
        detail.addView(sectionHeader("全部组合 · 按必出星级"));
        LinearLayout list = Ui.col(ctx);
        fillDetailList(list);
        detail.addView(list);
        body.addView(detail);

        body.addView(buildDurationSection());
        outer.addView(body);
        root.addView(outer);

        root.addView(Ui.divider(ctx, Ui.LINE));
        root.addView(buildToolbar());
        return root;
    }

    // ------------------------------------------------------------ 顶栏
    /**
     * 顶栏：标题 + 「识别到的标签」横条 + ✕
     *
     * 标签条是这次新增的关键交互：
     * 把 OCR 认出来的 5 个标签原样显示出来，用户一眼就能对：
     * 少了一个 / 认错了，点「重截」立刻再抓一次。
     * 每个标签还标了状态（识别到 / 未识别），没识别满 5 个时用青色提示。
     */
    private View buildTopBar() {
        LinearLayout wrap = Ui.col(ctx);
        wrap.setBackgroundColor(Ui.PANEL);

        // ---- 第一行：标题 + ✕ ----
        LinearLayout bar = Ui.row(ctx);
        bar.setPadding(Ui.dp(ctx, 14), 0, Ui.dp(ctx, 6), 0);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 44)));

        View accent = new View(ctx);
        accent.setBackgroundColor(Ui.AMBER);
        accent.setLayoutParams(new LinearLayout.LayoutParams(
                Ui.dp(ctx, 3), Ui.dp(ctx, 18)));
        LinearLayout.LayoutParams aLp = (LinearLayout.LayoutParams) accent.getLayoutParams();
        aLp.rightMargin = Ui.dp(ctx, 10);
        bar.addView(accent);

        TextView title = Ui.tv(ctx, "公招组合预测", 15f, Ui.TEXT);
        title.setTypeface(Ui.med());
        bar.addView(title);

        TextView meta = Ui.tv(ctx, combos.size() + " 组合", 11f, Ui.TEXT3);
        meta.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        bar.addView(meta, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        TextView close = Ui.tv(ctx, "✕", 19f, Ui.TEXT2);
        close.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                Ui.dp(ctx, 48), Ui.dp(ctx, 48));
        close.setLayoutParams(cLp);
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { cb.onClose(); }
        });
        bar.addView(close);
        wrap.addView(bar);

        // ---- 第二行：识别到的标签（可点击重截） ----
        wrap.addView(buildTagStrip());
        return wrap;
    }

    /**
     * 「识别到的 N 个」标签条。
     *
     * 设计意图：OCR 难免漏认/认错，与其让用户猜，不如把结果摊在明面上。
     *  · 已识别：琥珀描边 chip
     *  · 数量 < 5：右侧提示「点此重截」
     *  · 整条可点 → 重截
     */
    private View buildTagStrip() {
        LinearLayout box = Ui.row(ctx);
        box.setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 2), Ui.dp(ctx, 12), Ui.dp(ctx, 9));

        // 公招固定给 5 个标签，但 OCR 可能少认 —— 少于 5 时提示用户重截
        boolean full = tags.size() >= SLOT_COUNT;
        TextView label = Ui.tv(ctx, "识别到 " + tags.size() + "/" + SLOT_COUNT, 10f,
                full ? Ui.TEXT3 : Ui.TEAL);
        label.setTypeface(Ui.med());
        LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lLp.rightMargin = Ui.dp(ctx, 8);
        box.addView(label, lLp);

        // 已识别的标签
        for (String t : tags) {
            TextView c = Ui.chip(ctx, t, true);
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            cLp.rightMargin = Ui.dp(ctx, 4);
            box.addView(c, cLp);
        }

        // 占位：显示还差几个
        int missing = Math.max(0, SLOT_COUNT - tags.size());
        if (missing > 0) {
            TextView ph = Ui.tv(ctx, "+" + missing + " 未识别", 10f, Ui.TEXT_DIM);
            ph.setPadding(Ui.dp(ctx, 4), 0, Ui.dp(ctx, 4), 0);
            box.addView(ph);
        }

        box.addView(new View(ctx), new LinearLayout.LayoutParams(0, 1, 1f));

        // 重截入口
        TextView recapture = Ui.tv(ctx, "重截 ↻", 11f, Ui.AMBER);
        recapture.setTypeface(Ui.med());
        recapture.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 4), Ui.dp(ctx, 6), Ui.dp(ctx, 4));
        recapture.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { cb.onRecapture(); }
        });
        box.addView(recapture);

        // 整条也可点（点击非 chip 区域也能重截）
        box.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { cb.onRecapture(); }
        });
        return box;
    }
    // ------------------------------------------------------------ 不可靠提示
    /**
     * 识别可靠性警告条。
     *
     * 只在「定位结果可疑」或「有底层 note」时出现（见 TagFinder.Result）。
     * 用青色虚线框区分于琥珀色的结论区：它不是结论，是提醒。
     */
    private View buildWarningBar() {
        LinearLayout box = Ui.row(ctx);
        box.setBackground(new DashBorderDrawable(Ui.alpha(Ui.TEAL, 0.10f),
                Ui.alpha(Ui.TEAL, 0.45f), Ui.dp(ctx, 1), Ui.dp(ctx, 2)));
        box.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 7), Ui.dp(ctx, 10), Ui.dp(ctx, 7));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(ctx, 12);
        lp.rightMargin = Ui.dp(ctx, 12);
        lp.topMargin = Ui.dp(ctx, 8);
        box.setLayoutParams(lp);

        TextView tv = Ui.tv(ctx, warning, 10.5f, Ui.TEAL);
        tv.setLineSpacing(0, 1.15f);
        box.addView(tv, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return box;
    }

    // ------------------------------------------------------------ 结论区
    private View buildConclusion() {
        LinearLayout box = Ui.col(ctx);
        box.setBackground(Ui.round(Ui.INSET, Ui.dp(ctx, 1), Ui.LINE, Ui.dp(ctx, 2)));
        box.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 10), Ui.dp(ctx, 12), Ui.dp(ctx, 12));

        LinearLayout head = Ui.row(ctx);
        head.addView(sectionHeader("结论"));
        TextView more = Ui.tv(ctx, "标签可能被划 · 保底只由时长决定", 10f, Ui.TEXT3);
        more.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        head.addView(more, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(head);

        List<Recruit.Combo> good = new ArrayList<>();
        List<Recruit.Combo> mech = new ArrayList<>();
        List<Recruit.Combo> wasted = new ArrayList<>();
        for (Recruit.Combo c : combos) {
            if (c.supportMech) mech.add(c);
            else if (c.wastedOnLongRun) wasted.add(c);
            else if (c.guaranteed >= 4) good.add(c);
        }

        int shown = 0;
        for (Recruit.Combo c : good) {
            if (shown >= (landscape() ? 2 : 3)) break;
            box.addView(recoCard(c, shown));
            shown++;
        }

        if (shown == 0) {
            Recruit.Combo top = firstUsable();
            if (top != null) {
                TextView bad = Ui.tv(ctx, "没有 4★ 以上的组合，最好的是：",
                        11f, Ui.TEXT2);
                bad.setPadding(0, Ui.dp(ctx, 8), 0, Ui.dp(ctx, 4));
                box.addView(bad);
                box.addView(recoCard(top, 0));
            }
        }

        if (!mech.isEmpty()) box.addView(mechBar(mech.get(0)));

        if (!wasted.isEmpty()) {
            StringBuilder sb = new StringBuilder("不推荐：");
            for (int i = 0; i < wasted.size(); i++) {
                if (i > 0) sb.append("、");
                sb.append(wasted.get(i).label());
            }
            TextView w = Ui.tv(ctx, sb.toString(), 10f, Ui.TEXT_DIM);
            w.setPadding(0, Ui.dp(ctx, 8), 0, 0);
            box.addView(w);
        }
        return box;
    }

    private Recruit.Combo firstUsable() {
        for (Recruit.Combo c : combos) {
            if (!c.wastedOnLongRun && !c.supportMech) return c;
        }
        for (Recruit.Combo c : combos) if (!c.supportMech) return c;
        return null;
    }

    /** 推荐卡：序号 + 标签胶囊 + 时长 + 徽章 + 第二行 */
    private View recoCard(Recruit.Combo c, int idx) {
        LinearLayout card = Ui.row(ctx);
        card.setBackground(new BevelDrawable(Ui.PANEL2, Ui.LINE,
                Ui.dp(ctx, 1), Ui.dp(ctx, 8), Ui.dp(ctx, 2)));
        card.setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 8), Ui.dp(ctx, 8), Ui.dp(ctx, 8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(ctx, 8);
        card.setLayoutParams(lp);

        // 左强调条
        View bar = new View(ctx);
        bar.setBackgroundColor(idx == 0 ? Ui.AMBER : Ui.AMBER_LO);
        bar.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(ctx, 3),
                LinearLayout.LayoutParams.MATCH_PARENT));
        card.addView(bar);

        // 序号
        TextView num = Ui.display(ctx, String.format("%02d", idx + 1), 20f,
                idx == 0 ? Ui.AMBER : Ui.alpha(Ui.AMBER, 0.60f));
        LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(
                Ui.dp(ctx, 34), LinearLayout.LayoutParams.WRAP_CONTENT);
        nLp.leftMargin = Ui.dp(ctx, 8);
        num.setLayoutParams(nLp);
        card.addView(num);

        // 中间：标签 + 第二行
        LinearLayout mid = Ui.col(ctx);
        LinearLayout tagsRow = Ui.row(ctx);
        for (String t : c.tags) {
            TextView chip = Ui.chip(ctx, t, isRare(t));
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            cLp.rightMargin = Ui.dp(ctx, 4);
            tagsRow.addView(chip, cLp);
        }
        mid.addView(tagsRow);

        // 第二行：保底 / 命中 / 人数
        LinearLayout sub = Ui.row(ctx);
        sub.setPadding(0, Ui.dp(ctx, 6), 0, 0);
        sub.addView(badgeView(c));
        TextView cnt = Ui.tv(ctx, " · " + c.countText(), 11f, Ui.TEXT2);
        sub.addView(cnt);
        mid.addView(sub);

        card.addView(mid, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // 右：时间
        TextView time = Ui.mono(ctx, c.minTime, 15f, Ui.AMBER);
        time.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                Ui.dp(ctx, 48), Ui.dp(ctx, 28));
        time.setLayoutParams(tLp);
        card.addView(time);

        return card;
    }

    /**
     * 徽章语义：
     *   资深系 → 实心「必得 N★」
     *   时长能覆盖 → 实心「稳住 N★」
     *   靠标签命中 → 虚线「命中 N★」
     */
    private TextView badgeView(Recruit.Combo c) {
        boolean senior = isSenior(c);
        if (senior) return Ui.badge(ctx, "必得" + Ui.STAR_TXT[c.guaranteed],
                c.guaranteed, false);
        if (c.timeFloor >= c.guaranteed) {
            return Ui.badge(ctx, "稳住" + Ui.STAR_TXT[c.timeFloor], c.timeFloor, false);
        }
        return Ui.badge(ctx, "命中" + Ui.STAR_TXT[c.guaranteed], c.guaranteed, true);
    }

    // ------------------------------------------------------------ 支援机械条
    private View mechBar(Recruit.Combo c) {
        LinearLayout box = Ui.col(ctx);
        box.setBackground(new DashBorderDrawable(Ui.alpha(Ui.TEAL, 0.08f),
                Ui.alpha(Ui.TEAL, 0.40f), Ui.dp(ctx, 1), Ui.dp(ctx, 2)));
        box.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 8), Ui.dp(ctx, 10), Ui.dp(ctx, 9));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(ctx, 8);
        box.setLayoutParams(lp);

        LinearLayout head = Ui.row(ctx);

        TextView mark = Ui.tv(ctx, "机", 9.5f, Ui.VOID);
        mark.setTypeface(Ui.med());
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(Ui.round(Ui.TEAL, 0, 0, Ui.dp(ctx, 1)));
        LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(
                Ui.dp(ctx, 16), Ui.dp(ctx, 16));
        mLp.rightMargin = Ui.dp(ctx, 6);
        head.addView(mark, mLp);

        TextView name = Ui.tv(ctx, "支援机械", 13f, Ui.TEAL);
        name.setTypeface(Ui.med());
        head.addView(name);

        head.addView(new View(ctx), new LinearLayout.LayoutParams(0, 1, 1f));

        TextView time = Ui.mono(ctx, c.minTime, 13f, Ui.TEAL);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                Ui.dp(ctx, 40), LinearLayout.LayoutParams.WRAP_CONTENT);
        tLp.rightMargin = Ui.dp(ctx, 6);
        head.addView(time, tLp);

        // ★ v2.9.1（交叉审查）★
        // 原来写「必得1★」——但支援机械和普通标签一样可能被划掉，
        // 只有资深系才是真正不会划的（见 Recruit.durationTable 的说明）。
        // 这里改成和普通组合一致的口径：时长保底才叫「稳住」。
        //
        // ★ v2.9.2 修复（交叉审查）★ dashed 原来是 true（虚线），
        // 与明细区 badgeView() 对同一组合给出的实心「稳住1★」自相矛盾：
        // 支援机械的 minTime 是 1:00，而 1:00 池的下限恰好就是 1★ ——
        // 这是确定的时长保底，不是靠标签命中。语义对齐：实心 + 同一措辞。
        head.addView(Ui.badge(ctx, "稳住1★", 1, false));
        box.addView(head);

        // 名单
        StringBuilder names = new StringBuilder();
        // ★ v2.9.2（交叉审查）★ byRarity 是 HashMap，遍历顺序不稳定 ——
        // 同一批干员每次打开面板顺序都可能变。按星级升序 + 名字排序固定下来。
        List<Integer> mechRs = new ArrayList<>(c.byRarity.keySet());
        Collections.sort(mechRs);
        for (int mr : mechRs) {
            List<String> ml = new ArrayList<>(c.byRarity.get(mr));
            Collections.sort(ml);
            for (String n : ml) {
                if (names.length() > 0) names.append(" · ");
                names.append(n);
            }
        }
        TextView nv = Ui.tv(ctx, names.toString(), 11f, Ui.alpha(Ui.TEAL, 0.85f));
        nv.setPadding(0, Ui.dp(ctx, 5), 0, 0);
        box.addView(nv);

        TextView note = Ui.tv(ctx, "▸ 寻访拿不到，只能靠公招获得", 10f,
                Ui.alpha(Ui.TEAL, 0.70f));
        note.setPadding(0, Ui.dp(ctx, 3), 0, 0);
        box.addView(note);
        return box;
    }

    /**
     * 填充明细列表。
     *
     * 顺序（用户指定）：6★ → 5★ → 4★ → 支援机械 → 3★ → 2★ → 1★
     * 支援机械作为独立通栏，夹在 4★ 和 3★ 之间。
     */
    private void fillDetailList(LinearLayout list) {
        // 按「排序组号」分桶
        //   6..3 → 按星级
        //   支援机械 → 夹在 4 和 3 之间
        java.util.Map<String, Recruit.Combo> mechMap = new java.util.LinkedHashMap<>();
        java.util.Map<Integer, List<Recruit.Combo>> byStar = new java.util.TreeMap<>(
                java.util.Collections.reverseOrder());

        for (Recruit.Combo c : combos) {
            if (c.supportMech) {
                mechMap.put(c.label(), c);
            } else {
                int g = c.guaranteed;
                if (g >= 6) g = 6;
                if (g < 1) g = 1;
                List<Recruit.Combo> l = byStar.get(g);
                if (l == null) { l = new ArrayList<>(); byStar.put(g, l); }
                l.add(c);
            }
        }

        boolean mechPlaced = false;
        for (java.util.Map.Entry<Integer, List<Recruit.Combo>> e : byStar.entrySet()) {
            int star = e.getKey();
            // 在 3★ 组之前插入支援机械
            if (!mechPlaced && star <= 3 && !mechMap.isEmpty()) {
                list.addView(mechGroup(mechMap.size()));
                for (Recruit.Combo c : mechMap.values()) list.addView(comboRow(c));
                mechPlaced = true;
            }
            list.addView(groupHeader(star, e.getValue().size()));
            for (Recruit.Combo c : e.getValue()) list.addView(comboRow(c));
        }
        if (!mechPlaced && !mechMap.isEmpty()) {
            list.addView(mechGroup(mechMap.size()));
            for (Recruit.Combo c : mechMap.values()) list.addView(comboRow(c));
        }
        if (list.getChildCount() == 0) {
            TextView empty = Ui.tv(ctx, "没有可用组合", 12f, Ui.TEXT3);
            empty.setPadding(0, Ui.dp(ctx, 16), 0, Ui.dp(ctx, 16));
            list.addView(empty);
        }
    }

    /** 支援机械组头（青点 + 标题） */
    private View mechGroup(int count) {
        LinearLayout row = Ui.row(ctx);
        row.setPadding(0, Ui.dp(ctx, 10), 0, Ui.dp(ctx, 4));

        View dot = new View(ctx);
        dot.setBackgroundColor(Ui.TEAL);
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                Ui.dp(ctx, 8), Ui.dp(ctx, 8));
        dLp.rightMargin = Ui.dp(ctx, 7);
        row.addView(dot, dLp);

        TextView t = Ui.tv(ctx, "支援机械", 12f, Ui.TEAL);
        t.setTypeface(Ui.med());
        row.addView(t);

        View line = new View(ctx);
        line.setBackgroundColor(Ui.alpha(Ui.TEAL, 0.30f));
        LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(
                0, Math.max(1, Ui.dp(ctx, 1)), 1f);
        lLp.leftMargin = Ui.dp(ctx, 8);
        lLp.rightMargin = Ui.dp(ctx, 8);
        row.addView(line, lLp);

        TextView n = Ui.tv(ctx, count + " 组", 10f, Ui.TEXT3);
        row.addView(n);
        return row;
    }

    // ------------------------------------------------------------ 明细
    /** 组头：■ 6★ 必得 —————  N 组 */
    private View groupHeader(int star, int count) {
        LinearLayout row = Ui.row(ctx);
        row.setPadding(0, Ui.dp(ctx, 10), 0, Ui.dp(ctx, 4));

        View dot = new View(ctx);
        dot.setBackgroundColor(Ui.STAR[Math.max(1, Math.min(6, star))]);
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                Ui.dp(ctx, 8), Ui.dp(ctx, 8));
        dLp.rightMargin = Ui.dp(ctx, 7);
        row.addView(dot, dLp);

        TextView t = Ui.tv(ctx, star >= 1 ? Ui.STAR_TXT[star] : "支援机械", 12f, Ui.TEXT);
        t.setTypeface(Ui.med());
        row.addView(t);

        View line = new View(ctx);
        line.setBackgroundColor(Ui.alpha(Ui.STAR[Math.max(1, Math.min(6, star))], 0.30f));
        LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(
                0, Math.max(1, Ui.dp(ctx, 1)), 1f);
        lLp.leftMargin = Ui.dp(ctx, 8);
        lLp.rightMargin = Ui.dp(ctx, 8);
        row.addView(line, lLp);

        TextView n = Ui.tv(ctx, count + " 组", 10f, Ui.TEXT3);
        row.addView(n);
        return row;
    }

    /** 组合行 */
    private View comboRow(Recruit.Combo c) {
        final boolean isOpen = expanded.contains(c.label());
        // 低价值组合置灰。
        //
        // ★ v2.9.1（交叉审查）★ 原来写的是 `c.timeFloor < 4`，
        // 但 floorOfTime 只会返回 1/2/3，条件恒为真 —— 所有非支援机械的组合
        // 全被压到 0.72 透明度，本意「只把低星置灰」完全没生效。
        // 按组合自身的保底星级判断才符合意图。
        boolean low = c.guaranteed < 4 && !c.supportMech;

        LinearLayout box = Ui.col(ctx);
        box.setBackground(new BevelDrawable(Ui.PANEL2, Ui.LINE,
                Ui.dp(ctx, 1), Ui.dp(ctx, 8), Ui.dp(ctx, 2)));
        box.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 8), Ui.dp(ctx, 10), Ui.dp(ctx, 9));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(ctx, 6);
        box.setLayoutParams(lp);
        if (low) box.setAlpha(0.72f);

        LinearLayout head = Ui.row(ctx);

        TextView name = Ui.tv(ctx, c.label(), 13f, Ui.TEXT);
        name.setTypeface(Ui.med());
        head.addView(name, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView time = Ui.mono(ctx, c.minTime, 13f, low ? Ui.TEXT3 : Ui.AMBER);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                Ui.dp(ctx, 40), LinearLayout.LayoutParams.WRAP_CONTENT);
        tLp.rightMargin = Ui.dp(ctx, 6);
        head.addView(time, tLp);

        head.addView(badgeView(c));
        box.addView(head);

        // 干员行（星级降序）
        List<Integer> rs = new ArrayList<>(c.byRarity.keySet());
        java.util.Collections.sort(rs);
        java.util.Collections.reverse(rs);

        int limit = isOpen ? 99 : 4;
        for (int r : rs) {
            List<String> names = c.byRarity.get(r);
            // 若是支援机械组合，其 1★ 行已单独展示，这里跳过
            LinearLayout line = Ui.row(ctx);
            line.setPadding(0, Ui.dp(ctx, 4), 0, 0);

            int starIdx = Math.max(1, Math.min(6, r));
            TextView st = Ui.tv(ctx, Ui.STAR_TXT[starIdx], 11f, Ui.STAR[starIdx]);
            st.setTypeface(Ui.med());
            line.addView(st, new LinearLayout.LayoutParams(
                    Ui.dp(ctx, 26), LinearLayout.LayoutParams.WRAP_CONTENT));

            StringBuilder sb = new StringBuilder();
            int shown = Math.min(names.size(), limit);
            for (int i = 0; i < shown; i++) {
                if (i > 0) sb.append(" · ");
                sb.append(names.get(i));
            }
            if (names.size() > shown) sb.append("  +").append(names.size() - shown);
            TextView nv = Ui.tv(ctx, sb.toString(), 12f, Ui.TEXT2);
            line.addView(nv, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            box.addView(line);
        }

        // 点击展开/收起
        final View self = box;
        box.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (expanded.contains(c.label())) expanded.remove(c.label());
                else expanded.add(c.label());
                rebuildInto(self, c);
            }
        });
        return box;
    }

    /** 就地替换（避免整面板重建导致滚动位置丢失） */
    private void rebuildInto(View old, Recruit.Combo c) {
        if (!(old.getParent() instanceof LinearLayout)) return;
        LinearLayout parent = (LinearLayout) old.getParent();
        int idx = parent.indexOfChild(old);
        View fresh = comboRow(c);
        parent.removeViewAt(idx);
        parent.addView(fresh, idx);
    }

    // ------------------------------------------------------------ 时长参考（可折叠）
    /**
     * 时长参考区，默认收起。
     *
     * 展开后每行：时间码 | 6 格星级区间条 | 范围文字 | 备注
     * 区间条用小竖条亮起「该时长能覆盖的星级」，
     * 是全屏唯一的多色区域 —— 一眼看出哪条时长能盖到几星。
     */
    private View buildDurationSection() {
        LinearLayout box = Ui.col(ctx);
        box.setBackgroundColor(Ui.PANEL);
        box.setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 6), Ui.dp(ctx, 14), Ui.dp(ctx, 8));

        // 折叠头
        final LinearLayout head = Ui.row(ctx);
        head.setPadding(0, Ui.dp(ctx, 4), 0, Ui.dp(ctx, 4));

        final TextView label = Ui.sectionLabel(ctx, "时长参考");
        head.addView(label);

        final TextView arrow = Ui.tv(ctx, "▸", 11f, Ui.TEXT3);
        arrow.setPadding(Ui.dp(ctx, 6), 0, 0, 0);
        head.addView(arrow);

        head.addView(new View(ctx), new LinearLayout.LayoutParams(0, 1, 1f));

        TextView hint = Ui.tv(ctx, "1:00 / 4:00 / 7:40 / 9:00", 10f, Ui.TEXT_DIM);
        head.addView(hint);

        box.addView(head);

        // 折叠内容
        final LinearLayout body = Ui.col(ctx);
        body.setVisibility(View.GONE);
        body.setPadding(0, Ui.dp(ctx, 4), 0, Ui.dp(ctx, 2));

        body.addView(durationRow("1:00", 1, 4, "1~4★", "赌标签", false));
        body.addView(durationRow("4:00", 2, 5, "2~5★", "下限提到 2★", false));
        body.addView(durationRow("7:40", 3, 5, "3~5★", "锁 3★ 保底 ★推荐", true));
        body.addView(durationRow("9:00", 3, 5, "3~5★", "资深系专用", false));

        TextView note = Ui.tv(ctx,
                "标签可能被划 · 真正的保底来自时长",
                10f, Ui.TEXT_DIM);
        note.setPadding(0, Ui.dp(ctx, 6), 0, 0);
        body.addView(note);

        box.addView(body);

        // 点击展开/收起
        head.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean open = body.getVisibility() == View.VISIBLE;
                body.setVisibility(open ? View.GONE : View.VISIBLE);
                arrow.setText(open ? "▸" : "▾");
                label.setTextColor(open ? Ui.TEXT3 : Ui.AMBER);
                head.setAlpha(1f);
            }
        });
        return box;
    }

    /** 一行时长：时间码 | 区间条 | 范围 | 备注 */
    private View durationRow(String time, int from, int to, String range,
                             String note, boolean highlight) {
        LinearLayout row = Ui.row(ctx);
        row.setPadding(0, Ui.dp(ctx, 5), 0, Ui.dp(ctx, 5));

        TextView tv = Ui.mono(ctx, time, 12f, highlight ? Ui.AMBER : Ui.TEXT2);
        row.addView(tv, new LinearLayout.LayoutParams(
                Ui.dp(ctx, 42), LinearLayout.LayoutParams.WRAP_CONTENT));

        // 6 格星级区间条
        LinearLayout bars = Ui.row(ctx);
        for (int s = 1; s <= 6; s++) {
            View v = new View(ctx);
            boolean on = s >= from && s <= to;
            v.setBackgroundColor(on ? Ui.STAR[s] : Ui.TEXT_DIM);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    Ui.dp(ctx, 4), Ui.dp(ctx, 10));
            lp.rightMargin = Ui.dp(ctx, 2);
            bars.addView(v, lp);
        }
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        bLp.leftMargin = Ui.dp(ctx, 2);
        row.addView(bars, bLp);

        TextView rg = Ui.tv(ctx, range, 11f, highlight ? Ui.TEXT : Ui.TEXT2);
        rg.setPadding(Ui.dp(ctx, 10), 0, 0, 0);
        row.addView(rg, new LinearLayout.LayoutParams(
                Ui.dp(ctx, 44), LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView nt = Ui.tv(ctx, note, 10f, highlight ? Ui.AMBER : Ui.TEXT3);
        row.addView(nt, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        return row;
    }

    // ------------------------------------------------------------ 工具条
    private View buildToolbar() {
        LinearLayout bar = Ui.row(ctx);
        bar.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 8), Ui.dp(ctx, 12), Ui.dp(ctx, 10));
        bar.setBackgroundColor(Ui.PANEL);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final EditText input = new EditText(ctx);
        input.setHint("手输标签，用、分隔");
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(Ui.TEXT3);
        input.setSingleLine(true);
        input.setBackground(Ui.round(Ui.INSET, Ui.dp(ctx, 1), Ui.LINE2, Ui.dp(ctx, 2)));
        input.setPadding(Ui.dp(ctx, 8), 0, Ui.dp(ctx, 8), 0);
        // 不抢焦点，避免键盘盖住按钮
        input.setFocusableInTouchMode(false);
        input.setFocusable(false);
        input.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                input.setFocusableInTouchMode(true);
                input.setFocusable(true);
                input.requestFocus();
            }
        });
        if (!tags.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < tags.size(); i++) {
                if (i > 0) sb.append("、");
                sb.append(tags.get(i));
            }
            input.setText(sb.toString());
        }
        LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(
                0, Ui.dp(ctx, 42), 1f);
        iLp.rightMargin = Ui.dp(ctx, 8);
        bar.addView(input, iLp);

        bar.addView(btn("重算", true, new View.OnClickListener() {
            public void onClick(View v) { cb.onRecalc(input.getText().toString()); }
        }), btnLp(64, Ui.AMBER));
        bar.addView(btn("重截", false, new View.OnClickListener() {
            public void onClick(View v) { cb.onRecapture(); }
        }), btnLp(56, Ui.TEXT));
        bar.addView(btn("关闭", false, new View.OnClickListener() {
            public void onClick(View v) { cb.onClose(); }
        }), btnLp(52, Ui.TEXT3));
        return bar;
    }

    private LinearLayout.LayoutParams btnLp(int w, int textColor) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                Ui.dp(ctx, w), Ui.dp(ctx, 40));
        p.rightMargin = Ui.dp(ctx, 6);
        return p;
    }

    private TextView btn(String text, boolean primary, View.OnClickListener l) {
        TextView t = Ui.tv(ctx, text, 12.5f, primary ? Ui.VOID : Ui.TEXT);
        t.setTypeface(Ui.med());
        t.setGravity(Gravity.CENTER);
        t.setBackground(primary
                ? ctx.getResources().getDrawable(R.drawable.bg_btn_primary, null)
                : ctx.getResources().getDrawable(R.drawable.bg_btn_outline, null));
        t.setOnClickListener(l);
        return t;
    }

    private TextView sectionHeader(String s) {
        TextView t = Ui.sectionLabel(ctx, s);
        t.setPadding(0, Ui.dp(ctx, 6), 0, Ui.dp(ctx, 6));
        return t;
    }

    // ------------------------------------------------------------ 杂项
    private static boolean isSenior(Recruit.Combo c) {
        for (String t : c.tags) {
            if ("资深干员".equals(t) || "高级资深干员".equals(t)) return true;
        }
        return false;
    }

    private static boolean isRare(String t) {
        return "资深干员".equals(t) || "高级资深干员".equals(t)
                || "爆发".equals(t) || "控场".equals(t)
                || "召唤".equals(t) || "位移".equals(t)
                || "支援机械".equals(t);
    }
}
