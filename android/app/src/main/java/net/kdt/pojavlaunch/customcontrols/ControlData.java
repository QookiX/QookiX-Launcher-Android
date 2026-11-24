package net.kdt.pojavlaunch.customcontrols;

import static net.kdt.pojavlaunch.LwjglGlfwKeycode.GLFW_KEY_UNKNOWN;

import android.content.Context;
import android.util.ArrayMap;

import androidx.annotation.Keep;

import com.zhayi.qookix.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.customcontrols.buttons.ControlInterface;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.utils.JSONUtils;
import net.objecthunter.exp4j.ExpressionBuilder;
import net.objecthunter.exp4j.function.Function;

import org.lwjgl.glfw.CallbackBridge;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Keep
public class ControlData {

    public static final int SPECIALBTN_KEYBOARD = -1;
    public static final int SPECIALBTN_TOGGLECTRL = -2;
    public static final int SPECIALBTN_MOUSEPRI = -3;
    public static final int SPECIALBTN_MOUSESEC = -4;
    public static final int SPECIALBTN_VIRTUALMOUSE = -5;
    public static final int SPECIALBTN_MOUSEMID = -6;
    public static final int SPECIALBTN_SCROLLUP = -7;
    public static final int SPECIALBTN_SCROLLDOWN = -8;
    public static final int SPECIALBTN_MENU = -9;
    public static final int SPECIALBTN_WHEEL_GESTURE = -10;
    /** 强制切到游玩层 / GUI 层，供背包按钮一键换布局（ZL2 switch_layer 的轻量版）。 */
    public static final int SPECIALBTN_FORCE_GAMEPLAY = -11;
    public static final int SPECIALBTN_FORCE_MENU = -12;

    private static ControlData[] SPECIAL_BUTTONS;
    private static WeakReference<ExpressionBuilder> builder = new WeakReference<>(null);
    private static WeakReference<ArrayMap<String, String>> conversionMap = new WeakReference<>(null);

    static {
        buildExpressionBuilder();
        buildConversionMap();
    }

    // Internal usage only
    public transient boolean isHideable;
    /**
     * Both fields below are dynamic position data, auto updates
     * X and Y position, unlike the original one which uses fixed
     * position, so it does not provide auto-location when a control
     * is made on a small device, then import the control to a
     * bigger device or vice versa.
     */
    public String dynamicX, dynamicY;
    public boolean isToggle, passThruEnabled;
    public String name;
    public int[] keycodes;      //Should store up to 4 keys
    public float opacity;       //Alpha value from 0 to 1;
    public int bgColor;
    public int strokeColor;
    public float strokeWidth;     // Dp instead of % now
    public float cornerRadius;  //0-100%
    public boolean isSwipeable;
    public boolean displayInGame;
    public boolean displayInMenu;
    public String groupId;
    public boolean groupInitiallyHidden;
    public boolean labelOnly;
    public int scrollWhileHeld;
    public boolean autoClick;
    public Appearance appearance, darkAppearance;
    public float widthFraction, heightFraction;
    public boolean widthRelativeToHeight, heightRelativeToHeight;
    public Action pressAction, clickAction, longPressAction, doubleClickAction;

    @Keep
    public static class Action {
        public int[] keys;
        public String text;
        public String[] groups;
        public boolean autoClick;
        public boolean switchTouchMode;
    }

    @Keep
    public static class Appearance {
        public PaintStyle normal, pressed;
    }

    @Keep
    public static class PaintStyle {
        public int fill, outline, text;
        public float strokeDp, radiusDp, textSp;
        public float[] cornerPercent;
    }
    private float width;         //Dp instead of Px now
    private float height;        //Dp instead of Px now

    public ControlData() {
        this("button");
    }

    public ControlData(String name) {
        this(name, new int[]{});
    }

    public ControlData(String name, int[] keycodes) {
        this(name, keycodes, Tools.currentDisplayMetrics.widthPixels / 2f, Tools.currentDisplayMetrics.heightPixels / 2f);
    }

    public ControlData(String name, int[] keycodes, float x, float y) {
        this(name, keycodes, x, y, 50, 50);
    }

    public ControlData(android.content.Context ctx, int resId, int[] keycodes, float x, float y, boolean isSquare) {
        this(ctx.getResources().getString(resId), keycodes, x, y, isSquare);
    }

