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
    private float mCurrentX = 640f;
    private float mCurrentY = 360f;
    private long mDownTime = 0;
    private long mPacketCount = 0;
    private CursorOverlayController mCursorOverlay;
    private final UhidMouse mUhidMouse;
    private boolean mHasLastPos = false;
    private float mLastNormPx = -1f;
    private float mLastNormPy = -1f;

    public InputEventBridge() {
        mInputManager = (InputManager) InputManager.getInstance();
        initReflection();
        mUhidMouse = new UhidMouse();
        mUhidMouse.open();
    }

    public boolean hasNativeMouse() {
        return mUhidMouse != null && mUhidMouse.isOpen();
    }

    public void setCursorOverlay(CursorOverlayController cursorOverlay) {
        mCursorOverlay = cursorOverlay;
    }

    public void close() {
        if (mUhidMouse != null) {
            mUhidMouse.close();
        }
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
        mCurrentX = width / 2f;
        mCurrentY = height / 2f;
        mLastNormPx = -1f;
        mLastNormPy = -1f;
        mHasLastPos = false;
        Log.i(TAG, "Target display set to ID=" + displayId + " (" + width + "x" + height + ")");
    }

    public void handleMousePacket(byte[] payload) {
        if (payload.length < 12) return;

        int action = payload[0] & 0xFF;
        int button = payload[1] & 0xFF;
        short scrollDy = (short) (((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF));
        int normX = ((payload[4] & 0xFF) << 8) | (payload[5] & 0xFF);
        int normY = ((payload[6] & 0xFF) << 8) | (payload[7] & 0xFF);
        short relDx = (short) (((payload[8] & 0xFF) << 8) | (payload[9] & 0xFF));
        short relDy = (short) (((payload[10] & 0xFF) << 8) | (payload[11] & 0xFF));

        long now = SystemClock.uptimeMillis();
        int dx = 0;
        int dy = 0;

        if (action == 0) { // MOVE
            if (relDx != 0 || relDy != 0) {
                dx = relDx;
                dy = relDy;
                mLastNormPx = -1f;
                mCurrentX = Math.max(0, Math.min(mWidth - 1, mCurrentX + dx));
                mCurrentY = Math.max(0, Math.min(mHeight - 1, mCurrentY + dy));
                mHasLastPos = true;
            } else if (normX > 0 || normY > 0) {
                float px = (normX / 65535.0f) * mWidth;
                float py = (normY / 65535.0f) * mHeight;
                if (mLastNormPx >= 0 && mLastNormPy >= 0) {
                    dx = Math.round(px - mLastNormPx);
                    dy = Math.round(py - mLastNormPy);
                }
                mLastNormPx = px;
                mLastNormPy = py;
                mCurrentX = px;
                mCurrentY = py;
                mHasLastPos = true;
            }
        } else {
            // DOWN, UP, SCROLL: motion delta is strictly 0
            dx = 0;
            dy = 0;
            if (normX > 0 || normY > 0) {
                mLastNormPx = (normX / 65535.0f) * mWidth;
                mLastNormPy = (normY / 65535.0f) * mHeight;
                mCurrentX = mLastNormPx;
                mCurrentY = mLastNormPy;
            }
        }

        if (mPacketCount++ < 10 || (mPacketCount % 200 == 0) || action != 0) {
            Log.i(TAG, "Mouse packet #" + mPacketCount + ": action=" + action + " btn=" + button
                    + " pos=(" + mCurrentX + ", " + mCurrentY + ") dx=" + dx + " dy=" + dy);
        }

        // Native Linux UHID Mouse: Drives the official Android native cursor via kernel input subsystem
        if (mUhidMouse != null && mUhidMouse.isOpen()) {
            int targetButtons = 0;
            if (button >= 1 && button <= 5) {
                targetButtons = (1 << (button - 1));
            }

            if (action == 0) { // MOVE
                if (targetButtons != mUhidMouse.getCurrentButtons()) {
                    mUhidMouse.setButtons(targetButtons);
                }
                if (dx != 0 || dy != 0) {
                    mUhidMouse.sendMove(dx, dy);
                }
            } else if (action == 1) { // DOWN
                if (button >= 1 && button <= 5) {
                    mUhidMouse.setButton(button - 1, true);
                }
            } else if (action == 2) { // UP
                if (button >= 1 && button <= 5) {
                    mUhidMouse.setButton(button - 1, false);
                }
            } else if (action == 3) { // SCROLL
                mUhidMouse.sendScroll(scrollDy);
            }
            return;
        }

        if (mCursorOverlay != null) {
            mCursorOverlay.updatePosition(mCurrentX, mCurrentY);
        }

        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[1];
        props[0] = new MotionEvent.PointerProperties();
        props[0].id = 0;
        props[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;

        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[1];
        coords[0] = new MotionEvent.PointerCoords();
        coords[0].x = mCurrentX;
        coords[0].y = mCurrentY;

        int motionAction;
        int buttonState = (button == 1) ? MotionEvent.BUTTON_PRIMARY :
                          (button == 2) ? MotionEvent.BUTTON_SECONDARY :
                          (button == 3) ? MotionEvent.BUTTON_TERTIARY : 0;

        if (action == 0) { // MOVE
            motionAction = (buttonState != 0) ? MotionEvent.ACTION_MOVE : MotionEvent.ACTION_HOVER_MOVE;
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

        // For ACTION_UP, buttonState represents the remaining held buttons (typically 0)
        int eventButtonState = (action == 2) ? 0 : buttonState;

        MotionEvent event = MotionEvent.obtain(
                mDownTime > 0 ? mDownTime : now,
                now,
                motionAction,
                1,
                props,
                coords,
                0,
                eventButtonState,
                1.0f,
                1.0f,
                0,
                0,
                InputDevice.SOURCE_MOUSE,
                0
        );

        injectEvent(event);

        if (action == 2) {
            mDownTime = 0;
        }
    }

    public void handleKeyboardPacket(byte[] payload) {
        if (payload.length < 8) return;

        int action = payload[0] & 0xFF; // 0=DOWN, 1=UP
        int modifiers = payload[1] & 0xFF;
        int keycode = ((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF);
        long now = SystemClock.uptimeMillis();

        int keyAction = (action == 0) ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP;

        int metaState = 0;
        if ((modifiers & 0x01) != 0) metaState |= KeyEvent.META_SHIFT_ON;
        if ((modifiers & 0x02) != 0) metaState |= KeyEvent.META_CTRL_ON;
        if ((modifiers & 0x04) != 0) metaState |= KeyEvent.META_ALT_ON;
        if ((modifiers & 0x08) != 0) metaState |= KeyEvent.META_META_ON;

        KeyEvent event = new KeyEvent(
                now,
                now,
                keyAction,
                keycode,
                0,
                metaState,
                0,
                0,
                0,
                InputDevice.SOURCE_KEYBOARD
        );

        injectEvent(event);
    }

    private void injectEvent(InputEvent event) {
        if (event == null) return;

        if (mDisplayId > 0) {
            try {
                event.setDisplayId(mDisplayId);
            } catch (Throwable t) {
                if (mSetDisplayIdMethod != null) {
                    try {
                        mSetDisplayIdMethod.invoke(event, mDisplayId);
                    } catch (Exception ignored) {}
                }
            }
        }

        boolean success = false;
        try {
            success = mInputManager.injectInputEvent(event, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
        } catch (Throwable t) {
            if (mInjectInputEventMethod != null) {
                try {
                    Object res = mInjectInputEventMethod.invoke(mInputManager, event, 0 /* INJECT_INPUT_EVENT_MODE_ASYNC */);
                    success = (res instanceof Boolean) && (Boolean) res;
                } catch (Exception e) {
                    Log.w(TAG, "Failed injecting input event: " + e.getMessage());
                }
            } else {
                Log.w(TAG, "Failed injecting input event: " + t.getMessage());
            }
        }

        // If injection failed because display didn't have focus, fallback to default display
        if (!success && mDisplayId > 0) {
            try {
                event.setDisplayId(0);
                mInputManager.injectInputEvent(event, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
            } catch (Throwable ignored) {}
        }
    }
}
