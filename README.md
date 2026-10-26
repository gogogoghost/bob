# Local Agent for Android

A standalone, local-first Android agent app built with Jetpack Compose. It is not a LobeHub client and does not require a hosted server, account, or sign-in.

Agents, conversations, messages, provider settings, model lists, and UI preferences are stored on-device. Chat requests go directly from the device to the provider endpoint selected by the user. Local/LAN inference servers can be configured; cloud providers still require their own API credentials and network access. No chat data is sent to LobeHub.

## Features

- Create, edit, duplicate, and delete local agents with system prompts, model binding, opening messages/questions, and local avatars.
- Keep per-agent conversation history locally; resume, start, and delete conversations.
- Configure provider endpoints and models locally; use built-in provider settings or add an OpenAI-compatible endpoint.
- Providers and models start disabled; enable the providers and chat models you choose to use.
- Upgrades preserve enabled providers/models, credentials, custom endpoints, and conversation history. New provider/model entries start disabled.
- Stream model output directly from supported provider APIs; model availability and protocol support depend on the configured provider.
- Preserve partial answers when generation is stopped, reaches an output limit, or loses its connection. Message history records the completion status and provider finish reason instead of displaying truncated output as a successful answer.
- Opt into web search with provider-reported progress, clickable citations, and locally retained source references. Compatible OpenAI models use native search; other chat models can use a configured SearXNG JSON search service.
- Direct chat adapters cover 81 of the 85 catalog providers: OpenAI Chat Completions/Responses, legacy Completions, Anthropic Messages, Gemini, Ollama, Azure, Bedrock, Vertex AI, Cloudflare, Hugging Face, and provider-specific gateways. ChatGPT, GitHub Copilot, and SuperGrok support browser-based device authorization with encrypted token refresh. Fal, BFL, ComfyUI, and Replicate contain only non-chat catalog models; they are not exposed as chat providers.
- Choose app language, theme, and dynamic color.
- Use Android Auto Backup / device transfer for the local database, app preferences, and local attachments when system backup is enabled.

## Privacy and backup

Provider credentials are encrypted with Android Keystore AES-GCM and stored under Android's no-backup directory. The encryption key and credential ciphertext are intentionally excluded from cloud backup and device transfer. After restoring an app backup, re-enter provider API keys; provider names, endpoints, agents, conversations, messages, UI settings, and local files are backed up.

The app remains usable offline for agent management and history. Model generation requires the configured provider endpoint to be reachable. For local inference, configure an on-device or LAN-compatible endpoint.

The bundled provider/model metadata snapshot uses the public LobeHub Model Bank (commit `6c7a6fb033e443bc27bf34cbd5509ab3db7f22f3`) as a static catalog source only: 85 provider entries and 1,886 model entries. Four current Kimi Code model aliases are seeded in addition to that snapshot. The app makes no runtime requests to LobeHub. Chat eligibility follows the implemented adapter and model type; image, video, embedding, transcription, speech, and realtime entries remain outside the conversational interface.

### Provider invocation

