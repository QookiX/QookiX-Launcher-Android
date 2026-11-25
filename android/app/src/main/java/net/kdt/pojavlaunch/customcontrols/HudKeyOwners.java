package net.kdt.pojavlaunch.customcontrols;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/** Per-HUD ownership of keyboard and mouse edges; does not track external devices. */
public final class HudKeyOwners {
    private final Map<Integer, Set<Object>> owners = new HashMap<>();

    /** Returns whether the aggregate key/button state changed. */
    public boolean update(int key, Object owner, boolean down) {
        if (down) {
            Set<Object> held = owners.computeIfAbsent(key,
                    k -> Collections.newSetFromMap(new IdentityHashMap<>()));
            return held.add(owner) && held.size() == 1;
        }
        Set<Object> held = owners.get(key);
        if (held == null || !held.remove(owner) || !held.isEmpty()) return false;
        owners.remove(key);
        return true;
    }
}
