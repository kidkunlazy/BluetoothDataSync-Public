package com.example.bluetoothdatasync

import android.Manifest
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var macAddressInput: EditText
    private lateinit var appIdInput: EditText
    private lateinit var appKeyInput: EditText
    private lateinit var apiUrlInput: EditText
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var statusTextView: TextView
    private lateinit var lastResultTextView: TextView
    private lateinit var nextScanTextView: TextView
    private lateinit var rawDataTextView: TextView

    // --- 【修改 1】定义一个新的权限请求启动器，专门用于前台权限 ---
    private val requestForegroundPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.values.all { it }) {
                // 当前台权限被授予后，我们接着检查并请求后台权限
                checkAndRequestBackgroundLocationPermission()
            } else {
                Toast.makeText(this, "应用需要所有权限才能正常工作", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        title = getString(R.string.app_name)
        // 不再在 onCreate 时自动请求权限，而是在用户点击按钮时请求
        setupUI()
        loadPreferences()
        registerReceivers()
    }

    private fun setupUI() {
        macAddressInput = findViewById(R.id.macAddressInput)
        appIdInput = findViewById(R.id.appIdInput)
        appKeyInput = findViewById(R.id.appKeyInput)
        apiUrlInput = findViewById(R.id.apiUrlInput)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        statusTextView = findViewById(R.id.statusTextView)
        lastResultTextView = findViewById(R.id.lastResultTextView)
        nextScanTextView = findViewById(R.id.nextScanTextView)
        rawDataTextView = findViewById(R.id.rawDataTextView)

        // --- 【修改 2】修改“启动”按钮的逻辑 ---
        startButton.setOnClickListener {
            // 在启动服务前，先执行完整的权限检查
            checkAndRequestPermissions {
                // 只有当所有权限（包括后台权限）都满足后，这个代码块才会被执行
                startSyncService()
            }
        }
        stopButton.setOnClickListener { stopSyncService() }
    }

    private fun startSyncService() {
        val macAddress = macAddressInput.text.toString()
        val appId = appIdInput.text.toString()
        val appKey = appKeyInput.text.toString()
        val apiUrl = apiUrlInput.text.toString()

        if (macAddress.isBlank() || appId.isBlank() || appKey.isBlank() || apiUrl.isBlank()) {
            Toast.makeText(this, "请填写所有字段", Toast.LENGTH_SHORT).show()
            return
        }
        savePreferences()
        val serviceIntent = Intent(this, BluetoothSyncService::class.java).apply {
            action = BluetoothSyncService.ACTION_START_SERVICE
            putExtra("MAC_ADDRESS", macAddress)
            putExtra("APP_ID", appId)
            putExtra("APP_KEY", appKey)
            putExtra("API_URL", apiUrl)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
        Toast.makeText(this, "服务已启动", Toast.LENGTH_SHORT).show()
    }

    private fun stopSyncService() {
        val serviceIntent = Intent(this, BluetoothSyncService::class.java).apply {
            action = BluetoothSyncService.ACTION_STOP_SERVICE
        }
        startService(serviceIntent)
        Toast.makeText(this, "服务已停止", Toast.LENGTH_SHORT).show()
    }

    // --- 【修改 3】重构整个权限检查流程 ---
    private fun checkAndRequestPermissions(onAllPermissionsGranted: () -> Unit) {
        // 1. 定义需要的前台权限列表
        val foregroundPermissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android 12+
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                foregroundPermissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                foregroundPermissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            foregroundPermissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13+
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                foregroundPermissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // 2. 如果有需要请求的前台权限，则发起请求
        if (foregroundPermissionsToRequest.isNotEmpty()) {
            requestForegroundPermissionsLauncher.launch(foregroundPermissionsToRequest.toTypedArray())
        } else {
            // 3. 如果前台权限都有了，直接检查后台权限
            checkAndRequestBackgroundLocationPermission()
            // 4. 再次检查所有权限是否都已满足，如果满足则执行启动服务的操作
            if (areAllPermissionsGranted()) {
                onAllPermissionsGranted()
            }
        }
    }

    private fun checkAndRequestBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { // Android 10+
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                // 如果缺少后台权限，弹窗向用户解释并引导其去设置
                showBackgroundLocationPermissionRationale()
            }
        }
    }

    // --- 【新增函数】用于弹窗解释为什么需要后台权限 ---
    private fun showBackgroundLocationPermissionRationale() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.background_location_permission_title))
            .setMessage(getString(R.string.background_location_permission_message))
            .setPositiveButton(getString(R.string.go_to_settings)) { _, _ ->
                // 跳转到应用的权限设置页面
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                val uri = Uri.fromParts("package", packageName, null)
                intent.data = uri
                startActivity(intent)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // --- 【新增函数】用于在启动服务前做最终确认 ---
    private fun areAllPermissionsGranted(): Boolean {
        val hasFineLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasBackgroundLocation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Android 10 以下版本不需要此权限，视为已满足
        }
        // 您可以在这里添加对其他关键权限的检查，但后台位置是核心
        return hasFineLocation && hasBackgroundLocation
    }

    // ... 您其他的代码 (BroadcastReceiver, Preferences, Menu 等) 保持不变 ...

    private val statusUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val statusResId = intent?.getIntExtra(BluetoothSyncService.EXTRA_STATUS_RES_ID, R.string.status_stopped) ?: R.string.status_stopped
            val lastResultResId = intent?.getIntExtra(BluetoothSyncService.EXTRA_LAST_RESULT_RES_ID, R.string.result_none) ?: R.string.result_none
            val lastResultArg = intent?.getStringExtra(BluetoothSyncService.EXTRA_LAST_RESULT_ARG)

            statusTextView.text = getString(statusResId)
            lastResultTextView.text = if (lastResultArg != null) getString(lastResultResId, lastResultArg) else getString(lastResultResId)

            val nextScanTimestamp = intent?.getLongExtra(BluetoothSyncService.EXTRA_NEXT_SCAN_TIME, 0L) ?: 0L
            nextScanTextView.text = if (nextScanTimestamp == 0L) getString(R.string.next_scan_none) else
                SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(nextScanTimestamp))
        }
    }

    private val rawDataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            rawDataTextView.text = intent?.getStringExtra(BluetoothSyncService.EXTRA_RAW_DATA) ?: "无"
        }
    }

    private fun registerReceivers() {
        val statusFilter = IntentFilter(BluetoothSyncService.ACTION_STATUS_UPDATE)
        val dataFilter = IntentFilter(BluetoothSyncService.ACTION_RAW_DATA_RECEIVED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusUpdateReceiver, statusFilter, RECEIVER_EXPORTED)
            registerReceiver(rawDataReceiver, dataFilter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(statusUpdateReceiver, statusFilter)
            registerReceiver(rawDataReceiver, dataFilter)
        }
    }

    private fun savePreferences() {
        val prefs = getSharedPreferences("BluetoothSyncPrefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("MAC_ADDRESS", macAddressInput.text.toString())
            putString("APP_ID", appIdInput.text.toString())
            putString("APP_KEY", appKeyInput.text.toString())
            putString("API_URL", apiUrlInput.text.toString())
            apply()
        }
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences("BluetoothSyncPrefs", Context.MODE_PRIVATE)
        macAddressInput.setText(prefs.getString("MAC_ADDRESS", ""))
        appIdInput.setText(prefs.getString("APP_ID", ""))
        appKeyInput.setText(prefs.getString("APP_KEY", ""))
        apiUrlInput.setText(prefs.getString("API_URL", ""))
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(statusUpdateReceiver)
        unregisterReceiver(rawDataReceiver)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                val intent = Intent(this, SettingsActivity::class.java)
                startActivity(intent)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}