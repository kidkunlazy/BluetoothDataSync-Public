package com.example.bluetoothdatasync.manager

import android.content.Context
import android.content.Intent
import com.example.bluetoothdatasync.R
// 关键修复：移除了 ".service"，确保从正确的主包导入
import com.example.bluetoothdatasync.BluetoothSyncService

class ServiceStateManager(private val context: Context) {

    // 保存资源 ID 而不是字符串
    var statusResId: Int = R.string.status_stopped
        private set
    var lastResultResId: Int = R.string.result_none
        private set
    var lastResultArg: String? = null // 用于格式化字符串的参数 (例如 HTTP code)
        private set
    var nextScanTimestamp: Long = 0L
        private set

    fun updateAndBroadcast(
        statusResId: Int? = null,
        lastResultResId: Int? = null,
        lastResultArg: String? = null,
        clearLastResultArg: Boolean = false,
        nextScanTime: Long? = null
    ) {
        statusResId?.let { this.statusResId = it }
        lastResultResId?.let { this.lastResultResId = it }
        lastResultArg?.let { this.lastResultArg = it }
        if (clearLastResultArg) {
            this.lastResultArg = null
        }
        nextScanTime?.let { this.nextScanTimestamp = it }

        broadcast()
    }

    private fun broadcast() {
        val intent = Intent(BluetoothSyncService.ACTION_STATUS_UPDATE).apply {
            // 广播中也传递资源 ID
            putExtra(BluetoothSyncService.EXTRA_STATUS_RES_ID, statusResId)
            putExtra(BluetoothSyncService.EXTRA_LAST_RESULT_RES_ID, lastResultResId)
            putExtra(BluetoothSyncService.EXTRA_LAST_RESULT_ARG, lastResultArg)
            putExtra(BluetoothSyncService.EXTRA_NEXT_SCAN_TIME, nextScanTimestamp)
        }
        context.sendBroadcast(intent)
    }
}
