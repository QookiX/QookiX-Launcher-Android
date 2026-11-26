package net.kdt.pojavlaunch.customcontrols;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.zhayi.qookix.R;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 控件组管理（ZL2 图层显隐的实用版本）。
 *
 * 导入的 FCL / ZL2 布局会把同一图层的按钮打上 `groupId`，并可能标记
 * `groupInitiallyHidden`。这里分两层：
 *  - 第一层：列出所有组，勾选即显隐，与按钮上的 show_layer / hide_layer
 *    共用同一份 {@link ControlLayout#applyGroupActions} 状态；
 *  - 第二层：组内按钮可以移出、调整上下顺序（列表顺序即层级顺序），
 *    也可以把别的按钮并进来。
 */
public final class ControlGroupDialog {
    private ControlGroupDialog() {}

    public static void show(ControlLayout layout) {
        CustomControls controls = layout.getLayout();
        if (controls == null) return;
        Context context = layout.getContext();

        Map<String, Integer> counts = new LinkedHashMap<>();
        if (controls.mLayerDataList != null) {
            for (ControlLayerData layer : controls.mLayerDataList) {
                if (layer != null && layer.id != null && !layer.id.isEmpty()) {
                    counts.putIfAbsent(layer.id, 0);
                }
            }
        }
        if (controls.mControlDataList != null) {
            for (ControlData data : controls.mControlDataList) {
                if (data.groupId == null || data.groupId.isEmpty()) continue;
                counts.put(data.groupId, counts.getOrDefault(data.groupId, 0) + 1);
            }
        }
        if (controls.mJoystickDataList != null) {
            for (ControlJoystickData data : controls.mJoystickDataList) {
                if (data.groupId == null || data.groupId.isEmpty()) continue;
                counts.put(data.groupId, counts.getOrDefault(data.groupId, 0) + 1);
            }
        }

        if (counts.isEmpty()) {
            new AlertDialog.Builder(context, R.style.QookixAlertDialog)
                    .setTitle(R.string.qk_control_groups)
                    .setMessage(R.string.qk_control_groups_empty)
                    .setPositiveButton(R.string.qk_dialog_done, null)
                    .show();
            return;
        }

        LinearLayout column = ControlEditorUi.dialogRoot(context);

        List<String> ids = new ArrayList<>(counts.keySet());
        List<Runnable> refreshRows = new ArrayList<>(ids.size());

        AlertDialog dialog = new AlertDialog.Builder(context, R.style.QookixAlertDialog)
                .setTitle(R.string.qk_control_groups)
                .setNeutralButton(R.string.qk_control_groups_show_all, null)
                .setNegativeButton(R.string.qk_control_groups_reset, null)
                .setPositiveButton(R.string.qk_dialog_done, null)
                .create();

        for (String id : ids) {
            ControlLayerData layer = findLayer(controls, id);
            String label = (layer != null && layer.name != null && !layer.name.isBlank())
                    ? layer.name : shortName(id);
            int count = counts.getOrDefault(id, 0);

            LinearLayout row = ControlEditorUi.card(context);

            TextView toggle = ControlEditorUi.pillButton(context, "", true, null);
            TextView title = new TextView(context);
            title.setText(label + " (" + count + ")");
            title.setTextSize(14f);
            title.setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.qk_text_1));
            title.setPadding(ControlEditorUi.dp(context, 10), 0, ControlEditorUi.dp(context, 8), 0);
            title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Runnable refreshRow = () -> {
                boolean vis = layout.isGroupVisible(id, true);
                toggle.setText(vis ? R.string.qk_control_groups_visible : R.string.qk_control_groups_hidden);
                ControlEditorUi.stylePill(context, toggle, vis);
                title.setAlpha(vis ? 1f : 0.5f);
            };
            refreshRow.run();
            refreshRows.add(refreshRow);

            toggle.setOnClickListener(v -> {
                boolean next = !layout.isGroupVisible(id, true);
                layout.setGroupVisible(id, next);
                refreshRow.run();
            });

            TextView edit = ControlEditorUi.pillButton(
                    context, context.getString(R.string.qk_control_groups_edit), false, v -> {
                        dialog.dismiss();
                        showGroupDetail(layout, id);
                    });

            row.addView(toggle);
            row.addView(title);
            row.addView(edit);
            column.addView(row);
        }

        dialog.setView(ControlEditorUi.wrapScroll(context, column));
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                layout.setAllGroupsVisible(true);
                for (Runnable r : refreshRows) r.run();
            });
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
                layout.resetGroupVisibility();
                for (Runnable r : refreshRows) r.run();
            });
        });
        dialog.show();
    }

    /** 选一个组进入详情。 */
    private static void showGroupPicker(ControlLayout layout, List<String> ids) {
        String[] labels = new String[ids.size()];
        for (int i = 0; i < ids.size(); i++) labels[i] = shortName(ids.get(i));
        new AlertDialog.Builder(layout.getContext(), R.style.QookixAlertDialog)
                .setTitle(R.string.qk_control_groups_edit)
                .setItems(labels, (dialog, which) -> showGroupDetail(layout, ids.get(which)))
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    /** 组详情：组内按钮可移出、上下调序；还能把别的按钮并进来。 */
    private static void showGroupDetail(ControlLayout layout, String groupId) {
        CustomControls controls = layout.getLayout();
        if (controls == null) return;
        Context context = layout.getContext();

        LinearLayout column = ControlEditorUi.dialogRoot(context);

        for (ControlData data : new ArrayList<>(controls.mControlDataList)) {
            if (!groupId.equals(data.groupId)) continue;
            column.addView(memberRow(layout, controls, data, groupId, column));
        }
        addLayerSettings(layout, column, groupId);

        TextView add = ControlEditorUi.pillButton(
                context, context.getString(R.string.qk_control_groups_add), true,
                v -> showAddButtons(layout, groupId));
        add.setPadding(ControlEditorUi.dp(context, 12), ControlEditorUi.dp(context, 10),
                ControlEditorUi.dp(context, 12), ControlEditorUi.dp(context, 10));
        column.addView(add);

        new AlertDialog.Builder(context, R.style.QookixAlertDialog)
                .setTitle(R.string.qk_control_groups_edit)
                .setView(ControlEditorUi.wrapScroll(context, column))
                .setPositiveButton(R.string.qk_dialog_done, null)
                .show();
    }

    /** 整层属性：显示场景、默认隐藏、接鼠标/手柄时隐藏，直接写图层元数据。 */
    private static void addLayerSettings(ControlLayout layout, LinearLayout column, String groupId) {
        Context context = layout.getContext();
        CustomControls controls = layout.getLayout();
        ControlLayerData existing = findLayer(controls, groupId);

        column.addView(ControlEditorUi.sectionLabel(context, R.string.qk_control_layer_title));

        ControlEditorUi.switchRow(context, column, R.string.qk_control_layer_hidden,
                existing != null && existing.hide, (button, checked) -> {
                    layerOf(controls, groupId).hide = checked;
                    layout.setGroupVisible(groupId, !checked);
                    commit(layout);
                });

        ControlEditorUi.switchRow(context, column, R.string.qk_control_layer_hide_mouse,
                existing != null && existing.hideWhenMouse, (button, checked) -> {
                    layerOf(controls, groupId).hideWhenMouse = checked;
                    commit(layout);
                });

        ControlEditorUi.switchRow(context, column, R.string.qk_control_layer_hide_gamepad,
                existing != null && existing.hideWhenGamepad, (button, checked) -> {
                    layerOf(controls, groupId).hideWhenGamepad = checked;
                    commit(layout);
                });

        String type = existing == null || existing.visibilityType == null
                ? "always" : existing.visibilityType.toLowerCase(Locale.ROOT);
        int sceneIdx = type.equals("in_game") ? 1 : type.equals("in_menu") ? 2 : 0;
        ControlEditorUi.SegmentedBar sceneBar = ControlEditorUi.segmentedBar(
                context,
                new int[]{
                        R.string.qk_control_scene_all,
                        R.string.qk_control_scene_gameplay,
                        R.string.qk_control_scene_menu,
                },
                sceneIdx,
                position -> {
                    layerOf(controls, groupId).visibilityType =
                            position == 1 ? "in_game" : position == 2 ? "in_menu" : "always";
                    commit(layout);
                });
        column.addView(sceneBar.view);
    }

    private static ControlLayerData findLayer(CustomControls controls, String groupId) {
        if (controls == null || controls.mLayerDataList == null) return null;
        for (ControlLayerData layer : controls.mLayerDataList) {
            if (layer != null && groupId.equals(layer.id)) return layer;
        }
        return null;
    }

    private static ControlLayerData layerOf(CustomControls controls, String groupId) {
        ControlLayerData layer = findLayer(controls, groupId);
        if (layer != null) return layer;
        if (controls.mLayerDataList == null) controls.mLayerDataList = new ArrayList<>();
        layer = new ControlLayerData(groupId, "always", false, false, false);
        controls.mLayerDataList.add(layer);
        return layer;
    }

    /** 一行：按钮名 + 移出 + 上移 + 下移。 */
    private static View memberRow(ControlLayout layout, CustomControls controls, ControlData data,
                                  String groupId, LinearLayout column) {
        Context context = layout.getContext();
        LinearLayout row = ControlEditorUi.card(context);
        row.setTag(data);

        TextView name = new TextView(context);
        name.setText(data.name);
        name.setTextSize(13f);
        name.setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.qk_text_1));
        name.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(name);

        int gap = ControlEditorUi.dp(context, 6);
        TextView up = ControlEditorUi.pillButton(context, "↑", false, v -> move(layout, controls, data, -1, column));
        TextView down = ControlEditorUi.pillButton(context, "↓", false, v -> move(layout, controls, data, 1, column));
        TextView remove = ControlEditorUi.pillButton(context, context.getString(R.string.qk_control_groups_remove), false, v -> {
            data.groupId = null;
            commit(layout);
            column.removeView(row);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = gap;
        row.addView(up, lp);
        row.addView(down, lp);
        row.addView(remove, lp);
        return row;
    }

    /** 把不在组里的按钮列出来，多选即加入。 */
    private static void showAddButtons(ControlLayout layout, String groupId) {
        CustomControls controls = layout.getLayout();
        if (controls == null) return;
        List<ControlData> candidates = new ArrayList<>();
        for (ControlData data : controls.mControlDataList) {
            if (!groupId.equals(data.groupId)) candidates.add(data);
        }
        if (candidates.isEmpty()) {
            new AlertDialog.Builder(layout.getContext(), R.style.QookixAlertDialog)
                    .setMessage(R.string.qk_control_groups_none)
                    .setPositiveButton(R.string.qk_dialog_done, null)
                    .show();
            return;
        }
        String[] labels = new String[candidates.size()];
        boolean[] checked = new boolean[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) labels[i] = candidates.get(i).name;

        new AlertDialog.Builder(layout.getContext(), R.style.QookixAlertDialog)
                .setTitle(R.string.qk_control_groups_add)
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> {
                    if (!isChecked) return;
                    candidates.get(which).groupId = groupId;
                    commit(layout);
                })
                // 成员变了，重开一次详情才能看到新加入的按钮
                .setPositiveButton(R.string.qk_dialog_done, (dialog, which) -> showGroupDetail(layout, groupId))
                .show();
    }

    /** 上下移动按钮：列表顺序即层级顺序（后加入的盖在上面）。 */
    private static void move(ControlLayout layout, CustomControls controls, ControlData data,
                             int delta, LinearLayout column) {
        int index = controls.mControlDataList.indexOf(data);
        int target = index + delta;
        if (index < 0 || target < 0 || target >= controls.mControlDataList.size()) return;
        controls.mControlDataList.remove(index);
        controls.mControlDataList.add(target, data);
        commit(layout);

        View row = rowOf(column, data);
        if (row == null) return;
        int uiIndex = column.indexOfChild(row);
        int uiTarget = uiIndex + delta;
        // 最后一个子 View 是「添加按钮」，别越过它
        if (uiTarget < 0 || uiTarget >= column.getChildCount() - 1) return;
        column.removeView(row);
        column.addView(row, uiTarget);
    }

    private static View rowOf(LinearLayout column, ControlData data) {
        for (int i = 0; i < column.getChildCount(); i++) {
            View child = column.getChildAt(i);
            if (child.getTag() == data) return child;
        }
        return null;
    }

    private static void commit(ControlLayout layout) {
        layout.setModified(true);
        // 重排放到下一帧：这些按钮都在对话框的触摸回调里，
        // 同步刷新整棵控件树容易把输入分发拖到超时。
        layout.post(() -> layout.refreshControlVisibility());
    }

    private static Button miniButton(Context context, int textRes, View.OnClickListener listener) {
        return miniButton(context, context.getString(textRes), listener);
    }

    private static Button miniButton(Context context, String text, View.OnClickListener listener) {
        Button button = new Button(context);
        button.setText(text);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        return button;
    }

    /** groupId 是 uuid，展示时只留前 8 位。 */
    private static String shortName(String groupId) {
        return groupId.length() > 8 ? groupId.substring(0, 8) : groupId;
    }
}
