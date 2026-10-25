package net.kdt.pojavlaunch;

import static android.os.Build.VERSION.SDK_INT;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.database.Cursor;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.OpenableColumns;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.zhayi.qookix.R;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import org.lwjgl.glfw.CallbackBridge;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Pojav 的 {@code net.kdt.pojavlaunch.Tools} 的精简替身。
 *
 * <p>原版 Tools.java 有 1600 多行，牵扯整个启动器（下载、整合包、多运行时、文件选择器…）。
 * QookiX 只需要控制层真正用到的那 22 个成员，于是这里按原语义逐个实现，
 * 方法名/签名/行为与原版一致，这样上游的 customcontrols 代码可以原样编译。
 *
 * <p><b>与原版的差异（有意为之）</b>：
 * <ul>
 *   <li>存储根目录用 QookiX 的 {@code <files>/controlmap}（原版是
 *       {@code <storage>/games/PojavLauncher/controlmap}）；SharedPreferences 由 QookiX 管理。</li>
 *   <li>{@link #showError} 不再弹 Pojav 的 FatalErrorActivity，改为 Toast + logcat。</li>
 *   <li>{@link #checkStorageInteractive}/{@link #initStorageConstants} 对 QookiX 是恒真/空操作
 *       —— 应用私有目录不需要存储权限。</li>
 * </ul>
 */
public class Tools {

    public static final String APP_NAME = "QookiX";

    /** 主线程 Handler（原版同名同义）。 */
    public static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    /** Gson 实例：控制布局 JSON 的序列化/反序列化（字段名即磁盘格式，绝不能改）。 */
    public static final Gson GLOBAL_GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    /** 当前显示度量。控制层在<b>字段初始化</b>里就读它，所以必须在构造任何控件前赋值。 */
    public static DisplayMetrics currentDisplayMetrics = null;

    /** 当前实例的游戏目录（Pojav 的 Tools.DIR_GAME_NEW）。由 GameActivity 在 onCreate 里赋值。 */
    public static String DIR_GAME_NEW = null;

    /** 控制布局目录。 */
    public static String CTRLMAP_PATH = null;
    /** 默认控制布局文件（{@code CTRLMAP_PATH/default.json}）。 */
    public static String CTRLDEF_FILE = null;

    private Tools() {
    }

    // ---------------------------------------------------------------- 存储

    /**
     * 初始化存储常量。原版会去申请外部存储；QookiX 用应用私有目录，无需权限。
     */
    public static void initStorageConstants(Context context) {
        if (context == null) return;
        File dir = new File(context.getFilesDir(), "controlmap");
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(APP_NAME, "无法创建控制布局目录: " + dir);
        }
        CTRLMAP_PATH = dir.getAbsolutePath();
        CTRLDEF_FILE = CTRLMAP_PATH + "/default.json";
    }

    /** QookiX 使用应用私有目录，存储永远可用。 */
    public static boolean checkStorageInteractive(Activity activity) {
        return true;
    }

    // ---------------------------------------------------------------- 单位换算

    public static float dpToPx(float dp) {
        DisplayMetrics metrics = currentDisplayMetrics;
        if (metrics == null) {
            // 极早期的调用（布局 inflate 之前）兜底：按 1x 处理，避免 NPE 直接崩。
            return dp;
        }
        return dp * metrics.density;
    }

    public static float pxToDp(float px) {
        DisplayMetrics metrics = currentDisplayMetrics;
        if (metrics == null) return px;
        return px / metrics.density;
    }

    /** 原版语义：非空且非空白字符串。 */
    public static boolean isValidString(String string) {
        return string != null && !string.trim().isEmpty();
    }

    // ---------------------------------------------------------------- 文件读写

    public static String read(String path) throws IOException {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    public static void write(String path, String content) throws IOException {
        File file = new File(path);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建目录: " + parent);
        }
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8)) {
            writer.write(content);
        }
    }

    // ---------------------------------------------------------------- UI 线程

    public static void runOnUiThread(Runnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run();
        } else {
            MAIN_HANDLER.post(runnable);
        }
    }

    // ---------------------------------------------------------------- 平台判断

    public static boolean isAndroid8OrHigher() {
        return SDK_INT >= Build.VERSION_CODES.O;
    }

    public static boolean deviceSupportsGyro(Context context) {
        if (context == null) return false;
        try {
            SensorManager manager =
                    (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
            return manager != null
                    && manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ---------------------------------------------------------------- 显示

    /**
     * 读取并缓存当前显示度量（照 Pojav 原实现）。
     *
     * 必须用 {@code getRealMetrics}（真实屏幕尺寸，含系统栏区域），而不是
     * {@code getResources().getDisplayMetrics()}：后者会扣掉系统栏，算出来的
     * ${screen_width} / ${screen_height} 偏小，用 ${right} / ${bottom} 定位的控制按钮会整体偏移。
     * 多窗口 / 画中画下才退回窗口尺寸（此时「全屏」没有意义）。
     */
    public static DisplayMetrics getDisplayMetrics(Activity activity) {
        DisplayMetrics displayMetrics = new DisplayMetrics();

        if (SDK_INT >= Build.VERSION_CODES.N
                && (activity.isInMultiWindowMode() || activity.isInPictureInPictureMode())) {
            // 自由窗口/分屏下要的是窗口尺寸，不是屏幕尺寸
            displayMetrics = activity.getResources().getDisplayMetrics();
        } else {
            if (SDK_INT >= Build.VERSION_CODES.R) {
                activity.getDisplay().getRealMetrics(displayMetrics);
            } else {
                activity.getWindowManager().getDefaultDisplay().getRealMetrics(displayMetrics);
            }
            if (!LauncherPreferences.PREF_IGNORE_NOTCH) {
                // 不忽略刘海时，把刘海占掉的那一边从可用区域里扣掉
                if (activity.getResources().getConfiguration().orientation
                        == Configuration.ORIENTATION_PORTRAIT) {
                    displayMetrics.heightPixels -= LauncherPreferences.PREF_NOTCH_SIZE;
                } else {
                    displayMetrics.widthPixels -= LauncherPreferences.PREF_NOTCH_SIZE;
                }
            }
        }
        currentDisplayMetrics = displayMetrics;
        return displayMetrics;
    }

    /**
     * 把「屏幕边长 × 缩放系数」取整成偶数。
     * 奇数宽度会让部分 GPU 的 EGL 配置选择失败，原版同样强制偶数。
     */
    public static int getDisplayFriendlyRes(int displaySideRes, float scaling) {
        displaySideRes *= scaling;
        if (displaySideRes % 2 != 0) displaySideRes--;
        return displaySideRes;
    }

    /**
     * 把「游戏窗口尺寸」写进 {@link CallbackBridge#physicalWidth}/{@link CallbackBridge#physicalHeight}。
     *
     * 这两个值决定控制按钮动态表达式里的 ${screen_width} / ${screen_height} / ${right} / ${bottom}，
     * **必须在加载控制布局之前**调用；否则 ${bottom} / ${right} / ${screen_height} 会算出负数，
     * 按钮被摆到屏幕外 —— 表现为「只有靠 ${margin} 定位的左上角几个按钮可见，WASD 全都不见」。
     *
     * 优先用布局里的 {@code dimension_tracker}（一个 match_parent 的 View）的实测尺寸；
     * 但它还没测量时（onAttachedToWindow 阶段宽高都是 0）必须**回退到屏幕尺寸** ——
     * 这条回退正是 Pojav 原实现的行为，缺了它就等于按钮全部失位。
     */
    public static void updateWindowSize(Activity activity) {
        if (activity == null) return;
        currentDisplayMetrics = getDisplayMetrics(activity);

        View dimensionView = activity.findViewById(R.id.dimension_tracker);
        if (dimensionView != null) {
            int width = dimensionView.getWidth();
            int height = dimensionView.getHeight();
            if (width != 0 && height != 0) {
                Log.i(APP_NAME, "Using dimension_tracker for display dimensions; W="
                        + width + " H=" + height);
                CallbackBridge.physicalWidth = width;
                CallbackBridge.physicalHeight = height;
                return;
            }
            Log.w(APP_NAME, "Dimension tracker detected but dimensions out of date; "
                    + "falling back to display metrics");
        }

        CallbackBridge.physicalWidth = currentDisplayMetrics.widthPixels;
        CallbackBridge.physicalHeight = currentDisplayMetrics.heightPixels;
    }

    // ---------------------------------------------------------------- 错误提示

    public static void showError(Context context, Throwable throwable) {
        showError(context, throwable, false);
    }

    public static void showError(Context context, Throwable throwable, boolean exit) {
        Log.e(APP_NAME, "控制层异常", throwable);
        if (context == null) return;
        final String message = throwable == null
                ? "未知错误"
                : throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
        runOnUiThread(() -> {
            try {
                Toast.makeText(context.getApplicationContext(), message, Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {
            }
        });
    }

    // ---------------------------------------------------------------- 文件选择

    /** 从 {@code content://} Uri 解析出显示用文件名（导入控制布局时用）。 */
    public static String getFileName(Context context, Uri uri) {
        if (uri == null) return null;
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = context.getContentResolver()
                    .query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index >= 0) result = cursor.getString(index);
                }
            } catch (Throwable ignored) {
            }
        }
        if (result == null) {
            String path = uri.getPath();
            if (path != null) {
                int cut = path.lastIndexOf('/');
                result = cut >= 0 ? path.substring(cut + 1) : path;
            }
        }
        return result;
    }

    // ---------------------------------------------------------------- 退出

    /**
     * Pojav 的「强制关闭游戏」对话框。
     *
     * 注意与上游的差异：原版直接 {@code Process.killProcess} 杀掉整个进程；
     * QookiX 的游戏 JVM 需要优雅退出（Rust 侧要落存档/日志），所以由调用方
     * （{@code GameActivity}）先走 {@code TauriBridge.killGame()}，这里只保留
     * 极端情况下的兜底杀进程。
     */
    public static void dialogForceClose(Context ctx) {
        new AlertDialog.Builder(ctx)
                .setMessage(R.string.mcn_exit_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    try {
                        fullyExit();
                    } catch (Throwable th) {
                        Log.w(APP_NAME, "Could not enable System.exit() method!", th);
                    }
                })
                .show();
    }

    public static void fullyExit() {
        Process.killProcess(Process.myPid());
    }
}
