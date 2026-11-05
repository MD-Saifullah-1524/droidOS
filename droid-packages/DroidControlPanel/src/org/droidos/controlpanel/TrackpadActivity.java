package org.droidos.controlpanel;

import android.app.Activity;
import android.content.Context;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;

public class TrackpadActivity extends Activity {

    private static final String TAG = "droidOS-Trackpad";

    private View mTouchSurface;
    private Button mBtnClose;
    private Button mBtnLeftClick;
    private Button mBtnRightClick;

    private InputManager mInputManager;
    private DisplayManager mDisplayManager;

    private int mTargetDisplayId = Display.DEFAULT_DISPLAY;
    private int mTargetWidth = 1280;
    private int mTargetHeight = 720;
    private float mCursorX = 640;
    private float mCursorY = 360;

    private float mLastX;
    private float mLastY;
    private float mLastTwoFingerY;
    private float mScrollAccumulatorY = 0f;
    private float mSubpixelRemainderX = 0f;
    private float mSubpixelRemainderY = 0f;
    private long mLastScrollTime = 0;
    private static final float SCROLL_THRESHOLD_PX = 48.0f;
    private boolean mIsDragging = false;
    private long mDownTime = 0;
    private UhidMouse mUhidMouse;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_trackpad);

        mTouchSurface = findViewById(R.id.touch_surface);
        mBtnClose = findViewById(R.id.btn_close_trackpad);
        mBtnLeftClick = findViewById(R.id.btn_left_click);
        mBtnRightClick = findViewById(R.id.btn_right_click);

        mBtnClose.setOnClickListener(v -> finish());
        mBtnLeftClick.setOnClickListener(v -> triggerClick(1));
        mBtnRightClick.setOnClickListener(v -> triggerClick(2));

        mInputManager = (InputManager) InputManager.getInstance();
        mDisplayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        mUhidMouse = new UhidMouse();
        mUhidMouse.open();
        resolveTargetDisplay();

        setupTouchSurface();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mUhidMouse != null) {
            mUhidMouse.close();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resolveTargetDisplay();
    }

    private void resolveTargetDisplay() {
        if (mDisplayManager == null) return;
        Display[] displays = mDisplayManager.getDisplays();
        for (Display d : displays) {
            if (d.getDisplayId() != Display.DEFAULT_DISPLAY) {
                mTargetDisplayId = d.getDisplayId();
                Point size = new Point();
                d.getRealSize(size);
                mTargetWidth = Math.max(size.x, 320);
                mTargetHeight = Math.max(size.y, 240);
                if (mCursorX <= 0 || mCursorX >= mTargetWidth) mCursorX = mTargetWidth / 2f;
                if (mCursorY <= 0 || mCursorY >= mTargetHeight) mCursorY = mTargetHeight / 2f;
                Log.i(TAG, "Targeting secondary desktop display ID=" + mTargetDisplayId
                        + " (" + mTargetWidth + "x" + mTargetHeight + ")");
                return;
            }
        }

        Display def = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY);
        if (def != null) {
            mTargetDisplayId = Display.DEFAULT_DISPLAY;
            Point size = new Point();
            def.getRealSize(size);
            mTargetWidth = size.x;
            mTargetHeight = size.y;
            if (mCursorX <= 0 || mCursorX >= mTargetWidth) mCursorX = mTargetWidth / 2f;
            if (mCursorY <= 0 || mCursorY >= mTargetHeight) mCursorY = mTargetHeight / 2f;
        }
    }

    private void setupTouchSurface() {
        mTouchSurface.setOnTouchListener((v, event) -> {
            int pointerCount = event.getPointerCount();
            int action = event.getActionMasked();

            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    mLastX = event.getX();
                    mLastY = event.getY();
                    mDownTime = SystemClock.uptimeMillis();
                    mIsDragging = false;
                    mScrollAccumulatorY = 0f;
                    return true;

                case MotionEvent.ACTION_POINTER_DOWN:
                    if (pointerCount == 2) {
                        mLastTwoFingerY = (event.getY(0) + event.getY(1)) / 2f;
                        mScrollAccumulatorY = 0f;
                    }
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (pointerCount == 1) {
                        float dx = event.getX() - mLastX;
                        float dy = event.getY() - mLastY;
                        mLastX = event.getX();
                        mLastY = event.getY();

                        sendRelativeMove(dx, dy);
                    } else if (pointerCount == 2) {
                        float midY = (event.getY(0) + event.getY(1)) / 2f;
                        float dy = midY - mLastTwoFingerY;
                        mLastTwoFingerY = midY;
                        sendScroll(dy);
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    mScrollAccumulatorY = 0f;
                    mSubpixelRemainderX = 0f;
                    mSubpixelRemainderY = 0f;
                    return true;

                case MotionEvent.ACTION_POINTER_UP:
                    mScrollAccumulatorY = 0f;
                    mSubpixelRemainderX = 0f;
                    mSubpixelRemainderY = 0f;
                    return true;
            }
            return false;
        });
    }

    private void sendRelativeMove(float dx, float dy) {
        float dist = (float) Math.hypot(dx, dy);
        // Linear predictable tracking with gentle, natural acceleration
        float accel = 1.0f + Math.min(0.5f, dist / 28.0f);
        float sensitivity = 1.05f * accel;

        mSubpixelRemainderX += dx * sensitivity;
        mSubpixelRemainderY += dy * sensitivity;

        int moveX = (int) mSubpixelRemainderX;
        int moveY = (int) mSubpixelRemainderY;
        mSubpixelRemainderX -= moveX;
        mSubpixelRemainderY -= moveY;

        if (mUhidMouse != null && mUhidMouse.isOpen()) {
            if (moveX != 0 || moveY != 0) {
                mUhidMouse.sendMove(moveX, moveY);
            }
        } else {
            // Fallback path when UHID is unavailable
            mCursorX = Math.max(0, Math.min(mTargetWidth - 1, mCursorX + moveX));
            mCursorY = Math.max(0, Math.min(mTargetHeight - 1, mCursorY + moveY));

            long now = SystemClock.uptimeMillis();
            injectMotionEvent(now, now, MotionEvent.ACTION_HOVER_MOVE, mCursorX, mCursorY, 0, 0);
        }
    }

    private void sendScroll(float dy) {
        mScrollAccumulatorY += dy;
        int notches = (int) (mScrollAccumulatorY / SCROLL_THRESHOLD_PX);
        if (notches != 0) {
            long now = SystemClock.uptimeMillis();
            if (now - mLastScrollTime >= 50) {
                mScrollAccumulatorY -= notches * SCROLL_THRESHOLD_PX;
                mLastScrollTime = now;
                if (mUhidMouse != null && mUhidMouse.isOpen()) {
                    int clampedNotches = Math.max(-2, Math.min(2, notches));
                    mUhidMouse.sendScroll(clampedNotches);
                }
            }
        }
    }

    private void triggerClick(int button) {
        if (mUhidMouse != null && mUhidMouse.isOpen()) {
            int buttonIdx = (button == 2) ? 1 : 0;
            mUhidMouse.setButton(buttonIdx, true);
            mTouchSurface.postDelayed(() -> {
                if (mUhidMouse != null && mUhidMouse.isOpen()) {
                    mUhidMouse.setButton(buttonIdx, false);
                }
            }, 50);
        } else {
            long now = SystemClock.uptimeMillis();
            int buttonState = (button == 2) ? MotionEvent.BUTTON_SECONDARY : MotionEvent.BUTTON_PRIMARY;

            // Down
            injectMotionEvent(now, now, MotionEvent.ACTION_DOWN, mCursorX, mCursorY, buttonState, 0);
            // Up after brief delay
            injectMotionEvent(now, now + 30, MotionEvent.ACTION_UP, mCursorX, mCursorY, 0, 0);
        }
    }

    private void injectMotionEvent(long downTime, long eventTime, int action, float x, float y, int buttonState, int scrollDy) {
        if (mInputManager == null) return;

        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[1];
        props[0] = new MotionEvent.PointerProperties();
        props[0].id = 0;
        props[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;

        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[1];
        coords[0] = new MotionEvent.PointerCoords();
        coords[0].x = x;
        coords[0].y = y;
        if (action == MotionEvent.ACTION_SCROLL) {
            coords[0].setAxisValue(MotionEvent.AXIS_VSCROLL, scrollDy);
        }

        MotionEvent event = MotionEvent.obtain(
                downTime,
                eventTime,
                action,
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

        if (mTargetDisplayId > 0) {
            try {
                event.setDisplayId(mTargetDisplayId);
            } catch (Throwable ignored) {}
        }

        try {
            mInputManager.injectInputEvent(event, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
        } catch (Throwable t) {
            Log.w(TAG, "Failed injecting trackpad event: " + t.getMessage());
        } finally {
            event.recycle();
        }
    }
}
