package com.example.bluetoothdatasync

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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

    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.POST_NOTIFICATIONS
        )
    } else {
        arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.values.all { it }) {
                Toast.makeText(this, "所有权限已授予", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "部分权限被拒绝，功能可能受限", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        title = getString(R.string.app_name)
        checkAndRequestPermissions()
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

        startButton.setOnClickListener { startSyncService() }
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
        // UI 启动时也从 SharedPreferences 加载状态，但这部分逻辑可以简化或移除，因为广播会更新
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
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
