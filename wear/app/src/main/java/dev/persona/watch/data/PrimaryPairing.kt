package dev.persona.watch.data

import java.net.URI

internal data class PrimaryPairingRequest(val config: HubConfig, val reuseSaved: Boolean)
internal data class AuthenticatedPrimary(val request: PrimaryPairingRequest, val config: HubConfig, val snapshot: WatchSnapshot)

/** Verify a replacement before publishing it. A same-form retry can reuse an already issued credential. */
internal class PrimaryPairing {
    @Volatile private var pending: Pair<HubConfig, HubConfig>? = null
    fun authenticate(request: PrimaryPairingRequest, pair: (HubConfig) -> HubConfig,
                     refresh: (HubConfig) -> WatchSnapshot): AuthenticatedPrimary {
        val candidate = request.config
        val paired = if (request.reuseSaved) candidate else pending?.takeIf { it.first == candidate }?.second
            ?: pair(candidate).also { pending = candidate to it }
        return AuthenticatedPrimary(request, paired, refresh(paired))
    }
    /** Called only by an accepted UI completion, serialized with disconnect and connection changes. */
    fun publish(verified: AuthenticatedPrimary, save: (HubConfig) -> Unit) {
        save(verified.config)
        if (pending == (verified.request.config to verified.config)) pending = null
    }

    companion object {
        fun prepare(old: HubConfig?, address: String, token: String): PrimaryPairingRequest {
            if (address.trim().startsWith("persona:")) return PrimaryPairingRequest(PairingInput.parse(address, token), false)
            val origin = normalize(address)
            val same = old != null && origin == normalize(old.baseUrl)
            if (token.isBlank()) {
                if (!same) throw HubException("new_address_token")
                return PrimaryPairingRequest(old!!.copy(baseUrl = origin).validated(), true)
            }
            return PrimaryPairingRequest(HubConfig(origin, token.trim(), if (same) old?.fingerprint else null).validated(), false)
        }
        private fun normalize(address: String): String {
            val uri = try { URI(address.trim()) } catch (_: Exception) { throw HubException("invalid_address") }
            // Validate before canonicalizing so credentials/query/fragment can never be silently dropped.
            HubConfig(address.trim(), "validation-only").validated()
            val port = if (uri.port == 80 && uri.scheme == "http" || uri.port == 443 && uri.scheme == "https") -1 else uri.port
            return URI(uri.scheme.lowercase(), null, uri.host.lowercase(), port, uri.path.trimEnd('/'), null, null).toASCIIString()
        }
    }
}
