package cc.jaxy.bob.core.common.util

/**
 * Static provider configuration metadata adapted from upstream Model Bank entries.
 *
 * Local forms render API keys, endpoints, extra fields, and authentication modes
 * from this specification; provider requests and settings remain on-device.
 */

/** KeyVaults wire keys shared by all providers. */
const val VAULT_API_KEY = "apiKey"
const val VAULT_BASE_URL = "baseURL"

/** A single keyVaults-backed form field. */
data class ProviderField(
    /** Key inside `keyVaults` (e.g. `apiKey`, `region`). */
    val key: String,
    /** i18n template or literal label; `{name}` = provider display name. */
    val label: String,
    val placeholder: String = "",
    val desc: String = "",
    val type: FieldType = FieldType.TEXT,
    /** Options for [FieldType.SELECT]. */
    val options: List<String> = emptyList(),
    val required: Boolean = false,
)

enum class FieldType {
    /** Obscured secret input (API key, token, password). */
    SECRET,

    /** Plain single-line input (endpoint, account id, username). */
    TEXT,

    /** Dropdown over [ProviderField.options] (region, auth type). */
    SELECT,
}

/** Auth-mode variant for providers with mutually exclusive credentials. */
data class AuthMode(
    /** Stable id (`apiKey`, `awsCredentials`, `none`, `basic`, ...). */
    val id: String,
    val label: String,
    val fields: List<ProviderField>,
)

/**
 * Full render spec for one provider id.
 *
 * @param endpoint Placeholder + labels when the endpoint field shows; null
 *   hides it. Providers without a card `proxyUrl` but with a detail-page
 *   override (ollama, newapi) carry the override here.
 * @param apiKey Replacement for the default API key field; `SHOW_DEFAULT`
 *   keeps the generic key field, `HIDDEN` drops it.
 * @param extraFields Always-rendered vault fields after key/endpoint
 *   (e.g. Cloudflare account id, VertexAI region).
 * @param authModes Mutually exclusive credential sets (Bedrock, ComfyUI).
 *   When non-empty, [apiKey]/[extraFields] are ignored in favour of the
 *   active mode's fields.
 */
data class ProviderConfigSpec(
    val showApiKey: Boolean = true,
    val apiKey: ApiKeyField = ApiKeyField.SHOW_DEFAULT,
    val endpoint: EndpointField? = null,
    val extraFields: List<ProviderField> = emptyList(),
    val authModes: List<AuthMode> = emptyList(),
    /** Seed value for `keyVaults.authType`-style mode keys. */
    val authModeKey: String? = null,
    val defaultShowBrowserRequest: Boolean = false,
    val disableBrowserRequest: Boolean = false,
    val showChecker: Boolean = true,
    /** Whether this provider exposes an editable configuration form. */
    val showConfig: Boolean = true,
    val isOAuth: Boolean = false,
    val checkModel: String? = null,
)

enum class ApiKeyField { SHOW_DEFAULT, HIDDEN }

data class EndpointField(
    val placeholder: String,
    val title: String? = null,
    val desc: String? = null,
)

private const val GENERIC_ENDPOINT_PLACEHOLDER = "https://your-proxy-url.com/v1"

private fun ep(placeholder: String, title: String? = null, desc: String? = null) =
    EndpointField(placeholder, title, desc)

// ---------------------------------------------------------------------------
// Provider settings table: id -> spec (card-level, before detail overrides).
// Proxy placeholders come from `settings.proxyUrl.placeholder`.
// ---------------------------------------------------------------------------

