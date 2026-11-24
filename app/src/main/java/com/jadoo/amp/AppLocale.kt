package com.jadoo.amp

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * Per-app language.
 *
 * Android 13+ owns the setting: [LocaleManager] stores it, shows it under
 * Settings > Apps > JadOO DSP > Language, and recreates the activity. The
 * languages offered there come from `res/xml/locales_config.xml`.
 *
 * Android 9-12 (minSdk is 28) have no such API, so the choice is kept in
 * SharedPreferences and applied by wrapping the base context
 * ([wrap], called from every Activity and Service `attachBaseContext`).
 *
 * `null` always means "follow the system language".
 */
object AppLocale {
    /** Language tags that have a `values-xx/` folder. Keep in sync with locales_config.xml. */
    val SUPPORTED = listOf("en", "de", "fr", "pl", "zh-CN")

    private const val PREFS = "jadoo_locale"
    private const val KEY = "tag"

    /** The language the user picked, or null for the system default. */
    fun selectedTag(context: Context): String? {
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val list = context.getSystemService(LocaleManager::class.java)?.applicationLocales
            if (list == null || list.isEmpty) null else list[0].language.let { lang -> SUPPORTED.firstOrNull { it.substringBefore('-') == lang } }
        } else {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        }
        return raw?.takeIf { it in SUPPORTED }
    }

    /**
     * The supported language the phone's own language list resolves to: the
     * first phone language JadOO is translated into, else English (the
     * `values/` fallback). This is what "Follow system" shows.
     */
    fun systemTag(context: Context): String {
        val phone = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)?.systemLocales
        } else null
        val list = phone ?: android.content.res.Resources.getSystem().configuration.locales
        for (i in 0 until list.size()) {
            val lang = list[i].language
            SUPPORTED.firstOrNull { it.substringBefore('-') == lang }?.let { return it }
        }
        return "en"
    }

    /**
     * Applies [tag] (null = system default). On Android 13+ the system
     * recreates the activity; on older versions it is recreated here.
     */
    fun select(activity: Activity, tag: String?) {
        val safe = tag?.takeIf { it in SUPPORTED }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (safe == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(safe)
        } else {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .apply { if (safe == null) remove(KEY) else putString(KEY, safe) }
                .apply()
            activity.recreate()
        }
    }

    /**
     * Base-context wrapper for Android 9-12. A no-op on 13+ (the system does
     * it) and when the user follows the system language. Call from
     * `attachBaseContext` of every Activity and Service that shows text.
     */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?.takeIf { it in SUPPORTED } ?: return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }
}