    public ControlData(String name, int[] keycodes, float x, float y, boolean isSquare) {
        this(name, keycodes, x, y, isSquare ? 50 : 80, isSquare ? 50 : 30);
    }

    public ControlData(String name, int[] keycodes, float x, float y, float width, float height) {
        this(name, keycodes, Float.toString(x), Float.toString(y), width, height, false);
    }

    public ControlData(String name, int[] keycodes, String dynamicX, String dynamicY) {
        this(name, keycodes, dynamicX, dynamicY, 50, 50, false);
    }

    public ControlData(android.content.Context ctx, int resId, int[] keycodes, String dynamicX, String dynamicY, boolean isSquare) {
        this(ctx.getResources().getString(resId), keycodes, dynamicX, dynamicY, isSquare);
    }

    public ControlData(String name, int[] keycodes, String dynamicX, String dynamicY, boolean isSquare) {
        this(name, keycodes, dynamicX, dynamicY, isSquare ? 50 : 80, isSquare ? 50 : 30, false);
    }

    public ControlData(String name, int[] keycodes, String dynamicX, String dynamicY, float width, float height, boolean isToggle) {
        this(name, keycodes, dynamicX, dynamicY, width, height, isToggle, 1, 0x4D000000, 0xFFFFFFFF, 0, 0, true, true, false, false);
    }

    public ControlData(String name, int[] keycodes, String dynamicX, String dynamicY, float width, float height, boolean isToggle, float opacity, int bgColor, int strokeColor, float strokeWidth, float cornerRadius, boolean displayInGame, boolean displayInMenu, boolean isSwipable, boolean mousePassthrough) {
        this.name = name;
        this.keycodes = inflateKeycodeArray(keycodes);
        this.dynamicX = dynamicX;
        this.dynamicY = dynamicY;
        this.width = width;
        this.height = height;
        this.isToggle = isToggle;
        this.opacity = opacity;
        this.bgColor = bgColor;
        this.strokeColor = strokeColor;
        this.strokeWidth = strokeWidth;
        this.cornerRadius = cornerRadius;
        this.displayInGame = displayInGame;
        this.displayInMenu = displayInMenu;
        this.isSwipeable = isSwipable;
        this.passThruEnabled = mousePassthrough;
    }

    //Deep copy constructor
    public ControlData(ControlData controlData) {
        this(
                controlData.name,
                controlData.keycodes,
                controlData.dynamicX,
                controlData.dynamicY,
                controlData.width,
                controlData.height,
                controlData.isToggle,
                controlData.opacity,
                controlData.bgColor,
                controlData.strokeColor,
                controlData.strokeWidth,
                controlData.cornerRadius,
                controlData.displayInGame,
                controlData.displayInMenu,
                controlData.isSwipeable,
                controlData.passThruEnabled
        );
        groupId = controlData.groupId;
        groupInitiallyHidden = controlData.groupInitiallyHidden;
        labelOnly = controlData.labelOnly;
        scrollWhileHeld = controlData.scrollWhileHeld;
        autoClick = controlData.autoClick;
        appearance = controlData.appearance;
        darkAppearance = controlData.darkAppearance;
        widthFraction = controlData.widthFraction;
        heightFraction = controlData.heightFraction;
        widthRelativeToHeight = controlData.widthRelativeToHeight;
        heightRelativeToHeight = controlData.heightRelativeToHeight;
        pressAction = controlData.pressAction;
        clickAction = controlData.clickAction;
        longPressAction = controlData.longPressAction;
        doubleClickAction = controlData.doubleClickAction;
    }

