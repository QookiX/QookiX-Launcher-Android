package net.kdt.pojavlaunch.customcontrols;

import android.content.Context;

import androidx.appcompat.app.AlertDialog;

import com.zhayi.qookix.R;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 控件组管理（ZL2 图层显隐的最小版本）。
 *
 * 导入的 FCL / ZL2 布局会把同一图层的按钮打上 `groupId`，并可能标记
 * `groupInitiallyHidden`。这里把布局里出现的组列出来，勾选即显隐，
 * 与按钮上「show_layer / hide_layer」的运行时行为共用同一份
 * {@link ControlLayout#applyGroupActions} 状态。
 */
public final class ControlGroupDialog {
    private ControlGroupDialog() {}

    public static void show(ControlLayout layout) {
        CustomControls controls = layout.getLayout();
        if (controls == null) return;
        Context context = layout.getContext();

        Map<String, Boolean> groups = new LinkedHashMap<>();
        for (ControlData data : controls.mControlDataList) {
            if (data.groupId == null || data.groupId.isEmpty()) continue;
            groups.put(data.groupId, layout.isGroupVisible(data.groupId, !data.groupInitiallyHidden));
        }

        if (groups.isEmpty()) {
            new AlertDialog.Builder(context, R.style.QookixAlertDialog)
                    .setTitle(R.string.qk_control_groups)
                    .setMessage(R.string.qk_control_groups_empty)
                    .setPositiveButton(R.string.qk_dialog_done, null)
                    .show();
            return;
        }

        List<String> ids = new ArrayList<>(groups.keySet());
        String[] labels = ids.toArray(new String[0]);
        boolean[] checked = new boolean[labels.length];
        for (int i = 0; i < labels.length; i++) checked[i] = groups.get(labels[i]);

        new AlertDialog.Builder(context, R.style.QookixAlertDialog)
                .setTitle(R.string.qk_control_groups)
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) ->
                        layout.setGroupVisible(ids.get(which), isChecked))
                .setPositiveButton(R.string.qk_dialog_done, null)
                .show();
    }
}
