package net.kdt.pojavlaunch.customcontrols.mouse;

import android.os.Handler;
import android.os.Looper;

import android.view.MotionEvent;

import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.LwjglGlfwKeycode;

import org.lwjgl.glfw.CallbackBridge;

public class InGameEventProcessor implements TouchEventProcessor {
    private final Handler mGestureHandler = new Handler(Looper.getMainLooper());
    private final double mSensitivity;
    private boolean mEventTransitioned = true;
    private boolean fighting;
    private boolean suppressUntilDown = true;
    private boolean directTouchActive;
    private final PointerTracker mTracker = new PointerTracker();
    private final LeftClickGesture mLeftClickGesture = new LeftClickGesture(mGestureHandler);
    private final RightClickGesture mRightClickGesture = new RightClickGesture(mGestureHandler);

    public InGameEventProcessor(double sensitivity) {
        mSensitivity = sensitivity;
    }

    @Override
    public boolean processTouchEvent(MotionEvent motionEvent) {
        switch (motionEvent.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                directTouchActive = true;
                suppressUntilDown = false;
                mTracker.startTracking(motionEvent);
                if(LauncherPreferences.PREF_DISABLE_GESTURES) break;
                mEventTransitioned = false;
                checkGestures();
                break;
            case MotionEvent.ACTION_MOVE:
                if (!directTouchActive) break;
                try {
                    mTracker.trackEvent(motionEvent);
                    float[] motionVector = mTracker.getMotionVector();
                    float deltaX = (float) (motionVector[0] * mSensitivity);
                    float deltaY = (float) (motionVector[1] * mSensitivity);
                    mLeftClickGesture.setMotion(deltaX, deltaY);
                    mRightClickGesture.setMotion(deltaX, deltaY);
                    sendLookDelta(deltaX, deltaY);
                } catch (Throwable t) {
                    CallbackBridge.inputDebugLogPub("InGame MOVE EX: " + t);
                }
                if(LauncherPreferences.PREF_DISABLE_GESTURES || suppressUntilDown) break;
                checkGestures();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!directTouchActive) break;
                directTouchActive = false;
                suppressUntilDown = true;
                mTracker.cancelTracking();
                cancelGestures(motionEvent.getActionMasked() == MotionEvent.ACTION_CANCEL);
        }
        return true;
    }

    public boolean toggleFightGestures() {
        cancelGestures(true);
        suppressUntilDown = true;
        fighting = !fighting;
        mLeftClickGesture.setMouseButton(fighting ? LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT : LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT);
        mRightClickGesture.setMouseButton(fighting ? LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT : LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT);
        return fighting;
    }

    @Override
    public void cancelPendingActions() {
        directTouchActive = false;
        suppressUntilDown = true;
        mTracker.cancelTracking();
        cancelGestures(true);
    }

    /** HUD passthrough rotates the view without starting or interrupting canvas gestures. */
    public void moveFromControl(float dx, float dy) {
        if (!directTouchActive && (dx != 0 || dy != 0)) {
            sendLookDelta((float) (dx * mSensitivity), (float) (dy * mSensitivity));
        }
    }

    private void sendLookDelta(float dx, float dy) {
        CallbackBridge.mouseX += dx;
        CallbackBridge.mouseY += dy;
        CallbackBridge.deltaX = dx;
        CallbackBridge.deltaY = dy;
        CallbackBridge.sendCursorPos(CallbackBridge.mouseX, CallbackBridge.mouseY);
    }

    private void checkGestures() {
        mLeftClickGesture.inputEvent();
        // Only register right click events if it's a fresh event stream, not one after a transition.
        // This is done to avoid problems when people hold the button for just a bit too long after
        // exiting a menu for example.
        if(!mEventTransitioned) mRightClickGesture.inputEvent();
    }

    private void cancelGestures(boolean isSwitching) {
        mEventTransitioned = true;
        mLeftClickGesture.cancel(isSwitching);
        mRightClickGesture.cancel(isSwitching);
    }
}
