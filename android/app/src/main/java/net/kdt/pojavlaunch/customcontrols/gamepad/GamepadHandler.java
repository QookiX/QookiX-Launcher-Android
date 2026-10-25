package net.kdt.pojavlaunch.customcontrols.gamepad;

/**
 * 手柄输入的落点：把「一个按键码或轴 + 值」交给实现方处理。
 *
 * 由 {@link GamepadInputDispatcher} 解析原始事件后回调，
 * 实现方是 {@link Gamepad}（转成键鼠）或 direct 模式下的手柄缓冲写入器。
 */
public interface GamepadHandler {
    /**
     * @param keycode {@code KeyEvent.KEYCODE_*}（按键）或 {@code MotionEvent.AXIS_*}（摇杆 / 扳机 / 十字键轴）
     * @param value   按键：1 = 按下，0 = 抬起；轴：-1.0 ~ 1.0
     */
    void handleGamepadInput(int keycode, float value);
}
