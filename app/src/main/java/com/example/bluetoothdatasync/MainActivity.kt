package com.example.bluetoothdatasync

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.bluetoothdatasync.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private var isDialogShowing = false

    private val rawDataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothSyncService.ACTION_RAW_DATA_RECEIVED) {
                val rawData = intent.getStringExtra(BluetoothSyncService.EXTRA_RAW_DATA)
                binding.tvRawData.text = rawData ?: "未接收到数据"
            }
        }
    }

    private val statusUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothSyncService.ACTION_STATUS_UPDATE) {
                updateUiFromIntent(intent)
            }
        }
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    private val standardPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("BluetoothSyncPrefs", Context.MODE_PRIVATE)
        loadPreferences()

        binding.switchService.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (checkAllConditions()) {
                    startSyncService()
                } else {
                    binding.switchService.isChecked = false
                }
            } else {
                stopSyncService()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        binding.switchService.isChecked = BluetoothSyncService.isRunning
        updateUiFromPreferences()

        val rawDataFilter = IntentFilter(BluetoothSyncService.ACTION_RAW_DATA_RECEIVED)
        val statusFilter = IntentFilter(BluetoothSyncService.ACTION_STATUS_UPDATE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(rawDataReceiver, rawDataFilter, RECEIVER_NOT_EXPORTED)
            registerReceiver(statusUpdateReceiver, statusFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(rawDataReceiver, rawDataFilter)
            registerReceiver(statusUpdateReceiver, statusFilter)
        }

        checkAllConditions()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(rawDataReceiver)
        unregisterReceiver(statusUpdateReceiver)
    }

    private fun updateUiFromPreferences() {
        val status = prefs.getString("serviceStatus", if(BluetoothSyncService.isRunning) "已启用" else "已关闭")
        val lastResult = prefs.getString("lastSendMessage", "无")
        val nextScanTime = prefs.getLong("nextScanTimestamp", 0L)

        binding.tvServiceStatus.text = status
        binding.tvLastResult.text = lastResult
        binding.tvNextScanTime.text = if (nextScanTime == 0L || !BluetoothSyncService.isRunning) {
            "无"
        } else {
            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(nextScanTime))
        }
    }

    private fun updateUiFromIntent(intent: Intent) {
        val status = intent.getStringExtra(BluetoothSyncService.EXTRA_STATUS)
        val lastResult = intent.getStringExtra(BluetoothSyncService.EXTRA_LAST_RESULT)
        val nextScanTime = intent.getLongExtra(BluetoothSyncService.EXTRA_NEXT_SCAN_TIME, 0L)

        binding.tvServiceStatus.text = status
        binding.tvLastResult.text = lastResult
        binding.tvNextScanTime.text = if (nextScanTime == 0L) {
            "无"
        } else {
            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(nextScanTime))
        }
    }

    private fun checkAllConditions(): Boolean {
        if (!areSystemServicesEnabled()) return false
        if (!hasStandardPermissions()) {
            requestStandardPermissionsLauncher.launch(standardPermissions)
            return false
        }
        if (!hasBackgroundLocationPermission()) {
            showDialog(
                "需要后台定位权限",
                "为了确保App在后台也能持续扫描蓝牙设备，请在接下来的设置页面中，将位置权限修改为“始终允许”。",
                "REQUEST_BACKGROUND_LOCATION"
            )
            return false
        }
        return true
    }

    private fun hasStandardPermissions(): Boolean {
        return standardPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasBackgroundLocationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private val requestStandardPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.entries.all { it.value }) {
                checkAllConditions()
            } else {
                Toast.makeText(this, "必须授予蓝牙和前台定位权限才能使用", Toast.LENGTH_LONG).show()
            }
        }

    private val requestBackgroundLocationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                Toast.makeText(this, "后台定位权限已授予！", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "未授予后台定位权限，后台扫描功能可能受限", Toast.LENGTH_LONG).show()
            }
        }

    private fun areSystemServicesEnabled(): Boolean {
        if (bluetoothAdapter?.isEnabled == false) {
            showDialog("蓝牙未开启", "本应用需要开启蓝牙才能扫描设备。", BluetoothAdapter.ACTION_REQUEST_ENABLE)
            return false
        }
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) &&
            !locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            showDialog("位置服务未开启", "为了扫描到蓝牙设备，安卓系统要求开启位置服务。", Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            return false
        }
        return true
    }

    private fun showDialog(title: String, message: String, action: String) {
        if (isDialogShowing) return
        isDialogShowing = true

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("好的") { dialog, _ ->
                when (action) {
                    "REQUEST_BACKGROUND_LOCATION" -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            // 在安卓11及以上，系统会直接引导至设置页
                            // 在安卓10，会弹出系统对话框
                            requestBackgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        }
                    }
                    else -> {
                        val intent = Intent(action)
                        if (action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS) {
                            intent.data = Uri.fromParts("package", packageName, null)
                        }
                        startActivity(intent)
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton("取消") { dialog, _ -> dialog.dismiss() }
            .setOnDismissListener { isDialogShowing = false }
            .show()
    }

    private fun startSyncService() {
        val macAddress = binding.etMacAddress.text.toString().uppercase()
        val appId = binding.etAppId.text.toString()
        val appKey = binding.etAppKey.text.toString()
        val apiUrl = binding.etApiUrl.text.toString()

        if (macAddress.isBlank() || appId.isBlank() || appKey.isBlank() || apiUrl.isBlank()) {
            Toast.makeText(this, "所有输入框都不能为空", Toast.LENGTH_SHORT).show()
            binding.switchService.isChecked = false
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
        Toast.makeText(this, "同步服务已启动", Toast.LENGTH_SHORT).show()
    }

    private fun stopSyncService() {
        val serviceIntent = Intent(this, BluetoothSyncService::class.java).apply {
            action = BluetoothSyncService.ACTION_STOP_SERVICE
        }
        startService(serviceIntent)
        Toast.makeText(this, "同步服务已停止", Toast.LENGTH_SHORT).show()
    }

    private fun savePreferences() {
        prefs.edit().apply {
            putString("MAC_ADDRESS", binding.etMacAddress.text.toString())
            putString("APP_ID", binding.etAppId.text.toString())
            putString("APP_KEY", binding.etAppKey.text.toString())
            putString("API_URL", binding.etApiUrl.text.toString())
            apply()
        }
    }

    private fun loadPreferences() {
        binding.etMacAddress.setText(prefs.getString("MAC_ADDRESS", ""))
        binding.etAppId.setText(prefs.getString("APP_ID", ""))
        binding.etAppKey.setText(prefs.getString("APP_KEY", ""))
        binding.etApiUrl.setText(prefs.getString("API_URL", ""))
    }
}