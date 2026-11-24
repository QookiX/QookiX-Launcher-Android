package net.kdt.pojavlaunch.customcontrols;

import com.zhayi.qookix.R;
import android.content.*;

import androidx.annotation.Keep;

import java.io.IOException;
import java.util.*;
import net.kdt.pojavlaunch.*;

@Keep
public class CustomControls {
	public int version = -1;
    public boolean hideTouchControlsWithExternalInput;
    /** 接手柄时不隐藏触控键（ZL2 的 hideWhenGamepad 例外）；默认 false = 保持原行为。 */
    public boolean keepTouchControlsWithGamepad;
    public float scaledAt;
    public List<ControlData> mControlDataList;
    public List<ControlDrawerData> mDrawerDataList;
    public List<ControlJoystickData> mJoystickDataList;
    /** v9 起落盘：FCL / ZL2 的层属性整层生效，旧布局按 groupId 补默认层。 */
    public List<ControlLayerData> mLayerDataList;
    /** 共享样式表：同 styleId 的按钮外观由这里统一维护。 */
    public List<ControlStyleData> mStyleDataList;
    public CustomControls() {
    	this(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    }



    public CustomControls(List<ControlData> mControlDataList, List<ControlDrawerData> mDrawerDataList, List<ControlJoystickData> mJoystickDataList) {
    	this.mControlDataList = mControlDataList;
    	this.mDrawerDataList = mDrawerDataList;
    	this.mJoystickDataList = mJoystickDataList;
    	this.mLayerDataList = new ArrayList<>();
    	this.mStyleDataList = new ArrayList<>();
    	this.scaledAt = 100f;
    }

    /** 旧布局没有层元数据时，按按钮/摇杆上的 groupId 补默认层，不改变原有显隐行为。 */
    public void ensureLayers() {
    	if (mLayerDataList == null) mLayerDataList = new ArrayList<>();
    	Set<String> ids = new HashSet<>();
    	for (ControlLayerData layer : mLayerDataList) {
    		if (layer != null && layer.id != null) ids.add(layer.id);
    	}
    	if (mControlDataList != null) {
    		for (ControlData data : mControlDataList) addLayerId(data.groupId, ids);
    	}
    	if (mJoystickDataList != null) {
    		for (ControlJoystickData data : mJoystickDataList) addLayerId(data.groupId, ids);
    	}
    }

    private void addLayerId(String id, Set<String> ids) {
    	if (id == null || id.isEmpty() || ids.contains(id)) return;
    	ids.add(id);
    	mLayerDataList.add(new ControlLayerData(id, "always", false, false, false));
    }

    public void ensureStyles() { if (mStyleDataList == null) mStyleDataList = new ArrayList<>(); }

	// Generate default control
	// Here for historical reasons
	// Just admire it idk
	@SuppressWarnings("unused")
	public CustomControls(Context ctx) {
		this();
		this.mControlDataList.add(new ControlData(ControlData.getSpecialButtons()[0])); // Keyboard
		this.mControlDataList.add(new ControlData(ControlData.getSpecialButtons()[1])); // GUI
		this.mControlDataList.add(new ControlData(ControlData.getSpecialButtons()[2])); // Primary Mouse mControlDataList
		this.mControlDataList.add(new ControlData(ControlData.getSpecialButtons()[3])); // Secondary Mouse mControlDataList
		this.mControlDataList.add(new ControlData(ControlData.getSpecialButtons()[4])); // Virtual mouse toggle

		this.mControlDataList.add(new ControlData(ctx, R.string.control_debug, new int[]{LwjglGlfwKeycode.GLFW_KEY_F3}, "${margin}", "${margin}", false));
		this.mControlDataList.add(new ControlData(ctx, R.string.control_chat, new int[]{LwjglGlfwKeycode.GLFW_KEY_T}, "${margin} * 2 + ${width}", "${margin}", false));
		this.mControlDataList.add(new ControlData(ctx, R.string.control_listplayers, new int[]{LwjglGlfwKeycode.GLFW_KEY_TAB}, "${margin} * 4 + ${width} * 3", "${margin}", false));
		this.mControlDataList.add(new ControlData(ctx, R.string.control_thirdperson, new int[]{LwjglGlfwKeycode.GLFW_KEY_F5}, "${margin}", "${height} + ${margin}", false));

		this.mControlDataList.add(new ControlData(ctx, R.string.control_up, new int[]{LwjglGlfwKeycode.GLFW_KEY_W}, "${margin} * 2 + ${width}", "${bottom} - ${margin} * 3 - ${height} * 2", true));
		this.mControlDataList.add(new ControlData(ctx, R.string.control_left, new int[]{LwjglGlfwKeycode.GLFW_KEY_A}, "${margin}", "${bottom} - ${margin} * 2 - ${height}", true));
		this.mControlDataList.add(new ControlData(ctx, R.string.control_down, new int[]{LwjglGlfwKeycode.GLFW_KEY_S}, "${margin} * 2 + ${width}", "${bottom} - ${margin}", true));
		this.mControlDataList.add(new ControlData(ctx, R.string.control_right, new int[]{LwjglGlfwKeycode.GLFW_KEY_D}, "${margin} * 3 + ${width} * 2", "${bottom} - ${margin} * 2 - ${height}", true));

		this.mControlDataList.add(new ControlData(ctx, R.string.control_inventory, new int[]{LwjglGlfwKeycode.GLFW_KEY_E}, "${margin} * 3 + ${width} * 2", "${bottom} - ${margin}", true));
        
        ControlData shiftData = new ControlData(ctx, R.string.control_shift, new int[]{LwjglGlfwKeycode.GLFW_KEY_LEFT_SHIFT}, "${margin} * 2 + ${width}", "${screen_height} - ${margin} * 2 - ${height} * 2", true);
		shiftData.isToggle = true;
		this.mControlDataList.add(shiftData);
		this.mControlDataList.add(new ControlData(ctx, R.string.control_jump, new int[]{LwjglGlfwKeycode.GLFW_KEY_SPACE}, "${right} - ${margin} * 2 - ${width}", "${bottom} - ${margin} * 2 - ${height}", true));

		//The default controls carry layer metadata (v9)
		version = 9;
	}


	public void save(String path) throws IOException {
		ensureLayers();
		ensureStyles();
		version = 9;

		Tools.write(path, Tools.GLOBAL_GSON.toJson(this));
	}
}
