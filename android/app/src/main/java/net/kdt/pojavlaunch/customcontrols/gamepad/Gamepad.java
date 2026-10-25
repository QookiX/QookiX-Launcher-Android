package net.kdt.pojavlaunch.customcontrols.gamepad;

import static android.view.MotionEvent.AXIS_HAT_X;
import static android.view.MotionEvent.AXIS_HAT_Y;
import static android.view.MotionEvent.AXIS_LTRIGGER;
import static android.view.MotionEvent.AXIS_RTRIGGER;
import static android.view.MotionEvent.AXIS_RZ;
import static android.view.MotionEvent.AXIS_X;
import static android.view.MotionEvent.AXIS_Y;
import static android.view.MotionEvent.AXIS_Z;

import static net.kdt.pojavlaunch.customcontrols.gamepad.GamepadJoystick.DIRECTION_EAST;
import static net.kdt.pojavlaunch.customcontrols.gamepad.GamepadJoystick.DIRECTION_NONE;
import static net.kdt.pojavlaunch.customcontrols.gamepad.GamepadJoystick.DIRECTION_NORTH;
import static net.kdt.pojavlaunch.customcontrols.gamepad.GamepadJoystick.DIRECTION_NORTH_EAST;
import static net.kdt.pojavlaunch.customcontrols.gamepad.GamepadJoystick.DIRECTION_NORTH_WEST;
import static net.kdt.pojavlaunch.customcontrols.gamepad.GamepadJoystick.DIRECTION_SOUTH;
import static net.kdt.pojavlaunch.customcontrols.gamepad.GamepadJoystick.DIRECTION_SOUTH_EAST;
import static net.kdt.pojavlaunch.customcontrols.gamepad.GamepadJoystick.DIRECTION_SOUTH_WEST;
import static net.kdt.pojavlaunch.customcontrols.gamepad.GamepadJoystick.DIRECTION_WEST;
import static net.kdt.pojavlaunch.customcontrols.gamepad.GamepadJoystick.isJoystickEvent;
import static net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_SCALE_FACTOR;
import static net.kdt.pojavlaunch.utils.MCOptionUtils.getMcScale;
import static org.lwjgl.glfw.CallbackBridge.sendKeyPress;
import static org.lwjgl.glfw.CallbackBridge.sendMouseButton;

import android.content.Context;
import android.util.DisplayMetrics;
import android.view.Choreographer;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.core.content.res.ResourcesCompat;

import com.zhayi.qookix.R;
import net.kdt.pojavlaunch.GrabListener;
import net.kdt.pojavlaunch.LwjglGlfwKeycode;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.utils.MCOptionUtils;

import org.lwjgl.glfw.CallbackBridge;

/**
 * 实体手柄 → 键鼠模拟。
 *
 * Minecraft 原版不认手柄，所以这里把柄上的操作翻译成游戏认识的输入：
 * 按键 → 对应键（映射表见 {@link GamepadMap}），左摇杆 → WASD，右摇杆 → 转视角
 * （直接改 {@link CallbackBridge#mouseX} / {@link CallbackBridge#mouseY}），
 * 菜单里则换成另一张表（A 当鼠标左键、B 当 ESC、摇杆当滚轮）。
 *
 * 视角在没有抓取光标时会额外画一个虚拟光标（游戏自己的光标此时停在真实位置上，
 * 不画的话菜单里看不到鼠标在哪）。
 */
public class Gamepad implements GrabListener, GamepadHandler {

    /** 灵敏度按屏幕尺寸归一：手机屏小，同样的手势要转得慢一些才稳。 */
    private final double mSensitivityFactor;

    private final ImageView mPointerImageView;

    private final GamepadJoystick mLeftJoystick;
    private int mCurrentJoystickDirection = DIRECTION_NONE;

    private final GamepadJoystick mRightJoystick;
    private float mLastHorizontalValue = 0.0f;
    private float mLastVerticalValue = 0.0f;

    private static final double MOUSE_MAX_ACCELERATION = 2f;

