package net.kdt.pojavlaunch.customcontrols.gamepad;

/** 手柄上真实存在的按钮；标记为 toggleable 时按一下切换（潜行这类）。 */
public class GamepadButton extends GamepadEmulatedButton {
    public boolean isToggleable = false;
    private boolean mIsToggled = false;

    @Override
    protected void onDownStateChanged(boolean isDown) {
        if (isToggleable) {
            // 切换式：只认按下的那一下，抬起不动
            if (!isDown) return;
            mIsToggled = !mIsToggled;
            Gamepad.sendInput(keycodes, mIsToggled);
            return;
        }
        super.onDownStateChanged(isDown);
    }

    @Override
    public void resetButtonState() {
        if (!mIsDown && mIsToggled) {
            Gamepad.sendInput(keycodes, false);
            mIsToggled = false;
        } else {
            super.resetButtonState();
        }
    }
}
