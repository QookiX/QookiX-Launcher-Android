package net.kdt.pojavlaunch.customcontrols;

import static android.content.Context.INPUT_METHOD_SERVICE;
import static net.kdt.pojavlaunch.Tools.currentDisplayMetrics;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;


import com.google.gson.JsonSyntaxException;

import net.kdt.pojavlaunch.MinecraftGLSurface;
import net.kdt.pojavlaunch.GrabListener;
import com.zhayi.qookix.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.customcontrols.buttons.ControlButton;
import net.kdt.pojavlaunch.customcontrols.buttons.ControlDrawer;
import net.kdt.pojavlaunch.customcontrols.buttons.ControlInterface;
import net.kdt.pojavlaunch.customcontrols.buttons.ControlJoystick;
import net.kdt.pojavlaunch.customcontrols.buttons.ControlSubButton;
import net.kdt.pojavlaunch.customcontrols.handleview.ActionRow;
import net.kdt.pojavlaunch.customcontrols.handleview.ControlHandleView;
import net.kdt.pojavlaunch.customcontrols.handleview.EditControlSideDialog;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.lwjgl.glfw.CallbackBridge;

public class ControlLayout extends FrameLayout implements GrabListener {
	protected CustomControls mLayout;
	/* Accessible when inside the game by ControlInterface implementations, cached for perf. */
	private MinecraftGLSurface mGameSurface = null;

	/* Cache to buttons for performance purposes */
	private List<ControlInterface> mButtons;
	private boolean mModifiable = false;
	private boolean mIsModified;
	private boolean mControlVisible = false;
	private boolean mReadOnlyPreview;
	private boolean mExternalInput;
	private int mExternalSource = ControlInputSource.SOURCE_NONE;
	private boolean mGrabbing;
	private ControlScene mEditorScene = ControlScene.ALL;
	private ControlScene mForcedScene;
	private final java.util.Map<String, Boolean> groupVisibility = new java.util.HashMap<>();
	private Runnable mVisibilityListener;

	private EditControlSideDialog mControlDialog = null;
	private ControlHandleView mHandleView;
	private ControlButtonMenuListener mMenuListener;
	public ActionRow mActionRow = null;
	public String mLayoutFileName;

	public ControlLayout(Context ctx) {
		super(ctx);
	}

	public ControlLayout(Context ctx, AttributeSet attrs) {
		super(ctx, attrs);
	}


	public void loadLayout(String jsonPath) throws IOException, JsonSyntaxException {
		CustomControls layout = LayoutConverter.loadAndConvertIfNecessary(jsonPath);
		if(layout != null) {
			loadLayout(layout);
			updateLoadedFileName(jsonPath);
			return;
		}

		throw new IOException("Unsupported control layout version");
	}

	public void loadLayout(CustomControls controlLayout) {
		boolean sanitizedModified = false;
		if(controlLayout != null) {
			sanitizedModified = LayoutSanitizer.sanitizeLayout(controlLayout);
		}
		if(mActionRow == null){
			mActionRow = new ActionRow(getContext());
			addView(mActionRow);
		}

		removeAllButtons();
		if(mLayout != null) {
			mLayout.mControlDataList = null;
			mLayout = null;
		}

		System.gc();
		mapTable.clear();

		// Cleanup buttons only when input layout is null
		if (controlLayout == null) return;

		controlLayout.ensureLayers();
		mLayout = controlLayout;
		groupVisibility.clear();

		// Joystick(s) first, to workaround the touch dispatch
		for(ControlJoystickData joystick : mLayout.mJoystickDataList){
			addJoystickView(joystick);
		}

		//CONTROL BUTTON
		for (ControlData button : controlLayout.mControlDataList) {
			addControlView(button);
		}

		//CONTROL DRAWER
		for(ControlDrawerData drawerData : controlLayout.mDrawerDataList){
			ControlDrawer drawer = addDrawerView(drawerData);
			if(mModifiable) drawer.areButtonsVisible = true;
		}

		mLayout.scaledAt = LauncherPreferences.PREF_BUTTONSIZE;

		setModified(sanitizedModified);
		mButtons = null;
		refreshControlVisibility();
	} // loadLayout

