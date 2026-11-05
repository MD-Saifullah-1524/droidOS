package org.droidos.desktop;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class AppDrawerAdapter extends BaseAdapter {

    public static class AppEntry {
        public String label;
        public String packageName;
        public String className;
        public Drawable icon;
        public Intent launchIntent;
    }

    private final Context mContext;
    private final LayoutInflater mInflater;
    private final List<AppEntry> mAllApps = new ArrayList<>();
    private final List<AppEntry> mFilteredApps = new ArrayList<>();

    public AppDrawerAdapter(Context context) {
        mContext = context;
        mInflater = LayoutInflater.from(context);
        reloadApps();
    }

    public void reloadApps() {
        mAllApps.clear();
        PackageManager pm = mContext.getPackageManager();

        Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);

        List<ResolveInfo> activities = pm.queryIntentActivities(mainIntent, 0);
        for (ResolveInfo ri : activities) {
            if (ri.activityInfo.packageName.equals(mContext.getPackageName())) {
                continue; // Don't list self
            }

            AppEntry entry = new AppEntry();
            entry.label = ri.loadLabel(pm).toString();
            entry.packageName = ri.activityInfo.packageName;
            entry.className = ri.activityInfo.name;
            entry.icon = ri.loadIcon(pm);

            entry.launchIntent = pm.getLaunchIntentForPackage(entry.packageName);
            if (entry.launchIntent == null && ri.activityInfo != null) {
                entry.launchIntent = new Intent(Intent.ACTION_MAIN);
                entry.launchIntent.setClassName(ri.activityInfo.packageName, ri.activityInfo.name);
            }
            mAllApps.add(entry);
        }

        Collections.sort(mAllApps, Comparator.comparing(a -> a.label.toLowerCase(Locale.ROOT)));
        filter("");
    }

    public void filter(String query) {
        mFilteredApps.clear();
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            mFilteredApps.addAll(mAllApps);
        } else {
            for (AppEntry entry : mAllApps) {
                if (entry.label.toLowerCase(Locale.ROOT).contains(q)) {
                    mFilteredApps.add(entry);
                }
            }
        }
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return mFilteredApps.size();
    }

    @Override
    public AppEntry getItem(int position) {
        return mFilteredApps.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = mInflater.inflate(R.layout.item_start_app, parent, false);
            holder = new ViewHolder();
            holder.iconView = convertView.findViewById(R.id.app_icon);
            holder.labelView = convertView.findViewById(R.id.app_label);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        AppEntry entry = getItem(position);
        holder.iconView.setImageDrawable(entry.icon);
        holder.labelView.setText(entry.label);

        return convertView;
    }

    private static class ViewHolder {
        ImageView iconView;
        TextView labelView;
    }
}
