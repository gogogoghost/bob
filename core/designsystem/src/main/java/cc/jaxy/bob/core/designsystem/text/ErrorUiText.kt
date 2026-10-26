package cc.jaxy.bob.core.designsystem.text

import cc.jaxy.bob.core.common.result.AnError
import cc.jaxy.bob.core.designsystem.R

/**
 * Maps [AnError] to user-facing localized text.
 *
 * Rules:
 * - NETWORK → unreachable
 * - HTTP_401/403 and AUTH → invalid credentials
 * - other HTTP → 4xx/5xx specific messages
 * - TRPC_UNAUTHORIZED → session expired, other TRPC → service failed
 * - INVALID_INPUT → generic invalid input (raw English message never shown)
 * - else → non-blank message passthrough, unknown fallback
 */
fun AnError.toUiText(): UiText {
    if (code == "NETWORK") {
        return UiText.Res(R.string.error_network_unreachable)
    }
    if (code == "AUTH") {
        return UiText.Res(R.string.error_auth_invalid_credentials)
    }
    if (code.startsWith("HTTP_")) {
        val status = code.removePrefix("HTTP_").toIntOrNull()
        return when {
            status == 401 || status == 403 ->
                UiText.Res(R.string.error_auth_invalid_credentials)
            status == null -> UiText.Res(R.string.error_http_no_status)
            status in 400..499 -> UiText.Res(R.string.error_http_client_format, status)
            else -> UiText.Res(R.string.error_http_server_format, status)
        }
    }
    if (code.startsWith("TRPC_")) {
        if ("UNAUTHORIZED" in code) {
            return UiText.Res(R.string.error_session_expired)
        }
        return UiText.Res(R.string.error_service_failed)
    }
    if (code == "INVALID_INPUT") {
        return UiText.Res(R.string.error_invalid_input)
    }
    return UiText.Raw(message.ifBlank { "unknown error" })
}
