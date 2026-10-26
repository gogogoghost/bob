package cc.jaxy.bob.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import cc.jaxy.bob.core.designsystem.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Localized relative time: now / N minutes/hours ago / yesterday / M-d / yyyy-M-d.
 *
 * Composable because minute/hour units come from plurals. Null returns "",
 * future timestamps render as now.
 */
@Composable
fun relativeTimeText(epochMillis: Long?): String {
    if (epochMillis == null) return ""
    val zone = ZoneId.systemDefault()
    val now = Instant.now()
    val target = Instant.ofEpochMilli(epochMillis)
    val diffMinutes = ChronoUnit.MINUTES.between(target, now)
    if (diffMinutes < 1) return stringResource(R.string.ds_relative_now)
    if (diffMinutes < 60) {
        return pluralStringResource(
            R.plurals.ds_relative_minutes_ago,
            diffMinutes.toInt(),
            diffMinutes,
        )
    }
    val today = LocalDate.now(zone)
    val day = target.atZone(zone).toLocalDate()
    if (day == today.minusDays(1)) return stringResource(R.string.ds_relative_yesterday)
    val diffHours = ChronoUnit.HOURS.between(target, now)
    if (diffHours < 24) {
        return pluralStringResource(
            R.plurals.ds_relative_hours_ago,
            diffHours.toInt(),
            diffHours,
        )
    }
    if (day.year == today.year) {
        return day.format(DateTimeFormatter.ofPattern("M-d"))
    }
    return day.format(DateTimeFormatter.ofPattern("yyyy-M-d"))
}
