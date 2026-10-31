package com.ark.recruit;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/**
 * 骨架占位块 + 扫光。
 *
 * 识别期间代替结论区，避免用户对着空白等 1~2 秒。
 * 扫光用 LinearGradient 平移实现（不用 RenderEffect/Blur，那在部分机型会崩）。
 *
 * 动画由 SystemClock 驱动，不依赖 Animation 对象 —— 悬浮窗场景
 * ANIMATOR_DURATION_SCALE 常被设成 0，用属性动画会完全不动。
 */
class SkeletonView extends View {

    private final Paint base = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Matrix matrix = new Matrix();
    private LinearGradient grad;

    private boolean animate = true;
    private float corner;
    private long start;

    SkeletonView(Context c, boolean animate) {
        super(c);
        this.animate = animate;
        base.setColor(0x731A1F23);            // panel2 @45%
        corner = c.getResources().getDisplayMetrics().density * 2f;
        start = SystemClock.uptimeMillis();

        shine.setColor(0xFFFFFFFF);
        grad = new LinearGradient(0, 0, 260, 0,
                new int[]{0x00FFFFFF, 0x1AFFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        shine.setShader(grad);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        rect.set(0, 0, w, h);
        canvas.drawRoundRect(rect, corner, corner, base);

        if (animate && w > 0) {
            long t = (SystemClock.uptimeMillis() - start) % 1200;
            float x = (t / 1200f) * (w + 520) - 260;
            matrix.setTranslate(x, 0);
            grad.setLocalMatrix(matrix);
            canvas.save();
            canvas.clipRect(rect);
            canvas.drawRoundRect(rect, corner, corner, shine);
            canvas.restore();
            postInvalidateOnAnimation();
        }
    }
}
