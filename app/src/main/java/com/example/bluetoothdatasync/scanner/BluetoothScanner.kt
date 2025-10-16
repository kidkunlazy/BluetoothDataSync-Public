package com.example.bluetoothdatasync.scanner

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

@SuppressLint("MissingPermission")
class BluetoothScanner(context: Context) {

    // 定义回调接口，用于通知扫描结果
    interface ScannerListener {
        fun onScanResult(rawData: ByteArray)
        fun onScanFailed(errorCode: Int)
        fun onScanTimeout()
    }

    private val bluetoothAdapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val bleScanner by lazy { bluetoothAdapter?.bluetoothLeScanner }
    private val scanHandler = Handler(Looper.getMainLooper())

    private var isScanning = false
    private var listener: ScannerListener? = null

    companion object {
        private const val TAG = "BluetoothScanner"
        private const val SCAN_TIMEOUT_MS = 60000L // 扫描超时时间：60秒
    }

    /**
     * 启动蓝牙扫描。
     * @param targetMacAddress 目标设备的 MAC 地址
     * @param listener 回调监听器
     */
    fun startScan(targetMacAddress: String, listener: ScannerListener) {
        if (isScanning) {
            Log.w(TAG, "扫描已在进行中，请先停止")
            return
        }
        if (bluetoothAdapter?.isEnabled != true) {
            Log.w(TAG, "蓝牙未启用")
            listener.onScanFailed(-1) // 自定义错误码-1表示蓝牙关闭
            return
        }

        this.listener = listener
        val scanFilter = ScanFilter.Builder().setDeviceAddress(targetMacAddress).build()
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        isScanning = true
        bleScanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)
        Log.d(TAG, "蓝牙扫描已启动，目标: $targetMacAddress")

        // 设置超时任务
        scanHandler.postDelayed({
            if (isScanning) {
                stopScan()
                Log.w(TAG, "扫描超时")
                this.listener?.onScanTimeout()
            }
        }, SCAN_TIMEOUT_MS)
    }

    /**
     * 停止蓝牙扫描。
     */
    fun stopScan() {
        if (isScanning) {
            isScanning = false
            scanHandler.removeCallbacksAndMessages(null) // 移除超时回调
            try {
                bleScanner?.stopScan(scanCallback)
                Log.d(TAG, "蓝牙扫描已停止")
            } catch (e: Exception) {
                Log.e(TAG, "停止扫描时出错", e)
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            if (!isScanning) return
            stopScan() // 找到设备后立即停止扫描

            result?.scanRecord?.bytes?.let {
                listener?.onScanResult(it)
            } ?: listener?.onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR) // 数据为空
        }

        override fun onScanFailed(errorCode: Int) {
            if (!isScanning) return
            stopScan()
            Log.e(TAG, "蓝牙扫描失败，错误码: $errorCode")
            listener?.onScanFailed(errorCode)
        }
    }
}