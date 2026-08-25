//
// LanguageManager.kt
// In-app language picker (1.2.0). Port of the PGPony A14 Picker phase, with
// the same shape so the two apps stay maintainable together:
//
//   iOS:     LanguageManager.set() -> AppleLanguages + a Bundle override
//   Android: AppCompatDelegate.setApplicationLocales(LocaleListCompat...)
//
// AppCompat owns persistence end to end, and we deliberately do NOT keep a
// second copy in SharedPreferences:
//   * API 33+ : the system stores the per-app language. The user can also
//     change it from Settings -> System -> Languages -> App languages, which
//     would silently diverge from any private cache we kept.
//   * API 24-32 : AppCompat persists through AppLocalesMetadataHolderService
//     (declared in AndroidManifest.xml). On those API levels only
//     AppCompatActivity instances auto-recreate on a locale change, which is
//     why MainActivity extends AppCompatActivity as of this release and why
//     Theme.BurnPony was reparented to a Theme.AppCompat parent.
//
// AppCompatDelegate.getApplicationLocales() is the single source of truth.
// LanguageState is only a fast, observable mirror so the picker's checkmark
// moves on the same frame as the tap, before the recreate cycle completes.
//

package com.burnpony.app.i18n

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * The nine languages BurnPony ships strings for. [tag] is the BCP-47 tag
 * AppCompat and the resource loader use.
 *
 * Note the two folder-name mismatches: BCP-47 "pt-BR" maps to the legacy
 * resource folder values-pt-rBR, and "zh-CN" to values-zh-rCN.
 * LocaleListCompat normalizes both, so BCP-47 is used throughout the Kotlin
 * layer and the resource loader bridges to its own folder convention.
 */
enum class SupportedLanguage(val tag: String, val nativeName: String) {
    EN("en", "English"),
    DE("de", "Deutsch"),
    ES("es", "Español"),
    FR("fr", "Français"),
    IT("it", "Italiano"),
    JA("ja", "日本語"),
    PT_BR("pt-BR", "Português (Brasil)"),
    RU("ru", "Русский"),
    ZH_CN("zh-CN", "简体中文");

    companion object {
        /**
         * Resolve a language tag to one of the nine, snapping region
         * variants to the closest entry: de-AT to DE, es-419 to ES,
         * fr-CA to FR, pt-PT to PT_BR (Brazil is our only Portuguese),
         * zh-Hant/zh-TW to ZH_CN (Simplified is our only Chinese — an
         * imperfect but deliberate snap; Traditional readers get
         * Simplified rather than English). Returns null on no match.
         */
        fun resolve(tag: String?): SupportedLanguage? {
            if (tag.isNullOrBlank()) return null
            val normalized = tag.replace('_', '-')
            entries.firstOrNull { it.tag.equals(normalized, ignoreCase = true) }?.let { return it }
            return when (normalized.substringBefore('-').lowercase()) {
                "en" -> EN
                "de" -> DE
                "es" -> ES
                "fr" -> FR
                "it" -> IT
                "ja" -> JA
                "pt" -> PT_BR
                "ru" -> RU
                "zh" -> ZH_CN
                else -> null
            }
        }
    }
}

/** Process-wide observable mirror of the applied language tag. */
object LanguageState {
    val current: MutableState<String> = mutableStateOf(SupportedLanguage.EN.tag)

    /**
     * Idempotent bootstrap from AppCompat's persisted list. Safe to call
     * from BurnPonyApp.onCreate.
     *
     * With no persisted choice (fresh install) we only DETECT and mirror —
     * we do not call setApplicationLocales here. Doing so from onCreate
     * triggers an Activity recreate before the first Activity has reached
     * setContent on some API levels, and it is unnecessary: Android has
     * already loaded values-XX from the system locale, so the detected
     * language is what the user is looking at either way.
     */
    fun initFromAppCompat(context: Context) {
        val persisted = AppCompatDelegate.getApplicationLocales()
        val tag = (0 until persisted.size())
            .asSequence()
            .mapNotNull { persisted.get(it)?.toLanguageTag() }
            .firstOrNull()
        current.value = if (tag != null) {
            (SupportedLanguage.resolve(tag) ?: SupportedLanguage.EN).tag
        } else {
            LanguageManager.detectInitialLanguage(context).tag
        }
    }
}

object LanguageManager {

    /** Closest supported language to the device's preferred locales. */
    fun detectInitialLanguage(context: Context): SupportedLanguage {
        val configLocales = context.resources.configuration.locales
        for (i in 0 until configLocales.size()) {
            val locale: Locale = configLocales.get(i)
            SupportedLanguage.resolve(locale.toLanguageTag())?.let { return it }
        }
        return SupportedLanguage.EN
    }

    /**
     * Apply [lang]. AppCompat handles persistence, resource-cache
     * invalidation and Activity recreation; LanguageState is updated first
     * so a subscribed Composable repaints before the recreate lands.
     */
    fun setLanguage(lang: SupportedLanguage) {
        LanguageState.current.value = lang.tag
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(lang.tag))
    }

    fun current(): SupportedLanguage =
        SupportedLanguage.resolve(LanguageState.current.value) ?: SupportedLanguage.EN
}
