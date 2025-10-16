package com.example.bluetoothdatasync

import android.app.Application
import com.example.bluetoothdatasync.language.LanguageManager

class MyApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // 在应用启动时，初始化语言管理器
        LanguageManager.initialize(this)
    }
}
