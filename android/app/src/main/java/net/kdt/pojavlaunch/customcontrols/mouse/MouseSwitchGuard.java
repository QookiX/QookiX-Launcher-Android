package net.kdt.pojavlaunch.customcontrols.mouse;

/** 虚拟鼠标切换后短暂吞掉点击，避免切换那一下顺带触发左键（FCL CLICK 的 33ms 防误触）。 */
public final class MouseSwitchGuard {
    public static final long SUPPRESS_MS = 33;
    private static long switchedAt = -1;

    private MouseSwitchGuard() {}

    public static void markSwitched(long now) { switchedAt = now; }

    /** 只判断单击；按下保持、拖动和已经提交的长按定时器都不走这里。 */
    public static boolean shouldSuppressClick(long now) {
        return switchedAt >= 0 && now >= switchedAt && now - switchedAt < SUPPRESS_MS;
    }

    public static void reset() { switchedAt = -1; }
}
