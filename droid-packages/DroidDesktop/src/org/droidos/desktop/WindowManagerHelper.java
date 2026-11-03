package org.droidos.desktop;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.util.Log;
import android.view.Display;

import java.lang.reflect.Method;

public class WindowManagerHelper {

    private static final String TAG = "droidOS-DesktopWM";
    public static final int WINDOWING_MODE_FREEFORM = 5;

    public static void launchFreeformApp(Context context, Intent intent, Display display) {
        if (intent == null) return;

        try {
            ActivityOptions options = ActivityOptions.makeBasic();

            // Target the secondary desktop display
            int displayId = display != null ? display.getDisplayId() : 0;
            options.setLaunchDisplayId(displayId);

            // Calculate default window bounds (e.g., 850x550 centered)
            int displayWidth = display != null ? display.getWidth() : 1280;
            int displayHeight = display != null ? display.getHeight() : 720;

            int winWidth = Math.min(displayWidth - 80, 850);
            int winHeight = Math.min(displayHeight - 120, 560);
            int left = (displayWidth - winWidth) / 2;
            int top = (displayHeight - winHeight) / 2;

            Rect bounds = new Rect(left, top, left + winWidth, top + winHeight);
            options.setLaunchBounds(bounds);

            // Set windowing mode to FREEFORM (5)
            try {
                Method setLaunchWindowingMode = ActivityOptions.class.getMethod(
                        "setLaunchWindowingMode", int.class);
                setLaunchWindowingMode.invoke(options, WINDOWING_MODE_FREEFORM);
            } catch (Exception e) {
                Log.w(TAG, "Reflection for setLaunchWindowingMode failed: " + e.getMessage());
            }

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent, options.toBundle());
            Log.i(TAG, "Launched freeform app: " + intent.getComponent() + " on display " + displayId);

        } catch (Exception e) {
            Log.e(TAG, "Failed to launch freeform app", e);
            // Fallback: standard start
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        }
    }
}