- [OpenCode Go](https://opencode.ai/docs/go/#endpoints) uses the current protocol-specific `/inference/go/{openai,anthropic,google}/…` routes. Every request carries the conversation's stable `x-opencode-session` and the app's real client identity. [OpenCode Console/Zen](https://opencode.ai/docs/zen/#endpoints), AIHubMix, NewAPI, and ZenMux select the actual model protocol rather than sending every model to Chat Completions.
- Inference streams by default. Native OpenAI GPT/o-series models prefer the recommended [Responses API](https://developers.openai.com/api/docs/guides/migrate-to-responses), retaining Chat Completions for chat-only models. Anthropic/Gemini use native SSE, Ollama uses NDJSON, and Bedrock uses its binary event stream. Documented nonstream models and Straico's JSON API remain exceptions; o1/o3 families are no longer broadly forced into nonstream requests.
- Request rules include provider authentication headers, reasoning/history replay, model-specific sampling/output-token constraints, SSE/JSON/NDJSON decoding, and AWS binary event-stream decoding. Connectivity checks invoke the selected model; model-list support is handled separately.
- Known model output limits are retained rather than capped at 4,096 tokens. Finish-reason metadata never closes SSE early or discards later text/errors; a reason followed by unfinished content cannot make EOF a success. Provider-native completion signals distinguish successful answers from length exhaustion, filtering, tool requests, and premature EOF; stopping retains even the latest tokens not yet rendered on screen.
- Azure requires a deployed model and resource endpoint; Bedrock requires region plus bearer or AWS credentials/IAM access; Vertex supports express API keys or service-account JSON. Local servers and user-owned gateways require a reachable endpoint.
- Subscription authorization requires an eligible account and an external browser. OAuth tokens are excluded from backups. Disconnect removes this device's credentials, not the provider-side grant.
- [GitHub Models was retired on July 30, 2026](https://docs.github.com/en/github-models). Its former public endpoint cannot be used; the adapter remains available for an explicitly configured compatible proxy. GitHub Copilot is a separate, supported subscription flow.

Adapter support is not a guarantee that a provider will accept every account, region, historical catalog model, or subscription use case. Provider policies, quotas, model retirement, and IAM permissions still apply. OpenCode/Kimi coding subscriptions must be used within their published terms; the app does not impersonate an approved client.

## Web search

Open **Settings → Web search** for the dedicated search-services page. Settings itself contains only a navigation entry. Configure a service's API key and enable it in its detail page; enabling a service does not automatically select it or change an Agent's search policy. Keys use the existing encrypted, no-backup credential vault.

Preconfigured integrations: [Tavily](https://docs.tavily.com/documentation/api-reference/endpoint/search), [Brave Search](https://api-dashboard.search.brave.com/app/documentation/web-search/get-started), [Exa](https://exa.ai/docs/reference/search), [Serper](https://serper.dev/), [SerpApi](https://serpapi.com/search-api), [Bocha](https://open.bochaai.com/), [Firecrawl](https://docs.firecrawl.dev/api-reference/endpoint/search), [Linkup](https://docs.linkup.so/pages/documentation/endpoints/search/reference), [You.com](https://you.com/docs/api-reference/search/v1-search), and [SearXNG](https://docs.searxng.org/dev/search_api.html). SearXNG requires an instance with JSON output enabled; no public instance is supplied. Add custom services with a supported API protocol, a name, endpoint, and credentials; custom services can be edited or deleted independently.

The global default is **Off**, **Model native**, or a specific enabled search service. Each Agent has a compact selector for **Follow global**, **Off**, **Model native**, or a specific enabled service. Explicit Agent choices win; duplication preserves the choice, and Follow global clears the override. Native selection never falls back to an external service; explicit external selection uses that service even when the selected model supports native search.

The chat page shows the effective route and whether it comes from the Agent or global default, without settings controls. The policy is captured when a message is sent; later policy changes apply to subsequent messages. Unsupported native search and disabled, removed, or unconfigured selected services are reported explicitly rather than silently sending an unsearched request or switching services. Existing SearXNG configuration and Agent overrides migrate to the explicit policy model without a Room schema change.

- Compatible builtin OpenAI models use the stable Responses API [`web_search`](https://developers.openai.com/api/docs/guides/tools-web-search) tool; the model decides whether a question needs a search. Model/effort compatibility is explicit in `ProviderWebSearch.kt`; account access, availability, and search fees remain provider-controlled.
- External search works with enabled chat models, including OpenCode, Gemini, and local OpenAI-compatible servers. Only the latest question is sent as the search query. Up to five usable source snippets are provided as untrusted evidence to the selected model; the raw user question in local history is not rewritten. This is one retrieval round, not autonomous multi-step browsing or full-page fetching.
- API defaults are preconfigured, not bundled accounts: service credentials, endpoint access, available quota, terms, and search fees remain the user's responsibility. Custom endpoints use the selected protocol's full API endpoint; SearXNG also accepts an instance root. Use HTTPS except on a trusted local network. Search requests do not follow redirects or retry automatically.
- OpenAI native search sends the conversation to OpenAI, which may send queries and relevant content to its search partners. Search can incur additional charges. The effective preference controls app-requested search, not offline mode or search behavior inherently performed by a selected provider/model.
- Real search stages, queries, source titles/URLs, citation positions, partial answers, and interruption/failure status are retained with the assistant message. Sources remain clickable after reopening history; displayed inline markers do not alter the stored/copied answer. Missing or unsafe anchors fall back to source cards.
- Google Grounding is not enabled: its [Search Suggestions and retention/use requirements](https://ai.google.dev/gemini-api/terms#grounding-with-google-search) conflict with this app's full local history workflow. Gemini instead uses the same configurable independent search path as other non-native models.

Search failures are visible and do not silently invoke another service or pretend a model-only answer was searched. Cancelling closes the active search/inference request and saves already received sources even before answer text arrives.

## Build

```bash
# Requires Android SDK platform 37 and JDK 17+
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
./gradlew test
```