	//CONTROL BUTTON
	public void addControlButton(ControlData controlButton) {
		mLayout.mControlDataList.add(controlButton);
		addControlView(controlButton);
	}

	private void addControlView(ControlData controlButton) {
		final ControlButton view = new ControlButton(this, controlButton);

		if (!mModifiable) {
			view.setAlpha(view.getProperties().opacity);
			view.setFocusable(false);
			view.setFocusableInTouchMode(false);
		}
		addView(view);

		setModified(true);
	}

	// CONTROL DRAWER
	public void addDrawer(ControlDrawerData drawerData){
		mLayout.mDrawerDataList.add(drawerData);
		addDrawerView();
	}

	private void addDrawerView(){
		addDrawerView(null);
	}

	private ControlDrawer addDrawerView(ControlDrawerData drawerData){

		final ControlDrawer view = new ControlDrawer(this,drawerData == null ? mLayout.mDrawerDataList.get(mLayout.mDrawerDataList.size()-1) : drawerData);

		if (!mModifiable) {
			view.setAlpha(view.getProperties().opacity);
			view.setFocusable(false);
			view.setFocusableInTouchMode(false);
		}
		addView(view);
		//CONTROL SUB BUTTON
		for (ControlData subButton : view.getDrawerData().buttonProperties) {
			addSubView(view, subButton);
		}

		setModified(true);
		return view;
	}

	//CONTROL SUB-BUTTON
	public void addSubButton(ControlDrawer drawer, ControlData controlButton){
		//Yep there isn't much here
		drawer.getDrawerData().buttonProperties.add(controlButton);
		addSubView(drawer, drawer.getDrawerData().buttonProperties.get(drawer.getDrawerData().buttonProperties.size()-1 ));
	}

	private void addSubView(ControlDrawer drawer, ControlData controlButton){
		final ControlSubButton view = new ControlSubButton(this, controlButton, drawer);

		if (!mModifiable) {
			view.setAlpha(view.getProperties().opacity);
			view.setFocusable(false);
			view.setFocusableInTouchMode(false);
		}else{
			view.setVisible(true);
		}

		addView(view);
		drawer.addButton(view);


		setModified(true);
	}

	// JOYSTICK BUTTON
	public void addJoystickButton(ControlJoystickData data){
		mLayout.mJoystickDataList.add(data);
		addJoystickView(data);
		setModified(true);
	}

	public void addDirectionPad() {
		ControlJoystickData pad = new ControlJoystickData();
		pad.directionPad = true;
		addJoystickButton(pad);
	}

	public void addTextLabel() {
		ControlData label = new ControlData(getContext().getString(R.string.qk_control_label));
		label.labelOnly = true;
		label.bgColor = 0;
		label.strokeWidth = 0;
		addControlButton(label);
	}

	private void addJoystickView(ControlJoystickData data){
		ControlJoystick view = new ControlJoystick(this, data);

		if (!mModifiable) {
			view.setAlpha(view.getProperties().opacity);
			view.setFocusable(false);
			view.setFocusableInTouchMode(false);
		}
		addView(view);

	}


	private void removeAllButtons() {
		releaseHeldControls();
		for(ControlInterface button : getButtonChildren()){
			removeView(button.getControlView());
		}

		System.gc();
		//i wanna be sure that all the removed Views will be removed after a reload
		//because if frames will slowly go down after many control changes it will be warm and bad
	}

	public void saveLayout(String path) throws Exception {
		mLayout.save(path);
		setModified(false);
	}

	public void toggleControlVisible(){ mControlVisible = !mControlVisible; setControlVisible(mControlVisible); }
	public float getLayoutScale(){ return mLayout.scaledAt; }
	public CustomControls getLayout(){ return mLayout; }
	public void setControlVisible(boolean isVisible) { mControlVisible = isVisible; refreshControlVisibility(); }

