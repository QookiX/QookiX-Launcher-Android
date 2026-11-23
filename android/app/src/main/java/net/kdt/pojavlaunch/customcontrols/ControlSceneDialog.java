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
                .setNeutralButton(controls.hideTouchControlsWithExternalInput
                        ? R.string.qk_control_external_keep : R.string.qk_control_external_hide, (dialog, button) -> {
                    controls.hideTouchControlsWithExternalInput = !controls.hideTouchControlsWithExternalInput;
                    layout.setModified(true);
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }
}
