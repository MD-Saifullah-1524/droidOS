package org.droidos.aoaservice;

import android.hardware.input.InputManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.lang.reflect.Method;

public class InputEventBridge {

    private static final String TAG = "droidOS-InputBridge";

    private final InputManager mInputManager;
    private Method mInjectInputEventMethod;
    private Method mSetDisplayIdMethod;

    private int mDisplayId = 0;
    private int mWidth = 1280;
    private int mHeight = 720;
    private long mDownTime = 0;

    public InputEventBridge() {
        mInputManager = (InputManager) InputManager.getInstance();
        initReflection();
    }

    private void initReflection() {
        try {
            mInjectInputEventMethod = InputManager.class.getMethod(
                    "injectInputEvent", InputEvent.class, int.class);
        } catch (Exception e) {
            Log.e(TAG, "Failed to resolve injectInputEvent", e);
        }

        try {
            mSetDisplayIdMethod = InputEvent.class.getMethod("setDisplayId", int.class);
        } catch (Exception e) {
            Log.w(TAG, "setDisplayId not accessible via reflection", e);
        }
    }

    public void setTargetDisplay(int displayId, int width, int height) {
        mDisplayId = displayId;
        mWidth = width;
        mHeight = height;
        Log.i(TAG, "Target display set to ID=" + displayId + " (" + width + "x" + height + ")");
    }

    public void handleMousePacket(byte[] payload) {
        if (payload.length < 12) return;

        int action = payload[0] & 0xFF;
        int button = payload[1] & 0xFF;
        short scrollDy = (short) (((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF));
        int normX = ((payload[4] & 0xFF) << 8) | (payload[5] & 0xFF);
        int normY = ((payload[6] & 0xFF) << 8) | (payload[7] & 0xFF);

        float px = (normX / 65535.0f) * mWidth;
        float py = (normY / 65535.0f) * mHeight;
        long now = SystemClock.uptimeMillis();

        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[1];
        props[0] = new MotionEvent.PointerProperties();
        props[0].id = 0;
        props[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;

        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[1];
        coords[0] = new MotionEvent.PointerCoords();
        coords[0].x = px;
        coords[0].y = py;

        int motionAction;
        int buttonState = (button == 1) ? MotionEvent.BUTTON_PRIMARY :
                          (button == 2) ? MotionEvent.BUTTON_SECONDARY :
                          (button == 3) ? MotionEvent.BUTTON_TERTIARY : 0;

        if (action == 0) { // MOVE
            motionAction = MotionEvent.ACTION_HOVER_MOVE;
        } else if (action == 1) { // DOWN
            motionAction = MotionEvent.ACTION_DOWN;
            mDownTime = now;
        } else if (action == 2) { // UP
            motionAction = MotionEvent.ACTION_UP;
        } else if (action == 3) { // SCROLL
            motionAction = MotionEvent.ACTION_SCROLL;
            coords[0].setAxisValue(MotionEvent.AXIS_VSCROLL, scrollDy);
        } else {
            return;
        }

        MotionEvent event = MotionEvent.obtain(
                mDownTime > 0 ? mDownTime : now,
                now,
                motionAction,
                1,
                props,
                coords,
                0,
                buttonState,
                1.0f,
                1.0f,
                0,
                0,
                InputDevice.SOURCE_MOUSE,
                0
        );

        injectEvent(event);
    }

    public void handleKeyboardPacket(byte[] payload) {
        if (payload.length < 8) return;

        int action = payload[0] & 0xFF; // 0=DOWN, 1=UP
        int modifiers = payload[1] & 0xFF;
        int keycode = ((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF);
        long now = SystemClock.uptimeMillis();

        int keyAction = (action == 0) ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP;

        KeyEvent event = new KeyEvent(
                now,
                now,
                keyAction,
                keycode,
                0,
                modifiers,
                0,
                0,
                0,
                InputDevice.SOURCE_KEYBOARD
        );

        injectEvent(event);
    }

    private void injectEvent(InputEvent event) {
        if (mSetDisplayIdMethod != null && mDisplayId > 0) {
            try {
                mSetDisplayIdMethod.invoke(event, mDisplayId);
            } catch (Exception ignored) {}
        }

        if (mInjectInputEventMethod != null) {
            try {
                mInjectInputEventMethod.invoke(mInputManager, event, 0 /* INJECT_INPUT_EVENT_MODE_ASYNC */);
            } catch (Exception e) {
                Log.w(TAG, "Failed injecting input event: " + e.getMessage());
            }
        }
    }
}
