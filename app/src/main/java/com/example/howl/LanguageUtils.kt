package com.example.howl

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import java.util.Locale

object LanguageUtils {
    private const val PREFERENCES_NAME = "language_preferences"
    private const val LANGUAGE_KEY = "selected_language"
    private const val LANGUAGE_ENGLISH = "en"
    private const val LANGUAGE_CHINESE = "zh"
    
    // 获取SharedPreferences实例
    private fun getSharedPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }
    
    // 保存选定的语言
    fun saveLanguage(context: Context, language: String) {
        getSharedPreferences(context).edit().putString(LANGUAGE_KEY, language).apply()
    }
    
    // 获取保存的语言，如果没有保存则返回设备默认语言
    fun getSavedLanguage(context: Context): String {
        // 优先从SharedPreferences获取语言设置，确保即使DataRepository未初始化也能正确加载
        val savedLanguage = getSharedPreferences(context).getString(LANGUAGE_KEY, null)
        if (savedLanguage != null) {
            return savedLanguage
        }
        
        // 如果SharedPreferences中没有保存，再尝试从DataRepository获取（如果已初始化）
        if (DataRepository.isInitialised) {
            val repositoryLanguage = DataRepository.miscOptionsState.value.language
            if (repositoryLanguage.isNotEmpty()) {
                return repositoryLanguage
            }
        }
        
        // 最后使用设备默认语言
        return when (Locale.getDefault().language) {
            LANGUAGE_CHINESE -> LANGUAGE_CHINESE
            else -> LANGUAGE_ENGLISH
        }
    }
    
    // 获取基于选定语言的Configuration
    fun getLanguageConfiguration(context: Context): Configuration {
        val configuration = context.resources.configuration
        val locale = when (getSavedLanguage(context)) {
            LANGUAGE_CHINESE -> Locale.CHINESE
            else -> Locale.ENGLISH
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Android 8.0及以上使用setLocale方法
            configuration.setLocale(locale)
        } else {
            // 旧版本使用locale属性
            configuration.locale = locale
        }
        
        return configuration
    }
    
    // 应用语言设置到Context
    fun applyLanguage(context: Context): Context {
        val configuration = getLanguageConfiguration(context)
        return context.createConfigurationContext(configuration)
    }
    
    // 获取当前是否使用中文
    fun isChinese(context: Context): Boolean {
        return getSavedLanguage(context) == LANGUAGE_CHINESE
    }
    
    // 获取语言选项列表
    fun getLanguageOptions(): List<Pair<String, String>> {
        return listOf(
            Pair(LANGUAGE_ENGLISH, "English"),
            Pair(LANGUAGE_CHINESE, "中文")
        )
    }
}