	public void setModifiable(boolean isModifiable) {
		if (!isModifiable && mModifiable) removeEditWindow();
		if (isModifiable && !mModifiable) releaseHeldControls();
		mModifiable = isModifiable; mEditorScene = ControlScene.ALL;
		refreshControlVisibility();
	}

	public void setReadOnlyPreview(boolean preview) { mReadOnlyPreview = preview; refreshControlVisibility(); }
	public boolean isReadOnlyPreview() { return mReadOnlyPreview; }
	public ControlScene getEditorScene() { return mEditorScene; }
	public void setEditorScene(ControlScene scene) { removeEditWindow(); mEditorScene = scene; refreshControlVisibility(); }

	public void setExternalInput(boolean external) {
		setExternalInput(external ? ControlInputSource.SOURCE_MOUSE : ControlInputSource.SOURCE_NONE);
	}

	/** 按设备类型切层：鼠标/手柄可用不同的隐藏策略（ZL2 hideWhenMouse/Gamepad）。 */
	public void setExternalInput(int source) {
		boolean external = source != ControlInputSource.SOURCE_NONE;
		if (mModifiable || (mExternalInput == external && mExternalSource == source)) return;
		mExternalInput = external;
		mExternalSource = source;
		refreshControlVisibility();
	}
	// 说明：`setExternalInput(boolean)` 等价于鼠标/键盘来源，保留给旧调用点。

