package com.nuvio.tv.core.posters

/**
 * Spec 114 P0-9: whether a rating-poster template's host may use http. Only a private IP literal in its
 * canonical form qualifies: IPv4 10/8, 172.16/12, 192.168/16 as exactly four decimal octets (0-255, no
 * leading zeros), or IPv6 ULA fc00::/7. Everything else counts as a hostname and stays https-only.
 *
 * Parsed by hand, never through [java.net.InetAddress]: Android's resolver reads `010.0.0.1` as octal
 * (8.0.0.1, a public address) and accepts short and integer forms, so any looser reading could let a
 * template that looks private send the key in cleartext to the internet.
 *
 * [host] is `HttpUrl.host`, which is already canonical: lowercase, IPv6 without brackets and compressed,
 * and an IPv4-mapped IPv6 literal turned into plain IPv4 (`[::ffff:8.8.8.8]` reads as `8.8.8.8`).
 */
internal fun isHomeNetworkLiteral(host: String): Boolean =
    if (':' in host) isUniqueLocalIpv6(host) else ipv4Octets(host)?.let(::isPrivateIpv4) ?: false

private fun ipv4Octets(host: String): IntArray? {
    val parts = host.split('.')
    if (parts.size != 4) return null
    val octets = IntArray(4)
    for ((i, part) in parts.withIndex()) {
        if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return null
        if (part.length > 1 && part[0] == '0') return null
        val value = part.toInt()
        if (value > 255) return null
        octets[i] = value
    }
    return octets
}

private fun isPrivateIpv4(o: IntArray): Boolean =
    o[0] == 10 || (o[0] == 172 && o[1] in 16..31) || (o[0] == 192 && o[1] == 168)

/** fc00::/7: the first group's top seven bits are 1111110, i.e. a full four-digit group starting fc or fd. */
private fun isUniqueLocalIpv6(host: String): Boolean {
    val first = host.substringBefore(':')
    return first.length == 4 && first.all { it in '0'..'9' || it in 'a'..'f' } &&
        (first.startsWith("fc") || first.startsWith("fd"))
}
