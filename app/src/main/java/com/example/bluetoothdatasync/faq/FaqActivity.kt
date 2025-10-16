package com.example.bluetoothdatasync

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class FaqActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_faq)

        // 添加返回按钮到标题栏
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.faq_title_short)
    }

    // 处理返回按钮的点击事件
    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}