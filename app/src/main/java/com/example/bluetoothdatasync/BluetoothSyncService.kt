package com.example.bluetoothdatasync

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.example.bluetoothdatasync.api.ApiClient
import com.example.bluetoothdatasync.manager.ConfigurationManager
import com.example.bluetoothdatasync.manager.ServiceStateManager
import com.example.bluetoothdatasync.notification.NotificationHandler
import com.example.bluetoothdatasync.scanner.BluetoothScanner
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

// 关键修复：恢复了 toHexString() 函数的正确实现
fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

@SuppressLint("MissingPermission")
class BluetoothSyncService : Service(), BluetoothScanner.ScannerListener, ApiClient.ApiCallback {

    private val configManager: ConfigurationManager by lazy { ConfigurationManager(this) }
    private val stateManager: ServiceStateManager by lazy { ServiceStateManager(this) }
    private val notificationHandler: NotificationHandler by lazy { NotificationHandler(this) }
    private val bluetoothScanner: BluetoothScanner by lazy { BluetoothScanner(this) }
    private val apiClient: ApiClient by lazy { ApiClient() }

    private lateinit var wakeLock: PowerManager.WakeLock

    companion object {
        var isRunning = false
        private const val TAG = "BluetoothSyncService"
        private const val SCAN_PERIOD_MINUTES = 3L

        const val ACTION_START_SERVICE = "ACTION_START_SERVICE"
        const val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"
        const val ACTION_PERFORM_SCAN = "ACTION_PERFORM_SCAN"
        const val ACTION_RAW_DATA_RECEIVED = "com.example.bluetoothdatasync.RAW_DATA_RECEIVED"
        const val EXTRA_RAW_DATA = "EXTRA_RAW_DATA"
        const val ACTION_STATUS_UPDATE = "com.example.bluetoothdatasync.STATUS_UPDATE"
        const val EXTRA_NEXT_SCAN_TIME = "EXTRA_NEXT_SCAN_TIME"
        const val EXTRA_STATUS_RES_ID = "EXTRA_STATUS_RES_ID"
        const val EXTRA_LAST_RESULT_RES_ID = "EXTRA_LAST_RESULT_RES_ID"
        const val EXTRA_LAST_RESULT_ARG = "EXTRA_LAST_RESULT_ARG"
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BluetoothSync::WakeLock")
        wakeLock.setReferenceCounted(false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SERVICE -> handleStartService(intent)
            ACTION_PERFORM_SCAN -> handlePerformScan()
            ACTION_STOP_SERVICE -> handleStopService()
        }
        return START_STICKY
    }

    private fun handleStartService(intent: Intent?) {
        configManager.saveFromIntent(intent)
        notificationHandler.createNotificationChannel()
        stateManager.updateAndBroadcast(statusResId = R.string.status_enabled)
        val notification = notificationHandler.buildNotification(
            stateManager.statusResId, stateManager.lastResultResId, stateManager.lastResultArg, stateManager.nextScanTimestamp
        )
        startForeground(NotificationHandler.NOTIFICATION_ID, notification)
        scheduleNextScan(isImmediate = true)
    }

    private fun handleStopService() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(getScanPendingIntent())
        stopForeground(true)
        stopSelf()
    }

    private fun handlePerformScan() {
        if (!isRunning) return
        acquireWakeLock()
        stateManager.updateAndBroadcast(statusResId = R.string.status_scanning)
        updateNotification()
        bluetoothScanner.startScan(configManager.targetMacAddress, this)
    }

    override fun onScanResult(rawData: ByteArray) {
        stateManager.updateAndBroadcast(statusResId = R.string.status_sending)
        updateNotification()
        val rawDataHex = rawData.toHexString()
        sendBroadcast(Intent(ACTION_RAW_DATA_RECEIVED).putExtra(EXTRA_RAW_DATA, rawDataHex))
        apiClient.sendData(
            configManager.apiUrl, configManager.appId, configManager.appKey,
            configManager.targetMacAddress, rawDataHex, this
        )
    }

    override fun onScanFailed(errorCode: Int) {
        if (errorCode == -1) {
            stateManager.updateAndBroadcast(lastResultResId = R.string.result_bluetooth_off, clearLastResultArg = true)
        } else {
            stateManager.updateAndBroadcast(lastResultResId = R.string.result_scan_error, lastResultArg = errorCode.toString())
        }
        scheduleNextScan()
    }

    override fun onScanTimeout() {
        stateManager.updateAndBroadcast(lastResultResId = R.string.result_scan_timeout, clearLastResultArg = true)
        scheduleNextScan()
    }

    override fun onSuccess(response: Response) {
        val code = response.code.toString()
        if (response.isSuccessful) {
            stateManager.updateAndBroadcast(lastResultResId = R.string.result_success, lastResultArg = code)
        } else {
            stateManager.updateAndBroadcast(lastResultResId = R.string.result_server_error, lastResultArg = code)
        }
        scheduleNextScan()
    }

    override fun onFailure(e: IOException) {
        stateManager.updateAndBroadcast(lastResultResId = R.string.result_network_error, clearLastResultArg = true)
        scheduleNextScan()
    }

    private fun scheduleNextScan(isImmediate: Boolean = false) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val delay = if (isImmediate) 0 else TimeUnit.MINUTES.toMillis(SCAN_PERIOD_MINUTES)
        val triggerAtMillis = SystemClock.elapsedRealtime() + delay
        val nextScanTime = System.currentTimeMillis() + delay

        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, getScanPendingIntent()
        )

        if (!isImmediate) {
            stateManager.updateAndBroadcast(statusResId = R.string.status_waiting, nextScanTime = nextScanTime)
            updateNotification()
            releaseWakeLock()
        } else {
            stateManager.updateAndBroadcast(nextScanTime = nextScanTime)
        }
    }

    private fun getScanPendingIntent(): PendingIntent {
        val intent = Intent(this, BluetoothSyncService::class.java).apply { action = ACTION_PERFORM_SCAN }
        return PendingIntent.getService(this, 1001, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun updateNotification() {
        val notification = notificationHandler.buildNotification(
            stateManager.statusResId,
            stateManager.lastResultResId,
            stateManager.lastResultArg,
            stateManager.nextScanTimestamp
        )
        notificationHandler.updateNotification(notification)
    }

    private fun acquireWakeLock() {
        if (!wakeLock.isHeld) wakeLock.acquire(TimeUnit.MINUTES.toMillis(2))
    }

    private fun releaseWakeLock() {
        if (wakeLock.isHeld) wakeLock.release()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        bluetoothScanner.stopScan()
        releaseWakeLock()
        stateManager.updateAndBroadcast(
            statusResId = R.string.status_stopped,
            lastResultResId = R.string.result_none,
            clearLastResultArg = true,
            nextScanTime = 0L
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
