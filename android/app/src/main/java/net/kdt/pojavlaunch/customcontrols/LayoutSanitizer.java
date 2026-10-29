package net.kdt.pojavlaunch.customcontrols;

import java.util.Iterator;
import java.util.List;

public class LayoutSanitizer {

    // Maybe add more conditions here later?
    private static boolean isInvalidFormula(String formula) {
        return formula.contains("Infinity");
    }

    /**
     * GLFW 定义的最大键码（`GLFW_KEY_LAST` = 348）。
     *
     * 2026-10-29 加（外部审计 B26）：布局 JSON 是**用户可导入**的，
     * keycode 字段以前完全不校验，一路带到 native 的
     * `pojav_environ->keyDownBuffer[max(0, key-31)]` 直接下标写 ——
     * 那是 317 字节的堆缓冲，一个 999 就是堆越界写。
     * native 侧已加真实容量校验（越界跳过写入），这里再做一道：
     * 超出 GLFW 范围的键码直接归零（= 未知键，不会触发任何按键）。
     */
    private static final int MAX_KEYCODE = 348;

    /** 把越界的键码钉回合法范围（原地修改），返回是否改动过。 */
    private static boolean clampKeycodes(ControlData controlData) {
        int[] codes = controlData.keycodes;
        if (codes == null) return false;
        boolean changed = false;
        for (int i = 0; i < codes.length; i++) {
            if (codes[i] < 0 || codes[i] > MAX_KEYCODE) {
                codes[i] = 0;
                changed = true;
            }
        }
        return changed;
    }

    private static boolean isSaneData(ControlData controlData) {
        if(controlData.getWidth() == 0 || controlData.getHeight() == 0) return false;
        if(isInvalidFormula(controlData.dynamicX) || isInvalidFormula(controlData.dynamicY)) return false;
        return true;
    }

    private static ControlData getControlData(Object dataEntry) {
        if(dataEntry instanceof ControlData) {
            return (ControlData) dataEntry;
        }else if(dataEntry instanceof ControlDrawerData) {
            return ((ControlDrawerData) dataEntry).properties;
        }else throw new RuntimeException("Encountered wrong type during ControlData sanitization");
    }

    private static boolean sanitizeList(List<?> controlDataList) {
        boolean madeChanges = false;
        Iterator<?> iterator = controlDataList.iterator();
        while(iterator.hasNext()) {
            ControlData controlData = getControlData(iterator.next());
            if(!isSaneData(controlData)) {
                madeChanges = true;
                iterator.remove();
                continue;
            }
            // 键码越界不是「删掉这个按键」，而是钉回 0（保住用户画好的按钮）
            if (clampKeycodes(controlData)) madeChanges = true;
        }
        return madeChanges;
    }

    /**
     * Check all buttons in a control layout and ensure they're sane (contain values valid enough
     * to be displayed properly). Removes any buttons deemed not sane.
     * @param controls the original control layout.
     * @return whether the sanitization process made any changes to the layout
     */
    public static boolean sanitizeLayout(CustomControls controls) {
        boolean madeChanges = sanitizeList(controls.mControlDataList);
        if(sanitizeList(controls.mDrawerDataList)) madeChanges = true;
        if(sanitizeList(controls.mJoystickDataList)) madeChanges = true;
        return madeChanges;
    }
}
