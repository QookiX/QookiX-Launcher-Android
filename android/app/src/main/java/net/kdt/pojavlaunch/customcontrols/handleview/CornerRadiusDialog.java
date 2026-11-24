package net.kdt.pojavlaunch.customcontrols.handleview;

import android.content.Context;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.zhayi.qookix.R;

import net.kdt.pojavlaunch.customcontrols.ControlData;
import net.kdt.pojavlaunch.customcontrols.buttons.ControlInterface;

/**
 * 逐角圆角（ZL2 的 `cornerPercent[4]`）：四个角各自 0–100%。
 *
 * 导入的布局如果四角不一致，这里能保留并单独调；改完把平均值写回
 * `cornerRadius`，这样统一圆角 seekbar 和未识别旧字段的地方仍有近似值。
 */
final class CornerRadiusDialog {
    private CornerRadiusDialog() {}

    private static final String[] LABELS = {"左上", "右上", "右下", "左下"};

    static void show(Context context, ControlInterface button, Runnable onChanged) {
        ControlData data = button.getProperties();
        if (data.appearance == null) data.appearance = new ControlData.Appearance();
        ControlData.Appearance appearance = data.appearance;
        if (appearance.normal == null) appearance.normal = new ControlData.PaintStyle();
        ControlData.PaintStyle normal = appearance.normal;

        float[] corners = normal.cornerPercent;
        if (corners == null || corners.length != 4) {
            float base = data.cornerRadius;
            corners = new float[]{base, base, base, base};
        }
        final float[] working = corners.clone();

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(context.getResources().getDisplayMetrics().density * 12);
        column.setPadding(padding, padding, padding, padding);

        for (int i = 0; i < 4; i++) {
            final int index = i;
            TextView label = new TextView(context);
            label.setText(LABELS[i] + "  " + Math.round(working[i]) + "%");
            SeekBar bar = new SeekBar(context);
            bar.setMax(100);
            bar.setProgress(Math.round(working[i]));
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    working[index] = progress;
                    label.setText(LABELS[index] + "  " + progress + "%");
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {
                    normal.cornerPercent = working.clone();
                    data.cornerRadius = (working[0] + working[1] + working[2] + working[3]) / 4f;
                    button.updateProperties();
                    onChanged.run();
                }
            });
            column.addView(label);
            column.addView(bar);
        }

        new AlertDialog.Builder(context, R.style.QookixAlertDialog)
                .setTitle(R.string.customctrl_corner_radius_sides)
                .setView(column)
                .setPositiveButton(R.string.qk_dialog_done, null)
                .show();
    }
}
