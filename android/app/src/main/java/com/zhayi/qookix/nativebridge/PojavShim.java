package com.zhayi.qookix.nativebridge;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.lang.ref.WeakReference;

/**
 * Pojav 输入桥（`org.lwjgl.glfw.CallbackBridge`）需要的最小平台能力。
 *
 * 原版里这几处调用的是 Pojav 自己的 `net.kdt.pojavlaunch.MainActivity` 与 `Tools`，
 * 我们不想把整套 Pojav 应用层搬过来，于是用这个类顶上：
 *   - 剪贴板读写（游戏里 Ctrl+C / Ctrl+V）
 *   - CLIPBOARD_OPEN：把游戏里点到的链接交给系统浏览器打开
 *   - `Tools.getWeakReference` 的等价物
 */
public final class PojavShim {

    private static ClipboardManager clipboard;
    private static Context appContext;

    private PojavShim() {
    }

    /** 由 MainActivity 在 onCreate 里调用。 */
    public static void init(Context context) {
        appContext = context.getApplicationContext();
        try {
            clipboard = (ClipboardManager) appContext.getSystemService(Context.CLIPBOARD_SERVICE);
        } catch (Throwable ignored) {
            clipboard = null;
        }
    }

    /**
     * JVM 侧用的原生库目录（`<files>/natives`）。
     *
     * 关键：ART 侧也必须从这里加载 `libpojavexec.so`，不能走 `System.loadLibrary`
     * （那会从 APK 里再加载一份，两个不同文件 = 两个独立实例，
     * `pojav_environ` 全局变量不共享，GL 初始化时拿到的 `pojavWindow` 永远是 NULL）。
     */
    public static String nativeLibDir() {
        if (appContext == null) return null;
        return new java.io.File(appContext.getFilesDir(), "natives").getAbsolutePath();
    }

    public static boolean setClipboard(String copy) {
        if (clipboard == null || copy == null) return false;
        try {
            clipboard.setPrimaryClip(ClipData.newPlainText("Copy", copy));
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static String getClipboard() {
        if (clipboard == null || !clipboard.hasPrimaryClip()) return "";
        try {
            ClipData clip = clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return "";
            ClipDescription description = clip.getDescription();
            if (description != null
                    && !description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)) {
                return "";
            }
            CharSequence text = clip.getItemAt(0).getText();
            return text == null ? "" : text.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static void openLink(String url) {
        if (appContext == null || url == null || url.isEmpty()) return;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            appContext.startActivity(intent);
        } catch (Throwable ignored) {
        }
    }

    public static <T> T weakRef(WeakReference<T> reference) {
        return reference == null ? null : reference.get();
    }
}
