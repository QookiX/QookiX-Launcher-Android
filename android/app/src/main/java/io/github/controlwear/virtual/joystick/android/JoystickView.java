package io.github.controlwear.virtual.joystick.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 虚拟摇杆控件。
 *
 * <p><b>来源说明</b>：类名与公开 API 对齐 PojavLauncher 依赖的
 * {@code com.github.Mathias-Boulay:virtual-joystick-android:1.14}（原作者 controlwear，
 * Apache-2.0）。本机开发环境<b>无法访问 JitPack</b>（SSL 不通），无法直接拉取该 AAR，
 * 因此这里按 Pojav 侧实际用到的 API 面重新实现了一份等价控件：
 * <ul>
 *   <li>{@link #setOnMoveListener(OnMoveListener)}：{@code onMove(angle, strength)} +
 *       {@code onForwardLock(boolean)}</li>
 *   <li>{@link #setDeadzone(int)}、{@link #setFixedCenter(boolean)}、
 *       {@link #setAutoReCenterButton(boolean)}、{@link #setForwardLockDistance(int)}</li>
 *   <li>{@link #setBorderWidth(float)}、{@link #setBorderColor(int)}、{@link #setButtonColor(int)}</li>
 * </ul>
 *
 * <p><b>角度约定</b>（必须与 {@code ControlJoystick} 的方向判定一致）：
 * 0° = 右，90° = 上，180° = 左，270° = 下。
 * {@code ControlJoystick} 用 {@code (int)((angle + 22.5) / 45) % 8} 得到 8 方向，
 * 因此「往上推」必须落在 90° 附近才会映射成 W（前进）。
 *
 * <p><b>与原库的差异</b>：border width 按<b>像素</b>解释（Pojav 传的是
 * {@code Tools.dpToPx(...)} 的结果）；原库把它当成半径比例。这只影响描边粗细的视觉，
 * 不影响方向键行为。
 */
public class JoystickView extends View {

    /** 摇杆移动回调。 */
    public interface OnMoveListener {
        /**
         * @param angle    当前角度，0..360，0 = 右、90 = 上
         * @param strength 力度百分比 0..100，落在死区内为 0
         */
        void onMove(int angle, int strength);

        /** 前向锁定状态变化（推到底并锁定）。未启用前向锁定时不会回调。 */
        void onForwardLock(boolean isLocked);
    }

    private static final int DEFAULT_DEADZONE = 0;
    private static final float DEFAULT_BUTTON_SIZE_RATIO = 0.25f;

    private final Paint mBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mButtonPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mForwardLockPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private OnMoveListener mOnMoveListener;

    private float mBorderWidth = 0f;
    private int mBorderColor = Color.BLACK;
    private int mButtonColor = Color.BLACK;
    private float mButtonSizeRatio = DEFAULT_BUTTON_SIZE_RATIO;

    private int mDeadzone = DEFAULT_DEADZONE;
    private boolean mFixedCenter = false;
    private boolean mAutoReCenterButton = true;
    private float mForwardLockDistance = 0f;

    /** 摇杆中心（相对本 View）。fixedCenter 时恒为 View 中心。 */
    private float mCenterX;
    private float mCenterY;
    /** 按钮位置。 */
    private float mButtonX;
    private float mButtonY;

    private boolean mTracking = false;
    private boolean mForwardLocked = false;
    private double mAngle = 0;
    private int mStrength = 0;

    public JoystickView(Context context) {
        this(context, null);
    }

    public JoystickView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public JoystickView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mBorderPaint.setStyle(Paint.Style.STROKE);
        mButtonPaint.setStyle(Paint.Style.FILL);
        mForwardLockPaint.setStyle(Paint.Style.STROKE);
        mForwardLockPaint.setColor(Color.argb(120, 255, 255, 255));
        setFocusable(true);
    }

    // ------------------------------------------------------------ 公开 API

    public void setOnMoveListener(OnMoveListener listener) {
        mOnMoveListener = listener;
    }

    public void setDeadzone(int deadzone) {
        mDeadzone = Math.max(0, Math.min(100, deadzone));
    }

    public void setFixedCenter(boolean fixedCenter) {
        mFixedCenter = fixedCenter;
        if (fixedCenter) {
            mCenterX = getWidth() / 2f;
            mCenterY = getHeight() / 2f;
            resetButton();
            invalidate();
        }
    }

    public void setAutoReCenterButton(boolean autoReCenter) {
        mAutoReCenterButton = autoReCenter;
    }

    /** 前向锁定触发距离（像素）。传 0 关闭前向锁定。 */
    public void setForwardLockDistance(int distance) {
        mForwardLockDistance = Math.max(0, distance);
    }

    /** 描边宽度，单位像素。 */
    public void setBorderWidth(float width) {
        mBorderWidth = Math.max(0f, width);
        mBorderPaint.setStrokeWidth(mBorderWidth);
        invalidate();
    }

    public void setBorderColor(int color) {
        mBorderColor = color;
        mBorderPaint.setColor(color);
        invalidate();
    }

    public void setButtonColor(int color) {
        mButtonColor = color;
        mButtonPaint.setColor(color);
        invalidate();
    }

    public void setButtonSizeRatio(float ratio) {
        mButtonSizeRatio = Math.max(0.05f, Math.min(1f, ratio));
        invalidate();
    }

    public double getAngle() {
        return mAngle;
    }

    public int getStrength() {
        return mStrength;
    }

    public boolean isForwardLocked() {
        return mForwardLocked;
    }

    // ------------------------------------------------------------ 布局与绘制

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        // 摇杆永远是正方形：取宽高较小值
        int size = Math.min(getMeasuredWidth(), getMeasuredHeight());
        if (size > 0) setMeasuredDimension(size, size);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (mFixedCenter || !mTracking) {
            mCenterX = w / 2f;
            mCenterY = h / 2f;
            resetButton();
        }
    }

    private float getRadius() {
        return Math.min(getWidth(), getHeight()) / 2f;
    }

    private void resetButton() {
        mButtonX = mCenterX;
        mButtonY = mCenterY;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float radius = getRadius();
        if (radius <= 0) return;

        // 外圈
        if (mBorderWidth > 0) {
            canvas.drawCircle(mCenterX, mCenterY, radius - mBorderWidth / 2f, mBorderPaint);
        } else {
            mBorderPaint.setStrokeWidth(Math.max(1f, radius * 0.02f));
            canvas.drawCircle(mCenterX, mCenterY, radius - mBorderPaint.getStrokeWidth() / 2f, mBorderPaint);
        }

        // 前向锁定的落点提示
        if (mForwardLockDistance > 0) {
            canvas.drawCircle(mCenterX, mCenterY - mForwardLockDistance,
                    Math.max(4f, radius * 0.06f), mForwardLockPaint);
        }

        // 按钮
        float buttonRadius = radius * mButtonSizeRatio;
        canvas.drawCircle(mButtonX, mButtonY, buttonRadius, mButtonPaint);
    }

    // ------------------------------------------------------------ 触摸

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float radius = getRadius();
        if (radius <= 0) return false;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mTracking = true;
                if (mFixedCenter) {
                    mCenterX = getWidth() / 2f;
                    mCenterY = getHeight() / 2f;
                } else {
                    // 中心跟着手指落点走（原库的 relative 模式）
                    mCenterX = event.getX();
                    mCenterY = event.getY();
                }
                moveButton(event.getX(), event.getY());
                return true;

            case MotionEvent.ACTION_MOVE:
                if (!mTracking) return false;
                moveButton(event.getX(), event.getY());
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mTracking = false;
                if (mForwardLocked) {
                    mForwardLocked = false;
                    if (mOnMoveListener != null) mOnMoveListener.onForwardLock(false);
                }
                mStrength = 0;
                mAngle = 0;
                if (mAutoReCenterButton) resetButton();
                if (mOnMoveListener != null) mOnMoveListener.onMove(0, 0);
                invalidate();
                return true;

            default:
                return super.onTouchEvent(event);
        }
    }

    private void moveButton(float x, float y) {
        float radius = getRadius();
        float dx = x - mCenterX;
        float dy = y - mCenterY;

        // 限制在圆内
        float distance = (float) Math.hypot(dx, dy);
        float maxDistance = Math.max(1f, radius - radius * mButtonSizeRatio);
        if (distance > maxDistance) {
            float scale = maxDistance / distance;
            dx *= scale;
            dy *= scale;
            distance = maxDistance;
        }
        mButtonX = mCenterX + dx;
        mButtonY = mCenterY + dy;

        // 力度百分比
        int strength = (int) (100f * distance / maxDistance);
        if (strength < mDeadzone) strength = 0;
        mStrength = strength;

        // 角度：0 = 右，90 = 上（屏幕 y 向下，故取 -dy）
        double degrees = Math.toDegrees(Math.atan2(-dy, dx));
        if (degrees < 0) degrees += 360;
        mAngle = degrees;

        // 前向锁定：向上推超过设定距离即锁定，直到松手
        if (mForwardLockDistance > 0 && mOnMoveListener != null) {
            boolean shouldLock = mForwardLocked || -dy >= mForwardLockDistance;
            if (shouldLock != mForwardLocked) {
                mForwardLocked = shouldLock;
                mOnMoveListener.onForwardLock(shouldLock);
            }
        }

        if (mOnMoveListener != null) mOnMoveListener.onMove((int) degrees, strength);
        invalidate();
    }
}
