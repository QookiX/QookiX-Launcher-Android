package net.kdt.pojavlaunch.customcontrols;

import android.content.Context;
import androidx.appcompat.app.AlertDialog;
import com.zhayi.qookix.R;

public final class ControlSceneDialog {
    private ControlSceneDialog() {}

    public static void show(ControlLayout layout) {
        CustomControls controls = layout.getLayout();
        if (controls == null) return;
        Context context = layout.getContext();
        CharSequence[] choices = {
                context.getText(R.string.qk_control_scene_all),
                context.getText(R.string.qk_control_scene_gameplay),
                context.getText(R.string.qk_control_scene_menu),
        };
        new AlertDialog.Builder(context, R.style.QookixAlertDialog)
                .setTitle(R.string.qk_control_scene)
                .setSingleChoiceItems(choices, layout.getEditorScene().ordinal(), (dialog, position) -> {
                    layout.setEditorScene(ControlScene.values()[position]);
                    dialog.dismiss();
                })
                .setNeutralButton(R.string.qk_control_external_hide,
                        (dialog, button) -> showExternalOptions(layout))
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    /**
     * 外接设备隐藏策略：鼠标/键盘 与 手柄 分开配置（对齐 ZL2 的
     * hideWhenMouse / hideWhenGamepad），默认两项都勾选 = 老行为。
     */
    private static void showExternalOptions(ControlLayout layout) {
        CustomControls controls = layout.getLayout();
        if (controls == null) return;
        Context context = layout.getContext();
        String[] labels = {"接鼠标或键盘时隐藏触控键", "接手柄时隐藏触控键"};
        boolean[] checked = {
                controls.hideTouchControlsWithExternalInput,
                controls.hideTouchControlsWithExternalInput && !controls.keepTouchControlsWithGamepad,
        };
        new AlertDialog.Builder(context, R.style.QookixAlertDialog)
                .setTitle("外接设备")
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> {
                    if (which == 0) {
                        controls.hideTouchControlsWithExternalInput = isChecked;
                        // 关掉鼠标隐藏时手柄项也没意义，一并归零
                        if (!isChecked) controls.keepTouchControlsWithGamepad = false;
                    } else {
                        controls.keepTouchControlsWithGamepad = !isChecked;
                    }
                    layout.setModified(true);
                    layout.refreshControlVisibility();
                })
                .setPositiveButton("完成", null)
                .show();
    }
}
