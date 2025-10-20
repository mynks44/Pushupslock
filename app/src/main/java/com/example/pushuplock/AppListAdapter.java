package com.example.pushuplock;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class AppListAdapter extends RecyclerView.Adapter<AppListAdapter.VH> {

    private final Context context;
    private final PackageManager pm;
    private final List<ApplicationInfo> apps;

    public AppListAdapter(@NonNull Context context, @NonNull List<ApplicationInfo> apps) {
        this.context = context;
        this.apps = apps;
        this.pm = context.getPackageManager();
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_app_row, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        ApplicationInfo ai = apps.get(position);
        String label = ai.loadLabel(pm).toString();
        h.name.setText(label);
        h.icon.setImageDrawable(ai.loadIcon(pm));

        final String pkg = ai.packageName;
        boolean locked = AppLockManager.INSTANCE.getLocked(context, pkg) != null;
        h.toggle.setOnCheckedChangeListener(null);
        h.toggle.setChecked(locked);

        h.toggle.setOnCheckedChangeListener((btn, isChecked) -> {
            if (isChecked) {
                AppLockManager.INSTANCE.setLocked(context, pkg, 10); // default 10 min/rep
            } else {
                AppLockManager.INSTANCE.removeLock(context, pkg);
            }
        });
    }

    @Override
    public int getItemCount() { return apps.size(); }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView name;
        final Switch toggle;
        VH(@NonNull View v) {
            super(v);
            icon = v.findViewById(R.id.iv_app_icon);
            name = v.findViewById(R.id.tv_app_name);
            toggle = v.findViewById(R.id.sw_lock);
        }
    }
}
