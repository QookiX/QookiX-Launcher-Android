package net.kdt.pojavlaunch.customcontrols.buttons;

import static net.kdt.pojavlaunch.LwjglGlfwKeycode.GLFW_KEY_UNKNOWN;
import static org.lwjgl.glfw.CallbackBridge.sendKeyPress;
import static org.lwjgl.glfw.CallbackBridge.sendMouseButton;

import android.annotation.SuppressLint;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.content.res.Configuration;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.TextView;

import net.kdt.pojavlaunch.LwjglGlfwKeycode;
import net.kdt.pojavlaunch.Tools;
import com.zhayi.qookix.GameActivity;
import com.zhayi.qookix.R;
import net.kdt.pojavlaunch.customcontrols.ControlData;
import net.kdt.pojavlaunch.customcontrols.ControlLayout;
import net.kdt.pojavlaunch.customcontrols.ControlScene;
import net.kdt.pojavlaunch.customcontrols.handleview.EditControlSideDialog;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import androidx.core.content.ContextCompat;

import org.lwjgl.glfw.CallbackBridge;

@SuppressLint({"ViewConstructor", "AppCompatCustomView"})
public class ControlButton extends TextView implements ControlInterface {
    private final Paint mRectPaint = new Paint();
    protected ControlData mProperties;
    private final ControlLayout mControlLayout;

    /* Cache value from the ControlData radius for drawing purposes */
    private float mComputedRadius;

    protected boolean mIsToggled = false;
    protected boolean mIsPointerOutOfBounds = false;
    private MouseWheelGesture wheelGesture;
    private GestureDetector actionGestures;
    private int[] heldLongPressKeys;
    private int[] repeatingLongPressKeys;
    private final Runnable repeatScroll = new Runnable() {
        @Override public void run() {
            if (!isActivated() || !isShown() || mProperties.scrollWhileHeld == 0) return;
            CallbackBridge.sendScroll(0, Integer.signum(mProperties.scrollWhileHeld));
            postDelayed(this, 50);
        }
    };
    private final Runnable repeatClick = new Runnable() {
        @Override public void run() {
            if (!isActivated() || !isShown()) return;
            pulseRepeatableKeys(mProperties.keycodes);
            postDelayed(this, 20);
        }
    };
    private final Runnable repeatLongPress = new Runnable() {
        @Override public void run() {
            if (repeatingLongPressKeys == null || !isShown()) return;
            pulseRepeatableKeys(repeatingLongPressKeys);
            postDelayed(this, 20);
        }
    };

    public ControlButton(ControlLayout layout, ControlData properties) {
        super(layout.getContext());
        mControlLayout = layout;
        setGravity(Gravity.CENTER);
        setAllCaps(LauncherPreferences.PREF_BUTTON_ALL_CAPS);
        // QookiX 设计语言的正文色（比纯白柔和，长时间看不刺眼）
        setTextColor(ContextCompat.getColor(getContext(), R.color.qk_text_1));
        setPadding(4, 4, 4, 4);
        setTextSize(14); // Nullify the default size setting
        setOutlineProvider(null); // Disable shadow casting, removing one drawing pass
        // 游戏画面明暗不定，给标签加一层很淡的投影，保证任何背景下都读得清
        setShadowLayer(3f, 0f, 1f, 0xAA000000);

        // 编辑模式下点/长按按键是「打开属性面板」（见 ControlInterface#injectTouchEventBehavior），
        // 这里明确关掉文本相关的交互。用户反馈过「长按按键后按键上的文字被选中，还冒出
        // 系统的复制/删除菜单和选择手柄」—— 按键只是个按钮，不该有任何文本选择行为。
        // （触摸事件本来就被 injectTouchEventBehavior 消费掉了，但那是**一个** View 的行为，
        //  这里从控件本身关掉更保险，也避免以后有人调整触摸逻辑时又漏出来。）
        setTextIsSelectable(false);
        setLongClickable(false);
        setFocusable(false);
        setFocusableInTouchMode(false);

        //setOnLongClickListener(this);

        //When a button is created, the width/height has yet to be processed to fit the scaling.
        setProperties(preProcessProperties(properties, layout));

        injectBehaviors();
    }

