package com.example.bluetoothdatasync

import android.os.Bundle
import android.widget.Button
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import com.example.bluetoothdatasync.language.LanguageManager
import android.content.Intent
import android.widget.TextView
import android.net.Uri

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        title = getString(R.string.title_activity_settings)

        val languageRadioGroup: RadioGroup = findViewById(R.id.languageRadioGroup)
        val saveButton: Button = findViewById(R.id.saveButton)

        // 使用 LanguageManager 加载当前设置
        loadCurrentLanguageSetting(languageRadioGroup)

        val faqTextView: TextView = findViewById(R.id.faqTextView)
        faqTextView.setOnClickListener {
            val intent = Intent(this, FaqActivity::class.java)
            startActivity(intent)
        }

        val updateTextView: TextView = findViewById(R.id.updateTextView)
        updateTextView.setOnClickListener {
            // 定义你的项目地址
            val projectUrl = "https://github.com/kidkunlazy/BluetoothDataSync-Public"
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = Uri.parse(projectUrl)
            startActivity(intent)
        }

        saveButton.setOnClickListener {
            val selectedLanguage = when (languageRadioGroup.checkedRadioButtonId) {
                R.id.radioChinese -> "zh"
                R.id.radioEnglish -> "en"
                else -> "default"
            }
            // 使用 LanguageManager 保存并应用更改
            LanguageManager.saveLanguagePreference(this, selectedLanguage)
            LanguageManager.applyLanguageChangeAndRestart(this, selectedLanguage)
        }
    }

    private fun loadCurrentLanguageSetting(radioGroup: RadioGroup) {
        when (LanguageManager.getCurrentLanguage(this)) {
            "zh" -> radioGroup.check(R.id.radioChinese)
            "en" -> radioGroup.check(R.id.radioEnglish)
            else -> radioGroup.check(R.id.radioSystemDefault)
        }
    }
}
