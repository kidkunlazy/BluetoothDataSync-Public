package com.example.bluetoothdatasync

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

@SuppressLint("MissingPermission")
class BluetoothSyncService : Service() {

    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var targetMacAddress: String
    private lateinit var appId: String
    private lateinit var appKey: String
    private lateinit var apiUrl: String

    private val httpClient = OkHttpClient()
    private val scanHandler = Handler(Looper.getMainLooper())
    private val bluetoothAdapter: BluetoothAdapter by lazy {
        (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }
    private val bleScanner by lazy { bluetoothAdapter.bluetoothLeScanner }
    private var isScanning = false

    private var serviceStatus: String = "已关闭"
    private var lastSendMessage: String = "无"
    private var nextScanTimestamp: Long = 0L

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "BluetoothSyncChannel"
        const val NOTIFICATION_ID = 1
        var isRunning = false
        private const val TAG = "BluetoothSyncService"
        private const val SCAN_PERIOD_MINUTES = 3L

        const val ACTION_START_SERVICE = "ACTION_START_SERVICE"
        const val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"
        const val ACTION_PERFORM_SCAN = "ACTION_PERFORM_SCAN"

        const val ACTION_RAW_DATA_RECEIVED = "com.example.bluetoothdatasync.RAW_DATA_RECEIVED"
        const val EXTRA_RAW_DATA = "EXTRA_RAW_DATA"
        const val ACTION_STATUS_UPDATE = "com.example.bluetoothdatasync.STATUS_UPDATE"
        const val EXTRA_STATUS = "EXTRA_STATUS"
        const val EXTRA_LAST_RESULT = "EXTRA_LAST_RESULT"
        const val EXTRA_NEXT_SCAN_TIME = "EXTRA_NEXT_SCAN_TIME"
    }

