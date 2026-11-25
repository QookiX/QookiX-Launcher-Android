package net.kdt.pojavlaunch.customcontrols.buttons;

import java.util.LinkedHashSet;
import java.util.Set;

/** Emits only the changed edges across direction, forward lock and double tap. */
final class JoystickKeyState {
    interface Output { void send(int key, boolean down); }

    private final Output output;
    private Set<Integer> held = new LinkedHashSet<>();

    JoystickKeyState(Output output) { this.output = output; }

    void update(int[]... sources) {
        Set<Integer> next = new LinkedHashSet<>();
        for (int[] source : sources) {
            if (source != null) for (int key : source) if (key != 0) next.add(key);
        }
        Set<Integer> previous = held;
        held = next;
        for (int key : previous) if (!next.contains(key)) output.send(key, false);
        for (int key : next) if (!previous.contains(key)) output.send(key, true);
    }
}
