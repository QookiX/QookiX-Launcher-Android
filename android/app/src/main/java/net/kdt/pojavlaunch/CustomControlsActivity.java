package net.kdt.pojavlaunch;

import com.zhayi.qookix.R;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import androidx.drawerlayout.widget.DrawerLayout;
import androidx.activity.OnBackPressedCallback;

import net.kdt.pojavlaunch.customcontrols.ControlData;
import net.kdt.pojavlaunch.customcontrols.ControlGroupDialog;
import net.kdt.pojavlaunch.customcontrols.ControlDrawerData;
import net.kdt.pojavlaunch.customcontrols.ControlJoystickData;
import net.kdt.pojavlaunch.customcontrols.ControlLayout;
import net.kdt.pojavlaunch.customcontrols.ControlSceneDialog;
import net.kdt.pojavlaunch.customcontrols.ControlStyleDialog;
import net.kdt.pojavlaunch.customcontrols.EditorExitable;
import net.kdt.pojavlaunch.customcontrols.MouseWheelControls;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import java.io.IOException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class CustomControlsActivity extends androidx.appcompat.app.AppCompatActivity implements EditorExitable {

	// ── 供启动器调用的 intent 参数（启动器 → 编辑器）──────────────────────────
	/** 要编辑/预览的布局名（在 {@code Tools.CTRLMAP_PATH} 下，不带 .json）。 */
	public static final String EXTRA_LAYOUT = "layout";
	/** 只读预览模式（导入布局时先给用户看一眼），菜单变成「就用这个 / 取消」。 */
	public static final String EXTRA_PREVIEW = "preview";
	/** 预览确认后要另存成的布局名（仅预览模式用）。 */
	public static final String EXTRA_SAVE_AS = "saveAs";
	public static final String EXTRA_IMPORT_RESULT = "controlImportResult";

	private boolean mPreviewMode;
	private String mSaveAsName;
	private DrawerLayout mDrawerLayout;
	private ListView mDrawerNavigationView;
	private static final int ACTION_ADD = 1;
	private static final int ACTION_SCENE = 2;
	private static final int ACTION_GROUPS = 3;
	private static final int ACTION_STYLES = 4;
	private static final int ACTION_SAVE = 5;
	private static final int ACTION_LOAD = 6;
	private static final int ACTION_DEFAULT = 7;
	private static final int ACTION_EXPORT = 8;
	private static final int ACTION_EXIT = 9;

	private java.util.List<com.zhayi.qookix.control.MenuEntry> buildEditorMenu() {
		java.util.List<com.zhayi.qookix.control.MenuEntry> items = new java.util.ArrayList<>();
		items.add(new com.zhayi.qookix.control.MenuEntry(getString(R.string.qk_menu_section_edit), 0, true, -1));
		items.add(new com.zhayi.qookix.control.MenuEntry(getString(R.string.qk_control_add_widget), R.drawable.ic_qk_plus, false, ACTION_ADD));
		items.add(new com.zhayi.qookix.control.MenuEntry(getString(R.string.qk_control_scene), R.drawable.ic_qk_sliders, false, ACTION_SCENE));
		items.add(new com.zhayi.qookix.control.MenuEntry(getString(R.string.qk_control_groups), R.drawable.ic_qk_gamepad, false, ACTION_GROUPS));
		items.add(new com.zhayi.qookix.control.MenuEntry(getString(R.string.qk_control_styles), R.drawable.ic_qk_star, false, ACTION_STYLES));
		items.add(new com.zhayi.qookix.control.MenuEntry(getString(R.string.qk_menu_section_layout), 0, true, -1));
		items.add(new com.zhayi.qookix.control.MenuEntry(getString(R.string.qk_control_save), R.drawable.ic_qk_save, false, ACTION_SAVE));
		items.add(new com.zhayi.qookix.control.MenuEntry(getString(R.string.qk_control_load), R.drawable.ic_qk_folder, false, ACTION_LOAD));
		items.add(new com.zhayi.qookix.control.MenuEntry(getString(R.string.qk_control_select_default), R.drawable.ic_qk_star, false, ACTION_DEFAULT));
		items.add(new com.zhayi.qookix.control.MenuEntry(getString(R.string.qk_control_export), R.drawable.ic_qk_share, false, ACTION_EXPORT));
		items.add(new com.zhayi.qookix.control.MenuEntry(getString(R.string.qk_control_exit_editor), R.drawable.ic_qk_close, false, ACTION_EXIT));
		return items;
	}

	/** 预览模式菜单：0 = 就用这个，1 = 取消。 */
	private static final int[] PREVIEW_MENU_ICONS = {
			R.drawable.ic_qk_save,
			R.drawable.ic_qk_folder,
	};
	private ControlLayout mControlLayout;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// **必须先初始化 Pojav 的静态状态**。
		// 这个 Activity 以前只能从游戏里进，`Tools.initStorageConstants` 与
		// `LauncherPreferences.loadPreferences` 早就被 GameActivity 做过了；
		// 现在启动器能直接拉起它，而它自己从没做过初始化 ——
		// 于是 `PREF_DEFAULTCTRL_PATH` 是 null，`loadLayout` 直接 NPE 崩掉。
		// 写法与 GameActivity.onCreate 保持一致。
		LauncherPreferences.DEFAULT_PREF = getSharedPreferences("launcher_preferences", MODE_PRIVATE);
		LauncherPreferences.loadPreferences(this);

		// **必须在加载布局之前**把屏幕尺寸写进 CallbackBridge。
		// 按钮位置是用 ${screen_width}/${bottom}/${right}… 这些表达式算出来的，
		// 而 physicalWidth/Height 平时是游戏（GameActivity）设的 —— 从启动器直接进时
		// 它们是 0，于是 bottom/right 算出负数、按钮全被摆到屏幕外，
		// 表现就是「编辑器一片空白，什么都没有」（见 Tools.updateWindowSize 的注释）。
		Tools.updateWindowSize(this);

		setContentView(R.layout.activity_custom_controls);

		mControlLayout = findViewById(R.id.customctrl_controllayout);
		mDrawerLayout = findViewById(R.id.customctrl_drawerlayout);
		mDrawerNavigationView = findViewById(R.id.customctrl_navigation_view);
		View mPullDrawerButton = findViewById(R.id.drawer_button);
		android.widget.TextView mappingHint = findViewById(R.id.control_mapping_hint);
		if (mappingHint != null) {
			mControlLayout.setEditorPreviewListener(() -> mappingHint.setText(
					mControlLayout.isEditorPreview() ? R.string.qk_control_preview_hint : R.string.qk_control_mapping_hint));
		}

		// **返回键必须走 `OnBackPressedDispatcher`**。
		//
		// manifest 里 `android:enableOnBackInvokedCallback="true"`（Android 13+ 预测式返回），
		// 这时框架**不再调用** `onBackPressed()` —— 本 Activity 之前只有那个旧通路，
		// 于是按返回键直接 finish 掉整个编辑器，连 `askToExit()`（保存 / 退出确认）
		// 都从来没执行过。MainActivity / GameActivity / WryActivity 早就都注册了
		// dispatcher 回调，这里是最后一个漏掉的。
		getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
			@Override
			public void handleOnBackPressed() {
				// 逐层退出：颜色选择器 → 属性面板 → 才问「要不要退出编辑器」
				if (mControlLayout != null && mControlLayout.collapseEditLayers()) return;
				mControlLayout.askToExit(CustomControlsActivity.this);
			}
		});

		mPullDrawerButton.setOnClickListener(v -> mDrawerLayout.openDrawer(mDrawerNavigationView));
				mDrawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);

				Intent extra = getIntent();
				mPreviewMode = extra != null && extra.getBooleanExtra(EXTRA_PREVIEW, false);
				mSaveAsName = extra == null ? null : extra.getStringExtra(EXTRA_SAVE_AS);

				// 预览模式：只读 + 菜单换成「就用这个 / 取消」；否则是分组后的编辑器菜单。
				mControlLayout.setModifiable(!mPreviewMode);
				mControlLayout.setReadOnlyPreview(mPreviewMode);
				final java.util.List<com.zhayi.qookix.control.MenuEntry> editorEntries = mPreviewMode ? null : buildEditorMenu();
				mDrawerNavigationView.setAdapter(new com.zhayi.qookix.control.QookixMenuAdapter(this,
						mPreviewMode
								? com.zhayi.qookix.control.QookixMenuAdapter.entriesOf(this, R.array.menu_customcontrol_preview, PREVIEW_MENU_ICONS)
								: editorEntries));
				mDrawerNavigationView.setOnItemClickListener((parent, view, position, id) -> {
					if (mPreviewMode) {
						// 0 = 就用这个（把临时文件另存为正式布局），1 = 取消
						if (position == 0) confirmImportedLayout();
						else { discardTempLayout(); finish(); }
						return;
					}
					com.zhayi.qookix.control.MenuEntry item = editorEntries.get(position);
					if (item.isHeader()) return;
					switch (item.getId()) {
						case ACTION_ADD: showAddWidgetDialog(); break;
						case ACTION_SCENE: ControlSceneDialog.show(mControlLayout); break;
						case ACTION_GROUPS: ControlGroupDialog.show(mControlLayout); break;
						case ACTION_STYLES: ControlStyleDialog.show(mControlLayout); break;
						case ACTION_SAVE: mControlLayout.openSaveDialog(); break;
						case ACTION_LOAD: mControlLayout.openLoadDialog(); break;
						case ACTION_DEFAULT: mControlLayout.openSetDefaultDialog(); break;
						case ACTION_EXIT: mControlLayout.askToExit(CustomControlsActivity.this); break;
						case ACTION_EXPORT:
					try {
						// QookiX 没有 Pojav 的 scoped.FolderProvider，改用本项目已有的 FileProvider
			//  （authorities = <applicationId>.fileprovider，路径见 res/xml/file_paths.xml）。
					// Share a snapshot of the draft, without saving over the source or marking it clean.
					File snapshot = File.createTempFile("control-layout-", ".json", getCacheDir());
					mControlLayout.getLayout().save(snapshot.getAbsolutePath());
					Uri contentUri = androidx.core.content.FileProvider.getUriForFile(
							this, getPackageName() + ".fileprovider", snapshot);

						Intent shareIntent = new Intent();
						shareIntent.setAction(Intent.ACTION_SEND);
						shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
						shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
						shareIntent.setType("application/json");

						Intent sendIntent = Intent.createChooser(shareIntent, mControlLayout.mLayoutFileName);
						startActivity(sendIntent);
					}catch (Exception e) {
						Tools.showError(this, e);
					}
					break;
			}
			mDrawerLayout.closeDrawers();
		});
		try {
			// 启动器可以指定「编辑哪一份」；不指定就沿用游戏当前默认的那份。
			String want = extra == null ? null : extra.getStringExtra(EXTRA_LAYOUT);
			String path = (want == null || want.isBlank())
					? LauncherPreferences.PREF_DEFAULTCTRL_PATH
					: Tools.CTRLMAP_PATH + "/" + want + ".json";
			// 兜底：PREF_DEFAULTCTRL_PATH 理论上初始化后不会为空，但它是静态字段，
			// 万一为空就用默认布局文件（崩在这里的话整个 App 直接闪退，损失很大）
			if (path == null || path.trim().isEmpty()) path = Tools.CTRLDEF_FILE;
			// 内置那份布局原先只在**游戏启动**时才落盘。用户从没进过游戏（新装机 / 清过数据）
			// 却从启动器点「编辑布局」时，这里拿到的是个不存在的路径 →
			// `loadLayout` 抛 NoSuchFileException，界面上就是「弹报错 + 编辑器一片黑」
			// （2026-10-09 反馈）。先确保内置布局在，再兜「指定那份不在了」。
			com.zhayi.qookix.ControlLayoutSeed.ensure(this);
			if (!new File(path).isFile() && Tools.CTRLDEF_FILE != null) {
				android.util.Log.w("CustomControls", "要编辑的布局不存在，改用内置默认布局：" + path);
				path = Tools.CTRLDEF_FILE;
				android.widget.Toast.makeText(this, "这份布局不在了，已改为编辑内置默认布局",
						android.widget.Toast.LENGTH_LONG).show();
			}
			mControlLayout.loadLayout(path);

		}catch (Throwable e) {
			// 故意捕获 Throwable：布局文件损坏 / 字段缺失都可能抛运行时异常，
			// 这里崩掉等于用户只是点了个「编辑布局」就把 App 弄没了。
			Tools.showError(this, e);
		}
	}

	/**
	 * 预览确认：把临时布局另存为正式布局（名字来自启动器）。
	 *
	 * 为什么不直接改当前默认：用户导入的是别人分享的布局，得先起个名字、
	 * 而且不该在他还没确认时就把游戏正在用的那份换掉。
	 */
	private void confirmImportedLayout() {
		String importResult = getIntent().getStringExtra(EXTRA_IMPORT_RESULT);
		if (importResult != null) {
			setResult(RESULT_OK, new Intent().putExtra(EXTRA_IMPORT_RESULT, importResult));
			finish();
			return;
		}
		if (mSaveAsName == null || mSaveAsName.isBlank()) {
			Tools.showError(this, new IOException("缺少要保存的布局名"));
			finish();
			return;
		}
		try {
			File src = new File(Tools.CTRLMAP_PATH + "/" + mControlLayout.mLayoutFileName + ".json");
			File dst = new File(Tools.CTRLMAP_PATH + "/" + mSaveAsName + ".json");
			copyFile(src, dst);
			// 源是临时文件（TMP_IMPORT 前缀），用完删掉，别留在目录里
			if (src.getName().startsWith("TMP_IMPORT")) src.delete();
			setResult(RESULT_OK);
		} catch (IOException e) {
			Tools.showError(this, e);
			setResult(RESULT_CANCELED);
		}
		finish();
	}

	/** 用户放弃导入：清掉临时文件。 */
	private void discardTempLayout() {
		try {
			File src = new File(Tools.CTRLMAP_PATH + "/" + mControlLayout.mLayoutFileName + ".json");
			if (src.getName().startsWith("TMP_IMPORT")) src.delete();
		} catch (Throwable ignored) {
		}
	}

	private static void copyFile(File src, File dst) throws IOException {
		try (InputStream in = new java.io.FileInputStream(src);
		     OutputStream out = new FileOutputStream(dst)) {
			byte[] buf = new byte[8192];
			int n;
			while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
		}
	}

	@Override
	public void onBackPressed() {
		// 旧通路兜底（`enableOnBackInvokedCallback` 下不调用），逻辑与上面的 dispatcher 一致。
		if (mControlLayout != null && mControlLayout.collapseEditLayers()) return;
		mControlLayout.askToExit(this);
	}

	@Override
	public void exitEditor() {
		// **不能** `super.onBackPressed()`：会绕回 dispatcher 回调再走一次 `askToExit()`（重复弹框/死循环）。
		// 这里就是「保存 / 退出」确认之后要真正离开编辑器。
		finish();
	}

	private void showAddWidgetDialog() {
		float d = getResources().getDisplayMetrics().density;
		int pad = Math.round(d * 14);
		android.widget.LinearLayout root = new android.widget.LinearLayout(this);
		root.setOrientation(android.widget.LinearLayout.VERTICAL);
		root.setPadding(pad, Math.round(d * 8), pad, Math.round(d * 10));

		final androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(this, R.style.QookixAlertDialog)
				.setTitle(R.string.qk_control_add_widget)
				.setView(root)
				.setNegativeButton(R.string.dialog_cancel, null)
				.create();

		int[] labels = {
				R.string.qk_control_add_button,
				R.string.qk_control_add_drawer,
				R.string.qk_control_add_joystick,
				R.string.qk_control_add_direction_pad,
				R.string.customctrl_add_wheel,
				R.string.qk_control_add_text_label,
		};
		Runnable[] actions = {
				() -> mControlLayout.addControlButton(new ControlData("New")),
				() -> mControlLayout.addDrawer(new ControlDrawerData()),
				() -> mControlLayout.addJoystickButton(new ControlJoystickData()),
				() -> mControlLayout.addDirectionPad(),
				() -> MouseWheelControls.showAddDialog(mControlLayout),
				() -> mControlLayout.addTextLabel(),
		};

		for (int i = 0; i < labels.length; i += 2) {
			android.widget.LinearLayout row = new android.widget.LinearLayout(this);
			row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
			android.widget.LinearLayout.LayoutParams rlp = new android.widget.LinearLayout.LayoutParams(
					android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
			rlp.bottomMargin = Math.round(d * 8);
			row.setLayoutParams(rlp);
			for (int j = 0; j < 2 && i + j < labels.length; j++) {
				final Runnable act = actions[i + j];
				android.widget.TextView card = new android.widget.TextView(this);
				card.setText(labels[i + j]);
				card.setTextSize(13f);
				card.setGravity(android.view.Gravity.CENTER);
				card.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.qk_text_1));
				card.setPadding(Math.round(d * 10), Math.round(d * 14), Math.round(d * 10), Math.round(d * 14));
				android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
				bg.setColor(0xFF1E2331);
				bg.setCornerRadius(Math.round(d * 12));
				bg.setStroke(Math.round(d * 1), androidx.core.content.ContextCompat.getColor(this, R.color.qk_border));
				card.setBackground(bg);
				card.setOnClickListener(v -> {
					dialog.dismiss();
					act.run();
				});
				android.widget.LinearLayout.LayoutParams clp = new android.widget.LinearLayout.LayoutParams(
						0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
				if (j == 0) clp.rightMargin = Math.round(d * 8);
				row.addView(card, clp);
			}
			root.addView(row);
		}
		dialog.show();
	}

	@Override
	public void onConfigurationChanged(android.content.res.Configuration newConfig) {
		super.onConfigurationChanged(newConfig);
		if (mControlLayout != null) mControlLayout.onWindowSizeChanged();
	}
}
