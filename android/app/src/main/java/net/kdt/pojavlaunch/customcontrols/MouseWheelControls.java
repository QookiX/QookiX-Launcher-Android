package net.kdt.pojavlaunch.customcontrols;

import android.content.Context;

import androidx.appcompat.app.AlertDialog;

import com.zhayi.qookix.R;

public final class MouseWheelControls {
    private MouseWheelControls() {}

    public static void showAddDialog(ControlLayout layout) {
        Context context = layout.getContext();
        CharSequence[] directions = {
                context.getText(R.string.customctrl_wheel_gesture),
                context.getText(R.string.customctrl_wheel_up),
                context.getText(R.string.customctrl_wheel_down)
        };
        new AlertDialog.Builder(context, R.style.QookixAlertDialog)
                .setTitle(R.string.customctrl_add_wheel)
                .setItems(directions, (dialog, index) -> {
                    if (index == 0) {
                        layout.addControlButton(new ControlData(context.getString(R.string.customctrl_wheel_middle),
                                new int[]{ControlData.SPECIALBTN_WHEEL_GESTURE}, "0.5 * ${right}", "0.5 * ${bottom}", 58, 48, false));
                        return;
                    }
                    boolean up = index == 1;
                    String name = context.getString(up ? R.string.customctrl_wheel_up_short : R.string.customctrl_wheel_down_short);
                    int keycode = up ? ControlData.SPECIALBTN_SCROLLUP : ControlData.SPECIALBTN_SCROLLDOWN;
                    layout.addControlButton(new ControlData(name, new int[]{keycode},
                            "0.5 * ${right}", up ? "0.40 * ${bottom}" : "0.58 * ${bottom}", 58, 48, false));
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }
}
