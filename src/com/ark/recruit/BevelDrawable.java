package com.ark.recruit;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * 对角切角矩形。
 *
 * 签名式几何：左上 + 右下 切 10dp（45°），另两角保留 2dp 微圆角。
 * 卡片、按钮、徽章统一用这个 Drawable，形成一致的面板语言。
 *
 * XML 的 <shape> 只能四角同半径，做不到对角切角 + 异角圆角，
 * 所以这里用 Path 自绘。
 */
class BevelDrawable extends Drawable {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final float bevel;
    private final float radius;

    BevelDrawable(int fillColor, int strokeColor, int strokeW, int bevel, int radius) {
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(fillColor);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setColor(strokeColor);
        stroke.setStrokeWidth(strokeW);
        this.bevel = bevel;
        this.radius = radius;
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        float inset = stroke.getStrokeWidth() / 2f;
        rect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset);
        float bv = Math.min(bevel, Math.min(rect.width(), rect.height()) / 2f);
        float r = Math.min(radius, Math.min(rect.width(), rect.height()) / 2f);

        buildPath(bv, r);
        canvas.drawPath(path, fill);
        canvas.drawPath(path, stroke);
    }

    private void buildPath(float bv, float r) {
        path.reset();
        float l = rect.left, t = rect.top, rr = rect.right, bb = rect.bottom;

        // 起点：左上切角之后
        path.moveTo(l + bv, t);
        // 上边 → 右上圆角
        path.lineTo(rr - r, t);
        path.quadTo(rr, t, rr, t + r);
        // 右边 → 右下切角
        path.lineTo(rr, bb - bv);
        // 右下切角
        path.lineTo(rr - bv, bb);
        // 下边 → 左下圆角
        path.lineTo(l + r, bb);
        path.quadTo(l, bb, l, bb - r);
        // 左边 → 回到起点
        path.lineTo(l, t + bv);
        path.close();

        // 左上切角（用 lineTo 已隐含，这里补上斜边）
        // 上面 moveTo(l+bv, t) 后最后 lineTo(l, t+bv) 再 close 正好构成斜边
    }

    @Override
    public void setAlpha(int alpha) {
        fill.setAlpha(alpha);
        stroke.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        fill.setColorFilter(cf);
        stroke.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
