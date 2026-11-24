package net.kdt.pojavlaunch.customcontrols;

import static net.kdt.pojavlaunch.LwjglGlfwKeycode.*;

import androidx.annotation.Keep;

@Keep
public class ControlJoystickData extends ControlData {

    public int[] forwardKeys = {GLFW_KEY_W};
    public int[] backwardKeys = {GLFW_KEY_S};
    public int[] leftKeys = {GLFW_KEY_A};
    public int[] rightKeys = {GLFW_KEY_D};
    public int[] forwardLockKeys = {GLFW_KEY_LEFT_CONTROL};
    public int[][] directionKeys;
    public boolean directionPad;
    public boolean centerFollow;
    public boolean triggerOnMove;
    public int deadZonePercent = 35;
    public float forwardLockRatio = -1f;
    public int doubleTapKey;

    /* Whether the joystick can stay forward */
    public boolean forwardLock = false;
    /*
     * Whether the finger tracking is absolute (joystick jumps to where you touched)
     * or relative (joystick stays in the center)
     */
    public boolean absolute = false;

    public ControlJoystickData(){
        super();
    }

    public ControlJoystickData(ControlJoystickData properties) {
        super(properties);
        forwardLock = properties.forwardLock;
        absolute = properties.absolute;
        directionPad = properties.directionPad;
        centerFollow = properties.centerFollow;
        triggerOnMove = properties.triggerOnMove;
        deadZonePercent = properties.deadZonePercent;
        forwardLockRatio = properties.forwardLockRatio;
        doubleTapKey = properties.doubleTapKey;
        forwardKeys = properties.forwardKeys.clone();
        backwardKeys = properties.backwardKeys.clone();
        leftKeys = properties.leftKeys.clone();
        rightKeys = properties.rightKeys.clone();
        forwardLockKeys = properties.forwardLockKeys.clone();
        if (properties.directionKeys != null) {
            directionKeys = properties.directionKeys.clone();
            for (int i = 0; i < directionKeys.length; i++) {
                if (directionKeys[i] != null) directionKeys[i] = directionKeys[i].clone();
            }
        }
    }
}
