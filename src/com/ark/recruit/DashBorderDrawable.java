package com.ark.recruit;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * 虚线圆角边框。
 *
 * 为什么不用 XML：shape 不支持虚线（只有 <stroke android:dashWidth> 在 API 21+
 * 才部分生效，且无法配合圆角正确渲染）。support 机械条需要「虚线 = 有风险」的
 * 视觉语义，必须精确可控，所以自绘。
 */
class DashBorderDrawable extends Drawable {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final float radius;

    DashBorderDrawable(int fill, int strokeColor, int strokeW, int radius) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(fill);
        this.radius = radius;

        stroke.setStyle(Paint.Style.STROKE);
        stroke.setColor(strokeColor);
        stroke.setStrokeWidth(strokeW);
        stroke.setPathEffect(new DashPathEffect(new float[]{strokeW * 3f, strokeW * 2f}, 0));
    }

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        float inset = stroke.getStrokeWidth() / 2f;
        rect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset);

        // 填充
        path.reset();
        path.addRoundRect(rect, radius, radius, Path.Direction.CW);
        canvas.drawPath(path, paint);

        // 虚线描边
        path.reset();
        path.addRoundRect(rect, radius, radius, Path.Direction.CW);
        canvas.drawPath(path, stroke);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        stroke.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        paint.setColorFilter(cf);
        stroke.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
