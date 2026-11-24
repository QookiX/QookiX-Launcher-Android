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
        String[] labels = new String[ids.size()];
        boolean[] checked = new boolean[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            labels[i] = shortName(ids.get(i));
            checked[i] = groups.get(ids.get(i));
        }

        new AlertDialog.Builder(context, R.style.QookixAlertDialog)
                .setTitle(R.string.qk_control_groups)
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) ->
                        layout.setGroupVisible(ids.get(which), isChecked))
                .setNeutralButton(R.string.qk_control_groups_edit, (dialog, which) ->
                        showGroupPicker(layout, ids))
                .setPositiveButton(R.string.qk_dialog_done, null)
                .show();
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

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(context.getResources().getDisplayMetrics().density * 8);
        column.setPadding(pad, pad, pad, pad);

        for (ControlData data : new ArrayList<>(controls.mControlDataList)) {
            if (!groupId.equals(data.groupId)) continue;
            column.addView(memberRow(layout, controls, data, groupId, column));
        }
        addLayerSettings(layout, column, groupId);

        Button add = new Button(context);
        add.setText(R.string.qk_control_groups_add);
        add.setOnClickListener(v -> showAddButtons(layout, groupId));
        column.addView(add);

        ScrollView scroll = new ScrollView(context);
        scroll.addView(column);
        new AlertDialog.Builder(context, R.style.QookixAlertDialog)
                .setTitle(R.string.qk_control_groups_edit)
                .setView(scroll)
                .setPositiveButton(R.string.qk_dialog_done, null)
                .show();
    }

    /** 整层属性：显示场景、默认隐藏、接鼠标/手柄时隐藏，直接写图层元数据。 */
    private static void addLayerSettings(ControlLayout layout, LinearLayout column, String groupId) {
        Context context = layout.getContext();
        CustomControls controls = layout.getLayout();
        ControlLayerData existing = findLayer(controls, groupId);

        TextView title = new TextView(context);
        title.setText(R.string.qk_control_layer_title);
        column.addView(title);

        CheckBox hidden = new CheckBox(context);
        hidden.setText(R.string.qk_control_layer_hidden);
        hidden.setChecked(existing != null && existing.hide);
        hidden.setOnCheckedChangeListener((button, checked) -> {
            layerOf(controls, groupId).hide = checked;
            layout.setGroupVisible(groupId, !checked);
            commit(layout);
        });
        column.addView(hidden);

        CheckBox hideMouse = new CheckBox(context);
        hideMouse.setText(R.string.qk_control_layer_hide_mouse);
        hideMouse.setChecked(existing != null && existing.hideWhenMouse);
        hideMouse.setOnCheckedChangeListener((button, checked) -> {
            layerOf(controls, groupId).hideWhenMouse = checked;
            commit(layout);
        });
        column.addView(hideMouse);

        CheckBox hideGamepad = new CheckBox(context);
        hideGamepad.setText(R.string.qk_control_layer_hide_gamepad);
        hideGamepad.setChecked(existing != null && existing.hideWhenGamepad);
        hideGamepad.setOnCheckedChangeListener((button, checked) -> {
            layerOf(controls, groupId).hideWhenGamepad = checked;
            commit(layout);
        });
        column.addView(hideGamepad);

        Spinner scene = new Spinner(context);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item,
                new String[]{
                        context.getString(R.string.qk_control_scene_all),
                        context.getString(R.string.qk_control_scene_gameplay),
                        context.getString(R.string.qk_control_scene_menu)});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        scene.setAdapter(adapter);
        String type = existing == null || existing.visibilityType == null
                ? "always" : existing.visibilityType.toLowerCase(Locale.ROOT);
        scene.setSelection(type.equals("in_game") ? 1 : type.equals("in_menu") ? 2 : 0);
        scene.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                layerOf(controls, groupId).visibilityType =
                        position == 1 ? "in_game" : position == 2 ? "in_menu" : "always";
                commit(layout);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        column.addView(scene);
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
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setTag(data);

        TextView name = new TextView(context);
        name.setText(data.name);
        name.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(name);

        row.addView(miniButton(context, R.string.qk_control_groups_remove, v -> {
            data.groupId = null;
            commit(layout);
            column.removeView(row);
        }));
        row.addView(miniButton(context, "↑", v -> move(layout, controls, data, -1, column)));
        row.addView(miniButton(context, "↓", v -> move(layout, controls, data, 1, column)));
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
