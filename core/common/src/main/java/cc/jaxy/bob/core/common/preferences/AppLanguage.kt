package cc.jaxy.bob.core.common.preferences

/**
 * App language preference.
 *
 * [SYSTEM] follows the device locale; resources fall back to English for
 * unsupported locales. [CHINESE]/[ENGLISH] force that language via
 * per-app locales.
 */
enum class AppLanguage(val tag: String?) {
    SYSTEM(null),
    CHINESE("zh-CN"),
    ENGLISH("en"),
    ;

    companion object {
        fun fromTag(tag: String?): AppLanguage =
            entries.firstOrNull { it.tag == tag } ?: SYSTEM
    }
}
