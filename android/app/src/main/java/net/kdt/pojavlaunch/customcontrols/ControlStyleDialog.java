package net.kdt.pojavlaunch.customcontrols;

import android.content.Context;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.zhayi.qookix.R;

import net.kdt.pojavlaunch.customcontrols.buttons.ControlInterface;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 样式表入口：选一个样式 → 打开同款按钮的属性面板，改颜色/圆角即全体同步。 */
public final class ControlStyleDialog {
    private ControlStyleDialog() {}

    public static void show(ControlLayout layout) {
        CustomControls controls = layout.getLayout();
        if (controls == null) return;
        Context context = layout.getContext();
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (controls.mControlDataList != null) {
            for (ControlData data : controls.mControlDataList) {
                if (data.styleId == null || data.styleId.isEmpty()) continue;
                Integer old = counts.get(data.styleId);
                counts.put(data.styleId, old == null ? 1 : old + 1);
            }
        }
        if (counts.isEmpty()) {
            new AlertDialog.Builder(context, R.style.QookixAlertDialog)
                    .setTitle(R.string.qk_control_styles)
                    .setMessage(R.string.qk_control_styles_empty)
                    .setPositiveButton(R.string.qk_dialog_done, null)
                    .show();
            return;
        }

        List<String> ids = new ArrayList<>(counts.keySet());
        String[] labels = new String[ids.size()];
        for (int i = 0; i < ids.size(); i++) labels[i] = ids.get(i) + " (" + counts.get(ids.get(i)) + ")";
        new AlertDialog.Builder(context, R.style.QookixAlertDialog)
                .setTitle(R.string.qk_control_styles)
                .setMessage(R.string.qk_control_styles_hint)
                .setItems(labels, (dialog, which) -> editSample(layout, ids.get(which)))
                .setNeutralButton(R.string.qk_control_styles_sync_all, (dialog, which) -> {
                    ControlStyleManager.refreshAll(layout);
                    Toast.makeText(context, R.string.qk_control_styles_synced, Toast.LENGTH_SHORT).show();
                })
                .setPositiveButton(R.string.qk_dialog_done, null)
                .show();
    }

    private static void editSample(ControlLayout layout, String styleId) {
        for (ControlInterface button : layout.getButtonChildren()) {
            if (styleId.equals(button.getProperties().styleId)) {
                layout.editControlButton(button);
                return;
            }
        }
    }
}