    private double mMouseMagnitude;
    private double mMouseAngle;
    private double mMouseSensitivity = 19;

    private GamepadMap mGameMap;
    private GamepadMap mMenuMap;
    private GamepadMap mCurrentMap;

    private boolean isGrabbing;

    /* 每帧回调，用帧间隔把鼠标位移归一化，帧率变化时手感不变 */
    private final Choreographer mScreenChoreographer;
    private long mLastFrameTime;

    /** GUI 缩放变了要跟着改光标大小 */
    private final MCOptionUtils.MCOptionListener mGuiScaleListener = () -> notifyGUISizeChange(getMcScale());

    private boolean mRemoved = false;

    public Gamepad(View contextView, InputDevice inputDevice, boolean showCursor) {
        DisplayMetrics metrics = Tools.currentDisplayMetrics;
        mSensitivityFactor = (metrics == null || metrics.heightPixels == 0)
                ? 1.0
                : (1.4 * (1080f / metrics.heightPixels));

        mScreenChoreographer = Choreographer.getInstance();
        Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
            @Override
            public void doFrame(long frameTimeNanos) {
                tick(frameTimeNanos);
                if (!mRemoved) mScreenChoreographer.postFrameCallback(this);
            }
        };
        mScreenChoreographer.postFrameCallback(frameCallback);
        mLastFrameTime = System.nanoTime();

        MCOptionUtils.addMCOptionListener(mGuiScaleListener);

        mLeftJoystick = new GamepadJoystick(AXIS_X, AXIS_Y, inputDevice);
        mRightJoystick = new GamepadJoystick(AXIS_Z, AXIS_RZ, inputDevice);

        Context ctx = contextView.getContext();
        mPointerImageView = new ImageView(ctx);
        mPointerImageView.setImageDrawable(
                ResourcesCompat.getDrawable(ctx.getResources(), R.drawable.ic_gamepad_pointer, ctx.getTheme()));
        mPointerImageView.setLayoutParams(new FrameLayout.LayoutParams(pointerSize(getMcScale()), pointerSize(getMcScale())));

        CallbackBridge.sendCursorPos(CallbackBridge.windowWidth / 2f, CallbackBridge.windowHeight / 2f);

        if (showCursor && contextView.getParent() instanceof ViewGroup) {
            ((ViewGroup) contextView.getParent()).addView(mPointerImageView);
        }

        placePointerView(CallbackBridge.physicalWidth / 2, CallbackBridge.physicalHeight / 2);

