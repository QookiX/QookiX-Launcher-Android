package net.kdt.pojavlaunch.customcontrols.buttons;

import java.util.function.IntSupplier;

/** The key chord and repeat mode are fixed until this press ends. */
final class ControlPress {
    final int[] keys;
    final boolean autoClick;

    ControlPress(int[] configuredKeys, boolean autoClick, IntSupplier resolveBinding) {
        keys = configuredKeys.clone();
        this.autoClick = autoClick;
        if (keys.length != 0 && keys[0] >= 0) {
            int resolved = resolveBinding.getAsInt();
            if (resolved != 0) keys[0] = resolved;
        }
    }
}
