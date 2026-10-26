package cc.jaxy.bob.core.common.util

/**
 * Local provider-form overrides for built-in provider metadata.
 * Labels and descriptions are resolved by the settings UI from localized resources.
 */

/** String-keyed field so UI can localize without touching the spec. */
data class SpecialField(
    val key: String,
    val labelKey: String,
    val placeholderKey: String,
    val descKey: String,
    val type: FieldType = FieldType.TEXT,
    val options: List<String> = emptyList(),
)

data class SpecialAuthMode(
    val id: String,
    val labelKey: String,
    val fields: List<SpecialField>,
)

/**
 * Extra/replacement fields for one provider.
 *
 * @param replacesApiKey When true, [apiKeyItems] replace the default API key field.
 * @param endpoint Falls back to the card spec endpoint when null.
 */
data class SpecialFields(
    val replacesApiKey: Boolean = false,
    val apiKeyItems: List<SpecialField> = emptyList(),
    val endpoint: EndpointField? = null,
    val authModes: List<SpecialAuthMode> = emptyList(),
    val authModeKey: String? = null,
    /** Seed list of region options shared by bedrock/vertexai. */
    val regions: List<String> = emptyList(),
)

private fun secret(key: String, prefix: String) = SpecialField(
    key = key,
    labelKey = "${prefix}.title",
    placeholderKey = "${prefix}.placeholder",
    descKey = "${prefix}.desc",
    type = FieldType.SECRET,
)

private fun text(key: String, prefix: String) = SpecialField(
    key = key,
    labelKey = "${prefix}.title",
    placeholderKey = "${prefix}.placeholder",
    descKey = "${prefix}.desc",
    type = FieldType.TEXT,
)

const val VAULT_ACCESS_KEY_ID = "accessKeyId"
const val VAULT_SECRET_ACCESS_KEY = "secretAccessKey"
const val VAULT_SESSION_TOKEN = "sessionToken"
const val VAULT_REGION = "region"
const val VAULT_USERNAME = "username"
const val VAULT_PASSWORD = "password"
const val VAULT_AUTH_TYPE = "authType"
const val VAULT_ENDPOINT = "endpoint"
const val VAULT_ACCOUNT = "baseURLOrAccountID"

val BEDROCK_REGIONS = listOf(
    "us-east-1", "us-east-2", "us-west-1", "us-west-2", "ca-central-1",
    "us-gov-east-1", "us-gov-west-1", "sa-east-1",
    "eu-north-1", "eu-west-1", "eu-west-2", "eu-west-3",
    "eu-central-1", "eu-central-2", "eu-south-1", "eu-south-2",
    "me-south-1", "me-central-1", "af-south-1",
    "ap-south-1", "ap-south-2", "ap-east-1",
    "ap-southeast-1", "ap-southeast-2", "ap-southeast-3", "ap-southeast-4",
    "ap-northeast-1", "ap-northeast-2", "ap-northeast-3",
    "cn-north-1", "cn-northwest-1",
)

val VERTEX_AI_REGIONS = listOf(
    "global",
    "us-central1", "us-east1", "us-east4",
    "us-west1", "us-west2", "us-west3", "us-west4", "us-south1",
    "northamerica-northeast1", "northamerica-northeast2",
    "southamerica-east1", "southamerica-west1",
    "europe-central2", "europe-north1", "europe-southwest1",
    "europe-west1", "europe-west2", "europe-west3", "europe-west4",
    "europe-west6", "europe-west8", "europe-west9", "europe-west10",
    "europe-west12",
    "me-central1", "me-central2", "me-west1", "africa-south1",
    "asia-east1", "asia-east2",
    "asia-northeast1", "asia-northeast2", "asia-northeast3",
    "asia-south1", "asia-southeast1", "asia-southeast2",
    "australia-southeast1", "australia-southeast2",
)

