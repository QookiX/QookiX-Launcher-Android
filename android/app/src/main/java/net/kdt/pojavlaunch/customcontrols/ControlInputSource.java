package net.kdt.pojavlaunch.customcontrols;

import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

public final class ControlInputSource {
    private static final int[] GAMEPAD_AXES = {
            MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
            MotionEvent.AXIS_RX, MotionEvent.AXIS_RY, MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y,
            MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER,
    };

    private ControlInputSource() {}

    public static boolean beginsTouch(MotionEvent event) {
        return event.getActionMasked() == MotionEvent.ACTION_DOWN
                && (event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN) || event.isFromSource(InputDevice.SOURCE_STYLUS));
    }

    public static boolean isExternalKey(KeyEvent event) {
        InputDevice device = event.getDevice();
        return event.getAction() == KeyEvent.ACTION_DOWN && device != null && !device.isVirtual()
                && (device.supportsSource(InputDevice.SOURCE_GAMEPAD)
                || device.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC);
    }

    public static boolean isExternalMotion(MotionEvent event) {
        int action = event.getActionMasked();
        if (event.isFromSource(InputDevice.SOURCE_MOUSE) || event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)) {
            return action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_BUTTON_PRESS
                    || action == MotionEvent.ACTION_HOVER_MOVE || action == MotionEvent.ACTION_MOVE
                    || action == MotionEvent.ACTION_SCROLL;
        }
        if (action != MotionEvent.ACTION_MOVE || !event.isFromSource(InputDevice.SOURCE_JOYSTICK)) return false;
        InputDevice device = event.getDevice();
        for (int axis : GAMEPAD_AXES) {
            InputDevice.MotionRange range = device == null ? null : device.getMotionRange(axis, event.getSource());
            if (Math.abs(event.getAxisValue(axis)) > (range == null ? 0.25f : Math.max(0.25f, range.getFlat()))) return true;
        }
        return false;
    }
}
