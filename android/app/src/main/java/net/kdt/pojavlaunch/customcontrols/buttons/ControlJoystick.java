package net.kdt.pojavlaunch.customcontrols.buttons;

import static net.kdt.pojavlaunch.customcontrols.gamepad.GamepadJoystick.DIRECTION_EAST;
import static net.kdt.pojavlaunch.customcontrols.gamepad.GamepadJoystick.DIRECTION_NONE;
import static net.kdt.pojavlaunch.customcontrols.gamepad.GamepadJoystick.DIRECTION_NORTH;
import static net.kdt.pojavlaunch.customcontrols.gamepad.GamepadJoystick.DIRECTION_NORTH_EAST;
import static net.kdt.pojavlaunch.customcontrols.gamepad.GamepadJoystick.DIRECTION_NORTH_WEST;
import static net.kdt.pojavlaunch.customcontrols.gamepad.GamepadJoystick.DIRECTION_SOUTH;
import static net.kdt.pojavlaunch.customcontrols.gamepad.GamepadJoystick.DIRECTION_SOUTH_EAST;
import static net.kdt.pojavlaunch.customcontrols.gamepad.GamepadJoystick.DIRECTION_SOUTH_WEST;
import static net.kdt.pojavlaunch.customcontrols.gamepad.GamepadJoystick.DIRECTION_WEST;

import android.annotation.SuppressLint;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.customcontrols.ControlData;
import net.kdt.pojavlaunch.customcontrols.ControlJoystickData;
import net.kdt.pojavlaunch.customcontrols.ControlLayout;
import net.kdt.pojavlaunch.customcontrols.gamepad.GamepadJoystick;
import net.kdt.pojavlaunch.customcontrols.handleview.EditControlSideDialog;

import org.lwjgl.glfw.CallbackBridge;

import io.github.controlwear.virtual.joystick.android.JoystickView;

@SuppressLint("ViewConstructor")
public class ControlJoystick extends JoystickView implements ControlInterface {
    public final static int DIRECTION_FORWARD_LOCK = 8;
    private ControlJoystickData mControlData;
    private final Paint padPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private long centerTapTime = -1;
    private boolean centerTapCandidate;
    private boolean doubleTapKeyHeld;
    private float centerTapX, centerTapY;
    private int mLastDirectionInt = GamepadJoystick.DIRECTION_NONE;
    private int mCurrentDirectionInt = GamepadJoystick.DIRECTION_NONE;
    public ControlJoystick(ControlLayout parent, ControlJoystickData data) {
        super(parent.getContext());
        init(data, parent);
    }

    private static void sendInput(int[] keys, boolean isDown) {
        if (keys == null) return;
        for (int key : keys) {
            if (key == 0) continue;
            CallbackBridge.sendKeyPress(key, CallbackBridge.getCurrentMods(), isDown);
            CallbackBridge.setModifiers(key, isDown);
        }
    }

    private void init(ControlJoystickData data, ControlLayout layout) {
        mControlData = data;
        setProperties(preProcessProperties(data, layout));
        setFixedCenter(data.directionPad || data.absolute);
        setAutoReCenterButton(true);

        injectBehaviors();

        setOnMoveListener(new OnMoveListener() {
            @Override
            public void onMove(int angle, int strength) {
                mLastDirectionInt = mCurrentDirectionInt;
                mCurrentDirectionInt = getDirectionInt(angle, strength);

                if (mLastDirectionInt != mCurrentDirectionInt) {
                    sendDirectionalKeycode(mLastDirectionInt, false);
                    sendDirectionalKeycode(mCurrentDirectionInt, true);
                }
            }

            @Override
            public void onForwardLock(boolean isLocked) {
                sendInput(mControlData.forwardLockKeys, isLocked);
            }
        });
    }

    @Override
    public View getControlView() {
        return this;
    }

    @Override
    public ControlData getProperties() {
        return mControlData;
    }

