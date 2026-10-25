package net.kdt.pojavlaunch.prefs;

import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.P;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;

import net.kdt.pojavlaunch.Tools;

/**
 * Pojav 的 {@code LauncherPreferences} 的精简替身：只保留控制层（customcontrols、
 * Touchpad、Gyro、QuickSettingSideDialog…）真正读写的 17 个字段。
 *
 * <p><b>关键约束（照原版）</b>：这些字段全部是 {@code public static} <b>非 final</b>，
 * 因为 {@code QuickSettingSideDialog} 的取消逻辑会回滚它们，
 * {@code ControlLayout#openSetDefaultDialog} 也会写 {@code PREF_DEFAULTCTRL_PATH}。
 *
 * <p><b>与原版的差异</b>：原版的 {@code loadPreferences} 还要读多运行时（MultiRT）、
 * 解析自定义 Java 参数、算最优分辨率档位；QookiX 的 JRE 由 Rust 侧管理，
 * 这里只加载控制层关心的项，其余保持原版默认值。
 * 读取的 SharedPreferences 键名与原版完全一致，因此 Pojav 的偏好文件可以直接沿用。
 */
public class LauncherPreferences {

    public static final String PREF_KEY_CURRENT_PROFILE = "currentProfile";
    public static final String PREF_KEY_SKIP_NOTIFICATION_CHECK = "skipNotificationPermissionCheck";

    public static SharedPreferences DEFAULT_PREF;
    public static String PREF_RENDERER = "opengles2";

    public static boolean PREF_IGNORE_NOTCH = false;
    public static int PREF_NOTCH_SIZE = 0;
    public static float PREF_BUTTONSIZE = 100f;
    public static float PREF_MOUSESCALE = 1f;
    public static int PREF_LONGPRESS_TRIGGER = 300;
    public static String PREF_DEFAULTCTRL_PATH = null;
    public static String PREF_CUSTOM_JAVA_ARGS = "";
    public static boolean PREF_FORCE_ENGLISH = false;
    public static boolean PREF_DISABLE_GESTURES = false;
    public static boolean PREF_DISABLE_SWAP_HAND = false;
    public static float PREF_MOUSESPEED = 1f;
    public static int PREF_RAM_ALLOCATION;
    public static String PREF_DEFAULT_RUNTIME;
    public static boolean PREF_SUSTAINED_PERFORMANCE = false;
    public static boolean PREF_VIRTUAL_MOUSE_START = false;
    public static boolean PREF_USE_ALTERNATE_SURFACE = true;
    public static float PREF_SCALE_FACTOR = 1f;

    public static boolean PREF_ENABLE_GYRO = false;
    public static float PREF_GYRO_SENSITIVITY = 1f;
    public static int PREF_GYRO_SAMPLE_RATE = 16;
    public static boolean PREF_GYRO_SMOOTHING = true;
    public static boolean PREF_GYRO_INVERT_X = false;
    public static boolean PREF_GYRO_INVERT_Y = false;

    public static boolean PREF_BUTTON_ALL_CAPS = true;
    public static float PREF_DEADZONE_SCALE = 1f;

