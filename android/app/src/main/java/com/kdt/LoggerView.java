package com.kdt;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;

import com.zhayi.qookix.R;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * A class able to display logs to the user.
 *
 * 数据来源是 **Rust 写的启动日志文件**（`files/logs/launch-<实例>.log`），
 * 通过 {@link #setLogFile(File)} 指定 —— 不再用 `Logger.setLogListener`。
 *
 * 为什么要改：Java 和 Rust 在争抢 fd 1/2。
 *   · Java 先（`Logger.begin` → `stdio_is.c`）：`pipe()` + `dup2(pfd[1], 1/2)`，
 *     由那边的 logger_thread 读管道再回调 Java —— 这个回调原本就是本面板的数据源。
 *   · Rust 后（`launch.rs` → `android_env::redirect_output`）：把 fd 1/2
 *     `dup2` 到 `logs/launch-<id>.log`。
 * 后者顶掉了管道的写端，于是**再没人往管道里写**，logger_thread 的 `read()`
 * 永久阻塞 → 回调一次都不触发 → 这个面板永远空白。
 *
 * 现在只保留一条链路：fd 重定向完全交给 Rust，面板直接跟随那个文件。
 */
public class LoggerView extends ConstraintLayout {
    /** 轮询间隔：这是给人看的调试面板，500ms 足够，也不至于太耗电 */
    private static final long POLL_INTERVAL_MS = 500;
    /** 首次打开时最多回溯多少字节（游戏跑了很久以后日志可能有几 MB，别一次全塞进 TextView） */
    private static final long INITIAL_TAIL_BYTES = 32 * 1024;
    /** 面板最多保留的字符数：超了就丢掉前半段（原来这里留了个 TODO，长时间游戏会把内存吃满） */
    private static final int MAX_TEXT_CHARS = 200_000;

    private ToggleButton mLogToggle;
    private DefocusableScrollView mScrollView;
    private TextView mLogTextView;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private File mLogFile;
    /** 已经消费到文件第几个**字节**（不是字符：下面算偏移时必须用字节下标） */
    private long mOffset;
    private Runnable mPoller;


    public LoggerView(@NonNull Context context) {
        this(context, null);
    }

    public LoggerView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        // Triggers the log view shown state by default when viewing it
        mLogToggle.setChecked(visibility == VISIBLE);
    }

    /**
     * Inflate the layout, and add component behaviors
     */
    private void init(){
        inflate(getContext(), R.layout.view_logger, this);
        mLogTextView = findViewById(R.id.content_log_view);
        mLogTextView.setTypeface(Typeface.MONOSPACE);
        //TODO clamp the max text so it doesn't go oob
        mLogTextView.setMaxLines(Integer.MAX_VALUE);
        mLogTextView.setEllipsize(null);
        mLogTextView.setVisibility(GONE);

        // Toggle log visibility
        mLogToggle = findViewById(R.id.content_log_toggle_log);
        mLogToggle.setOnCheckedChangeListener(
                (compoundButton, isChecked) -> {
                    mLogTextView.setVisibility(isChecked ? VISIBLE : GONE);
                    if (isChecked) {
                        startPolling();
                    } else {
                        mLogTextView.setText("");
                        stopPolling();
                    }
                });
        mLogToggle.setChecked(false);

        // Remove the loggerView from the user View
        ImageButton cancelButton = findViewById(R.id.log_view_cancel);
        cancelButton.setOnClickListener(view -> LoggerView.this.setVisibility(GONE));

        // Set the scroll view
        mScrollView = findViewById(R.id.content_log_scroll);
        mScrollView.setKeepFocusing(true);

        //Set up the autoscroll switch
        ToggleButton autoscrollToggle = findViewById(R.id.content_log_toggle_autoscroll);
        autoscrollToggle.setOnCheckedChangeListener(
                (compoundButton, isChecked) -> {
                    if(isChecked) mScrollView.fullScroll(View.FOCUS_DOWN);
                    mScrollView.setKeepFocusing(isChecked);
                }
        );
        autoscrollToggle.setChecked(true);
    }

    /**
     * 指定要跟随的日志文件。
     *
     * 传进来的应该是 Rust 写的启动日志（`files/logs/launch-<实例>.log`）——
     * 原因见类注释里关于 fd 争抢的说明。
     */
    public void setLogFile(File file) {
        mLogFile = file;
        mOffset = 0;
    }

    private void startPolling() {
        if (mLogFile == null || mPoller != null) return;
        // 第一次打开时只回溯文件尾部一段，避免几 MB 的历史日志一次灌进 TextView
        if (mOffset == 0) {
            long len = mLogFile.length();
            mOffset = Math.max(0, len - INITIAL_TAIL_BYTES);
        }
        mPoller = new Runnable() {
            @Override
            public void run() {
                readNewLines();
                mHandler.postDelayed(this, POLL_INTERVAL_MS);
            }
        };
        mHandler.post(mPoller);
    }

    private void stopPolling() {
        if (mPoller != null) {
            mHandler.removeCallbacks(mPoller);
            mPoller = null;
        }
        // 下次打开重新从尾部读起；接着上次的位置会漏掉中间那段
        mOffset = 0;
    }

    /**
     * 从窗口树上摘下来时必须停掉轮询。
     *
     * 原先只有「用户手动关掉开关」才会走 stopPolling()。如果日志面板开着的时候直接销毁
     * Activity（退出游戏、旋转屏幕导致重建），mPoller 会一直挂在主线程 Handler 上每 500ms
     * 自我重投，并持有这个 View 及其 Context —— 等于**泄漏整个 Activity**，还持续读文件。
     */
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopPolling();
    }

    /** 读出自 mOffset 起的新增内容并按完整行追加。经 Handler 投递，运行在主线程。 */
    private void readNewLines() {
        if (mLogFile == null || !mLogFile.isFile()) return;
        try (RandomAccessFile raf = new RandomAccessFile(mLogFile, "r")) {
            long len = raf.length();
            if (len <= mOffset) return;
            raf.seek(mOffset);
            int want = (int) Math.min(len - mOffset, 64 * 1024);
            byte[] buf = new byte[want];
            int n = raf.read(buf);
            if (n <= 0) return;

            // 只消费到**最后一个换行**为止，剩下的半行等下一轮补齐。
            // 必须按字节找换行：mOffset 是字节偏移，用 String.length()（UTF-16 字符数）
            // 去回退会在中文日志上错位。'\n' 是 ASCII，UTF-8 的续字节都 >= 0x80，字节扫描安全。
            int lastNl = -1;
            for (int i = n - 1; i >= 0; i--) {
                if (buf[i] == '\n') {
                    lastNl = i;
                    break;
                }
            }
            if (lastNl < 0) return;
            mOffset += lastNl + 1;
            appendText(new String(buf, 0, lastNl, "UTF-8"));
        } catch (IOException ignored) {
            // 文件可能正在被写入或刚被截断，下一轮再看
        }
    }

    private void appendText(String text) {
        if (mLogTextView.getVisibility() != VISIBLE) return;
        mLogTextView.append(text + '\n');
        CharSequence all = mLogTextView.getText();
        if (all.length() > MAX_TEXT_CHARS) {
            // 丢掉前半段，别让面板无限长大
            mLogTextView.setText(all.subSequence(all.length() - MAX_TEXT_CHARS / 2, all.length()));
        }
        if (mScrollView.isKeepFocusing()) mScrollView.fullScroll(View.FOCUS_DOWN);
    }
}