    public static ControlData[] getSpecialButtons() {
        if (SPECIAL_BUTTONS == null) {
            SPECIAL_BUTTONS = new ControlData[]{
                    new ControlData("Keyboard", new int[]{SPECIALBTN_KEYBOARD}, "${margin} * 3 + ${width} * 2", "${margin}", false),
                    new ControlData("GUI", new int[]{SPECIALBTN_TOGGLECTRL}, "${margin}", "${bottom} - ${margin}"),
                    new ControlData("PRI", new int[]{SPECIALBTN_MOUSEPRI}, "${margin}", "${screen_height} - ${margin} * 3 - ${height} * 3"),
                    new ControlData("SEC", new int[]{SPECIALBTN_MOUSESEC}, "${margin} * 3 + ${width} * 2", "${screen_height} - ${margin} * 3 - ${height} * 3"),
                    new ControlData("Mouse", new int[]{SPECIALBTN_VIRTUALMOUSE}, "${right}", "${margin}", false),

                    new ControlData("MID", new int[]{SPECIALBTN_MOUSEMID}, "${margin}", "${margin}"),
                    new ControlData("SCROLLUP", new int[]{SPECIALBTN_SCROLLUP}, "${margin}", "${margin}"),
                    new ControlData("SCROLLDOWN", new int[]{SPECIALBTN_SCROLLDOWN}, "${margin}", "${margin}"),
                    new ControlData("MENU", new int[]{SPECIALBTN_MENU}, "${margin}", "${margin}"),
                    new ControlData("WHEEL_GESTURE", new int[]{SPECIALBTN_WHEEL_GESTURE}, "${margin}", "${margin}"),
                    new ControlData("SCENE_GAMEPLAY", new int[]{SPECIALBTN_FORCE_GAMEPLAY}, "${margin}", "${margin}"),
                    new ControlData("SCENE_MENU", new int[]{SPECIALBTN_FORCE_MENU}, "${margin}", "${margin}")
            };
        }

        return SPECIAL_BUTTONS;
    }

    public static List<String> buildSpecialButtonArray(Context context) {
        ControlData[] buttons = getSpecialButtons();
        List<String> labels = new ArrayList<>(buttons.length);
        for (int index = buttons.length - 1; index >= 0; index--) {
            ControlData button = buttons[index];
            switch (button.keycodes[0]) {
                case SPECIALBTN_WHEEL_GESTURE: labels.add(context.getString(R.string.customctrl_wheel_gesture)); break;
                case SPECIALBTN_FORCE_GAMEPLAY: labels.add(context.getString(R.string.customctrl_scene_gameplay)); break;
                case SPECIALBTN_FORCE_MENU: labels.add(context.getString(R.string.customctrl_scene_menu)); break;
                case SPECIALBTN_SCROLLUP: labels.add(context.getString(R.string.customctrl_wheel_up)); break;
                case SPECIALBTN_SCROLLDOWN: labels.add(context.getString(R.string.customctrl_wheel_down)); break;
                default: labels.add("SPECIAL_" + button.name);
            }
        }
        return labels;
    }

    private static float calculate(String math) {
        setExpression(math);
        return (float) builder.get().build().evaluate();
    }

    private static int[] inflateKeycodeArray(int[] keycodes) {
        int[] inflatedArray = new int[]{GLFW_KEY_UNKNOWN, GLFW_KEY_UNKNOWN, GLFW_KEY_UNKNOWN, GLFW_KEY_UNKNOWN};
        System.arraycopy(keycodes, 0, inflatedArray, 0, keycodes.length);
        return inflatedArray;
    }

    /**
     * Create a builder, keep a weak reference to it to use it with all views on first inflation
     */
    private static void buildExpressionBuilder() {
        ExpressionBuilder expressionBuilder = new ExpressionBuilder("1 + 1")
                .function(new Function("dp", 1) {
                    @Override
                    public double apply(double... args) {
                        return Tools.pxToDp((float) args[0]);
                    }
                })
                .function(new Function("px", 1) {
                    @Override
                    public double apply(double... args) {
                        return Tools.dpToPx((float) args[0]);
                    }
                });
        builder = new WeakReference<>(expressionBuilder);
    }

    /**
     * wrapper for the WeakReference to the expressionField.
     *
     * @param stringExpression the expression to set.
     */
    private static void setExpression(String stringExpression) {
        if (builder.get() == null) buildExpressionBuilder();
        builder.get().expression(stringExpression);
    }

    /**
     * Build a shared conversion map without the ControlData dependent values
     * You need to set the view dependent values before using it.
     */
    private static void buildConversionMap() {
        // Values in the map below may be always changed
        ArrayMap<String, String> keyValueMap = new ArrayMap<>(10);
        keyValueMap.put("top", "0");
        keyValueMap.put("left", "0");
        keyValueMap.put("right", "DUMMY_RIGHT");
        keyValueMap.put("bottom", "DUMMY_BOTTOM");
        keyValueMap.put("width", "DUMMY_WIDTH");
        keyValueMap.put("height", "DUMMY_HEIGHT");
        keyValueMap.put("screen_width", "DUMMY_DATA");
        keyValueMap.put("screen_height", "DUMMY_DATA");
        keyValueMap.put("margin", Integer.toString((int) ControlInterface.getMarginDistance()));
        keyValueMap.put("preferred_scale", "DUMMY_DATA");

        conversionMap = new WeakReference<>(keyValueMap);
    }

