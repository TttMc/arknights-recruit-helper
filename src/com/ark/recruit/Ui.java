package com.ark.recruit;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * UI 工厂：集中管理尺寸换算、字体、颜色、常用组件。
 *
 * 设计依据 DESIGN.md：
 *  · 底板三级亮度阶梯（inset < panel < panel2），不用阴影
 *  · 琥珀 #FFB020 是唯一行动色
 *  · 星级色是信息色，不与行动色混用
 *  · 切角规则：左上 + 右下 10dp，另两角 2dp（见 BevelDrawable）
 */
final class Ui {

    // ---------- 色板 ----------
    static final int VOID     = 0xFF0A0D0F;
    static final int PANEL    = 0xFF14181B;
    static final int PANEL2   = 0xFF1A1F23;
    static final int INSET    = 0xFF0E1214;
    static final int LINE     = 0xFF262E34;
    static final int LINE2    = 0xFF333D45;
    static final int LINE_HI  = 0xFF46525B;

    static final int TEXT     = 0xFFE8EDF0;
    static final int TEXT2    = 0xFF9AA7AE;
    static final int TEXT3    = 0xFF5F6B72;
    static final int TEXT_DIM = 0xFF3E474D;

    static final int AMBER    = 0xFFFFB020;
    static final int AMBER_HI = 0xFFFFC857;
    static final int AMBER_LO = 0xFFB87811;
    static final int TEAL     = 0xFF2FD2C4;
    static final int GREEN    = 0xFF34D399;
    static final int RED      = 0xFFFF5A5A;

    static final int CHIP_BG  = 0xFF22282D;
    static final int ROW_PRESS= 0xFF1F262B;

    /** 星级色：索引 1~6 */
    static final int[] STAR = {
            0,
            0xFF7C878E,  // 1
            0xFF5FD08A,  // 2
            0xFF4E9BFF,  // 3
            0xFFB36BFF,  // 4
            0xFFFFE066,  // 5
            0xFFFF7A3C,  // 6
    };

    static final String[] STAR_TXT = {"", "1★", "2★", "3★", "4★", "5★", "6★"};

    // ---------- 字体 ----------
    private static Typeface tCond, tMed;

    static Typeface cond() {
        if (tCond == null) {
            tCond = Typeface.create("sans-serif-condensed", Typeface.BOLD);
        }
        return tCond;
    }

    static Typeface med() {
        if (tMed == null) {
            tMed = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        }
        return tMed;
    }

    // ---------- 换算 ----------
    static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    static int sp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v,
                c.getResources().getDisplayMetrics());
    }

    // ---------- 文本组件 ----------
    static TextView tv(Context c, String text, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** 大号数字（序号、标题） */
    static TextView display(Context c, String text, float sp, int color) {
        TextView t = tv(c, text, sp, color);
        t.setTypeface(cond());
        t.setLetterSpacing(0.02f);
        return t;
    }

    /** 等宽时间码 */
    static TextView mono(Context c, String text, float sp, int color) {
        TextView t = tv(c, text, sp, color);
        t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        t.setLetterSpacing(0.06f);
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }

    /** 分区标题：小字号 + 大字距（工业 HUD 手法） */
    static TextView sectionLabel(Context c, String text) {
        TextView t = tv(c, text.toUpperCase(), 9.5f, TEXT3);
        t.setTypeface(med());
        t.setLetterSpacing(0.14f);
        return t;
    }

    // ---------- 形状 ----------
    static GradientDrawable round(int fill, int strokeW, int strokeColor, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radius);
        if (strokeW > 0) g.setStroke(strokeW, strokeColor);
        return g;
    }

    /** 半透明叠加（用于徽章底、支援机械条底） */
    static int alpha(int color, float a) {
        int A = (int) (Color.alpha(color) * a);
        return Color.argb(A, Color.red(color), Color.green(color), Color.blue(color));
    }

    /** 标签胶囊 */
    static TextView chip(Context c, String text, boolean rare) {
        TextView t = tv(c, text, 11.5f, rare ? AMBER : TEXT);
        t.setTypeface(med());
        t.setLetterSpacing(0.02f);
        t.setPadding(dp(c, 6), dp(c, 3), dp(c, 6), dp(c, 3));
        GradientDrawable g = round(rare ? 0xFF1F1B12 : CHIP_BG, dp(c, 1),
                rare ? AMBER_LO : LINE2, dp(c, 2));
        t.setBackground(g);
        return t;
    }

    /** 星级徽章：实心=时长保底（确定），虚线=标签命中才有（有风险） */
    static TextView badge(Context c, String text, int rarity, boolean dashed) {
        int sc = STAR[Math.max(1, Math.min(6, rarity))];
        TextView t = tv(c, text, 10f, sc);
        t.setTypeface(med());
        t.setPadding(dp(c, 6), dp(c, 2), dp(c, 6), dp(c, 2));
        if (dashed) {
            DashBorderDrawable d = new DashBorderDrawable(
                    alpha(sc, 0.12f), alpha(sc, 0.45f), dp(c, 1), dp(c, 2));
            t.setBackground(d);
        } else {
            t.setBackground(round(alpha(sc, 0.14f), dp(c, 1), alpha(sc, 0.45f), dp(c, 2)));
        }
        return t;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    static LinearLayout.LayoutParams lp(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    static void pad(Context c, View v, float l, float t, float r, float b) {
        v.setPadding(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
    }

    /** 1dp 细线 */
    static View divider(Context c, int color) {
        View v = new View(c);
        v.setBackgroundColor(color);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 1))));
        return v;
    }

    static void setMargin(View v, int l, int t, int r, int b) {
        if (v.getLayoutParams() instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) v.getLayoutParams();
            p.setMargins(l, t, r, b);
            v.setLayoutParams(p);
        }
    }

    private Ui() {}
}
