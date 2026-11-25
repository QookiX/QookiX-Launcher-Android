package net.kdt.pojavlaunch.customcontrols;

import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.kdt.pickafile.FileListView;
import com.kdt.pickafile.FileSelectedListener;
import com.zhayi.qookix.R;
import java.io.File;
import java.io.IOException;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

final class ControlEditorDialogs {
	private final ControlLayout layout;

	ControlEditorDialogs(ControlLayout layout) {
		this.layout = layout;
	}

	void confirmUnsaved(Runnable proceed) {
		new AlertDialog.Builder(layout.getContext(), R.style.QookixAlertDialog)
				.setTitle(R.string.file_editor_unsaved)
				.setPositiveButton(R.string.qk_control_save, (dialog, button) -> openSaveDialog(proceed))
				.setNeutralButton(R.string.file_editor_modified, (dialog, button) -> proceed.run())
				.setNegativeButton(R.string.dialog_cancel, null)
				.show();
	}

	void openSaveDialog(Runnable afterSave) {
		AlertDialog.Builder builder = new AlertDialog.Builder(layout.getContext(), R.style.QookixAlertDialog);
		View form = LayoutInflater.from(builder.getContext()).inflate(R.layout.dialog_control_layout_name, layout, false);
		TextInputLayout field = form.findViewById(R.id.control_layout_name_field);
		TextInputEditText edit = form.findViewById(R.id.control_layout_name);
		String layoutName = layout.mLayoutFileName;
		edit.setText(layoutName != null && layoutName.startsWith("./") ? layoutName.substring(2) : layoutName);
		builder.setTitle(R.string.qk_control_save_title).setView(form)
				.setPositiveButton(R.string.qk_control_save, null).setNegativeButton(R.string.dialog_cancel, null);
		final AlertDialog dialog = builder.create();
		dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
				.setOnClickListener(v -> saveLayoutFromDialog(dialog, field, edit, afterSave)));
		dialog.show();
	}

	private void saveLayoutFromDialog(AlertDialog dialog, TextInputLayout field, TextInputEditText edit, Runnable afterSave) {
		String name = edit.getText() == null ? "" : edit.getText().toString().trim();
		if (name.isBlank()) {
			field.setError(layout.getContext().getString(R.string.qk_control_name_required));
			return;
		}
		String currentName = layout.mLayoutFileName;
		if (currentName != null && currentName.startsWith("./")) currentName = currentName.substring(2);
		File destination = new File(Tools.CTRLMAP_PATH, name + ".json");
		if (name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..")
				|| name.regionMatches(true, 0, "TMP_IMPORT", 0, "TMP_IMPORT".length())
				|| (destination.exists() && !name.equals(currentName))) {
			field.setError(layout.getContext().getString(R.string.import_control_invalid_name));
			return;
		}
		try {
			layout.updateLoadedFileName(layout.saveToDirectory(name));
			Toast.makeText(layout.getContext(), layout.getContext().getString(R.string.qk_control_saved, name), Toast.LENGTH_SHORT).show();
			dialog.dismiss();
			if (afterSave != null) afterSave.run();
		} catch (Exception error) {
			Tools.showError(layout.getContext(), error);
		}
	}

	void openLoadDialog() {
		AlertDialog.Builder builder = new AlertDialog.Builder(layout.getContext(), R.style.QookixAlertDialog);
		builder.setTitle(R.string.qk_control_load).setNegativeButton(R.string.dialog_cancel, null);

		final AlertDialog dialog = builder.create();
		FileListView flv = new FileListView(dialog, "json");
		flv.setDialogTitleListener(null);
		// 不列导入中转文件：它们不是玩家的布局，App 内的布局列表也看不到它们，
		// 在这里露出来只会让用户以为「多了一堆删不掉的文件」。
		flv.skipFilePrefixes("TMP_IMPORT");
		if(Build.VERSION.SDK_INT < 29)flv.listFileAt(new File(Tools.CTRLMAP_PATH));
		else flv.lockPathAt(new File(Tools.CTRLMAP_PATH));
		flv.setFileSelectedListener(new FileSelectedListener(){

			@Override
			public void onFileSelected(File file, String path) {
				dialog.dismiss();
				layout.beforeReplacingLayout(() -> loadSelected(path, false));
			}
		});
		dialog.setView(flv);
		dialog.show();
	}

	void openSetDefaultDialog() {
		AlertDialog.Builder builder = new AlertDialog.Builder(layout.getContext(), R.style.QookixAlertDialog);
		builder.setTitle(R.string.qk_control_select_default).setNegativeButton(R.string.dialog_cancel, null);

		final AlertDialog dialog = builder.create();
		FileListView flv = new FileListView(dialog, "json");
		flv.setDialogTitleListener(null);
		flv.skipFilePrefixes("TMP_IMPORT");
		flv.lockPathAt(new File(Tools.CTRLMAP_PATH));
		flv.setFileSelectedListener(new FileSelectedListener(){

			@Override
			public void onFileSelected(File file, String path) {
				dialog.dismiss();
				layout.beforeReplacingLayout(() -> loadSelected(path, true));
			}
		});
		dialog.setView(flv);
		dialog.show();
	}

	private void loadSelected(String path, boolean makeDefault) {
		try {
			layout.loadLayout(path);
			if (makeDefault) {
				LauncherPreferences.DEFAULT_PREF.edit().putString("defaultCtrl", path).apply();
				LauncherPreferences.PREF_DEFAULTCTRL_PATH = path;
			}
		} catch (IOException | RuntimeException error) {
			Tools.showError(layout.getContext(), error);
		}
	}

	void openExitDialog(EditorExitable exitListener) {
		new AlertDialog.Builder(layout.getContext(), R.style.QookixAlertDialog)
				.setTitle(R.string.qk_control_exit_editor)
				.setMessage(R.string.qk_control_exit_confirm)
				.setPositiveButton(R.string.qk_control_exit, (dialog, button) -> exitListener.exitEditor())
				.setNegativeButton(R.string.dialog_cancel, null)
				.show();
	}

}
