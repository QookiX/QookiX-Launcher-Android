package net.kdt.pojavlaunch.customcontrols.handleview;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.vectordrawable.graphics.drawable.VectorDrawableCompat;

import com.zhayi.qookix.R;

/**
 * 抽屉拉手（Pojav 的「顶部半圆 + 齿轮」）。
 *
 * <p><b>与上游的差异（纯视觉）</b>：上游画的是贴着屏幕上缘的黑色半圆 + 33% 透明度的齿轮，
 * 观感很「复古」也不好看。这里改成 QookiX 的胶囊按钮：
 * 圆角药丸底（深色表面 + 强调色描边）+ 居中的强调色齿轮图标。
 * 触摸行为、尺寸语义（宽 × 高）与点击监听接线完全不变。
 */
public class DrawerPullButton extends View {

    private final Paint mBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mBounds = new RectF();
    private VectorDrawableCompat mDrawable;

    public DrawerPullButton(Context context) {super(context); init();}
    public DrawerPullButton(Context context, @Nullable AttributeSet attrs) {super(context, attrs); init();}

    private void init(){
        mDrawable = VectorDrawableCompat.create(getContext().getResources(), R.drawable.ic_sharp_settings_24, null);
        if (mDrawable != null) {
            mDrawable.setTint(ContextCompat.getColor(getContext(), R.color.qk_accent_light));
        }
        mBackgroundPaint.setStyle(Paint.Style.FILL);
        mBackgroundPaint.setColor(ContextCompat.getColor(getContext(), R.color.qk_surface_float));
        mStrokePaint.setStyle(Paint.Style.STROKE);
        mStrokePaint.setStrokeWidth(getResources().getDisplayMetrics().density);
        mStrokePaint.setColor(ContextCompat.getColor(getContext(), R.color.qk_accent_border));
        setAlpha(1f);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float radius = getHeight() / 2f;
        mBounds.set(0f, 0f, getWidth(), getHeight());
        mBounds.inset(mStrokePaint.getStrokeWidth() / 2f, mStrokePaint.getStrokeWidth() / 2f);
        canvas.drawRoundRect(mBounds, radius, radius, mBackgroundPaint);
        canvas.drawRoundRect(mBounds, radius, radius, mStrokePaint);

        if (mDrawable == null) return;

        int size = Math.round(getHeight() * 0.58f);
        int centerX = getWidth() / 2;
        int centerY = getHeight() / 2;
        mDrawable.setBounds(
                centerX - size / 2,
                centerY - size / 2,
                centerX + size / 2,
                centerY + size / 2);
        mDrawable.draw(canvas);
    }
}
