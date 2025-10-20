package com.example.pushuplock

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button as M3Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.pushuplock.AppListAdapter

class MainActivity : AppCompatActivity() {

    private lateinit var rv: RecyclerView
    private lateinit var btnOverlay: Button
    private lateinit var btnAccessibility: Button
    private lateinit var btnDebugOverlay: Button  // Added debug overlay button

    // Android 13+ notifications runtime permission
    private val requestNotiPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLockManager.init(this)
        setContentView(R.layout.activity_main)

        // === Views ===
        rv = findViewById(R.id.rv_apps)
        btnOverlay = findViewById(R.id.btn_overlay_settings)
        btnAccessibility = findViewById(R.id.btn_accessibility)
        btnDebugOverlay = findViewById<Button>(R.id.btn_debug_overlay)  // find debug button

        // === Buttons ===
        btnOverlay.setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
        btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // Debug overlay button listener
        btnDebugOverlay?.setOnClickListener {
            val pkg = "com.instagram.android" // hardcoded target package
            val i = Intent(this, LockOverlayService::class.java).apply {
                putExtra(LockOverlayService.EXTRA_TARGET_PACKAGE, pkg)
                putExtra(LockOverlayService.EXTRA_SHOW_COUNTDOWN_ONLY, false)
            }
            ContextCompat.startForegroundService(this, i)
        }

        // Android 13+ notification permission ===
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val nm = NotificationManagerCompat.from(this)
            if (!nm.areNotificationsEnabled()) {
                requestNotiPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Load user-launchable apps (non-system) ===
        val pm = packageManager
        val userApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter {
                pm.getLaunchIntentForPackage(it.packageName) != null &&
                        (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0
            }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }

        // RecyclerView wiring ===
        val adapter = AppListAdapter(this, userApps)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
        rv.addItemDecoration(
            DividerItemDecoration(this, DividerItemDecoration.VERTICAL)
        )
        val btnDebug = findViewById<Button>(R.id.btn_debug_overlay)
        btnDebug.setOnClickListener {
            val intent = Intent(this, LockOverlayService::class.java).apply {
                putExtra(LockOverlayService.EXTRA_TARGET_PACKAGE, "com.instagram.android") // test Instagram
                putExtra(LockOverlayService.EXTRA_SHOW_COUNTDOWN_ONLY, false)
            }
            androidx.core.content.ContextCompat.startForegroundService(this, intent)
        }



        //  the Compose screen instead of XML, swap to this:
        // setContent { AppLockScreen() }
    }
}




@Composable
fun AppLockScreen() {
    val context = LocalContext.current
    val pm = context.packageManager

    val apps = remember {
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter {
                pm.getLaunchIntentForPackage(it.packageName) != null &&
                        (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0
            }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
    }

    Column(modifier = Modifier.padding(16.dp)) {

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            M3Button(onClick = {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                context.startActivity(intent)
            }) { Text("Enable Overlay") }

            M3Button(onClick = {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                context.startActivity(intent)
            }) { Text("Accessibility") }

            // Debug Overlay button in Compose UI
            M3Button(onClick = {
                val pkg = "com.instagram.android"
                val i = Intent(context, LockOverlayService::class.java).apply {
                    putExtra(LockOverlayService.EXTRA_TARGET_PACKAGE, pkg)
                    putExtra(LockOverlayService.EXTRA_SHOW_COUNTDOWN_ONLY, false)
                }
                ContextCompat.startForegroundService(context, i)
            }) { Text("Debug Overlay") }
        }

        Spacer(modifier = Modifier.width(0.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            apps.forEach { appInfo ->
                val label = appInfo.loadLabel(pm).toString()
                val packageName = appInfo.packageName
                val locked = remember {
                    mutableStateOf(AppLockManager.getLocked(context, packageName) != null)
                }
                val minutes = remember {
                    mutableStateOf(
                        AppLockManager.getLocked(context, packageName)
                            ?.minutesPerRep?.toString() ?: "10"
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(text = label, modifier = Modifier.weight(1f))
                    OutlinedTextField(
                        value = minutes.value,
                        onValueChange = { minutes.value = it },
                        modifier = Modifier.width(80.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
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