    /**
     * 从 SharedPreferences 加载偏好。键名与原版逐一对齐。
     *
     * @param ctx 用于 {@link Tools#initStorageConstants(Context)} 初始化
     *            {@link Tools#CTRLDEF_FILE}（它是 {@link #PREF_DEFAULTCTRL_PATH} 的默认值）。
     */
    public static void loadPreferences(Context ctx) {
        Tools.initStorageConstants(ctx);

        if (DEFAULT_PREF == null) {
            Log.w(Tools.APP_NAME, "LauncherPreferences.loadPreferences: DEFAULT_PREF 为空，使用默认值");
            PREF_DEFAULTCTRL_PATH = Tools.CTRLDEF_FILE;
            return;
        }

        PREF_RENDERER = DEFAULT_PREF.getString("renderer", "opengles2");
        PREF_BUTTONSIZE = DEFAULT_PREF.getInt("buttonscale", 100);
        PREF_MOUSESCALE = DEFAULT_PREF.getInt("mousescale", 100) / 100f;
        PREF_MOUSESPEED = DEFAULT_PREF.getInt("mousespeed", 100) / 100f;
        PREF_IGNORE_NOTCH = DEFAULT_PREF.getBoolean("ignoreNotch", false);
        PREF_LONGPRESS_TRIGGER = DEFAULT_PREF.getInt("timeLongPressTrigger", 300);
        PREF_DEFAULTCTRL_PATH = DEFAULT_PREF.getString("defaultCtrl", Tools.CTRLDEF_FILE);
        PREF_FORCE_ENGLISH = DEFAULT_PREF.getBoolean("force_english", false);
        PREF_DISABLE_GESTURES = DEFAULT_PREF.getBoolean("disableGestures", false);
        PREF_DISABLE_SWAP_HAND = DEFAULT_PREF.getBoolean("disableDoubleTap", false);
        PREF_CUSTOM_JAVA_ARGS = DEFAULT_PREF.getString("javaArgs", "");
        PREF_VIRTUAL_MOUSE_START = DEFAULT_PREF.getBoolean("mouse_start", false);
        PREF_USE_ALTERNATE_SURFACE = DEFAULT_PREF.getBoolean("alternate_surface", true);
        PREF_SCALE_FACTOR = DEFAULT_PREF.getInt("resolutionRatio", 100) / 100f;
        PREF_ENABLE_GYRO = DEFAULT_PREF.getBoolean("enableGyro", false);
        PREF_GYRO_SENSITIVITY = DEFAULT_PREF.getInt("gyroSensitivity", 100) / 100f;
        PREF_GYRO_SAMPLE_RATE = DEFAULT_PREF.getInt("gyroSampleRate", 16);
        PREF_GYRO_SMOOTHING = DEFAULT_PREF.getBoolean("gyroSmoothing", true);
        PREF_GYRO_INVERT_X = DEFAULT_PREF.getBoolean("gyroInvertX", false);
        PREF_GYRO_INVERT_Y = DEFAULT_PREF.getBoolean("gyroInvertY", false);
        PREF_BUTTON_ALL_CAPS = DEFAULT_PREF.getBoolean("buttonAllCaps", false);
        PREF_DEADZONE_SCALE = DEFAULT_PREF.getInt("gamepad_deadzone_scale", 100) / 100f;
    }

    /**
     * 计算刘海/挖孔尺寸，避免控件被裁掉。原版方法，逐行照搬。
     */
    public static void computeNotchSize(Activity activity) {
        if (SDK_INT < P) {
            Tools.updateWindowSize(activity);
            return;
        }
        try {
            final Rect cutout;
            if (SDK_INT >= Build.VERSION_CODES.S) {
                cutout = activity.getWindowManager().getCurrentWindowMetrics()
                        .getWindowInsets().getDisplayCutout().getBoundingRects().get(0);
            } else {
                cutout = activity.getWindow().getDecorView().getRootWindowInsets()
                        .getDisplayCutout().getBoundingRects().get(0);
            }

            // 刘海尺寸随旋转变化，分别处理
            int orientation = activity.getResources().getConfiguration().orientation;
            if (orientation == Configuration.ORIENTATION_PORTRAIT) {
                PREF_NOTCH_SIZE = cutout.height();
            } else if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                PREF_NOTCH_SIZE = cutout.width();
            } else {
                PREF_NOTCH_SIZE = Math.min(cutout.width(), cutout.height());
            }
        } catch (Exception e) {
            Log.i("NOTCH DETECTION", "No notch detected, or the device is in split screen mode");
            PREF_NOTCH_SIZE = -1;
        }
        Tools.updateWindowSize(activity);
    }

    /** 供诊断用：当前分辨率倍率对应的像素宽度。 */
    public static int scaledWidth(DisplayMetrics metrics) {
        return Tools.getDisplayFriendlyRes(metrics.widthPixels, PREF_SCALE_FACTOR);
    }
}
