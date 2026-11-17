package dev.persona.watch.data

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
            val origin = HubConfig.normalizeBaseUrl(address)
            val same = HubConfig.sameOrigin(old?.baseUrl, origin)
            if (token.isBlank()) {
                if (!same) throw HubException("new_address_token")
                return PrimaryPairingRequest(old!!.validated(), true)
            }
            return PrimaryPairingRequest(HubConfig(origin, token.trim(), if (same) old?.fingerprint else null).validated(), false)
        }
    }
}
