package com.example.bluetoothdatasync.manager

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences

/**
 * 负责管理应用的配置信息。
 * 从 SharedPreferences 加载和保存配置。
 * @param context 上下文环境
 */
class ConfigurationManager(private val context: Context) {

    // 使用懒加载方式获取 SharedPreferences 实例
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("BluetoothSyncPrefs", Context.MODE_PRIVATE)
    }

    // 公开的配置属性
    var targetMacAddress: String = ""
        private set
    var appId: String = ""
        private set
    var appKey: String = ""
        private set
    var apiUrl: String = ""
        private set

    init {
        // 类初始化时自动加载配置
        load()
    }

    /**
     * 从 SharedPreferences 加载配置到内存中。
     */
    fun load() {
        targetMacAddress = prefs.getString("MAC_ADDRESS", "") ?: ""
        appId = prefs.getString("APP_ID", "") ?: ""
        appKey = prefs.getString("APP_KEY", "") ?: ""
        apiUrl = prefs.getString("API_URL", "") ?: ""
    }

    /**
     * 从启动服务的 Intent 中提取配置信息并保存。
     * @param intent 启动服务时传递的 Intent
     */
    fun saveFromIntent(intent: Intent?) {
        intent ?: return
        prefs.edit().apply {
            intent.getStringExtra("MAC_ADDRESS")?.let { putString("MAC_ADDRESS", it) }
            intent.getStringExtra("APP_ID")?.let { putString("APP_ID", it) }
            intent.getStringExtra("APP_KEY")?.let { putString("APP_KEY", it) }
            intent.getStringExtra("API_URL")?.let { putString("API_URL", it) }
            apply()
        }
        // 保存后立即重新加载，确保内存中的配置是最新的
        load()
    }
}
