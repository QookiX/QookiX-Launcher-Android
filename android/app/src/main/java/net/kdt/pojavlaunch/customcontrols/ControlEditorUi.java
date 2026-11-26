package net.kdt.pojavlaunch.customcontrols;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;

import com.zhayi.qookix.R;

/** QookiX 暗色圆角卡片 + 暖金强调色弹窗组件。 */
final class ControlEditorUi {
    private ControlEditorUi() {}

    static int dp(Context context, int value) {
        return Math.round(context.getResources().getDisplayMetrics().density * value);
    }

    static ScrollView wrapScroll(Context context, View content) {
        ScrollView scroll = new ScrollView(context);
        scroll.addView(content);
        return scroll;
    }

    static LinearLayout dialogRoot(Context context) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8));
        return column;
    }

    static TextView sectionLabel(Context context, int textRes) {
        TextView label = new TextView(context);
        label.setText(textRes);
        label.setTextSize(12f);
        label.setTextColor(ContextCompat.getColor(context, R.color.qk_text_3));
        label.setPadding(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 6));
        return label;
    }

    static LinearLayout card(Context context) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1E2331);
        bg.setCornerRadius(dp(context, 12));
        bg.setStroke(dp(context, 1), ContextCompat.getColor(context, R.color.qk_border));
        box.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(context, 8);
        box.setLayoutParams(lp);
        return box;
    }

    static SwitchCompat switchRow(Context context, LinearLayout parent, int titleRes,
                                  boolean checked, CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = card(context);
        TextView title = new TextView(context);
        title.setText(titleRes);
        title.setTextSize(14f);
        title.setTextColor(ContextCompat.getColor(context, R.color.qk_text_1));
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        SwitchCompat sw = new SwitchCompat(context);
        sw.setChecked(checked);
        int accent = ContextCompat.getColor(context, R.color.qk_accent);
        sw.setThumbTintList(new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{accent, 0xFFB0B4C3}));
        sw.setTrackTintList(new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{0x55E5A955, 0x33FFFFFF}));
        sw.setOnCheckedChangeListener(listener);

        row.addView(title);
        row.addView(sw);
        row.setOnClickListener(v -> {
            if (sw.isEnabled()) sw.toggle();
        });
        parent.addView(row);
        return sw;
    }

    static TextView pillButton(Context context, String text, boolean accent, View.OnClickListener listener) {
        TextView btn = new TextView(context);
        btn.setText(text);
        btn.setTextSize(12f);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(context, 10), dp(context, 6), dp(context, 10), dp(context, 6));
        stylePill(context, btn, accent);
        btn.setOnClickListener(listener);
        return btn;
    }

    static void stylePill(Context context, TextView btn, boolean accent) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(context, 18));
        if (accent) {
            bg.setColor(0xFF2A241A);
            bg.setStroke(dp(context, 1), 0x66F2B866);
            btn.setTextColor(ContextCompat.getColor(context, R.color.qk_accent_light));
        } else {
            bg.setColor(0xFF232838);
            bg.setStroke(dp(context, 1), ContextCompat.getColor(context, R.color.qk_border));
            btn.setTextColor(ContextCompat.getColor(context, R.color.qk_text_2));
        }
        btn.setBackground(bg);
    }

    interface IntConsumer {
        void accept(int index);
    }

    static final class SegmentedBar {
        final LinearLayout view;
        private final TextView[] tabs;
        private final Context context;

        private SegmentedBar(Context context, LinearLayout view, TextView[] tabs) {
            this.context = context;
            this.view = view;
            this.tabs = tabs;
        }

        void select(int index) {
            int accent = ContextCompat.getColor(context, R.color.qk_accent);
            int textMuted = ContextCompat.getColor(context, R.color.qk_text_2);
            for (int i = 0; i < tabs.length; i++) {
                boolean active = i == index;
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(dp(context, 8));
                bg.setColor(active ? accent : Color.TRANSPARENT);
                tabs[i].setBackground(bg);
                tabs[i].setTextColor(active ? 0xFF14151D : textMuted);
                tabs[i].setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
            }
        }
    }

    static SegmentedBar segmentedBar(Context context, int[] labelRes, int selectedIndex, IntConsumer onSelect) {
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setPadding(dp(context, 3), dp(context, 3), dp(context, 3), dp(context, 3));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF151924);
        bg.setCornerRadius(dp(context, 10));
        bg.setStroke(dp(context, 1), ContextCompat.getColor(context, R.color.qk_border));
        container.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(context, 8);
        container.setLayoutParams(lp);

        TextView[] tabs = new TextView[labelRes.length];
        SegmentedBar bar = new SegmentedBar(context, container, tabs);
        for (int i = 0; i < labelRes.length; i++) {
            final int idx = i;
            TextView tab = new TextView(context);
            tab.setText(labelRes[i]);
            tab.setTextSize(13f);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(dp(context, 6), dp(context, 7), dp(context, 6), dp(context, 7));
            tab.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            tab.setOnClickListener(v -> {
                bar.select(idx);
                onSelect.accept(idx);
            });
            tabs[i] = tab;
            container.addView(tab);
        }
        bar.select(Math.max(0, Math.min(selectedIndex, labelRes.length - 1)));
        return bar;
    }
}
