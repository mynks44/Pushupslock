package com.example.pushuplock

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.CompoundButton
import androidx.recyclerview.widget.RecyclerView
import kotlinx.android.synthetic.main.item_app_row.view.*

class AppListAdapter(
        private val context: Context,
        private val apps: List<ApplicationInfo>
) : RecyclerView.Adapter<AppListAdapter.ViewHolder>() {

private val pm: PackageManager = context.packageManager

override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
    val v = LayoutInflater.from(parent.context).inflate(R.layout.item_app_row, parent, false)
    return ViewHolder(v)
}

override fun onBindViewHolder(holder: ViewHolder, position: Int) {
    val appInfo = apps[position]
    val label = appInfo.loadLabel(pm).toString()
    val pkg = appInfo.packageName
    holder.itemView.tv_app_label.text = label

    val locked = AppLockManager.getLocked(context, pkg)
    holder.itemView.et_minutes.setText(locked?.minutesPerRep?.toString() ?: "10")
    holder.itemView.toggle_lock.isChecked = locked != null

    holder.itemView.toggle_lock.setOnCheckedChangeListener { _, isChecked ->
        if (isChecked) {
            val minutes = holder.itemView.et_minutes.text.toString().toIntOrNull() ?: 10
            AppLockManager.setLocked(context, pkg, minutes)
        } else {
            AppLockManager.removeLock(context, pkg)
        }
    }

    // update minutes when user edits the EditText
    holder.itemView.et_minutes.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            val minutes = s?.toString()?.toIntOrNull() ?: return
            if (AppLockManager.getLocked(context, pkg) != null) {
                AppLockManager.setLocked(context, pkg, minutes)
            }
        }
    })
}

override fun getItemCount(): Int = apps.size

class ViewHolder(val container: android.view.View) : RecyclerView.ViewHolder(container)
}