        reloadGamepadMaps();
        CallbackBridge.addGrabListener(this);
    }

    private static int pointerSize(int guiScale) {
        return (int) ((22 * guiScale) / PREF_SCALE_FACTOR);
    }

    /** 重建映射表（切回游戏内/菜单时会重来一遍）。 */
    public void reloadGamepadMaps() {
        if (mGameMap != null) mGameMap.resetPressedState();
        if (mMenuMap != null) mMenuMap.resetPressedState();
        mGameMap = GamepadMap.getDefaultGameMap();
        mMenuMap = GamepadMap.getDefaultMenuMap();
        mCurrentMap = mGameMap;
        // 强制刷新一次当前状态
        boolean currentGrab = CallbackBridge.isGrabbing();
        isGrabbing = !currentGrab;
        onGrabState(currentGrab);
    }

    public void updateJoysticks() {
        updateDirectionalJoystick();
        updateMouseJoystick();
    }

    public void notifyGUISizeChange(int newSize) {
        int size = pointerSize(newSize);
        mPointerImageView.post(() -> mPointerImageView.setLayoutParams(new FrameLayout.LayoutParams(size, size)));
    }

    /** 把一张映射表里的键码按「按下/抬起」发给游戏。 */
    public static void sendInput(short[] keycodes, boolean isDown) {
        for (short keycode : keycodes) {
            switch (keycode) {
                case GamepadMap.MOUSE_SCROLL_DOWN:
                    if (isDown) CallbackBridge.sendScroll(0, -1);
                    break;
                case GamepadMap.MOUSE_SCROLL_UP:
                    if (isDown) CallbackBridge.sendScroll(0, 1);
                    break;
                case GamepadMap.MOUSE_LEFT:
                    sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, isDown);
                    break;
                case GamepadMap.MOUSE_MIDDLE:
                    sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_MIDDLE, isDown);
                    break;
                case GamepadMap.MOUSE_RIGHT:
                    sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT, isDown);
                    break;
                case GamepadMap.UNSPECIFIED:
                    break;
                default:
                    sendKeyPress(keycode, CallbackBridge.getCurrentMods(), isDown);
                    CallbackBridge.setModifiers(keycode, isDown);
                    break;
            }
        }
    }

    /** 摇杆/轴的通用判断（按键由 {@link GamepadDpad} 判）。 */
    public static boolean isGamepadEvent(MotionEvent event) {
        return isJoystickEvent(event);
    }

    public static boolean isGamepadEvent(KeyEvent event) {
        boolean isGamepad = ((event.getSource() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD)
                || ((event.getDevice() != null)
                    && ((event.getDevice().getSources() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD));
        return isGamepad && GamepadDpad.isDpadEvent(event);
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : (value > max ? max : value);
    }

    /** 每帧按右摇杆的角度/幅度推进虚拟鼠标位置。 */
    private void tick(long frameTimeNanos) {
        long newFrameTime = System.nanoTime();
        if (mLastHorizontalValue != 0 || mLastVerticalValue != 0) {
            double acceleration = Math.pow(mMouseMagnitude, MOUSE_MAX_ACCELERATION);
            if (acceleration > 1) acceleration = 1;

            // 距离 = 角度方向 × 加速度 × 灵敏度；再按帧间隔归一（1.0 = 60Hz）
            float deltaX = (float) (Math.cos(mMouseAngle) * acceleration * mMouseSensitivity);
            float deltaY = (float) (Math.sin(mMouseAngle) * acceleration * mMouseSensitivity);
            newFrameTime = System.nanoTime();
            float deltaTimeScale = ((newFrameTime - mLastFrameTime) / 16666666f);
            deltaX *= deltaTimeScale;
            deltaY *= deltaTimeScale;

            CallbackBridge.mouseX += deltaX;
            CallbackBridge.mouseY -= deltaY;

            if (!isGrabbing) {
                // 没抓取时鼠标要停在窗口内，并让虚拟光标跟上
                CallbackBridge.mouseX = clamp(CallbackBridge.mouseX, 0, CallbackBridge.windowWidth);
                CallbackBridge.mouseY = clamp(CallbackBridge.mouseY, 0, CallbackBridge.windowHeight);
                placePointerView((int) (CallbackBridge.mouseX / PREF_SCALE_FACTOR),
                                 (int) (CallbackBridge.mouseY / PREF_SCALE_FACTOR));
            }

            CallbackBridge.sendCursorPos(CallbackBridge.mouseX, CallbackBridge.mouseY);
        }
        mLastFrameTime = newFrameTime;
    }

    private void updateMouseJoystick() {
        GamepadJoystick currentJoystick = isGrabbing ? mRightJoystick : mLeftJoystick;
        float horizontalValue = currentJoystick.getHorizontalAxis();
        float verticalValue = currentJoystick.getVerticalAxis();
        if (horizontalValue != mLastHorizontalValue || verticalValue != mLastVerticalValue) {
            mLastHorizontalValue = horizontalValue;
            mLastVerticalValue = verticalValue;

            mMouseMagnitude = currentJoystick.getMagnitude();
            mMouseAngle = currentJoystick.getAngleRadian();

            tick(System.nanoTime());
            return;
        }
        mLastHorizontalValue = horizontalValue;
        mLastVerticalValue = verticalValue;

        mMouseMagnitude = currentJoystick.getMagnitude();
        mMouseAngle = currentJoystick.getAngleRadian();
    }

    /** 摇杆量化成 8 个方向，只在方向变化时补发抬起/按下。 */
    private void updateDirectionalJoystick() {
        GamepadJoystick currentJoystick = isGrabbing ? mLeftJoystick : mRightJoystick;

        int lastJoystickDirection = mCurrentJoystickDirection;
        mCurrentJoystickDirection = currentJoystick.getHeightDirection();

        if (mCurrentJoystickDirection == lastJoystickDirection) return;

        sendDirectionalKeycode(lastJoystickDirection, false, getCurrentMap());
        sendDirectionalKeycode(mCurrentJoystickDirection, true, getCurrentMap());
    }

    private GamepadMap getCurrentMap() {
        return mCurrentMap;
    }

    private static void sendDirectionalKeycode(int direction, boolean isDown, GamepadMap map) {
        switch (direction) {
            case DIRECTION_NORTH:
                map.DIRECTION_FORWARD.update(isDown);
                break;
            case DIRECTION_NORTH_EAST:
                map.DIRECTION_FORWARD.update(isDown);
                map.DIRECTION_RIGHT.update(isDown);
                break;
            case DIRECTION_EAST:
                map.DIRECTION_RIGHT.update(isDown);
                break;
            case DIRECTION_SOUTH_EAST:
                map.DIRECTION_RIGHT.update(isDown);
                map.DIRECTION_BACKWARD.update(isDown);
                break;
            case DIRECTION_SOUTH:
                map.DIRECTION_BACKWARD.update(isDown);
                break;
            case DIRECTION_SOUTH_WEST:
                map.DIRECTION_BACKWARD.update(isDown);
                map.DIRECTION_LEFT.update(isDown);
                break;
            case DIRECTION_WEST:
                map.DIRECTION_LEFT.update(isDown);
                break;
            case DIRECTION_NORTH_WEST:
                map.DIRECTION_FORWARD.update(isDown);
                map.DIRECTION_LEFT.update(isDown);
                break;
        }
    }

    private void placePointerView(int x, int y) {
        mPointerImageView.setX(x - mPointerImageView.getWidth() / 2f);
        mPointerImageView.setY(y - mPointerImageView.getHeight() / 2f);
    }

    /** 光标抓取状态变化：换映射表、换灵敏度、显隐虚拟光标。 */
    @Override
    public void onGrabState(boolean isGrabbing) {
        boolean lastGrabbingValue = this.isGrabbing;
        this.isGrabbing = isGrabbing;
        if (lastGrabbingValue == isGrabbing) return;

        mCurrentMap.resetPressedState();
        if (isGrabbing) {
            mCurrentMap = mGameMap;
            mPointerImageView.setVisibility(View.INVISIBLE);
            mMouseSensitivity = 18;
            return;
        }

        mCurrentMap = mMenuMap;
        // 松开之前按住的方向键，否则回到菜单后角色会自己走
        sendDirectionalKeycode(mCurrentJoystickDirection, false, mGameMap);

        CallbackBridge.sendCursorPos(CallbackBridge.windowWidth / 2f, CallbackBridge.windowHeight / 2f);
        placePointerView(CallbackBridge.physicalWidth / 2, CallbackBridge.physicalHeight / 2);
        mPointerImageView.setVisibility(View.VISIBLE);
        // 菜单里鼠标速度与分辨率相关，按屏幕尺寸换算
        mMouseSensitivity = 19 * PREF_SCALE_FACTOR / mSensitivityFactor;
    }

    @Override
    public void handleGamepadInput(int keycode, float value) {
        boolean isKeyEventDown = value == 1f;
        switch (keycode) {
            case KeyEvent.KEYCODE_BUTTON_A:
                getCurrentMap().BUTTON_A.update(isKeyEventDown);
                break;
            case KeyEvent.KEYCODE_BUTTON_B:
                getCurrentMap().BUTTON_B.update(isKeyEventDown);
                break;
            case KeyEvent.KEYCODE_BUTTON_X:
                getCurrentMap().BUTTON_X.update(isKeyEventDown);
                break;
            case KeyEvent.KEYCODE_BUTTON_Y:
                getCurrentMap().BUTTON_Y.update(isKeyEventDown);
                break;

            case KeyEvent.KEYCODE_BUTTON_L1:
                getCurrentMap().SHOULDER_LEFT.update(isKeyEventDown);
                break;
            case KeyEvent.KEYCODE_BUTTON_R1:
                getCurrentMap().SHOULDER_RIGHT.update(isKeyEventDown);
                break;

            case KeyEvent.KEYCODE_BUTTON_L2:
                getCurrentMap().TRIGGER_LEFT.update(isKeyEventDown);
                break;
            case KeyEvent.KEYCODE_BUTTON_R2:
                getCurrentMap().TRIGGER_RIGHT.update(isKeyEventDown);
                break;

            case KeyEvent.KEYCODE_BUTTON_THUMBL:
                getCurrentMap().THUMBSTICK_LEFT.update(isKeyEventDown);
                break;
            case KeyEvent.KEYCODE_BUTTON_THUMBR:
                getCurrentMap().THUMBSTICK_RIGHT.update(isKeyEventDown);
                break;

            case KeyEvent.KEYCODE_DPAD_UP:
                getCurrentMap().DPAD_UP.update(isKeyEventDown);
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                getCurrentMap().DPAD_DOWN.update(isKeyEventDown);
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                getCurrentMap().DPAD_LEFT.update(isKeyEventDown);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                getCurrentMap().DPAD_RIGHT.update(isKeyEventDown);
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
                // 十字键中心按下：当作四个方向都松开
                getCurrentMap().DPAD_RIGHT.update(false);
                getCurrentMap().DPAD_LEFT.update(false);
                getCurrentMap().DPAD_UP.update(false);
                getCurrentMap().DPAD_DOWN.update(false);
                break;

            case KeyEvent.KEYCODE_BUTTON_START:
                getCurrentMap().BUTTON_START.update(isKeyEventDown);
                break;
            case KeyEvent.KEYCODE_BUTTON_SELECT:
                getCurrentMap().BUTTON_SELECT.update(isKeyEventDown);
                break;

            /* 以下是轴（摇杆 / 十字键 / 扳机） */
            case AXIS_HAT_X:
                getCurrentMap().DPAD_RIGHT.update(value > 0.85);
                getCurrentMap().DPAD_LEFT.update(value < -0.85);
                break;
            case AXIS_HAT_Y:
                getCurrentMap().DPAD_DOWN.update(value > 0.85);
                getCurrentMap().DPAD_UP.update(value < -0.85);
                break;

            case AXIS_X:
                mLeftJoystick.setXAxisValue(value);
                updateJoysticks();
                break;
            case AXIS_Y:
                mLeftJoystick.setYAxisValue(value);
                updateJoysticks();
                break;

            case AXIS_Z:
                mRightJoystick.setXAxisValue(value);
                updateJoysticks();
                break;
            case AXIS_RZ:
                mRightJoystick.setYAxisValue(value);
                updateJoysticks();
                break;

            case AXIS_RTRIGGER:
                getCurrentMap().TRIGGER_RIGHT.update(value > 0.5);
                break;
            case AXIS_LTRIGGER:
                getCurrentMap().TRIGGER_LEFT.update(value > 0.5);
                break;

            default:
                // 认不出来的键（各家的扩展键）不动作：总比乱发一个空格好
                break;
        }
    }

    /**
     * 停掉手柄并把它加进视图层级的东西全部摘掉。
     * 调用后这个实例不可再用，必须重新 new 一个。
     */
    public void removeSelf() {
        mRemoved = true;
        CallbackBridge.removeGrabListener(this);
        MCOptionUtils.removeMCOptionListener(mGuiScaleListener);
        ViewGroup viewGroup = (ViewGroup) mPointerImageView.getParent();
        if (viewGroup != null) viewGroup.removeView(mPointerImageView);
    }
}
