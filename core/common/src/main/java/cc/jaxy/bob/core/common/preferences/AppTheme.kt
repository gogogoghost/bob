package cc.jaxy.bob.core.common.preferences

/**
 * App theme preference.
 *
 * [SYSTEM] follows the system dark mode; [LIGHT]/[DARK] force that mode
 * regardless of system.
 */
enum class AppTheme {
    SYSTEM,
    LIGHT,
    DARK,
    ;

    companion object {
        fun fromName(name: String?): AppTheme =
            entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}
