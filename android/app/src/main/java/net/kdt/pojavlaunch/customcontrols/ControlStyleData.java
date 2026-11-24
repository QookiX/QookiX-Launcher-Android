package net.kdt.pojavlaunch.customcontrols;

import androidx.annotation.Keep;

/** 布局级共享样式：同 styleId 的按钮共用这份外观，编辑器改一处即全体生效。 */
@Keep
public class ControlStyleData {
    public String id;
    public ControlData.Appearance appearance, darkAppearance;
    public boolean animateSwap;
    public ControlStyleData() {}
}