    @Override
    public View getControlView() {return this;}

    public ControlData getProperties() {
        return mProperties;
    }

    public void setProperties(ControlData properties, boolean changePos) {
        mProperties = properties;
        ControlInterface.super.setProperties(properties, changePos);
        mComputedRadius = ControlInterface.super.computeCornerRadius(mProperties.cornerRadius);

        if (mProperties.isToggle) {
            //For the toggle layer
            final TypedValue value = new TypedValue();
            getContext().getTheme().resolveAttribute(androidx.appcompat.R.attr.colorAccent, value, true);
            mRectPaint.setColor(value.data);
            mRectPaint.setAlpha(128);
        } else {
            mRectPaint.setColor(Color.WHITE);
            mRectPaint.setAlpha(60);
        }

        setText(properties.name);
        applyImportedAppearance();
    }
    /**
     * 让标签随按钮尺寸缩放。
     *
     * 上游把字号固定成 14sp：在 50dp 的方形键上偏小、在 80x30 的宽键上又偏大，
     * 是「复古感」的主要来源之一。这里按短边取 42%，并夹在 11~17sp 之间。
     */
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float minSide = Math.min(w, h);
        if (minSide <= 0) return;
        float density = getResources().getDisplayMetrics().scaledDensity;
        float sizePx = minSide * 0.42f;
        sizePx = Math.max(11f * density, Math.min(sizePx, 17f * density));
        setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx);
        applyImportedAppearance();
    }

    @Override
    public void setActivated(boolean activated) {
        boolean changed = activated != isActivated();
        super.setActivated(activated);
        if (changed) applyImportedAppearance();
    }

    private boolean applyImportedAppearance() {
        if (mProperties == null) return false;
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        ControlData.Appearance appearance = dark && mProperties.darkAppearance != null
                ? mProperties.darkAppearance : mProperties.appearance;
        if (appearance == null || appearance.normal == null) return false;
        ControlData.PaintStyle style = (isActivated() || mIsToggled) && appearance.pressed != null
                ? appearance.pressed : appearance.normal;
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(style.fill);
        drawable.setStroke(Math.round(Tools.dpToPx(Math.max(0f, Math.min(100f, style.strokeDp)))), style.outline);
        if (style.cornerPercent != null && style.cornerPercent.length == 4) {
            float minSide = Math.min(getWidth(), getHeight());
            float[] radii = new float[8];
            for (int i = 0; i < 4; i++) {
                int corner = getLayoutDirection() == LAYOUT_DIRECTION_RTL ? i ^ 1 : i;
                float radius = minSide * Math.max(0f, Math.min(100f, style.cornerPercent[corner])) / 100f;
                radii[2 * i] = radii[2 * i + 1] = radius;
            }
            drawable.setCornerRadii(radii);
        } else {
            drawable.setCornerRadius(Tools.dpToPx(Math.max(0f, Math.min(400f, style.radiusDp))));
        }
        setBackground(drawable);
        setTextColor(style.text);
        setAllCaps(false);
        if (style.textSp > 0 && style.textSp <= 100) setTextSize(style.textSp);
        return true;
    }
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mProperties.appearance != null || mProperties.darkAppearance != null) return;
        if (mIsToggled || (!mProperties.isToggle && isActivated()) || (wheelGesture != null && wheelGesture.pointer() != -1))
            canvas.drawRoundRect(0, 0, getWidth(), getHeight(), mComputedRadius, mComputedRadius, mRectPaint);
    }


    public void loadEditValues(EditControlSideDialog editControlPopup){
        editControlPopup.loadValues(getProperties());
    }

    /** Add another instance of the ControlButton to the parent layout */
    public void cloneButton(){
        ControlData cloneData = new ControlData(getProperties());
        cloneData.dynamicX = "0.5 * ${screen_width}";
        cloneData.dynamicY = "0.5 * ${screen_height}";
        ((ControlLayout) getParent()).addControlButton(cloneData);
    }

    /** Remove any trace of this button from the layout */
    public void removeButton() {
        getControlLayoutParent().getLayout().mControlDataList.remove(getProperties());
        getControlLayoutParent().removeView(this);
        // 2026-10-29（外部审计 B36）：删除也是修改 —— 原来没标脏，
        // 退出编辑时不提示保存，用户以为删掉了，下次进游戏按钮又回来。
        getControlLayoutParent().setModified(true);
    }


    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (mProperties.labelOnly && !mControlLayout.getModifiable()) return false;
        return super.dispatchTouchEvent(event);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mProperties.containsKeycode(ControlData.SPECIALBTN_WHEEL_GESTURE)) return onWheelTouch(event);
        dispatchActionGesture(event);
        switch (event.getActionMasked()){
            case MotionEvent.ACTION_MOVE:
                //Send the event to be taken as a mouse action
                if(getProperties().passThruEnabled && CallbackBridge.isGrabbing()){
                    View gameSurface = getControlLayoutParent().getGameSurface();
                    if(gameSurface != null) gameSurface.dispatchTouchEvent(event);
                }

                //If out of bounds
                if(event.getX() < getControlView().getLeft() || event.getX() > getControlView().getRight() ||
                        event.getY() < getControlView().getTop()  || event.getY() > getControlView().getBottom()){
                    if(getProperties().isSwipeable && !mIsPointerOutOfBounds){
                        //Remove keys
                        if(!triggerToggle()) {
                            sendKeyPresses(false);
                        }
                    }
                    mIsPointerOutOfBounds = true;
                    getControlLayoutParent().onTouch(this, event);
                    break;
                }

                //Else if we now are in bounds
                if(mIsPointerOutOfBounds) {
                    getControlLayoutParent().onTouch(this, event);
                    //RE-press the button
                    if(getProperties().isSwipeable && !getProperties().isToggle){
                        sendKeyPresses(true);
                    }
                }
                mIsPointerOutOfBounds = false;
                break;

            case MotionEvent.ACTION_DOWN: // 0
            case MotionEvent.ACTION_POINTER_DOWN: // 5
                animatePressFeedback(true);
                if(!getProperties().isToggle){
                    sendKeyPresses(true);
                }
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    runActionExtras(mProperties.pressAction);
                }
                break;

            case MotionEvent.ACTION_UP: // 1
            case MotionEvent.ACTION_CANCEL: // 3
            case MotionEvent.ACTION_POINTER_UP: // 6
                animatePressFeedback(false);
                if(getProperties().passThruEnabled){
                    View gameSurface = getControlLayoutParent().getGameSurface();
                    if(gameSurface != null) gameSurface.dispatchTouchEvent(event);
                }
                if(mIsPointerOutOfBounds) getControlLayoutParent().onTouch(this, event);
                mIsPointerOutOfBounds = false;
                releaseLongPressKeys();

                if (event.getActionMasked() == MotionEvent.ACTION_CANCEL ? !mProperties.isToggle : !triggerToggle()) {
                    sendKeyPresses(false);
                }
                break;

            default:
                return false;
        }

        return super.onTouchEvent(event);
    }

    private void dispatchActionGesture(MotionEvent event) {
        if (mProperties.clickAction == null && mProperties.longPressAction == null &&
                mProperties.doubleClickAction == null) return;
        if (actionGestures == null) {
            GestureDetector.SimpleOnGestureListener listener = new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent e) { return true; }
                @Override public boolean onSingleTapUp(MotionEvent e) {
                    if (mProperties.doubleClickAction == null) runAction(mProperties.clickAction);
                    return true;
                }
                @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                    runAction(mProperties.clickAction);
                    return true;
                }
                @Override public void onLongPress(MotionEvent e) {
                    ControlData.Action action = mProperties.longPressAction;
                    if (action == null || !isShown() || mControlLayout.getModifiable()) return;
                    if (action.autoClick) {
                        repeatingLongPressKeys = action.keys;
                        repeatLongPress.run();
                    } else {
                        heldLongPressKeys = action.keys;
                    }
                    if (heldLongPressKeys != null) {
                        for (int key : heldLongPressKeys) dispatchKey(key, true, true);
                    }
                    if (repeatingLongPressKeys != null) {
                        for (int key : repeatingLongPressKeys) {
                            if (!isRepeatableKey(key)) dispatchKey(key, true, true);
                        }
                    }
                    runActionExtras(action);
                }
                @Override public boolean onDoubleTapEvent(MotionEvent e) {
                    if (e.getActionMasked() == MotionEvent.ACTION_UP) runAction(mProperties.doubleClickAction);
                    return true;
                }
            };
            actionGestures = new GestureDetector(getContext(), listener);
            actionGestures.setIsLongpressEnabled(mProperties.longPressAction != null);
            if (mProperties.doubleClickAction == null) actionGestures.setOnDoubleTapListener(null);
        }
        actionGestures.onTouchEvent(event);
    }

    private void runAction(ControlData.Action action) {
        if (action == null || !isShown() || mControlLayout.getModifiable()) return;
        if (action.keys != null) {
            for (int key : action.keys) dispatchKey(key, true, true);
            for (int i = action.keys.length - 1; i >= 0; i--) dispatchKey(action.keys[i], false, false);
        }
        runActionExtras(action);
    }

    private void runActionExtras(ControlData.Action action) {
        if (action == null) return;
        if (action.switchTouchMode) {
            net.kdt.pojavlaunch.MinecraftGLSurface surface = mControlLayout.getGameSurface();
            if (surface != null) {
                int label = surface.toggleFightGestures() ? R.string.qk_control_gesture_fight : R.string.qk_control_gesture_build;
                android.widget.Toast.makeText(getContext(), label, android.widget.Toast.LENGTH_SHORT).show();
            }
        }
        if (action.text != null) {
            for (int i = 0; i < action.text.length(); i++) {
                CallbackBridge.sendChar(action.text.charAt(i), CallbackBridge.getCurrentMods());
            }
        }
        if (action.groups != null) mControlLayout.applyGroupActions(action.groups);
    }

    private void releaseLongPressKeys() {
        removeCallbacks(repeatLongPress);
        repeatingLongPressKeys = null;
        if (heldLongPressKeys == null) return;
        int[] keys = heldLongPressKeys;
        heldLongPressKeys = null;
        for (int i = keys.length - 1; i >= 0; i--) dispatchKey(keys[i], false, false);
    }

    private boolean onWheelTouch(MotionEvent event) {
        if (wheelGesture == null) {
            wheelGesture = new MouseWheelGesture(ViewConfiguration.get(getContext()).getScaledTouchSlop(), Tools.dpToPx(20));
        }
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            wheelGesture.start(event.getPointerId(0), event.getX(), event.getY());
            invalidate();
        } else if (action == MotionEvent.ACTION_CANCEL) {
            wheelGesture.cancel();
            invalidate();
        } else if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            int index = event.findPointerIndex(wheelGesture.pointer());
            if (index == -1 || (action != MotionEvent.ACTION_MOVE && index != event.getActionIndex())) return true;
            int steps = wheelGesture.move(event.getX(index), event.getY(index));
            if (steps != 0) CallbackBridge.sendScroll(0, steps);
            if (action != MotionEvent.ACTION_MOVE) {
                if (wheelGesture.finish()) {
                    sendKeyPresses(true);
                    sendKeyPresses(false);
                }
                invalidate();
            }
        }
        return true;
    }

    @Override
    public void cancelControlInput() {
        if (wheelGesture != null) wheelGesture.cancel();
        releaseLongPressKeys();
        if (actionGestures != null) {
            long now = android.os.SystemClock.uptimeMillis();
            MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
            actionGestures.onTouchEvent(cancel);
            cancel.recycle();
            actionGestures = null;
        }
        mIsToggled = false;
        if (isActivated()) sendKeyPresses(false);
        mIsPointerOutOfBounds = false;
        invalidate();
    }


    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean triggerToggle(){
        //returns true a the toggle system is triggered
        if(mProperties.isToggle){
            mIsToggled = !mIsToggled;
            invalidate();
            sendKeyPresses(mIsToggled);
            return true;
        }
        return false;
    }

    /** 按下缩小、抬起回弹：FCL / ZL2 都有这个手感，缺了会觉得按钮「没反应」。 */
    private void animatePressFeedback(boolean pressed) {
        animate().cancel();
        animate().scaleX(pressed ? 0.94f : 1f).scaleY(pressed ? 0.94f : 1f)
                .setDuration(pressed ? 60 : 100).start();
    }

    public void sendKeyPresses(boolean isDown){
        boolean startsPress = isDown && !isActivated();
        setActivated(isDown);
        if (!isDown) {
            removeCallbacks(repeatScroll);
            removeCallbacks(repeatClick);
        }
        if (startsPress && mProperties.scrollWhileHeld != 0) repeatScroll.run();
        if (startsPress && mProperties.autoClick) repeatClick.run();
        for (int keycode : mProperties.keycodes) {
            if (!mProperties.autoClick || !isRepeatableKey(keycode)) dispatchKey(keycode, isDown, startsPress);
        }
    }

    private static boolean isRepeatableKey(int key) {
        return key > 0 || key == ControlData.SPECIALBTN_MOUSEPRI || key == ControlData.SPECIALBTN_MOUSESEC ||
                key == ControlData.SPECIALBTN_MOUSEMID || key == ControlData.SPECIALBTN_SCROLLUP ||
                key == ControlData.SPECIALBTN_SCROLLDOWN;
    }

    private void pulseRepeatableKeys(int[] keys) {
        for (int key : keys) if (isRepeatableKey(key)) dispatchKey(key, true, true);
        for (int i = keys.length - 1; i >= 0; i--) {
            if (isRepeatableKey(keys[i])) dispatchKey(keys[i], false, false);
        }
    }

    private void dispatchKey(int keycode, boolean isDown, boolean startsPress) {
        if (keycode == 0) return;
        if (keycode >= GLFW_KEY_UNKNOWN) {
            sendKeyPress(keycode, CallbackBridge.getCurrentMods(), isDown);
            CallbackBridge.setModifiers(keycode, isDown);
        } else {
            sendSpecialKey(keycode, isDown, startsPress);
        }
    }

    private void sendSpecialKey(int keycode, boolean isDown, boolean startsPress){
        switch (keycode) {
            case ControlData.SPECIALBTN_KEYBOARD:
                if(isDown) GameActivity.switchKeyboardState();
                break;

            case ControlData.SPECIALBTN_TOGGLECTRL:
                if(isDown)getControlLayoutParent().toggleControlVisible();
                break;

            case ControlData.SPECIALBTN_VIRTUALMOUSE:
                if(isDown) GameActivity.toggleMouse(getContext());
                break;

            case ControlData.SPECIALBTN_MOUSEPRI:
                sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, isDown);
                break;

            case ControlData.SPECIALBTN_MOUSEMID:
            case ControlData.SPECIALBTN_WHEEL_GESTURE:
                sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_MIDDLE, isDown);
                break;

            case ControlData.SPECIALBTN_MOUSESEC:
                sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT, isDown);
                break;

            case ControlData.SPECIALBTN_SCROLLDOWN:
                if (startsPress) CallbackBridge.sendScroll(0, -1d);
                break;

            case ControlData.SPECIALBTN_SCROLLUP:
                if (startsPress) CallbackBridge.sendScroll(0, 1d);
                break;
            case ControlData.SPECIALBTN_MENU:
                if (startsPress) mControlLayout.notifyAppMenu();
                break;

            case ControlData.SPECIALBTN_FORCE_GAMEPLAY:
                if (isDown) getControlLayoutParent().forceScene(ControlScene.GAMEPLAY);
                break;

            case ControlData.SPECIALBTN_FORCE_MENU:
                if (isDown) getControlLayoutParent().forceScene(ControlScene.MENU);
                break;
        }
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }
}
