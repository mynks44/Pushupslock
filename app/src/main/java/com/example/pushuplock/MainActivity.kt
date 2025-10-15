package com.example.pushuplock

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat.startActivity
import androidx.recyclerview.widget.RecyclerView
import com.example.pushuplock.ui.theme.PushupLockTheme
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var rv: RecyclerView
    private lateinit var btnOverlay: Button
    private lateinit var btnAccessibility: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLockManager.init(this)
        setContentView(R.layout.activity_main)

        rv = findViewById(R.id.rv_apps)
        btnOverlay = findViewById(R.id.btn_overlay_settings)
        btnAccessibility = findViewById(R.id.btn_accessibility)

        btnOverlay.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        val pm = packageManager
        val userApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null && (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }

        val adapter = AppListAdapter(this, userApps)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
        rv.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
    }
}

@Composable
fun AppLockScreen() {
    val context = LocalContext.current
    val pm = context.packageManager

    // Load installed apps
    val apps = remember {
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter {
                pm.getLaunchIntentForPackage(it.packageName) != null &&
                        (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0
            }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
    }

    Column(modifier = Modifier.padding(16.dp)) {

        // Buttons for overlay and accessibility
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(onClick = {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}"))
                context.startActivity(intent)
            }) {
                Text("Enable Overlay")
            }

            Button(onClick = {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                context.startActivity(intent)
            }) {
                Text("Accessibility")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // List of apps with toggles
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            apps.forEach { appInfo ->
                val label = appInfo.loadLabel(pm).toString()
                val packageName = appInfo.packageName
                val locked = remember { mutableStateOf(AppLockManager.getLocked(context, packageName) != null) }
                val minutes = remember {
                    mutableStateOf(
                        AppLockManager.getLocked(context, packageName)?.minutesPerRep?.toString() ?: "10"
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = label,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = minutes.value,
                        onValueChange = { minutes.value = it },
                        modifier = Modifier.width(80.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number)
                    )
                    Switch(
                        checked = locked.value,
                        onCheckedChange = { isChecked ->
                            if (isChecked) {
                                val mins = minutes.value.toIntOrNull() ?: 10
                                AppLockManager.setLocked(context, packageName, mins)
                                Toast.makeText(context, "Locked $packageName", Toast.LENGTH_SHORT).show()
                            } else {
                                AppLockManager.removeLock(context, packageName)
                                Toast.makeText(context, "Unlocked $packageName", Toast.LENGTH_SHORT).show()
                            }
                            locked.value = isChecked
                        }
                    )
                }
            }
        }
    }
}
