package com.shilapi.xcertplay.network

/** onStarted can precede AP address assignment. Never fill that gap with another network. */
internal object LocalOnlyHotspotInterfacePolicy {
    data class Candidate(val name: String, val ipv4: Set<String>, val bssid: String?)

    fun select(
        candidates: List<Candidate>,
        previousAddresses: Set<String>,
        upstreamInterfaces: Set<String>,
        configuredBssid: String?,
    ): Candidate? {
        val possible = candidates.filter {
            it.name !in upstreamInterfaces && it.ipv4.isNotEmpty() &&
                it.name.matches(Regex("(?:ap|wlan|swlan|softap)[0-9]+|ap_br_[a-z0-9_]+"))
        }
        val fresh = possible.filter { candidate -> candidate.ipv4.any { it !in previousAddresses } }
        if (configuredBssid != null) {
            possible.singleOrNull { it.bssid.equals(configuredBssid, ignoreCase = true) }?.let { return it }
            // Android 11+ hides interface MACs from apps (the Lenovo TB353FU on Android 16 never matched),
            // so a single AP that gained its address after the start is ours.
        }
        return fresh.singleOrNull()
    }
}
