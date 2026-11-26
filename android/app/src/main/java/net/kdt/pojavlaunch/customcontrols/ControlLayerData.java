package net.kdt.pojavlaunch.customcontrols;

import androidx.annotation.Keep;

/** 图层元数据：FCL / ZL2 的层属性整层生效，字段名即磁盘格式。 */
@Keep
public class ControlLayerData {
    public String id;
    public String name;
    /** always / in_game / in_menu */
    public String visibilityType = "always";
    public boolean hide;
    public boolean hideWhenMouse;
    public boolean hideWhenGamepad;

    public ControlLayerData() {}

    public ControlLayerData(String id, String visibilityType, boolean hide,
                            boolean hideWhenMouse, boolean hideWhenGamepad) {
        this.id = id;
        this.visibilityType = visibilityType;
        this.hide = hide;
        this.hideWhenMouse = hideWhenMouse;
        this.hideWhenGamepad = hideWhenGamepad;
    }
}
