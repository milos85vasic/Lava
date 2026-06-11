package lava.network.dto

import kotlinx.serialization.Serializable

/**
 * Dynamic Provider Discovery (2026-06-11, spec §4.2). Wire DTO mirroring one
 * entry of the lava-api-go `GET /v1/providers` catalogue response.
 *
 * Field-for-field with the API's provider descriptor. `capabilities` and
 * `authType` arrive as raw strings — the tolerant string→enum mapping happens
 * later in [lava.data.provider.ProviderCatalogRepository] (so this wire layer
 * stays a dumb, lenient mirror of the server contract and an unknown enum value
 * on the wire NEVER fails JSON parsing).
 *
 * `kind` is `"native"` or `"jackett"`; `indexer` is present only for jackett
 * entries (the underlying Jackett indexer id). Defaults are supplied for the
 * optional/list fields so a partially-populated server payload still parses
 * (the [lava.network.serialization.JsonFactory] also sets `ignoreUnknownKeys`).
 */
@Serializable
data class ProviderDescriptorDto(
    val id: String,
    val displayName: String,
    val kind: String,
    val indexer: String? = null,
    val capabilities: List<String> = emptyList(),
    val authType: String,
    val encoding: String,
    val baseUrls: List<String> = emptyList(),
    val supportsAnonymous: Boolean = false,
)

/**
 * Top-level body of `GET /v1/providers`: `{ "providers": [ … ] }`.
 */
@Serializable
data class ProvidersResponseDto(
    val providers: List<ProviderDescriptorDto> = emptyList(),
)
