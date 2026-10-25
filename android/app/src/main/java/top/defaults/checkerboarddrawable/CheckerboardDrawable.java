package top.defaults.checkerboarddrawable;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * 透明底色棋盘格。
 *
 * <p><b>来源说明</b>：对齐 PojavLauncher 依赖的
 * {@code com.github.duanhong169:checkerboarddrawable:1.0.2}（Apache-2.0）中用到的
 * {@code create()} / {@code draw(Canvas)} 两个 API。本机无法访问 JitPack，
 * 因此按同等视觉效果自行实现：把透明区域画成浅灰/深灰交替的方格，
 * 让 {@code net.kdt.pojavlaunch.colorselector} 里的透明度编辑看得见。
 */
public class CheckerboardDrawable extends Drawable {

    private static final int DEFAULT_SIZE = 16;

    private final Paint mPaint = new Paint();
    private final int mSize;

    private CheckerboardDrawable(int size, int colorOdd, int colorEven) {
        mSize = Math.max(2, size);
        Bitmap bitmap = Bitmap.createBitmap(mSize * 2, mSize * 2, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint();
        paint.setColor(colorEven);
        canvas.drawRect(0, 0, mSize * 2, mSize * 2, paint);
        paint.setColor(colorOdd);
        canvas.drawRect(0, 0, mSize, mSize, paint);
        canvas.drawRect(mSize, mSize, mSize * 2, mSize * 2, paint);

        mPaint.setShader(new BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
    }

    public static CheckerboardDrawable create() {
        return new CheckerboardDrawable(DEFAULT_SIZE, Color.LTGRAY, Color.WHITE);
    }

    public static CheckerboardDrawable create(int size) {
        return new CheckerboardDrawable(size, Color.LTGRAY, Color.WHITE);
    }

    public static CheckerboardDrawable create(int size, int colorOdd, int colorEven) {
        return new CheckerboardDrawable(size, colorOdd, colorEven);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        canvas.drawRect(bounds, mPaint);
    }

    @Override
    public void setAlpha(int alpha) {
        mPaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(android.graphics.ColorFilter colorFilter) {
        mPaint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return android.graphics.PixelFormat.OPAQUE;
    }
}
