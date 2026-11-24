package net.kdt.pojavlaunch.customcontrols.handleview;

import android.content.Context;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.zhayi.qookix.R;

import net.kdt.pojavlaunch.customcontrols.ControlData;
import net.kdt.pojavlaunch.customcontrols.buttons.ControlInterface;

/**
 * 属性面板里的「按下时切换建造 / 战斗手势」（FCL 的 switchTouchMode）。
 *
 * 运行时由 {@code ControlButton.runActionExtras} 消费：切换后触摸板长按
 * 分别用左键 / 右键抓取（建造适合拖拽，战斗适合攻击）。这里只负责把
 * 这个动作开关写进 pressAction。
 */
final class ControlActionExtras {
    private ControlActionExtras() {}

    static void update(TextView view, ControlInterface button) {
        ControlData data = button == null ? null : button.getProperties();
        boolean on = data != null && data.pressAction != null && data.pressAction.switchTouchMode;
        view.setText(on ? R.string.customctrl_gesture_switch_on : R.string.customctrl_gesture_switch);
    }

    static void show(Context context, ControlInterface button, Runnable onChanged) {
        ControlData data = button.getProperties();
        boolean on = data.pressAction != null && data.pressAction.switchTouchMode;
        CharSequence[] items = {
                context.getString(R.string.customctrl_gesture_switch_off),
                context.getString(R.string.customctrl_gesture_switch_on),
        };
        new AlertDialog.Builder(context, R.style.QookixAlertDialog)
                .setTitle(R.string.customctrl_gesture_switch)
                .setSingleChoiceItems(items, on ? 1 : 0, (dialog, which) -> {
                    ControlData.Action action = data.pressAction;
                    if (action == null) action = data.pressAction = new ControlData.Action();
                    action.switchTouchMode = which == 1;
                    onChanged.run();
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }
}