private val CARD_SPECS: Map<String, ProviderConfigSpec> = mapOf(
    "ai21" to ProviderConfigSpec(checkModel = "jamba-mini"),
    "ai302" to ProviderConfigSpec(
        endpoint = ep("https://api.302.ai/v1"), checkModel = "gpt-4o",
    ),
    "ai360" to ProviderConfigSpec(
        disableBrowserRequest = true, checkModel = "360gpt-turbo",
    ),
    "aihubmix" to ProviderConfigSpec(
        endpoint = ep("https://aihubmix.com"), checkModel = "gpt-4.1-nano",
    ),
    "akashchat" to ProviderConfigSpec(checkModel = "Meta-Llama-3-1-8B-Instruct-FP8"),
    "antgroup" to ProviderConfigSpec(checkModel = "Ling-2.6-flash"),
    "anthropic" to ProviderConfigSpec(
        endpoint = ep("https://api.anthropic.com"),
        checkModel = "claude-opus-4-5-20251101",
    ),
    // azure/azureai get apiKeyItems + endpoint via detail overrides below.
    "azure" to ProviderConfigSpec(),
    "azureai" to ProviderConfigSpec(),
    "baichuan" to ProviderConfigSpec(
        endpoint = ep("https://api.baichuan-ai.com/v1"), checkModel = "Baichuan3-Turbo",
    ),
    "bailiancodingplan" to ProviderConfigSpec(
        endpoint = ep("https://coding.dashscope.aliyuncs.com/v1"),
        disableBrowserRequest = true, checkModel = "qwen3-coder-plus",
    ),
    "bedrock" to ProviderConfigSpec(checkModel = "anthropic.claude-instant-v1"),
    "bfl" to ProviderConfigSpec(disableBrowserRequest = true),
    "cerebras" to ProviderConfigSpec(
        endpoint = ep("https://api.cerebras.ai/v1"), checkModel = "llama3.1-8b",
    ),
    "chatgpt" to ProviderConfigSpec(
        showApiKey = false, apiKey = ApiKeyField.HIDDEN,
        disableBrowserRequest = true, isOAuth = true, checkModel = "gpt-5.5",
    ),
    "cloudflare" to ProviderConfigSpec(
        disableBrowserRequest = true,
        checkModel = "@hf/meta-llama/meta-llama-3-8b-instruct",
    ),
    "cohere" to ProviderConfigSpec(
        endpoint = ep("https://api.cohere.ai/compatibility/v1"),
        checkModel = "command-r7b-12-2024",
    ),
    "cometapi" to ProviderConfigSpec(
        endpoint = ep("https://api.cometapi.com/v1"), checkModel = "gpt-5-mini",
    ),
    "comfyui" to ProviderConfigSpec(disableBrowserRequest = true),
    "deepseek" to ProviderConfigSpec(
        endpoint = ep("https://api.deepseek.com/v1"), checkModel = "deepseek-v4-flash",
    ),
    "fal" to ProviderConfigSpec(disableBrowserRequest = true),
    "fireworksai" to ProviderConfigSpec(
        endpoint = ep("https://api.fireworks.ai/inference/v1"),
        checkModel = "accounts/fireworks/models/llama-v3p2-3b-instruct",
    ),
    "giteeai" to ProviderConfigSpec(
        endpoint = ep("https://ai.gitee.com/v1"),
        disableBrowserRequest = true, checkModel = "Qwen2.5-72B-Instruct",
    ),
    "github" to ProviderConfigSpec(
        checkModel = "microsoft/Phi-3-mini-4k-instruct",
    ),
    "githubcopilot" to ProviderConfigSpec(
        showApiKey = false, apiKey = ApiKeyField.HIDDEN,
        isOAuth = true, checkModel = "gpt-5-mini",
    ),
    "glmcodingplan" to ProviderConfigSpec(
        endpoint = ep("https://open.bigmodel.cn/api/coding/paas/v4"),
        disableBrowserRequest = true, checkModel = "GLM-4.7",
    ),
    "google" to ProviderConfigSpec(
        endpoint = ep("https://generativelanguage.googleapis.com"),
        checkModel = "gemini-3-flash-preview",
    ),
    "groq" to ProviderConfigSpec(
        endpoint = ep("https://api.groq.com/openai/v1"),
        checkModel = "llama-3.1-8b-instant",
    ),
    "higress" to ProviderConfigSpec(
        endpoint = ep("https://127.0.0.1:8080/v1"), checkModel = "qwen-max",
    ),
    "huggingface" to ProviderConfigSpec(
        disableBrowserRequest = true,
        checkModel = "mistralai/Mistral-7B-Instruct-v0.2",
    ),
    "hunyuan" to ProviderConfigSpec(
        endpoint = ep("https://tokenhub.tencentmaas.com/v1"),
        disableBrowserRequest = true, checkModel = "hunyuan-role-latest",
    ),
    "infiniai" to ProviderConfigSpec(
        endpoint = ep("https://cloud.infini-ai.com/maas/v1"),
        disableBrowserRequest = true, checkModel = "qwen3-8b",
    ),
    "internlm" to ProviderConfigSpec(
        endpoint = ep("https://chat.intern-ai.org.cn/api/v1"),
        disableBrowserRequest = true, checkModel = "intern-latest",
    ),
    "jina" to ProviderConfigSpec(
        endpoint = ep("https://deepsearch.jina.ai/v1"),
        checkModel = "jina-deepsearch-v1",
    ),
    "kimicodingplan" to ProviderConfigSpec(
        endpoint = ep("https://api.kimi.com/coding"),
        disableBrowserRequest = true, checkModel = "kimi-for-coding",
    ),
    "lmstudio" to ProviderConfigSpec(
        endpoint = ep("http://127.0.0.1:1234/v1"),
        defaultShowBrowserRequest = true,
    ),
    "longcat" to ProviderConfigSpec(
        endpoint = ep("https://api.longcat.chat/openai/v1"), checkModel = "LongCat-2.0",
    ),
    "meta" to ProviderConfigSpec(
        endpoint = ep("https://api.meta.ai/v1"),
        disableBrowserRequest = true, checkModel = "muse-spark-1.3",
    ),
    "minimax" to ProviderConfigSpec(
        endpoint = ep("https://api.minimaxi.com/v1"),
        disableBrowserRequest = true, checkModel = "MiniMax-M2.1",
    ),
    "minimaxcodingplan" to ProviderConfigSpec(
        endpoint = ep("https://api.minimaxi.com/v1"),
        disableBrowserRequest = true, checkModel = "MiniMax-M2.7",
    ),
    "mistral" to ProviderConfigSpec(
        endpoint = ep("https://api.mistral.ai/v1"),
        disableBrowserRequest = true, checkModel = "ministral-3b-latest",
    ),
    "modelscope" to ProviderConfigSpec(
        endpoint = ep("https://api-inference.modelscope.cn/v1"),
        disableBrowserRequest = true, checkModel = "Qwen/Qwen3-4B",
    ),
    "moonshot" to ProviderConfigSpec(
        endpoint = ep("https://api.moonshot.cn/v1"),
        disableBrowserRequest = true, checkModel = "kimi-k2.6",
    ),
    "nebius" to ProviderConfigSpec(
        endpoint = ep("https://api.studio.nebius.com/v1"),
        checkModel = "Qwen/Qwen2.5-Coder-7B",
    ),
    "newapi" to ProviderConfigSpec(
        endpoint = ep("https://your.new-api-provider.com"),
        checkModel = "gpt-4o-mini",
    ),
    "novita" to ProviderConfigSpec(
        endpoint = ep("https://api.novita.ai/v3/openai"),
        disableBrowserRequest = true,
        checkModel = "meta-llama/llama-3.1-8b-instruct",
    ),
    "nvidia" to ProviderConfigSpec(
        endpoint = ep("https://integrate.api.nvidia.com/v1"),
        disableBrowserRequest = true,
        checkModel = "meta/llama-3.2-1b-instruct",
    ),
    // ollama endpoint injected by its detail page (no card proxyUrl).
    "ollama" to ProviderConfigSpec(
        showApiKey = false, apiKey = ApiKeyField.HIDDEN,
        endpoint = ep("http://127.0.0.1:11434"),
        defaultShowBrowserRequest = true, checkModel = "deepseek-r1",
    ),
    "ollamacloud" to ProviderConfigSpec(
        disableBrowserRequest = true, checkModel = "gpt-oss:20b",
    ),
    // OpenAI uses a direct OpenAI-compatible endpoint.
    "openai" to ProviderConfigSpec(
        endpoint = ep("https://api.openai.com/v1"),
        checkModel = "gpt-5.2",
    ),
    "opencodecodingplan" to ProviderConfigSpec(
        endpoint = ep("https://opencode.ai/inference/go/v1"),
        disableBrowserRequest = true, checkModel = "glm-5.1",
    ),
    "opencodezen" to ProviderConfigSpec(
        endpoint = ep("https://opencode.ai/inference/v1"),
        checkModel = "claude-sonnet-4-5",
    ),
    "openrouter" to ProviderConfigSpec(
        endpoint = ep("https://openrouter.ai/api/v1"),
        disableBrowserRequest = true,
        checkModel = "google/gemma-2-9b-it:free",
    ),
    "perplexity" to ProviderConfigSpec(
        endpoint = ep("https://api.perplexity.ai"),
        disableBrowserRequest = true, checkModel = "sonar",
    ),
    "ppio" to ProviderConfigSpec(
        disableBrowserRequest = true,
        checkModel = "deepseek/deepseek-r1-distill-qwen-32b",
    ),
    "qiniu" to ProviderConfigSpec(
        endpoint = ep("https://openai.qiniu.com/v1"), checkModel = "deepseek-r1",
    ),
    "qwen" to ProviderConfigSpec(
        endpoint = ep("https://dashscope.aliyuncs.com/compatible-mode/v1"),
        disableBrowserRequest = true, checkModel = "qwen-flash",
    ),
    "replicate" to ProviderConfigSpec(
        endpoint = ep("https://api.replicate.com"),
        disableBrowserRequest = true,
        checkModel = "black-forest-labs/flux-1.1-pro",
    ),
    "sambanova" to ProviderConfigSpec(
        endpoint = ep("https://api.sambanova.ai/v1"),
        disableBrowserRequest = true, checkModel = "MiniMax-M2.7",
    ),
    "search1api" to ProviderConfigSpec(
        endpoint = ep("https://api.search1api.com/v1"),
        checkModel = "deepseek-r1-70b-fast-online",
    ),
    "sensenova" to ProviderConfigSpec(
        endpoint = ep("https://token.sensenova.cn/v1"),
        disableBrowserRequest = true, checkModel = "sensenova-6.7-flash-lite",
    ),
    "siliconcloud" to ProviderConfigSpec(
        endpoint = ep("https://api.siliconflow.cn/v1"),
        checkModel = "Pro/zai-org/GLM-4.7",
    ),
    "spark" to ProviderConfigSpec(
        disableBrowserRequest = true, checkModel = "lite",
    ),
    "stepfun" to ProviderConfigSpec(
        endpoint = ep("https://api.stepfun.com/v1"),
        disableBrowserRequest = true, checkModel = "step-2-mini",
    ),
    "straico" to ProviderConfigSpec(checkModel = "microsoft/phi-4"),
    "streamlake" to ProviderConfigSpec(
        endpoint = ep("https://wanqing.streamlakeapi.com/api/gateway/v1/endpoints"),
        checkModel = "KAT-Coder-Air-V1",
    ),
    "supergrok" to ProviderConfigSpec(
        showApiKey = false, apiKey = ApiKeyField.HIDDEN,
        disableBrowserRequest = true, isOAuth = true, checkModel = "grok-4.5",
    ),
    "taichu" to ProviderConfigSpec(
        endpoint = ep("https://cloud.zidongtaichu.com/maas/v1"),
        checkModel = "taichu_llm",
    ),
    "tencentcloud" to ProviderConfigSpec(
        endpoint = ep("https://api.lkeap.cloud.tencent.com/v1"),
        disableBrowserRequest = true, checkModel = "deepseek-v3",
    ),
    "togetherai" to ProviderConfigSpec(
        endpoint = ep("https://api.together.xyz/v1"),
        checkModel = "meta-llama/Llama-Vision-Free",
    ),
    "unsloth" to ProviderConfigSpec(
        endpoint = ep("http://127.0.0.1:8888/v1"),
        defaultShowBrowserRequest = true,
    ),
    "upstage" to ProviderConfigSpec(
        endpoint = ep("https://api.upstage.ai/v1/solar"),
        checkModel = "solar-1-mini-chat",
    ),
    "v0" to ProviderConfigSpec(
        disableBrowserRequest = true, checkModel = "v0-1.5-md",
    ),
    "vercelaigateway" to ProviderConfigSpec(
        disableBrowserRequest = true, checkModel = "openai/gpt-5-nano",
    ),
    "vertexai" to ProviderConfigSpec(
        disableBrowserRequest = true, checkModel = "gemini-3-flash-preview",
    ),
    "vllm" to ProviderConfigSpec(endpoint = ep("http://localhost:8000/v1")),
    "volcengine" to ProviderConfigSpec(
        endpoint = ep("https://ark.cn-beijing.volces.com/api/v3"),
        disableBrowserRequest = true, checkModel = "doubao-seed-1.8",
    ),
    "volcenginecodingplan" to ProviderConfigSpec(
        endpoint = ep("https://ark.cn-beijing.volces.com/api/coding/v3"),
        disableBrowserRequest = true, checkModel = "doubao-seed-code",
    ),
    "wenxin" to ProviderConfigSpec(
        endpoint = ep("https://qianfan.baidubce.com/v2"),
        checkModel = "ernie-4.5-turbo-latest",
    ),
    "xai" to ProviderConfigSpec(
        endpoint = ep("https://api.x.ai/v1"),
        disableBrowserRequest = true, checkModel = "grok-4.3",
    ),
    "xiaomimimo" to ProviderConfigSpec(
        endpoint = ep("https://api.xiaomimimo.com/v1"),
        disableBrowserRequest = true, checkModel = "mimo-v2.5",
    ),
    "xinference" to ProviderConfigSpec(endpoint = ep("http://localhost:9997/v1")),
    "zenmux" to ProviderConfigSpec(
        endpoint = ep("https://zenmux.ai"),
        disableBrowserRequest = true, checkModel = "openai/gpt-5-nano",
    ),
    "zeroone" to ProviderConfigSpec(
        endpoint = ep("https://api.lingyiwanwu.com/v1"),
        checkModel = "yi-lightning",
    ),
    "zhipu" to ProviderConfigSpec(
        endpoint = ep("https://open.bigmodel.cn/api/paas/v4"),
        checkModel = "glm-4.5-flash",
    ),
    "zai" to ProviderConfigSpec(),
)

/** Custom providers use the generic OpenAI-compatible key and endpoint form. */
fun customProviderSpec(): ProviderConfigSpec = ProviderConfigSpec(
    endpoint = ep(GENERIC_ENDPOINT_PLACEHOLDER),
)

/**
 * Resolve the local configuration form for [providerId]. Unknown ids use the
 * generic key/endpoint form; model selection is limited to implemented adapters.
 */
fun providerConfigSpec(providerId: String, source: String?): ProviderConfigSpec {
    if (source == "custom") return customProviderSpec()
    val spec = CARD_SPECS[providerId.lowercase()]
        ?: ProviderConfigSpec(endpoint = ep(GENERIC_ENDPOINT_PLACEHOLDER))
    val defaultEndpoint = providerDefaultEndpoint(providerId)
    return if (defaultEndpoint != null && (spec.endpoint == null || spec.endpoint.placeholder == GENERIC_ENDPOINT_PLACEHOLDER)) {
        spec.copy(endpoint = ep(defaultEndpoint))
    } else spec
}

