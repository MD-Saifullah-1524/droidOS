package org.droidos.controlpanel;

import android.app.Activity;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;

public class TrackpadActivity extends Activity {

    private View mTouchSurface;
    private Button mBtnClose;
    private Button mBtnLeftClick;
    private Button mBtnRightClick;

    private float mLastX;
    private float mLastY;
    private boolean mIsDragging = false;
    private long mDownTime = 0;

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

        setupTouchSurface();
    }

    private void setupTouchSurface() {
        mTouchSurface.setOnTouchListener((v, event) -> {
            int pointerCount = event.getPointerCount();
            int action = event.getActionMasked();

            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    mLastX = event.getX();
                    mLastY = event.getY();
                    mDownTime = System.currentTimeMillis();
                    mIsDragging = false;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float dx = event.getX() - mLastX;
                    float dy = event.getY() - mLastY;
                    mLastX = event.getX();
                    mLastY = event.getY();

                    if (Math.abs(dx) > 2 || Math.abs(dy) > 2) {
                        mIsDragging = true;
                    }

                    if (pointerCount == 1) {
                        // Relative cursor movement
                        sendRelativeMove(dx, dy);
                    } else if (pointerCount == 2) {
                        // Two-finger scroll
                        sendScroll(dy);
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    long duration = System.currentTimeMillis() - mDownTime;
                    if (!mIsDragging && duration < 250) {
                        // Tap to click
                        triggerClick(1);
                    }
                    mIsDragging = false;
                    return true;

                case MotionEvent.ACTION_POINTER_UP:
                    if (pointerCount == 2 && !mIsDragging) {
                        // Two-finger tap -> Right Click
                        triggerClick(2);
                    }
                    return true;
            }
            return false;
        });
    }

    private void sendRelativeMove(float dx, float dy) {
        // Dispatches to InputInjector or AOA Service
    }

    private void sendScroll(float dy) {
        // Dispatches scroll delta
    }

    private void triggerClick(int button) {
        // button: 1=Left, 2=Right
    }
}