    @SuppressLint("WakelockTimeout")
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "服务实例创建")
        isRunning = true

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BluetoothSync::WakeLock")
        wakeLock.setReferenceCounted(false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_SERVICE
        Log.d(TAG, "服务收到命令: $action")

        loadPreferences()

        when (action) {
            ACTION_START_SERVICE -> handleStartService(intent)
            ACTION_PERFORM_SCAN -> handlePerformScan()
            ACTION_STOP_SERVICE -> handleStopService()
        }

        return START_STICKY
    }

    private fun handleStartService(intent: Intent?) {
        serviceStatus = "已启用"
        savePreferencesFromIntent(intent)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        scheduleNextScan(isImmediate = true)
    }

    private fun handleStopService() {
        Log.d(TAG, "正在停止服务并取消所有计划任务。")
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(getScanPendingIntent())
        stopForeground(true)
        stopSelf()
    }

    private fun handlePerformScan() {
        if (!isRunning) {
            Log.w(TAG, "服务已被标记为停止，取消本次扫描任务。")
            return
        }
        if (!wakeLock.isHeld) {
            wakeLock.acquire(TimeUnit.MINUTES.toMillis(2))
            Log.d(TAG, "WakeLock已获取，防止CPU休眠。")
        }
        serviceStatus = "正在扫描设备..."
        updateStatusAndBroadcast()
        startBleScan()
    }

    private fun scheduleNextScan(isImmediate: Boolean = false) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val delay = if (isImmediate) 0 else TimeUnit.MINUTES.toMillis(SCAN_PERIOD_MINUTES)
        val triggerAtMillis = SystemClock.elapsedRealtime() + delay

        nextScanTimestamp = System.currentTimeMillis() + delay

        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            triggerAtMillis,
            getScanPendingIntent()
        )

        Log.d(TAG, "下一次扫描任务已安排在: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(nextScanTimestamp))}")

        if (!isImmediate) {
            onTaskFinished()
        }
    }

    private fun getScanPendingIntent(): PendingIntent {
        val intent = Intent(this, BluetoothSyncService::class.java).apply { action = ACTION_PERFORM_SCAN }
        return PendingIntent.getService(this, 1001, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun onTaskFinished() {
        serviceStatus = "等待下次扫描"
        updateStatusAndBroadcast()
        releaseWakeLockAfterTask()
    }

    private fun releaseWakeLockAfterTask() {
        if (::wakeLock.isInitialized && wakeLock.isHeld) {
            wakeLock.release()
            Log.d(TAG, "任务完成，WakeLock已释放。")
        }
    }

    private fun startBleScan() {
        if (isScanning) {
            Log.w(TAG, "扫描状态异常(isScanning=true)，强制停止旧扫描...")
            stopBleScan()
        }

        if (!bluetoothAdapter.isEnabled) {
            Log.w(TAG, "跳过本次任务: 蓝牙已关闭")
            lastSendMessage = "跳过: 蓝牙已关闭"
            scheduleNextScan()
            return
        }

        val scanFilter = ScanFilter.Builder().setDeviceAddress(targetMacAddress).build()
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        isScanning = true
        bleScanner.startScan(listOf(scanFilter), scanSettings, scanCallback)
        Log.d(TAG, "蓝牙扫描已启动，目标: $targetMacAddress")

        val SCAN_TIMEOUT_MS = 60000L
        scanHandler.postDelayed({
            if (isScanning) {
                Log.w(TAG, "扫描超时，未找到设备。")
                stopBleScan()
                lastSendMessage = "失败: 扫描超时"
                scheduleNextScan()
            }
        }, SCAN_TIMEOUT_MS)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            if (!isScanning) return
            result?.let {
                stopBleScan()
                val rawData = it.scanRecord?.bytes
                if (rawData != null) {
                    serviceStatus = "正在发送数据..."
                    updateStatusAndBroadcast()
                    val rawDataHex = rawData.toHexString()
                    val intent = Intent(ACTION_RAW_DATA_RECEIVED).apply { putExtra(EXTRA_RAW_DATA, rawDataHex) }
                    sendBroadcast(intent)
                    sendDataToServer(rawDataHex)
                } else {
                    lastSendMessage = "失败: 广播数据为空"
                    scheduleNextScan()
                }
            }
        }
        override fun onScanFailed(errorCode: Int) {
            if (!isScanning) return
            Log.e(TAG, "蓝牙扫描失败，错误码: $errorCode")
            stopBleScan()
            lastSendMessage = "失败: 蓝牙扫描错误 ($errorCode)"
            scheduleNextScan()
        }
    }

    private fun sendDataToServer(rawDataHex: String) {
        try {
            val jsonObject = JSONObject().apply {
                put("mac", targetMacAddress)
                put("data", rawDataHex)
            }
            val requestBody = jsonObject.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder().url(apiUrl).addHeader("X-LC-Id", appId).addHeader("X-LC-Key", appKey).addHeader("Content-Type", "application/json").post(requestBody).build()

            httpClient.newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) {
                    Log.e(TAG, "数据发送失败", e)
                    lastSendMessage = "失败: 网络请求错误 (${e.message})"
                    scheduleNextScan()
                }
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    val responseBody = response.body?.string()
                    lastSendMessage = if (response.isSuccessful) "成功 (HTTP ${response.code})" else "失败: 服务器错误 (HTTP ${response.code})"
                    Log.d(TAG, "服务器响应: $responseBody")
                    response.close()
                    scheduleNextScan()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "构建或发送请求时发生异常", e)
            lastSendMessage = "失败：程序异常 (${e.message})"
            scheduleNextScan()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        Log.d(TAG, "服务实例销毁")
        scanHandler.removeCallbacksAndMessages(null)
        stopBleScan()
        releaseWakeLockAfterTask()

        serviceStatus = "已关闭"
        lastSendMessage = "无"
        nextScanTimestamp = 0
        updateStatusAndBroadcast()
    }

    private fun stopBleScan() {
        if (isScanning) {
            isScanning = false
            try {
                bleScanner.stopScan(scanCallback)
                Log.d(TAG, "蓝牙扫描已停止")
            } catch (e: Exception) {
                Log.e(TAG, "停止蓝牙扫描时出错", e)
            }
        }
    }

    private fun buildNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val nextScanTimeFormatted = if (nextScanTimestamp == 0L || !isRunning) "无" else SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(nextScanTimestamp))
        val style = NotificationCompat.BigTextStyle()
            .setBigContentTitle("蓝牙数据同步服务")
            .bigText("""
                服务状态: $serviceStatus
                上次发送: $lastSendMessage
                下次扫描: $nextScanTimeFormatted
                """.trimIndent())
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("蓝牙同步服务运行中")
            .setContentText("状态: $serviceStatus")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setStyle(style)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateStatusAndBroadcast() {
        updateNotification()
        saveStatusToPreferences()
        sendStatusBroadcast()
    }

    private fun updateNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(NOTIFICATION_CHANNEL_ID, "蓝牙同步服务通道", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(serviceChannel)
        }
    }

    private fun savePreferencesFromIntent(intent: Intent?) {
        val prefs = getSharedPreferences("BluetoothSyncPrefs", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        intent?.getStringExtra("MAC_ADDRESS")?.let { editor.putString("MAC_ADDRESS", it) }
        intent?.getStringExtra("APP_ID")?.let { editor.putString("APP_ID", it) }
        intent?.getStringExtra("APP_KEY")?.let { editor.putString("APP_KEY", it) }
        intent?.getStringExtra("API_URL")?.let { editor.putString("API_URL", it) }
        editor.apply()
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences("BluetoothSyncPrefs", Context.MODE_PRIVATE)
        targetMacAddress = prefs.getString("MAC_ADDRESS", "") ?: ""
        appId = prefs.getString("APP_ID", "") ?: ""
        appKey = prefs.getString("APP_KEY", "") ?: ""
        apiUrl = prefs.getString("API_URL", "") ?: ""
    }

    private fun saveStatusToPreferences() {
        val prefs = getSharedPreferences("BluetoothSyncPrefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("serviceStatus", serviceStatus)
            putString("lastSendMessage", lastSendMessage)
            putLong("nextScanTimestamp", nextScanTimestamp)
            apply()
        }
    }

    private fun sendStatusBroadcast() {
        val intent = Intent(ACTION_STATUS_UPDATE).apply {
            putExtra(EXTRA_STATUS, serviceStatus)
            putExtra(EXTRA_LAST_RESULT, lastSendMessage)
            putExtra(EXTRA_NEXT_SCAN_TIME, nextScanTimestamp)
        }
        sendBroadcast(intent)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}