    public float insertDynamicPos(String dynamicPos) {
        // Insert value to ${variable}
        String insertedPos = JSONUtils.insertSingleJSONValue(dynamicPos, fillConversionMap());

        // Calculate, because the dynamic position contains some math equations
        return calculate(insertedPos);
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean containsKeycode(int keycodeToCheck) {
        for (int keycode : keycodes)
            if (keycodeToCheck == keycode)
                return true;

        return false;
    }

    // ── 导入样式与旧字段的双向同步 ─────────────────────────────────────────
    // 编辑器只认 bgColor/strokeColor/cornerRadius，导入的布局用的是 appearance。
    // 不同步的话：打开属性面板看到的是默认黑底，改完颜色又被 appearance 盖回去。

    /** 导入样式 → 旧字段：让属性面板显示当前实际外观。 */
    public void appearanceToLegacy() {
        Appearance a = appearance != null ? appearance : darkAppearance;
        if (a == null || a.normal == null) return;
        bgColor = a.normal.fill;
        strokeColor = a.normal.outline;
        strokeWidth = a.normal.strokeDp;
        float[] corners = a.normal.cornerPercent;
        if (corners != null && corners.length == 4) {
            cornerRadius = (corners[0] + corners[1] + corners[2] + corners[3]) / 4f;
        }
    }

    /** 旧字段 → 导入样式：编辑器改完颜色/圆角后写回，两套绘制路径结果一致。 */
    public void legacyToAppearance() {
        for (Appearance a : new Appearance[]{appearance, darkAppearance}) {
            if (a == null) continue;
            for (PaintStyle s : new PaintStyle[]{a.normal, a.pressed}) {
                if (s == null) continue;
                s.fill = bgColor;
                s.outline = strokeColor;
                s.strokeDp = strokeWidth;
                s.cornerPercent = new float[]{cornerRadius, cornerRadius, cornerRadius, cornerRadius};
            }
        }
    }

    //Getters || setters (with conversion for ease of use)
    public float getWidth() {
        return resolveSize(width, widthFraction, widthRelativeToHeight);
    }

    public void setWidth(float widthInPx) {
        width = Tools.pxToDp(widthInPx);
        widthFraction = 0;
    }

    public float getHeight() {
        return resolveSize(height, heightFraction, heightRelativeToHeight);
    }

    public void setHeight(float heightInPx) {
        height = Tools.pxToDp(heightInPx);
        heightFraction = 0;
    }

    private static float resolveSize(float dp, float fraction, boolean relativeToHeight) {
        int reference = relativeToHeight ? CallbackBridge.physicalHeight : CallbackBridge.physicalWidth;
        if (reference > 0 && fraction > 0 && fraction <= 1f) return Math.max(1f, reference * fraction);
        return Tools.dpToPx(dp);
    }

    /**
     * Fill the conversionMap with controlData dependent values.
     * The returned valueMap should NOT be kept in memory.
     *
     * @return the valueMap to use.
     */
    private Map<String, String> fillConversionMap() {
        ArrayMap<String, String> valueMap = conversionMap.get();
        if (valueMap == null) {
            buildConversionMap();
            valueMap = conversionMap.get();
        }

        valueMap.put("right", Float.toString(CallbackBridge.physicalWidth - getWidth()));
        valueMap.put("bottom", Float.toString(CallbackBridge.physicalHeight - getHeight()));
        valueMap.put("width", Float.toString(getWidth()));
        valueMap.put("height", Float.toString(getHeight()));
        valueMap.put("screen_width", Integer.toString(CallbackBridge.physicalWidth));
        valueMap.put("screen_height", Integer.toString(CallbackBridge.physicalHeight));
        valueMap.put("preferred_scale", Float.toString(LauncherPreferences.PREF_BUTTONSIZE));

        return valueMap;
    }

}
