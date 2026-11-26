package net.kdt.pojavlaunch.customcontrols;

import android.content.Context;
import android.widget.LinearLayout;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;

import com.zhayi.qookix.R;

public final class ControlSceneDialog {
    private ControlSceneDialog() {}

    public static void show(ControlLayout layout) {
        CustomControls controls = layout.getLayout();
        if (controls == null) return;
        Context context = layout.getContext();
        LinearLayout column = ControlEditorUi.dialogRoot(context);

        final SwitchCompat[] mouseRef = new SwitchCompat[1];
        final ControlEditorUi.SegmentedBar[] barRef = new ControlEditorUi.SegmentedBar[1];

        ControlEditorUi.switchRow(context, column, R.string.qk_control_preview_mode,
                layout.isEditorPreview(), (buttonView, isChecked) -> {
                    layout.setEditorPreview(isChecked);
                    if (mouseRef[0] != null) {
                        mouseRef[0].setEnabled(isChecked);
                        ((android.view.View) mouseRef[0].getParent()).setAlpha(isChecked ? 1f : 0.45f);
                        if (!isChecked) mouseRef[0].setChecked(false);
                    }
                    if (barRef[0] != null) {
                        barRef[0].select(layout.getEditorScene().ordinal());
                    }
                });

        column.addView(ControlEditorUi.sectionLabel(context, R.string.qk_control_preview_scene));
        ControlScene[] scenes = ControlScene.values();
        ControlEditorUi.SegmentedBar sceneBar = ControlEditorUi.segmentedBar(
                context,
                new int[]{
                        R.string.qk_control_scene_all,
                        R.string.qk_control_scene_gameplay,
                        R.string.qk_control_scene_menu,
                },
                layout.getEditorScene().ordinal(),
                idx -> layout.setEditorScene(scenes[idx]));
        barRef[0] = sceneBar;
        column.addView(sceneBar.view);

        SwitchCompat mouseSwitch = ControlEditorUi.switchRow(context, column, R.string.qk_control_preview_mouse,
                layout.isPreviewMouse(), (buttonView, isChecked) -> layout.setPreviewMouse(isChecked));
        mouseSwitch.setEnabled(layout.isEditorPreview());
        ((android.view.View) mouseSwitch.getParent()).setAlpha(layout.isEditorPreview() ? 1f : 0.45f);
        mouseRef[0] = mouseSwitch;

        new AlertDialog.Builder(context, R.style.QookixAlertDialog)
                .setTitle(R.string.qk_control_scene)
                .setView(ControlEditorUi.wrapScroll(context, column))
                .setNeutralButton(R.string.qk_control_external_hide,
                        (dialog, button) -> showExternalOptions(layout))
                .setPositiveButton(R.string.qk_dialog_done, null)
                .show();
    }

    /** 外接设备隐藏策略：鼠标/键盘与手柄分开配置。 */
    private static void showExternalOptions(ControlLayout layout) {
        CustomControls controls = layout.getLayout();
        if (controls == null) return;
        Context context = layout.getContext();
        LinearLayout column = ControlEditorUi.dialogRoot(context);

        ControlEditorUi.switchRow(context, column, R.string.qk_control_external_mouse_kb,
                controls.hideTouchControlsWithExternalInput, (btn, isChecked) -> {
                    controls.hideTouchControlsWithExternalInput = isChecked;
                    if (!isChecked) controls.keepTouchControlsWithGamepad = false;
                    layout.setModified(true);
                    layout.refreshControlVisibility();
                });

        ControlEditorUi.switchRow(context, column, R.string.qk_control_external_gamepad,
                controls.hideTouchControlsWithExternalInput && !controls.keepTouchControlsWithGamepad,
                (btn, isChecked) -> {
                    controls.keepTouchControlsWithGamepad = !isChecked;
                    layout.setModified(true);
                    layout.refreshControlVisibility();
                });

        new AlertDialog.Builder(context, R.style.QookixAlertDialog)
                .setTitle(R.string.qk_control_external_settings)
                .setView(ControlEditorUi.wrapScroll(context, column))
                .setPositiveButton(R.string.qk_dialog_done, null)
                .show();
    }
}
