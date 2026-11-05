package org.droidos.aoaservice;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

public class CursorOverlayController {

    private static final String TAG = "droidOS-Cursor";
    private static final int TYPE_INTERNAL_SYSTEM_WINDOW = 2005;
    private static final int TYPE_APPLICATION_OVERLAY = 2038;

    private final Context mContext;
    private Context mDisplayContext;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private WindowManager mWindowManager;
    private FrameLayout mContainer;
    private ImageView mCursorIcon;

    private boolean mIsAttached = false;
    private float mCurrentX = 640;
    private float mCurrentY = 360;
    private int mWidth = 1280;
    private int mHeight = 720;

    public CursorOverlayController(Context context, Display display, int width, int height) {
        mContext = context;
        mWidth = width;
        mHeight = height;
        mCurrentX = width / 2f;
        mCurrentY = height / 2f;

        try {
            mDisplayContext = (display != null) ? context.createDisplayContext(display) : context;
            mWindowManager = (WindowManager) mDisplayContext.getSystemService(Context.WINDOW_SERVICE);
        } catch (Throwable t) {
            Log.e(TAG, "Failed creating display context for cursor: " + t.getMessage(), t);
        }
    }

    public void show() {
        mMainHandler.post(() -> {
            if (mIsAttached || mWindowManager == null) return;

            try {
                Context ctx = (mDisplayContext != null) ? mDisplayContext : mContext;
                mContainer = new FrameLayout(ctx);
                mCursorIcon = new ImageView(ctx);
                mCursorIcon.setImageResource(R.drawable.ic_mouse_cursor);

                FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                );
                mContainer.addView(mCursorIcon, iconParams);

                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        TYPE_INTERNAL_SYSTEM_WINDOW,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                        PixelFormat.TRANSLUCENT
                );
                params.gravity = Gravity.TOP | Gravity.LEFT;

                try {
                    mWindowManager.addView(mContainer, params);
                } catch (Throwable t1) {
                    Log.w(TAG, "TYPE_INTERNAL_SYSTEM_WINDOW failed (" + t1.getMessage() + "), trying TYPE_APPLICATION_OVERLAY...");
                    params.type = TYPE_APPLICATION_OVERLAY;
                    mWindowManager.addView(mContainer, params);
                }

                mIsAttached = true;
                mCursorIcon.setTranslationX(mCurrentX);
                mCursorIcon.setTranslationY(mCurrentY);
                Log.i(TAG, "Cursor overlay window successfully attached at " + mCurrentX + ", " + mCurrentY);
            } catch (Throwable t) {
                Log.e(TAG, "Failed adding cursor overlay: " + t.getMessage(), t);
            }
        });
    }

    public void updatePosition(float x, float y) {
        mCurrentX = Math.max(0, Math.min(mWidth, x));
        mCurrentY = Math.max(0, Math.min(mHeight, y));

        mMainHandler.post(() -> {
            if (mCursorIcon != null && mIsAttached) {
                mCursorIcon.setTranslationX(mCurrentX);
                mCursorIcon.setTranslationY(mCurrentY);
            }
        });
    }

    public void hide() {
        mMainHandler.post(() -> {
            if (mIsAttached && mContainer != null && mWindowManager != null) {
                try {
                    mWindowManager.removeView(mContainer);
                } catch (Throwable ignored) {}
                mIsAttached = false;
                mContainer = null;
                mCursorIcon = null;
                Log.i(TAG, "Cursor overlay window removed.");
            }
        });
    }
}
