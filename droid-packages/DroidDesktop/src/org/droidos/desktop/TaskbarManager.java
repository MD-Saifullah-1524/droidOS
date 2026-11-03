package org.droidos.desktop;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.util.HashMap;
import java.util.Map;

public class TaskbarManager {

    private final Context mContext;
    private final LinearLayout mTasksContainer;
    private final LayoutInflater mInflater;
    private final Map<String, View> mActiveTasks = new HashMap<>();

    public interface OnTaskClickListener {
        void onTaskClick(String packageName);
    }

    private OnTaskClickListener mListener;

    public TaskbarManager(Context context, LinearLayout container) {
        mContext = context;
        mTasksContainer = container;
        mInflater = LayoutInflater.from(context);
    }

    public void setOnTaskClickListener(OnTaskClickListener listener) {
        mListener = listener;
    }

    public void addOrUpdateTask(String packageName, Drawable icon) {
        if (mActiveTasks.containsKey(packageName)) {
            // Already present, highlight
            View item = mActiveTasks.get(packageName);
            if (item != null) {
                View pill = item.findViewById(R.id.running_pill);
                if (pill != null) pill.setVisibility(View.VISIBLE);
            }
            return;
        }

        View taskView = mInflater.inflate(R.layout.item_taskbar_running, mTasksContainer, false);
        ImageView iconView = taskView.findViewById(R.id.running_app_icon);
        View pill = taskView.findViewById(R.id.running_pill);

        if (icon != null) {
            iconView.setImageDrawable(icon);
        } else {
            iconView.setImageResource(android.R.drawable.sym_def_app_icon);
        }

        pill.setVisibility(View.VISIBLE);

        taskView.setOnClickListener(v -> {
            if (mListener != null) {
                mListener.onTaskClick(packageName);
            }
        });

        mActiveTasks.put(packageName, taskView);
        mTasksContainer.addView(taskView);
    }

    public void removeTask(String packageName) {
        View taskView = mActiveTasks.remove(packageName);
        if (taskView != null) {
            mTasksContainer.removeView(taskView);
        }
    }

    public void clearAll() {
        mActiveTasks.clear();
        mTasksContainer.removeAllViews();
    }
}