private val SPECIAL_FIELDS: Map<String, SpecialFields> = mapOf(
    "azure" to SpecialFields(
        replacesApiKey = true,
        apiKeyItems = listOf(
            secret(VAULT_API_KEY, "azure.token"),
            text(VAULT_BASE_URL, "azure.endpoint"),
        ),
    ),
    "azureai" to SpecialFields(
        replacesApiKey = true,
        apiKeyItems = listOf(
            secret(VAULT_API_KEY, "azureai.token"),
            text(VAULT_BASE_URL, "azureai.endpoint"),
        ),
    ),
    "bedrock" to SpecialFields(
        replacesApiKey = true,
        authModeKey = VAULT_AUTH_TYPE,
        authModes = listOf(
            SpecialAuthMode(
                id = "apiKey",
                labelKey = "bedrock.authMode.options.apiKey",
                fields = listOf(secret(VAULT_API_KEY, "bedrock.apiKey")),
            ),
            SpecialAuthMode(
                id = "awsCredentials",
                labelKey = "bedrock.authMode.options.awsCredentials",
                fields = listOf(
                    text(VAULT_ACCESS_KEY_ID, "bedrock.accessKeyId"),
                    secret(VAULT_SECRET_ACCESS_KEY, "bedrock.secretAccessKey"),
                    secret(VAULT_SESSION_TOKEN, "bedrock.sessionToken"),
                ),
            ),
        ),
        apiKeyItems = listOf(
            SpecialField(
                key = VAULT_REGION,
                labelKey = "bedrock.region.title",
                placeholderKey = "bedrock.region.placeholder",
                descKey = "bedrock.region.desc",
                type = FieldType.SELECT,
                options = BEDROCK_REGIONS,
            ),
        ),
    ),
    "cloudflare" to SpecialFields(
        replacesApiKey = true,
        apiKeyItems = listOf(
            secret(VAULT_API_KEY, "cloudflare.apiKey"),
            text(VAULT_ACCOUNT, "cloudflare.baseURLOrAccountID"),
        ),
    ),
    "comfyui" to SpecialFields(
        replacesApiKey = true,
        authModeKey = VAULT_AUTH_TYPE,
        authModes = listOf(
            SpecialAuthMode(id = "none", labelKey = "comfyui.authType.options.none", fields = emptyList()),
            SpecialAuthMode(
                id = "basic", labelKey = "comfyui.authType.options.basic",
                fields = listOf(
                    text(VAULT_USERNAME, "comfyui.username"),
                    secret(VAULT_PASSWORD, "comfyui.password"),
                ),
            ),
            SpecialAuthMode(
                id = "bearer", labelKey = "comfyui.authType.options.bearer",
                fields = listOf(secret(VAULT_API_KEY, "comfyui.apiKey")),
            ),
            // Custom header authentication is not implemented by this local form.
            SpecialAuthMode(id = "custom", labelKey = "comfyui.authType.options.custom", fields = emptyList()),
        ),
        apiKeyItems = listOf(text(VAULT_BASE_URL, "comfyui.baseURL")),
    ),
    "github" to SpecialFields(
        replacesApiKey = true,
        apiKeyItems = listOf(secret(VAULT_API_KEY, "github.personalAccessToken")),
    ),
    "vertexai" to SpecialFields(
        replacesApiKey = true,
        apiKeyItems = listOf(
            secret(VAULT_API_KEY, "vertexai.apiKey"),
            SpecialField(
                key = VAULT_REGION,
                labelKey = "vertexai.region.title",
                placeholderKey = "vertexai.region.placeholder",
                descKey = "vertexai.region.desc",
                type = FieldType.SELECT,
                options = VERTEX_AI_REGIONS,
            ),
        ),
    ),
)

fun specialFieldsFor(providerId: String): SpecialFields? =
    SPECIAL_FIELDS[providerId.lowercase()]

/** Selects AWS credentials when access-key fields are configured. */
fun inferBedrockAuthMode(vaults: Map<String, String?>): String {
    vaults[VAULT_AUTH_TYPE]?.takeIf { it == "apiKey" || it == "awsCredentials" }?.let { return it }
    val hasAws = !vaults[VAULT_ACCESS_KEY_ID].isNullOrBlank() ||
        !vaults[VAULT_SECRET_ACCESS_KEY].isNullOrBlank()
    return if (hasAws) "awsCredentials" else "apiKey"
}
