package cc.jaxy.bob.core.common.model

/**
 * Cross-feature model-pick payload. Features must not depend on each other,
 * so the format/parse helpers live here as pure functions.
 *
 * Format: "providerId\nmodelId\ndisplayName" (providerId/displayName may be empty).
 */
data class PickedModel(
    val providerId: String? = null,
    val modelId: String = "",
    val displayName: String? = null,
)

const val PICKED_MODEL_KEY = "picked_model"

fun formatPickedModel(providerId: String?, modelId: String, displayName: String?): String =
    "${providerId.orEmpty()}\n${modelId}\n${displayName.orEmpty()}"

fun parsePickedModel(raw: String?): PickedModel? {
    if (raw == null) return null
    val parts = raw.split("\n")
    if (parts.size != 3) return null
    val modelId = parts[1]
    if (modelId.isBlank()) return null
    return PickedModel(
        providerId = parts[0].takeIf { it.isNotBlank() },
        modelId = modelId,
        displayName = parts[2].takeIf { it.isNotBlank() },
    )
}
