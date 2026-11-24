package net.kdt.pojavlaunch.customcontrols;

import net.kdt.pojavlaunch.customcontrols.buttons.ControlInterface;

import java.util.LinkedHashSet;
import java.util.Set;

/** 样式表为外观单一真源：按钮里的 appearance 只是按 styleId 同步出来的副本。 */
public final class ControlStyleManager {
    private ControlStyleManager() {}

    public static ControlStyleData find(CustomControls layout, String styleId) {
        if (styleId == null || styleId.isEmpty() || layout == null || layout.mStyleDataList == null) return null;
        for (ControlStyleData style : layout.mStyleDataList) {
            if (style != null && styleId.equals(style.id)) return style;
        }
        return null;
    }

    /** 导入/加载后按样式表覆盖按钮外观；没有样式记录时保留内联 appearance 作为回退。 */
    public static void applyAll(CustomControls layout) {
        if (layout == null || layout.mControlDataList == null) return;
        for (ControlData data : layout.mControlDataList) {
            ControlStyleData style = find(layout, data.styleId);
            if (style == null) continue;
            data.appearance = copy(style.appearance);
            data.darkAppearance = copy(style.darkAppearance);
        }
    }

    /** 属性面板改完外观后调用：刷新当前按钮，并把改动回写到同款按钮。 */
    public static void syncButton(ControlInterface button) {
        if (button == null) return;
        button.updateProperties();
        ControlLayout layout = button.getControlLayoutParent();
        if (layout == null) return;
        ControlData data = button.getProperties();
        syncFromButton(layout.getLayout(), data);
        refresh(layout, data.styleId);
    }

    /** 属性面板改完外观后回写样式表，再让同款按钮一起刷新。 */
    public static void syncFromButton(CustomControls layout, ControlData data) {
        ControlStyleData style = find(layout, data.styleId);
        if (style == null) return;
        style.appearance = copy(data.appearance);
        style.darkAppearance = copy(data.darkAppearance);
    }

    public static void refresh(ControlLayout layout, String styleId) {
        if (layout == null || styleId == null || styleId.isEmpty()) return;
        CustomControls controls = layout.getLayout();
        for (ControlInterface button : layout.getButtonChildren()) {
            ControlData data = button.getProperties();
            if (!styleId.equals(data.styleId)) continue;
            ControlStyleData style = find(controls, styleId);
            if (style != null) {
                data.appearance = copy(style.appearance);
                data.darkAppearance = copy(style.darkAppearance);
            }
            button.updateProperties();
        }
    }

    public static void refreshAll(ControlLayout layout) {
        if (layout == null || layout.getLayout() == null) return;
        Set<String> ids = new LinkedHashSet<>();
        for (ControlData data : layout.getLayout().mControlDataList) {
            if (data.styleId != null && !data.styleId.isEmpty()) ids.add(data.styleId);
        }
        for (String id : ids) refresh(layout, id);
    }

    public static ControlData.Appearance copy(ControlData.Appearance source) {
        if (source == null) return null;
        ControlData.Appearance copy = new ControlData.Appearance();
        copy.normal = copy(source.normal);
        copy.pressed = copy(source.pressed);
        return copy;
    }

    public static ControlData.PaintStyle copy(ControlData.PaintStyle source) {
        if (source == null) return null;
        ControlData.PaintStyle copy = new ControlData.PaintStyle();
        copy.fill = source.fill;
        copy.outline = source.outline;
        copy.text = source.text;
        copy.strokeDp = source.strokeDp;
        copy.radiusDp = source.radiusDp;
        copy.textSp = source.textSp;
        copy.cornerPercent = source.cornerPercent == null ? null : source.cornerPercent.clone();
        return copy;
    }
}
