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
import net.kdt.pojavlaunch.customcontrols.ControlDrawerData;
import net.kdt.pojavlaunch.customcontrols.ControlJoystickData;
import net.kdt.pojavlaunch.customcontrols.ControlLayout;
import net.kdt.pojavlaunch.customcontrols.ControlSceneDialog;
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

	private boolean mPreviewMode;
	private String mSaveAsName;
	private DrawerLayout mDrawerLayout;
	private ListView mDrawerNavigationView;
	private static final int[] MENU_ICONS = {
			R.drawable.ic_qk_plus,     // 添加按键
			R.drawable.ic_qk_plus,     // 添加组合键
			R.drawable.ic_qk_plus,     // 添加摇杆
			R.drawable.ic_qk_plus,     // 添加滚轮按键
			R.drawable.ic_qk_folder,   // 加载
			R.drawable.ic_qk_save,     // 保存
			R.drawable.ic_qk_star,     // 选择默认控制布局
			R.drawable.ic_qk_share,    // 导出
			R.drawable.ic_qk_sliders,  // 控件场景
	};
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

				// 预览模式：只读 + 菜单换成「就用这个 / 取消」；否则是可编辑的完整编辑器。
				mControlLayout.setModifiable(!mPreviewMode);
				mControlLayout.setReadOnlyPreview(mPreviewMode);
				mDrawerNavigationView.setAdapter(new com.zhayi.qookix.control.QookixMenuAdapter(this,
						com.zhayi.qookix.control.QookixMenuAdapter.entriesOf(this,
								mPreviewMode ? R.array.menu_customcontrol_preview : R.array.menu_customcontrol_customactivity,
								mPreviewMode ? PREVIEW_MENU_ICONS : MENU_ICONS)));
				mDrawerNavigationView.setOnItemClickListener((parent, view, position, id) -> {
					if (mPreviewMode) {
									// 0 = 就用这个（把临时文件另存为正式布局），1 = 取消
									if (position == 0) confirmImportedLayout();
									else { discardTempLayout(); finish(); }
									return;
								}
					switch(position) {
						case 0: mControlLayout.addControlButton(new ControlData("New")); break;
						case 1: mControlLayout.addDrawer(new ControlDrawerData()); break;
						case 2: mControlLayout.addJoystickButton(new ControlJoystickData()); break;
						case 3: MouseWheelControls.showAddDialog(mControlLayout); break;
						case 4: mControlLayout.openLoadDialog(); break;
						case 5: mControlLayout.openSaveDialog(this); break;
						case 6: mControlLayout.openSetDefaultDialog(); break;
						case 8: ControlSceneDialog.show(mControlLayout); break;
						case 7: // Saving the currently shown control
					try {
						// QookiX 没有 Pojav 的 scoped.FolderProvider（那个 DocumentsProvider 依赖
			//  @string/storageProviderAuthorities，由 Gradle resValue 生成）。这里改用本项目
			//  已有的 FileProvider（authorities = <applicationId>.fileprovider，
			//  路径映射见 res/xml/file_paths.xml 的 controlmap）。
					String jsonPath = mControlLayout.saveToDirectory(mControlLayout.mLayoutFileName);
					Uri contentUri = androidx.core.content.FileProvider.getUriForFile(
							this, getPackageName() + ".fileprovider", new java.io.File(jsonPath));

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
		// 旧通路（`enableOnBackInvokedCallback` 下不会被调用），保留一份兜底，
		// 逻辑与上面注册的 dispatcher 回调保持一致。详见那里和 GameActivity 的注释。
		if (mControlLayout != null && mControlLayout.collapseEditLayers()) return;
		mControlLayout.askToExit(this);
	}

	@Override
	public void exitEditor() {
		// **不能** `super.onBackPressed()`：那会绕回上面注册的 dispatcher 回调，
		// 又走一次 `askToExit()`（重复弹框，甚至死循环）。
		// 这里就是「保存 / 退出」确认之后要真正离开编辑器。
		finish();
	}
}
