package net.kdt.pojavlaunch.customcontrols.buttons;

/** A wheel drag only follows the finger that started it. */
final class MouseWheelGesture {
    private final float touchSlop;
    private final float stepSize;
    private int pointer = -1;
    private float startX;
    private float startY;
    private float wheelY;
    private boolean moved;

    MouseWheelGesture(float touchSlop, float stepSize) {
        this.touchSlop = touchSlop;
        this.stepSize = Math.max(stepSize, touchSlop);
    }

    void start(int id, float x, float y) {
        pointer = id;
        startX = x;
        startY = wheelY = y;
        moved = false;
    }

    int pointer() {
        return pointer;
    }

    int move(float x, float y) {
        if (pointer == -1) return 0;
        if (!moved && Math.hypot(x - startX, y - startY) <= touchSlop) return 0;
        moved = true;
        int steps = (int) ((wheelY - y) / stepSize);
        wheelY -= steps * stepSize;
        return steps;
    }

    boolean finish() {
        boolean click = pointer != -1 && !moved;
        cancel();
        return click;
    }

    void cancel() {
        pointer = -1;
    }
}