    @Override
    public void setProperties(ControlData properties, boolean changePos) {
        mControlData = (ControlJoystickData) properties;
        mControlData.isHideable = true;
        ControlInterface.super.setProperties(properties, changePos);
        setDeadzone(mControlData.deadZonePercent);
        configureImportedTracking(mControlData.centerFollow, mControlData.triggerOnMove, mControlData.forwardLockRatio);
        postDelayed(() -> {
            setForwardLockDistance(mControlData.forwardLock ? (int) Tools.dpToPx(60) : 0);
            setFixedCenter(mControlData.directionPad || mControlData.absolute);
        }, 10);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!mControlData.directionPad) {
            super.onDraw(canvas);
            return;
        }
        float unit = Math.min(getWidth(), getHeight()) / 3f;
        drawPadTile(canvas, unit, 1, 0, "▲", mCurrentDirectionInt >= 1 && mCurrentDirectionInt <= 3);
        drawPadTile(canvas, unit, 0, 1, "◀", mCurrentDirectionInt >= 3 && mCurrentDirectionInt <= 5);
        drawPadTile(canvas, unit, 1, 1, "", false);
        drawPadTile(canvas, unit, 2, 1, "▶", mCurrentDirectionInt == 0 || mCurrentDirectionInt == 1 || mCurrentDirectionInt == 7);
        drawPadTile(canvas, unit, 1, 2, "▼", mCurrentDirectionInt >= 5 && mCurrentDirectionInt <= 7);
    }

    private void drawPadTile(Canvas canvas, float unit, int column, int row, String label, boolean held) {
        float x = column * unit;
        float y = row * unit;
        float gap = unit * 0.05f;
        padPaint.setColor(held ? mControlData.strokeColor : mControlData.bgColor);
        canvas.drawRoundRect(x + gap, y + gap, x + unit - gap, y + unit - gap, gap * 2, gap * 2, padPaint);
        padPaint.setColor(0xffffffff);
        padPaint.setTextAlign(Paint.Align.CENTER);
        padPaint.setTextSize(unit * 0.4f);
        canvas.drawText(label, x + unit / 2, y + unit / 2 - (padPaint.ascent() + padPaint.descent()) / 2, padPaint);
    }

    @Override
    public void removeButton() {
        getControlLayoutParent().getLayout().mJoystickDataList.remove(getProperties());
        getControlLayoutParent().removeView(this);
    }

    @Override
    public void cloneButton() {
        ControlJoystickData data = new ControlJoystickData(mControlData);
        getControlLayoutParent().addJoystickButton(data);
    }


    @Override
    public void setBackground() {
        setBorderWidth((int) Tools.dpToPx(getProperties().strokeWidth * (getControlLayoutParent().getLayoutScale()/100f)));
        setBorderColor(getProperties().strokeColor);
        setBackgroundColor(getProperties().bgColor);
    }

    @Override
    public void sendKeyPresses(boolean isDown) {/*STUB since non swipeable*/ }

    @Override
    public void cancelControlInput() {
        resetJoystickInput();
        centerTapTime = -1;
        centerTapCandidate = false;
        if (doubleTapKeyHeld) toggleDoubleTapKey();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mControlData.doubleTapKey >= 32 && mControlData.doubleTapKey <= 348) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    centerTapCandidate = isCenterTouch(event);
                    centerTapX = event.getX();
                    centerTapY = event.getY();
                    if (!centerTapCandidate) centerTapTime = -1;
                    break;
                case MotionEvent.ACTION_MOVE:
                    int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
                    if (Math.hypot(event.getX() - centerTapX, event.getY() - centerTapY) > slop) {
                        centerTapCandidate = false;
                        centerTapTime = -1;
                    }
                    break;
                case MotionEvent.ACTION_CANCEL:
                    centerTapCandidate = false;
                    centerTapTime = -1;
                    break;
                case MotionEvent.ACTION_UP:
                    long now = event.getEventTime();
                    if (centerTapCandidate && now - event.getDownTime() <= ViewConfiguration.getTapTimeout()) {
                        if (centerTapTime >= 0 && now - centerTapTime <= 400) {
                            toggleDoubleTapKey();
                            centerTapTime = -1;
                        } else centerTapTime = now;
                    } else centerTapTime = -1;
                    centerTapCandidate = false;
                    break;
            }
        }
        return super.onTouchEvent(event);
    }

    private void toggleDoubleTapKey() {
        doubleTapKeyHeld = !doubleTapKeyHeld;
        sendInput(new int[]{mControlData.doubleTapKey}, doubleTapKeyHeld);
    }

    @Override
    public void loadEditValues(EditControlSideDialog editControlPopup) {
        editControlPopup.loadJoystickValues(mControlData);
    }

    private int getDirectionInt(int angle, int intensity) {
        if (intensity == 0) return DIRECTION_NONE;
        return (int) (((angle + 22.5) / 45) % 8);
    }

    private void sendDirectionalKeycode(int direction, boolean isDown) {
        int[][] overrides = mControlData.directionKeys;
        if (overrides != null && direction >= 0 && direction < overrides.length && overrides[direction] != null) {
            sendInput(overrides[direction], isDown);
            return;
        }
        switch (direction) {
            case DIRECTION_NORTH:
                sendInput(mControlData.forwardKeys, isDown);
                break;
            case DIRECTION_NORTH_EAST:
                sendInput(mControlData.forwardKeys, isDown);
                sendInput(mControlData.rightKeys, isDown);
                break;
            case DIRECTION_EAST:
                sendInput(mControlData.rightKeys, isDown);
                break;
            case DIRECTION_SOUTH_EAST:
                sendInput(mControlData.rightKeys, isDown);
                sendInput(mControlData.backwardKeys, isDown);
                break;
            case DIRECTION_SOUTH:
                sendInput(mControlData.backwardKeys, isDown);
                break;
            case DIRECTION_SOUTH_WEST:
                sendInput(mControlData.backwardKeys, isDown);
                sendInput(mControlData.leftKeys, isDown);
                break;
            case DIRECTION_WEST:
                sendInput(mControlData.leftKeys, isDown);
                break;
            case DIRECTION_NORTH_WEST:
                sendInput(mControlData.forwardKeys, isDown);
                sendInput(mControlData.leftKeys, isDown);
                break;
            case DIRECTION_FORWARD_LOCK:
                sendInput(mControlData.forwardLockKeys, isDown);
                break;
        }
    }

}
