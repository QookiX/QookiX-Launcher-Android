package net.kdt.pojavlaunch.customcontrols.gamepad;

import android.view.KeyEvent;

/**
 * 手柄上并不存在、由其他输入模拟出来的按钮（例如左摇杆量化出的 WASD 方向键）。
 *
 * {@link #keycodes} 最多 4 个键码，按下/抬起时一次性发给游戏。
 */
public class GamepadEmulatedButton {
    public short[] keycodes;
    protected boolean mIsDown = false;

    public void update(KeyEvent event) {
        boolean isKeyDown = (event.getAction() == KeyEvent.ACTION_DOWN);
        update(isKeyDown);
    }

    public void update(boolean isKeyDown) {
        if (isKeyDown != mIsDown) {
            mIsDown = isKeyDown;
            onDownStateChanged(mIsDown);
        }
    }

    /** 复位（例如松开抓取时），必要时补一个抬起事件，避免游戏里按键卡住。 */
    public void resetButtonState() {
        if (mIsDown) Gamepad.sendInput(keycodes, false);
        mIsDown = false;
    }

    protected void onDownStateChanged(boolean isDown) {
        Gamepad.sendInput(keycodes, mIsDown);
    }
}
