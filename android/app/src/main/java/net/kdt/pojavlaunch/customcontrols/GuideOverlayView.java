package net.kdt.pojavlaunch.customcontrols;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/**
 * 编辑器拖动时的对齐辅助线（ZL2 GuideLine 的轻量版）。
 *
 * 只画当前吸附到的那一条横线/竖线；坐标由 {@link ControlInterface#snapAndAlign}
 * 在判定命中相邻控件边缘时给出，抬手时由 {@link ControlLayout#hideSnapGuides()} 移除。
 * 不接收触摸（不可点击），事件会继续落到下面的控件上。
 */
public class GuideOverlayView extends View {
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float mGuideX = -1, mGuideY = -1;

    public GuideOverlayView(Context context) {
        super(context);
        mPaint.setColor(0x80FFFFFF);
        mPaint.setStrokeWidth(1.5f);
        setClickable(false);
        setFocusable(false);
    }

    /** 传 -1 表示该方向不画。 */
    public void setGuides(float x, float y) {
        mGuideX = x;
        mGuideY = y;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mGuideX >= 0) canvas.drawLine(mGuideX, 0, mGuideX, getHeight(), mPaint);
        if (mGuideY >= 0) canvas.drawLine(0, mGuideY, getWidth(), mGuideY, mPaint);
    }
}
