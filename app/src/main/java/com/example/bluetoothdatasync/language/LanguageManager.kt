package com.example.bluetoothdatasync.language

import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * 一个单例对象，用于管理应用的语言设置。
 */
object LanguageManager {

    private const val PREFS_NAME = "Settings"
    private const val PREF_KEY_LANGUAGE = "Language"

    /**
     * 在应用启动时调用，应用已保存的语言偏好。
     * @param context Application context.
     */
    fun initialize(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedLanguage = prefs.getString(PREF_KEY_LANGUAGE, "default") ?: "default"
        applyLocale(savedLanguage)
    }

    /**
     * 保存用户选择的语言偏好。
     * @param context Context to access SharedPreferences.
     * @param language The language code ("zh", "en") or "default".
     */
    fun saveLanguagePreference(context: Context, language: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_KEY_LANGUAGE, language).apply()
    }

    /**
     * 获取当前保存的语言设置。
     * @param context Context to access SharedPreferences.
     * @return The saved language code or "default".
     */
    fun getCurrentLanguage(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(PREF_KEY_LANGUAGE, "default") ?: "default"
    }

    /**
     * 应用语言更改并重启应用以使更改生效。
     * @param activity The current activity from which to restart the app.
     * @param language The new language code to apply.
     */
    fun applyLanguageChangeAndRestart(activity: AppCompatActivity, language: String) {
        applyLocale(language)

        // 重启应用以使所有界面都应用新语言
        val intent = activity.packageManager.getLaunchIntentForPackage(activity.packageName)
        intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent != null) {
            activity.startActivity(intent)
            activity.finishAffinity() // 关闭所有现有 Activity
        }
    }

    /**
     * 核心逻辑：将指定的语言应用到整个应用。
     * @param language The language code to apply.
     */
    private fun applyLocale(language: String) {
        val localeList = if (language == "default") {
            LocaleListCompat.getEmptyLocaleList() // 使用系统默认语言
        } else {
            LocaleListCompat.forLanguageTags(language)
        }
        AppCompatDelegate.setApplicationLocales(localeList)
    }
}
