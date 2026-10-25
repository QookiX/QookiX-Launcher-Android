package net.kdt.pojavlaunch.customcontrols.gamepad;

import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import java.util.HashSet;

/**
 * 把原始手柄事件解析成「按键码 / 轴 + 值」，交给 {@link GamepadHandler}。
 *
 * 上游这一步是第三方库 `fr.spse.gamepad_remapper` 干的（只在 JitPack 发布，构建时
 * 拉不到就编不过），这里自己实现，只做手柄能跑起来必需的三件事：
 *
 * 1. 去重 —— 长按的重复 ACTION_DOWN、同一次动作发两遍的 DOWN/UP 都只放行一次；
 * 2. 摇杆死区 —— 设置里的 `gamepad_deadzone_scale` 作用在 {@link #BASE_DEADZONE} 上；
 * 3. 右摇杆轴名兼容 —— 有的柄报 Z/RZ，有的报 RX/RY，统一按 Z/RZ 往上报。
 *
 * 不做自定义重映射（那是要配一套设置界面的事），映射表就是
 * {@link GamepadMap} 里的默认那两张。
 */
public class GamepadInputDispatcher {

    /** 死区基数；设置里的倍率（0.5~2.0，默认 1.0）在它基础上缩放。 */
    private static final float BASE_DEADZONE = 0.15f;

    private final HashSet<Integer> mPressedKeys = new HashSet<>();
    /** 这个手柄的右摇杆报在 RX/RY 上（而不是惯例的 Z/RZ） */
    private final boolean mRightStickOnRxRy;

    public GamepadInputDispatcher(InputDevice device) {
        mRightStickOnRxRy = device != null
                && device.getMotionRange(MotionEvent.AXIS_RX) != null
                && device.getMotionRange(MotionEvent.AXIS_Z) == null;
    }

    public void handleKeyEvent(KeyEvent event, GamepadHandler handler) {
        int keycode = event.getKeyCode();
        // 长按会不断重复 ACTION_DOWN，按住不放只需要第一下
        if (event.getRepeatCount() != 0) return;
        int action = event.getAction();
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) return;

        boolean isDown = action == KeyEvent.ACTION_DOWN;
        if (isDown) {
            if (!mPressedKeys.add(keycode)) return;
        } else {
            if (!mPressedKeys.remove(keycode)) return;
        }
        handler.handleGamepadInput(keycode, isDown ? 1f : 0f);
    }

    public void handleMotionEvent(MotionEvent event, GamepadHandler handler) {
        float deadzone = deadzone();
        int rightX = mRightStickOnRxRy ? MotionEvent.AXIS_RX : MotionEvent.AXIS_Z;
        int rightY = mRightStickOnRxRy ? MotionEvent.AXIS_RY : MotionEvent.AXIS_RZ;

        dispatchAxis(handler, event, MotionEvent.AXIS_X, MotionEvent.AXIS_X, deadzone);
        dispatchAxis(handler, event, MotionEvent.AXIS_Y, MotionEvent.AXIS_Y, deadzone);
        // 右摇杆统一报成 Z/RZ，Gamepad 那边只认这两个
        dispatchAxis(handler, event, rightX, MotionEvent.AXIS_Z, deadzone);
        dispatchAxis(handler, event, rightY, MotionEvent.AXIS_RZ, deadzone);

        // 十字键在部分柄上是轴不是按键；扳机同理
        dispatchAxis(handler, event, MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_X, 0f);
        dispatchAxis(handler, event, MotionEvent.AXIS_HAT_Y, MotionEvent.AXIS_HAT_Y, 0f);
        dispatchAxis(handler, event, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_LTRIGGER, 0f);
        dispatchAxis(handler, event, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_RTRIGGER, 0f);
    }

    /**
     * @param readAxis   从这个轴上取值
     * @param reportAxis 往上汇报成哪个轴（右摇杆兼容用）
     */
    private static void dispatchAxis(GamepadHandler handler, MotionEvent event,
                                     int readAxis, int reportAxis, float deadzone) {
        float value = event.getAxisValue(readAxis);
        if (deadzone > 0f && Math.abs(value) < deadzone) value = 0f;
        handler.handleGamepadInput(reportAxis, value);
    }

    /** 死区上限 0.9：再大就等于摇杆被吃掉了。 */
    private static float deadzone() {
        float scale = LauncherPreferences.PREF_DEADZONE_SCALE;
        return Math.max(0f, Math.min(BASE_DEADZONE * scale, 0.9f));
    }
}
