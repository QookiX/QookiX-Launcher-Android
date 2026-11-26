package net.kdt.pojavlaunch.customcontrols;

import java.util.Locale;
import java.util.Map;

/** 整层显隐规则：与按钮级场景、组开关叠加，复用同一份 groupVisibility 状态。 */
final class ControlLayerVisibility {
    private ControlLayerVisibility() {}

    static ControlLayerData find(CustomControls layout, String id) {
        if (id == null || id.isEmpty() || layout == null || layout.mLayerDataList == null) return null;
        for (ControlLayerData layer : layout.mLayerDataList) {
            if (layer != null && id.equals(layer.id)) return layer;
        }
        return null;
    }

    static boolean initialVisible(CustomControls layout, String id, boolean fallback) {
        ControlLayerData layer = find(layout, id);
        return layer == null ? fallback : !layer.hide;
    }

    static boolean visible(CustomControls layout, String id, boolean fallback,
                           Map<String, Boolean> groupVisibility, ControlScene scene,
                           boolean applyRules, boolean preview, boolean externalInput, int source) {
        boolean visible = id == null || groupVisibility.getOrDefault(id, initialVisible(layout, id, fallback));
        if (!visible || !applyRules) return visible;
        ControlLayerData layer = find(layout, id);
        if (layer == null) return true;
        String type = layer.visibilityType == null ? "always" : layer.visibilityType.toLowerCase(Locale.ROOT);
        if (scene != ControlScene.ALL) {
            if (type.equals("in_game") && scene != ControlScene.GAMEPLAY) return false;
            if (type.equals("in_menu") && scene != ControlScene.MENU) return false;
        }
        if (!externalInput) return true;
        if (source == ControlInputSource.SOURCE_MOUSE) return !layer.hideWhenMouse;
        if (source == ControlInputSource.SOURCE_GAMEPAD) return !layer.hideWhenGamepad;
        return true;
    }
}
