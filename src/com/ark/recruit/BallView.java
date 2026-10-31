package com.ark.recruit;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

/**
 * 悬浮球。
 *
 * 结构（由外到内）：
 *   底盘圆 → 外环(2dp) → 旋转进度弧(3dp) → 中心「招」→ 右下角工业小方块
 *
 * 状态：
 *   idle    外环 35% 透明，弧极慢旋转
 *   pressed 整体 0.92 缩放，外环 100%
 *   busy    弧缩到 30° 并快速旋转，中心字变「…」
 *   ok      弧一次扫满 360° 后淡出
 *   error   弧变红闪两次
 *
 * 用 SystemClock.uptimeMillis() 驱动，不需要 Animation 对象，
 * 也就不受 ANIMATOR_DURATION_SCALE 影响（悬浮窗里这个值经常是 0）。
 */
class BallView extends View {

    static final int IDLE = 0, BUSY = 1, OK = 2, ERROR = 3;

    private final Paint pBase = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pArc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pMark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();

    private int state = IDLE;
    private float pressScale = 1f;
    private long stateStart = SystemClock.uptimeMillis();
    private long okStart = 0, errStart = 0;

    private final float density;

    /** IDLE 状态的重绘间隔（毫秒）。弧每 3 秒转 90°，10fps 已经足够顺滑 */
    private static final long IDLE_TICK_MS = 100L;

    /** 缓存的 FontMetrics（避免 onDraw 每帧分配） */
    private Paint.FontMetrics fmCache;
    private float textSizeCache = -1f;

    BallView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        setLayerType(LAYER_TYPE_SOFTWARE, null); // 保证 Path/弧在所有机型一致

        pBase.setStyle(Paint.Style.FILL);
        pBase.setColor(0xE61A1F23);

        pRing.setStyle(Paint.Style.STROKE);
        pRing.setStrokeWidth(dp(2));
        pRing.setColor(Ui.AMBER);
        pRing.setAlpha(90);

        pArc.setStyle(Paint.Style.STROKE);
        pArc.setStrokeWidth(dp(3));
        pArc.setStrokeCap(Paint.Cap.ROUND);
        pArc.setColor(Ui.AMBER);

        pText.setColor(Ui.AMBER);
        pText.setTextAlign(Paint.Align.CENTER);
        pText.setTypeface(Ui.cond());

        pMark.setColor(Ui.LINE_HI);
    }

    void setState(int s) {
        state = s;
        stateStart = SystemClock.uptimeMillis();
        if (s == OK) okStart = stateStart;
        if (s == ERROR) errStart = stateStart;
        invalidate();
    }

    void setPressScale(float f) {
        pressScale = f;
        invalidate();
    }

    private float dp(float v) { return v * density; }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;

        canvas.save();
        canvas.scale(pressScale, pressScale, cx, cy);

        // 底盘
        float rBase = Math.min(w, h) / 2f - dp(1);
        canvas.drawCircle(cx, cy, rBase, pBase);

        // 外环
        pRing.setAlpha(state == IDLE ? 90 : 255);
        canvas.drawCircle(cx, cy, rBase - dp(1), pRing);

        // 进度弧
        float rArc = rBase - dp(3.5f);
        oval.set(cx - rArc, cy - rArc, cx + rArc, cy + rArc);

        switch (state) {
            case BUSY: {
                float t = ((now - stateStart) % 900) / 900f;
                pArc.setColor(Ui.AMBER);
                pArc.setAlpha(255);
                canvas.drawArc(oval, t * 360f, 30f, false, pArc);
                break;
            }
            case OK: {
                float t = Math.min(1f, (now - okStart) / 400f);
                pArc.setColor(Ui.AMBER);
                pArc.setAlpha((int) (255 * (1 - t)));
                canvas.drawArc(oval, -90, 360 * t, false, pArc);
                break;
            }
            case ERROR: {
                long e = now - errStart;
                if (e < 500) {
                    pArc.setColor(Ui.RED);
                    pArc.setAlpha(((e / 120) % 2 == 0) ? 255 : 60);
                    canvas.drawArc(oval, -90, 90, false, pArc);
                }
                break;
            }
            default: { // IDLE：每 3s 缓慢转 90°
                float t = ((now - stateStart) % 3000) / 3000f;
                pArc.setColor(Ui.AMBER);
                pArc.setAlpha(200);
                canvas.drawArc(oval, t * 360f, 90, false, pArc);
                break;
            }
        }

        // 中心字
        String s = (state == BUSY) ? "…" : "招";
        pText.setTextSize(dp(state == BUSY ? 15 : 22));
        pText.setAlpha(255);
        // ★ v2.9.2（交叉审查）：FontMetrics 缓存成字段，不在 onDraw 里 new ★
        Paint.FontMetrics fm = fmCache;
        if (fm == null || textSizeCache != pText.getTextSize()) {
            fm = pText.getFontMetrics();
            fmCache = fm;
            textSizeCache = pText.getTextSize();
        }
        float ty = cy - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(s, cx, ty, pText);

        // 右下工业小方块
        canvas.drawRect(cx + rBase * 0.52f, cy + rBase * 0.52f,
                cx + rBase * 0.52f + dp(5), cy + rBase * 0.52f + dp(5), pMark);

        canvas.restore();

        // ★ v2.9.2 修复（交叉审查）：IDLE 不再常驻 60fps 重绘 ★
        //
        // 原来条件是 `state == IDLE || state == BUSY ...` 且无条件
        // postInvalidateOnAnimation()：球只要挂着就永久以屏幕刷新率重绘，
        // 而且这个 View 是 LAYER_TYPE_SOFTWARE（纯 CPU 绘制）——
        // 常驻耗电，还会让合成器每个 vsync 都产生新帧，游戏里既费电又可能掉帧。
        //
        // 现在 IDLE 改成低频 tick（每 100ms 一帧，弧是每 3 秒转 90°，
        // 10fps 完全够顺滑），BUSY / 动画尾巴仍按 vsync 走。
        if (state == BUSY
                || (state == OK && now - okStart < 450)
                || (state == ERROR && now - errStart < 520)) {
            postInvalidateOnAnimation();
        } else if (state == IDLE) {
            postInvalidateDelayed(IDLE_TICK_MS);
        }
    }
}
