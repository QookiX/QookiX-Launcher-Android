package net.kdt.pojavlaunch.customcontrols.gamepad;

import static android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC;
import static android.view.InputDevice.SOURCE_GAMEPAD;

import android.view.KeyEvent;

/** 判断一个按键事件是不是手柄方向键发出来的。 */
public class GamepadDpad {
    /**
     * 排除「类键盘」设备：带全键盘的手柄（部分飞行摇杆 / 键盘型手柄）
     * 会把手柄按键也报成字母键，交给键盘路径处理更符合预期。
     */
    public static boolean isDpadEvent(KeyEvent event) {
        return event.isFromSource(SOURCE_GAMEPAD)
                && (event.getDevice() == null
                    || event.getDevice().getKeyboardType() != KEYBOARD_TYPE_ALPHABETIC);
    }
}