	/** 按钮里的「切到 GUI 层」：强制场景直到游戏重新抓住鼠标（见 onGrabState）。 */
	public void forceScene(ControlScene scene) {
		if (mModifiable || mForcedScene == scene) return;
		mForcedScene = scene;
		refreshControlVisibility();
	}
	public void setVisibilityListener(Runnable listener) { mVisibilityListener = listener; }
	public void releaseHeldControls() {
		for (ControlInterface button : getButtonChildren()) button.cancelControlInput();
		mapTable.clear();
	}
	@Override protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		CallbackBridge.addGrabListener(this);
	}

	@Override protected void onDetachedFromWindow() {
		CallbackBridge.removeGrabListener(this);
		releaseHeldControls();
		super.onDetachedFromWindow();
	}

	@Override public void onGrabState(boolean grabbing) {
		mGrabbing = grabbing;
		// 游戏重新抓住鼠标说明已回到游玩态，解除按钮强制切过去的 GUI 层
		if (grabbing) mForcedScene = null;
		refreshControlVisibility();
	}

	public void refreshControlVisibility() {
		if (mLayout == null) return;
		boolean preview = mModifiable || mReadOnlyPreview;
		ControlScene scene = preview ? mEditorScene
				: (mForcedScene != null ? mForcedScene : (mGrabbing ? ControlScene.GAMEPLAY : ControlScene.MENU));
		boolean hideForDevice = !preview && mExternalInput && mLayout.hideTouchControlsWithExternalInput;
		// 手柄例外（ZL2 的 hideWhenGamepad）：接了手柄但只想藏一半时仍保留触控键
		if (hideForDevice && mExternalSource == ControlInputSource.SOURCE_GAMEPAD
				&& mLayout.keepTouchControlsWithGamepad) hideForDevice = false;
		for (ControlInterface button : getButtonChildren()) {
			if (button instanceof ControlSubButton) continue;
			ControlData data = button.getProperties();
			boolean included = scene.shows(data.displayInGame, data.displayInMenu);
			boolean visible = ControlLayerVisibility.visible(mLayout, data.groupId, !data.groupInitiallyHidden,
					groupVisibility, scene, !preview || mReadOnlyPreview, preview, mExternalInput, mExternalSource);
			button.setVisible(preview ? included && (!mReadOnlyPreview || visible)
					: visible && !hideForDevice && (!data.isHideable || (mControlVisible && included)));
		}
		if (mVisibilityListener != null) mVisibilityListener.run();
	}

	public void applyGroupActions(String[] actions) {
		for (String action : actions) {
			if (action == null) continue;
			int separator = action.indexOf(':');
			String id = separator < 0 ? action : action.substring(0, separator);
			String operation = separator < 0 ? "" : action.substring(separator + 1);
			for (ControlInterface button : getButtonChildren()) {
				ControlData data = button.getProperties();
				if (!id.equals(data.groupId)) continue;
				boolean visible = groupVisibility.getOrDefault(id, ControlLayerVisibility.initialVisible(mLayout, id, !data.groupInitiallyHidden));
				groupVisibility.put(id, operation.equals("show") || (!operation.equals("hide") && !visible));
				break;
			}
		}
		refreshControlVisibility();
	}

	private GuideOverlayView mGuideOverlay;

	public boolean isGroupVisible(String id, boolean fallback) { return groupVisibility.getOrDefault(id, ControlLayerVisibility.initialVisible(mLayout, id, fallback)); }

	public void setGroupVisible(String id, boolean visible) {
		groupVisibility.put(id, visible);
		refreshControlVisibility();
	}

	/** 拖动吸附时显示对齐辅助线；-1 表示该方向不画。 */
	public void showSnapGuides(float x, float y) {
		if (mGuideOverlay == null) {
			mGuideOverlay = new GuideOverlayView(getContext());
			mGuideOverlay.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
			addView(mGuideOverlay);
		}
		mGuideOverlay.setGuides(x, y);
	}

	public void hideSnapGuides() {
		if (mGuideOverlay == null) return;
		removeView(mGuideOverlay);
		mGuideOverlay = null;
	}

	public boolean getModifiable(){
		return mModifiable;
	}

	public void setModified(boolean isModified) {
		mIsModified = isModified;
	}

	public List<ControlInterface> getButtonChildren(){
		if(mModifiable || mButtons == null){
			mButtons = new ArrayList<>();
			for(int i=0; i<getChildCount(); ++i){
				View v = getChildAt(i);
				if(v instanceof ControlInterface)
					mButtons.add(((ControlInterface) v));
			}
		}

		return mButtons;
	}

	/** 窗口尺寸变化后重算百分比尺寸与 ${right}/${bottom} 定位。 */
	public void onWindowSizeChanged() {
		if (getContext() instanceof android.app.Activity) Tools.updateWindowSize((android.app.Activity) getContext());
		post(this::refreshControlButtonPositions);
	}

	public void refreshControlButtonPositions(){
		for(ControlInterface button : getButtonChildren()){
			ControlData data = button.getProperties();
			if (data.widthFraction > 0 || data.heightFraction > 0) {
				button.updateProperties();
			} else {
				button.setDynamicX(data.dynamicX);
				button.setDynamicY(data.dynamicY);
			}
		}
	}

    @Override
    public void onViewAdded(View child) {
        super.onViewAdded(child);
        if (child instanceof ControlInterface) mButtons = null;
    }

    @Override
    public void onViewRemoved(View child) {
        super.onViewRemoved(child);
        if (child instanceof ControlInterface) mButtons = null;
        if(child instanceof ControlInterface && mControlDialog != null){
			mControlDialog.disappearColor();
            mControlDialog.disappear(false);
        }
    }

    /**
	 * Load the layout if needed, and pass down the burden of filling values
	 * to the button at hand.
	 */
	public void editControlButton(ControlInterface button){
		if(mControlDialog == null){
			// When the panel is null, it needs to inflate first.
			// So inflate it, then process it on the next frame
			mControlDialog = new EditControlSideDialog(getContext(), this);
			post(() -> editControlButton(button));
			return;
		}

		mControlDialog.internalChanges = true;
		mControlDialog.setCurrentlyEditedButton(button);

		mControlDialog.appear(button.getControlView().getX() + button.getControlView().getWidth()/2f < currentDisplayMetrics.widthPixels/2f);
		button.loadEditValues(mControlDialog);

		mControlDialog.internalChanges = false;

		mControlDialog.disappearColor();

		if(mHandleView == null){
			mHandleView = new ControlHandleView(getContext());
			addView(mHandleView);
		}
		mHandleView.setControlButton(button);

		//mHandleView.show();
	}

	/** Swap the panel if the button position requires it */
	public void adaptPanelPosition(){
		if(mControlDialog != null) mControlDialog.adaptPanelPosition();
	}


	final HashMap<View, ControlInterface> mapTable = new HashMap<>();

	//While this is called onTouch, this should only be called from a ControlButton.
	public void onTouch(View v, MotionEvent ev) {
		ControlInterface lastControlButton = mapTable.get(v);

		// Map location to screen coordinates
		ev.offsetLocation(v.getX(), v.getY());


		//Check if the action is cancelling, reset the lastControl button associated to the view
		if (ev.getActionMasked() == MotionEvent.ACTION_UP
				|| ev.getActionMasked() == MotionEvent.ACTION_CANCEL
				|| ev.getActionMasked() == MotionEvent.ACTION_POINTER_UP) {
			if (lastControlButton != null) lastControlButton.sendKeyPresses(false);
			mapTable.put(v, null);
			return;
		}

		if (ev.getActionMasked() != MotionEvent.ACTION_MOVE) return;


		//Optimization pass to avoid looking at all children again
		if (lastControlButton != null) {
			System.out.println("last control button check" + ev.getX() + "-" + ev.getY() + "-" + lastControlButton.getControlView().getX() + "-" + lastControlButton.getControlView().getY());
			if (ev.getX() > lastControlButton.getControlView().getX()
					&& ev.getX() < lastControlButton.getControlView().getX() + lastControlButton.getControlView().getWidth()
					&& ev.getY() > lastControlButton.getControlView().getY()
					&& ev.getY() < lastControlButton.getControlView().getY() + lastControlButton.getControlView().getHeight()) {
				return;
			}
		}

		//Release last keys
		if (lastControlButton != null) lastControlButton.sendKeyPresses(false);
		mapTable.remove(v);

		// Update the state of all swipeable buttons
		for (ControlInterface button : getButtonChildren()) {
			if (!button.getProperties().isSwipeable || button.getControlView().getVisibility() != VISIBLE) continue;

			if (ev.getX() > button.getControlView().getX()
					&& ev.getX() < button.getControlView().getX() + button.getControlView().getWidth()
					&& ev.getY() > button.getControlView().getY()
					&& ev.getY() < button.getControlView().getY() + button.getControlView().getHeight()) {

				//Press the new key
				if (!button.equals(lastControlButton)) {
					button.sendKeyPresses(true);
					mapTable.put(v, button);
					return;
				}

			}
		}
	}

	@SuppressLint("ClickableViewAccessibility")
	@Override
	public boolean onTouchEvent(MotionEvent event) {
		if (mModifiable && event.getActionMasked() != MotionEvent.ACTION_UP || mControlDialog == null)
			return true;

		InputMethodManager imm = (InputMethodManager) getContext().getSystemService(INPUT_METHOD_SERVICE);

		// When the input window cannot be hidden, it returns false
		if(!imm.hideSoftInputFromWindow(getWindowToken(), 0)){
			if(mControlDialog.disappearLayer()){
				mActionRow.setFollowedButton(null);
				mHandleView.hide();
			}
		}
		return true;
	}

	/**
	 * 逐层关闭编辑界面：**颜色选择器 → 属性面板**。
	 *
	 * 返回 true 表示「这次返回键已经被我处理掉了」（关掉了某一层），调用方
	 * （{@code CustomControlsActivity.onBackPressed}）就不该再做别的 ——
	 * 尤其是不能弹「退出编辑器」确认框。
	 * 两层都没开时返回 false，返回键才会走到「是否退出编辑器」。
	 *
	 * 与 `onTouchEvent` 里那段逻辑的区别：那个只在**键盘没显示**时才关面板
	 * （「第一次收键盘、第二次收面板」的常见交互），而返回键是用户明确的
	 * 「我要退出去」意图，键盘在不在都该退一层。
	 */
	public boolean collapseEditLayers() {
		// **必须判「面板是否真的在显示」**：`mControlDialog` 一旦被创建就永远非 null
		// （按钮第一次被点开后就常驻了，只是 `disappear()` 而已）。只判 null 的话，
		// 第二次之后的返回键会被一直吃掉、`askToExit()` 永远轮不到 ——
		// 表现成「面板早关了，但怎么按返回键都退不出编辑器」。
		if (mControlDialog == null || !mControlDialog.isDisplaying()) return false;

		// 名称/尺寸是 EditText，键盘很可能还开着，一起收掉
		InputMethodManager imm = (InputMethodManager) getContext().getSystemService(INPUT_METHOD_SERVICE);
		imm.hideSoftInputFromWindow(getWindowToken(), 0);

		// disappearLayer() 内部自己判断：颜色选择器开着就只关它（返回 false），
		// 否则关属性面板（返回 true）
		if (mControlDialog.disappearLayer()) {
			if (mActionRow != null) mActionRow.setFollowedButton(null);
			if (mHandleView != null) mHandleView.hide();
		}
		return true;
	}

	public void removeEditWindow() {
		InputMethodManager imm = (InputMethodManager) getContext().getSystemService(INPUT_METHOD_SERVICE);
		imm.hideSoftInputFromWindow(getWindowToken(), 0);
		if(mControlDialog != null) { mControlDialog.disappearColor(); mControlDialog.disappear(true); }
		if(mActionRow != null) mActionRow.setFollowedButton(null);
		if(mHandleView != null) mHandleView.hide();
	}

	public void save(String path){
		try {
			mLayout.save(path);
			// 保存成功后清脏，否则退出时会反复提示「有未保存改动」
			setModified(false);
		} catch (IOException e) {Log.e("ControlLayout", "Failed to save the layout at:" + path);}
	}


	public boolean hasMenuButton() {
		for(ControlInterface controlInterface : getButtonChildren()){
			if (controlInterface.getControlView().getVisibility() != VISIBLE) continue;
			for (int keycode : controlInterface.getProperties().keycodes) if (keycode == ControlData.SPECIALBTN_MENU) return true;
		}
		return false;
	}

	public void setMenuListener(ControlButtonMenuListener menuListener) { this.mMenuListener = menuListener; }
	public void notifyAppMenu() { if(mMenuListener != null) mMenuListener.onClickedMenu(); }

	/** Cached getter for perf purposes */
	public MinecraftGLSurface getGameSurface(){
		if(mGameSurface == null){
			mGameSurface = findViewById(R.id.main_game_render_view);
		}
		return mGameSurface;
	}

	public void askToExit(EditorExitable editorExitable) {
		if(mIsModified) {
			openSaveDialog(editorExitable);
		}else{
			openExitDialog(editorExitable);
		}
	}

	public void updateLoadedFileName(String path) {
		path = path.replace(Tools.CTRLMAP_PATH, ".");
		path = path.substring(0, path.length() - 5);
		mLayoutFileName = path;
	}

	public String saveToDirectory(String name) throws Exception{
		String jsonPath = Tools.CTRLMAP_PATH + "/" + name + ".json";
		saveLayout(jsonPath);
		return jsonPath;
	}

	private final ControlEditorDialogs dialogs = new ControlEditorDialogs(this);

	public void openSaveDialog(EditorExitable editor) { dialogs.openSaveDialog(editor); }
	public void openLoadDialog() { dialogs.openLoadDialog(); }
	public void openSetDefaultDialog() { dialogs.openSetDefaultDialog(); }
	public void openExitDialog(EditorExitable editor) { dialogs.openExitDialog(editor); }
}
