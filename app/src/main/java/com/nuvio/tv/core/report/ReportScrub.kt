package com.nuvio.tv.core.report

/**
 * Spec 116 D6: removes secrets and personal data from report text on the TV, before the user sees
 * the preview and before upload. Pure: no Android, no I/O. Best effort by design (D1): the preview
 * is the control; this catches what it can find.
 *
 * Steps run in the order D6 numbers them, line by line:
 * 1. whole URLs (any scheme spelling) → `<url host=H>` / `<url addon=Name>`, magnets → `<magnet>`;
 * 2. installed addon hosts anywhere → `<addon=Name>`;
 * 3. known values (from [knownValuesFrom]) at boundaries → `<secret>`;
 * 4. patterns (headers, credential names, JWT, hashes, ids, emails, IPs, long opaque runs);
 * 5. whole lines of the watch-history tags dropped;
 * 6. control characters escaped as `\u00XX`.
 */
object ReportScrub {

    /** Header written by the crash recorder (P2) above each record; the opaque-run rule skips it. */
    const val CRASH_HEADER_PREFIX = "# crash "

    /** D6 step 5: bulk watch-history dumps. */
    private val DENIED_TAGS = setOf(
        "WatchProgressPrefs",
        "WatchProgressSyncService",
        "WatchProgressRepo",
        "WatchedItemsPrefs",
        "WatchedItemsSyncService",
    )

    private val DEBRID_PROVIDERS = setOf(
        "realdebrid", "real-debrid", "alldebrid", "premiumize", "debridlink", "debrid-link",
        "torbox", "offcloud", "putio", "easydebrid",
    )

    /** URL pieces that are path words, never secrets (D6 step 3), compared lowercase. */
    private val STOP_PIECES = setOf(
        "manifest.json", "configure", "resolve", "player_api.php", "get.php", "xmltv.php", "stream",
        "catalog", "subtitles", "series", "m3u_plus", "playlist.m3u", "playlist.m3u8", "index.m3u", "index.m3u8",
    ) + DEBRID_PROVIDERS

    /** Listed credential names, matched in any case; the lowercase joined spellings cover `ACCESSTOKEN`. */
    private val CREDENTIAL_NAMES = listOf(
        "token", "access_token", "refresh_token", "accesstoken", "refreshtoken", "idtoken", "api_key", "apikey",
        "auth", "password", "apipassword", "pass", "secret", "clientsecret", "sig", "signature",
    )

    /**
     * The one grammar of a credential name (D6 steps 3 and 4), for use under `(?i)`: any `_`-joined
     * prefix (`tmdb_`, `client_`, `_`), then a listed name or a camelCase name ending in
     * Token/Secret/Password (`authToken`, case-sensitive). Every segment class excludes `_`, so the
     * prefix splits one way per `_` count; it is capped at 8 segments because Java recurses once per
     * group repetition (a long `a_a_…` run would overflow the stack).
     */
    private val CREDENTIAL_NAME =
        "(?:[A-Za-z0-9]*_){0,8}(?:(?-i:[a-z][A-Za-z0-9]*(?:Token|Secret|Password))|${CREDENTIAL_NAMES.joinToString("|")})"

    /** Step 4's header names (HEADER_LINE); step 3 counts them as credential names too. */
    private const val HEADER_NAMES = "authorization|cookie|set-cookie|apikey|x-api-key"

    /**
     * Names step 3 adds to step 4's ([CREDENTIAL_NAME_TAIL]): the header names, and names that only
     * stored URLs carry, `username`/`user` (also as a tail, `xc_username`) and bare `key`, never as a
     * tail (`sort_key=seeders` stays). Step 4's NAME_VALUE leaves the stored-only ones out so
     * `key=watch_progress` stays.
     */
    private val STORED_ONLY_NAME = Regex("""(?i)$HEADER_NAMES|(?:[A-Za-z0-9]*_){0,8}(?:username|user)|key""")

    /**
     * Step 4's view of a whole name: the grammar at its end, whole or after a character outside
     * `[A-Za-z0-9_]` (`x-auth-token`, `X-Plex-Token`, `cfg.password`), as NAME_VALUE finds it.
     */
    private val CREDENTIAL_NAME_TAIL = Regex("""(?i)(?<![A-Za-z0-9_])(?:$CREDENTIAL_NAME)$""")

    private const val MIN_PIECE = 8
    private const val MIN_CREDENTIAL = 6

    // threadtime: "MM-DD HH:MM:SS.mmm  PID  TID L Tag     : message"
    private const val LOGCAT_HEAD = """\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}\s+\d+\s+\d+ [VDIWEFA] """
    private val LOGCAT_TAG = Regex("""^$LOGCAT_HEAD(\S+?)\s*:""")

    // Group 1 = the separator. The scheme is capped at 32 chars so each start costs O(32) (no
    // quadratic scan of a long hex run). It may start after any character (a digit in `#1https`, an
    // ANSI `[32m`, a `%XX` escape of a nested encoded URL) except inside a `%XX` escape; a letter run
    // longer than 32 before the scheme stays as text, the URL itself is still dropped whole.
    private val URL = Regex(
        """(?<!%[0-9A-Fa-f])[A-Za-z][A-Za-z0-9+.\-]{0,31}(://|:\\/\\/|%3[Aa]%2[Ff]%2[Ff])[^\s"'<>]*"""
    )
    private val URL_SPLIT = charArrayOf('/', '|', '=', ',', '&', ';', ':', '?', '@', '"', '{', '}', '[', ']')
    private val URL_TRAILING = Regex("""[).,;\]]+$""")
    private val MAGNET = Regex("""magnet:\?[^\s"'<>]*""", RegexOption.IGNORE_CASE)
    private val PERCENT = Regex("""%([0-9A-Fa-f]{2})""")

    private val HEADER_LINE = Regex(
        """(?i)(?<![A-Za-z0-9_\-])($HEADER_NAMES)(\s*:\s*)\S.*$"""
    )
    // A name starts after a character outside `[A-Za-z0-9_]`, so `x-auth-token=` matches on `token`.
    // The value runs to `)` too: stopping there would leave the rest of a password holding one.
    private val NAME_VALUE = Regex("""(?i)(?<![A-Za-z0-9_])($CREDENTIAL_NAME)=([^&\s,;"'<>]+)""")
    // The quoted name ends in the grammar, whole or after such a character (`"X-Emby-Token"`).
    private val JSON_NAME_VALUE =
        Regex("""(?i)"((?:[^"\s]*[^A-Za-z0-9_"\s])?$CREDENTIAL_NAME)"\s*:\s*"[^"]*"""")
    private val JWT = Regex("""eyJ[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]*""")
    private val BUILD_ID = Regex("""BuildId: [0-9A-Fa-f]+""")
    private val HEX_HASH = Regex("""(?<![A-Za-z0-9])[0-9A-Fa-f]{40}(?![A-Za-z0-9])""")
    private val BASE32_HASH = Regex("""(?<![A-Za-z0-9])[A-Z2-7]{32}(?![A-Za-z0-9])""")
    private val UUID = Regex(
        """(?<![A-Za-z0-9])[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}(?![A-Za-z0-9])"""
    )
    // The left boundary keeps a long run with no `@` linear (one start per run, not one per char).
    private val EMAIL = Regex("""(?<![A-Za-z0-9._%+\-])[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
    // A full stop beside the address is punctuation; a full stop then a digit is a 5-part version.
    private val IPV4 = Regex("""(?<!\d)(?<!\d\.)(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})(?!\.?\d)""")
    private val MAC = Regex("""(?<![\w:\-])(?:[0-9A-Fa-f]{2}[:\-]){5}[0-9A-Fa-f]{2}(?![\w:\-])""")
    // Up to 8 colons: an unbracketed address with a port (`InetSocketAddress.toString()`).
    private val IPV6_CANDIDATE = Regex("""(?<![\w:])(?<!\w\.)(?:[0-9A-Fa-f]{0,4}:){2,8}[0-9A-Fa-f]{0,4}(?![\w:])""")
    private val HEX_GROUP = Regex("""[0-9A-Fa-f]{1,4}""")
    private val OPAQUE = Regex("""(?<![A-Za-z0-9_\-])[A-Za-z0-9_\-]{20,}(?![A-Za-z0-9_\-])""")
    // Standard and base64url alphabets in one run, so a blob glued to `_`/`-` is never cut into pieces
    // under OPAQUE's 20; a run with no `+`, `=` or digit-only `/` segments is left whole to OPAQUE.
    // A start after `=` is allowed (`sid=<blob>`): padding only ends a run, so `=` before one starts a new one.
    private val BASE64 = Regex("""(?<![A-Za-z0-9+/_\-])[A-Za-z0-9+/_\-]{32,}={0,2}(?![A-Za-z0-9+/=_\-])""")
    private val IDENTIFIER = Regex("""^[A-Za-z_$][A-Za-z0-9_$]*$""")
    // A frame's shape (`at pkg.Class.method(`), with or without the threadtime prefix and its tab.
    private val STACK_FRAME = Regex("""^(?:$LOGCAT_HEAD\S+?\s*:\s*)?\s*at [\w$.<>\-]+\(""")
    private val ANR_THREAD = Regex("""^"[^"]*" .*prio=""")

    /**
     * D6 step 3's derivation. [storedUrls]: every stored user URL (addons, Live TV sources, ...);
     * [credentials]: stored keys, tokens and passwords; [addonHosts]: installed addon hosts, never
     * known values (step 2 names them instead).
     */
    fun knownValuesFrom(
        storedUrls: Collection<String>,
        credentials: Collection<String>,
        addonHosts: Set<String>,
    ): Set<String> {
        val hosts = addonHosts.mapTo(HashSet()) { it.lowercase() }
        // A credential name is never a value: as a known value it would hide the name from step 4.
        val pathWord = { piece: String ->
            piece.lowercase().let { it in STOP_PIECES || it in hosts } || isCredentialName(piece)
        }
        val out = HashSet<String>()
        for (url in storedUrls) {
            val raw = url.replace("\\/", "/")
            val pieces = percentDecode(raw).split(*URL_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }
            pieces.forEachIndexed { i, piece ->
                if (piece.length >= MIN_PIECE && !pathWord(piece) && !piece.all { it in 'a'..'z' }) out += piece
                // A debrid key or a credential value (`password=`, JSON `"password":"…"`) counts at any case.
                if (piece.lowercase() in DEBRID_PROVIDERS || isCredentialName(piece)) {
                    pieces.getOrNull(i + 1)?.takeIf { it.length >= MIN_CREDENTIAL && !pathWord(it) }?.let { out += it }
                }
            }
            // Whole values, split on the raw text before decoding so an encoded `&`, `/` or `@` stays inside.
            val query = raw.substringAfter('?', missingDelimiterValue = "").substringBefore('#')
            for (pair in query.split('&')) {
                val name = percentDecode(pair.substringBefore('=', missingDelimiterValue = ""))
                val value = percentDecode(pair.substringAfter('=', missingDelimiterValue = ""))
                if (isCredentialName(name) && value.length >= MIN_CREDENTIAL) out += value
            }
            val authority = raw.substringAfter("://", missingDelimiterValue = "").takeWhile { it !in "/?#" }
            if ('@' in authority) {
                authority.substringBeforeLast('@').split(':', limit = 2)
                    .map { percentDecode(it) }
                    .filterTo(out) { it.length >= MIN_CREDENTIAL }
            }
        }
        credentials.filterTo(out) { it.length >= MIN_CREDENTIAL }
        return out
    }

    /** [name] in its original case (the camelCase rule needs it). */
    private fun isCredentialName(name: String): Boolean =
        CREDENTIAL_NAME_TAIL.containsMatchIn(name) || STORED_ONLY_NAME.matches(name)

    /**
     * Scrubs [text] (D6 steps 1-6). [addonHosts] maps each installed addon's host (any case) to its
     * name, or to null when the name is not known (another profile's addon). Iteration order is
     * install order: two addons on one host take the first one's name.
     */
    fun scrub(text: String, knownValues: Set<String>, addonHosts: Map<String, String?>): String {
        val hosts = LinkedHashMap<String, String?>()
        for ((host, name) in addonHosts) if (host.isNotEmpty()) hosts.putIfAbsent(host.lowercase(), name)
        // A host is not touched by another host label (`x.host`, `host.x`); a full stop before a space
        // or the line end is punctuation.
        val hostRegex = alternation(
            hosts.keys,
            before = """(?<![A-Za-z0-9\-])(?<![A-Za-z0-9\-]\.)""",
            after = """(?![A-Za-z0-9\-])(?!\.[A-Za-z0-9\-])""",
            ignoreCase = true,
        )
        // D6 step 3: no letter or digit beside a known value, except that a `%XX` escape before it
        // counts as a boundary.
        val known = alternation(
            knownValues,
            before = """(?:(?<![A-Za-z0-9])|(?<=%[0-9A-Fa-f]{2}))""",
            after = """(?![A-Za-z0-9])""",
            ignoreCase = false,
        )
        return text.split('\n')
            .filterNot { it.isDeniedTag() }
            .joinToString("\n") { line -> scrubLine(line, known, hostRegex, hosts) }
    }

    private fun scrubLine(line: String, known: Regex?, hostRegex: Regex?, hosts: Map<String, String?>): String {
        var s = MAGNET.replace(line, "<magnet>")
        s = URL.replace(s) { m -> urlMarker(m, hosts) }
        if (hostRegex != null) s = hostRegex.replace(s) { m -> addonMarker(hosts[m.value.lowercase()]) }
        if (known != null) s = known.replace(s, "<secret>")
        s = patterns(s)
        return escapeControls(s)
    }

    private fun urlMarker(m: MatchResult, hosts: Map<String, String?>): String {
        val trailing = URL_TRAILING.find(m.value)?.value.orEmpty()
        val url = m.value.dropLast(trailing.length)
        val separator = m.groups[1]!!
        val rest = url.drop(separator.range.last + 1 - m.range.first)
        // The authority is cut before decoding, so an encoded `/`, `?`, `#` or `@` in a password stays
        // inside it; a URL whose separator is itself encoded is decoded first.
        val encoded = separator.value.startsWith('%')
        val body = if (encoded) percentDecode(rest) else rest.replace("\\/", "/")
        val authority = body.takeWhile { it != '/' && it != '?' && it != '#' }
        val hostPort = authority.substringAfterLast('@')
        val host = if (hostPort.startsWith('[')) {
            hostPort.substringAfter('[').substringBefore(']')
        } else {
            // Decoded first, then cut like a raw authority (path, userinfo, port), so an encoded
            // delimiter (`h%2Fget.php%3Fuser%3D…`, `bob:pw%40h`, `bob%3Apw%40h`) never reaches the marker.
            (if (encoded) hostPort else percentDecode(hostPort))
                .takeWhile { it !in "/?#\\" && !it.isWhitespace() }
                .substringAfterLast('@')
                .substringBefore(':')
        }.lowercase()
        val ipv4 = ipv4Kind(host)
        val marker = when {
            host.isEmpty() -> "<url>"
            host in hosts -> hosts[host]?.let { "<url addon=$it>" } ?: "<url addon>"
            ipv4 != null -> "<url $ipv4>"
            isIpv6(host) -> "<url ip>"
            else -> "<url host=$host>"
        }
        return marker + trailing
    }

    private fun addonMarker(name: String?): String = name?.let { "<addon=$it>" } ?: "<addon>"

    private fun patterns(line: String): String {
        // BuildId values in native frames are diagnostics, not hashes (D6 step 4).
        val protected = mutableListOf<String>()
        var s = BUILD_ID.replace(line) { m -> protected += m.value; "\uE000${protected.size - 1}\uE001" }
        s = HEADER_LINE.replace(s) { m -> m.groupValues[1] + m.groupValues[2] + "<token>" }
        s = NAME_VALUE.replace(s) { m -> m.groupValues[1] + "=<token>" }
        s = JSON_NAME_VALUE.replace(s) { m -> "\"" + m.groupValues[1] + "\":\"<token>\"" }
        s = JWT.replace(s, "<token>")
        s = HEX_HASH.replace(s, "<hash>")
        s = BASE32_HASH.replace(s, "<hash>")
        s = UUID.replace(s, "<id>")
        s = EMAIL.replace(s, "<email>")
        s = IPV4.replace(s) { m -> ipv4Kind(m.value)?.let { "<$it>" } ?: m.value }
        s = MAC.replace(s, "<ip>")
        s = IPV6_CANDIDATE.replace(s) { m -> ipv6Marker(m.value) ?: m.value }
        if (!skipsOpaque(s)) s = opaque(s)
        protected.forEachIndexed { i, value -> s = s.replace("\uE000$i\uE001", value) }
        return s
    }

    private fun skipsOpaque(line: String): Boolean =
        STACK_FRAME.containsMatchIn(line) || "FATAL EXCEPTION:" in line ||
            line.startsWith(CRASH_HEADER_PREFIX) || ANR_THREAD.containsMatchIn(line)

    // Base64 first: OPAQUE would otherwise take the part before a `+` and leave the rest. A `/` alone
    // counts only when every segment has a digit, so file paths stay (D6 step 4, P1b).
    private fun opaque(line: String): String {
        val s = BASE64.replace(line) { m ->
            val run = m.value
            val looksEncoded = '+' in run || '=' in run ||
                ('/' in run && run.split('/').filter { it.isNotEmpty() }.all { seg -> seg.any { it.isDigit() } })
            if (looksEncoded) "<token>" else run
        }
        return OPAQUE.replace(s) { m ->
            val run = m.value
            val keep = isScreamingSnake(run) || !run.any { it.isDigit() } || !run.any { it.isLetter() } ||
                isNameLike(run) || isIdentifierChain(s, m.range)
            if (keep) run else "<token>"
        }
    }

    /**
     * `^[A-Z][A-Z0-9]*(_[A-Z0-9]+)+$` (D6 step 4) without a regex: Java's recursion per group
     * repetition would overflow on a long `A_1_…` run.
     */
    private fun isScreamingSnake(run: String): Boolean =
        run.first() in 'A'..'Z' && '_' in run && !run.endsWith('_') && "__" !in run &&
            run.all { it in 'A'..'Z' || it in '0'..'9' || it == '_' }

    /**
     * Thread and release names (Arshad 2026-11-14, escalation e8ef04a1): every `-`/`_` part is all
     * letters, all digits, or ≤ 5 chars (`DefaultDispatcher-worker-12`, `Movie_2023_2160p_UHD_BluRay_REMUX`).
     * A long mixed part (`tb_6f1e2d3c…`) is secret-like and still hides.
     */
    private fun isNameLike(run: String): Boolean =
        run.split('-', '_').all { part ->
            part.length <= 5 || part.all { it.isLetter() } || part.all { it.isDigit() }
        }

    /** A run inside `a.b.C` style Java identifier chains (every part an identifier, one lowercase). */
    private fun isIdentifierChain(line: String, range: IntRange): Boolean {
        val chainChar = { c: Char -> c.isLetterOrDigit() || c == '_' || c == '$' || c == '.' }
        var start = range.first
        while (start > 0 && chainChar(line[start - 1])) start--
        var end = range.last + 1
        while (end < line.length && chainChar(line[end])) end++
        val chunk = line.substring(start, end).trim('.')
        if ('.' !in chunk) return false
        val parts = chunk.split('.')
        return parts.all { IDENTIFIER.matches(it) } && parts.any { it.first().isLowerCase() }
    }

    private fun String.isDeniedTag(): Boolean =
        LOGCAT_TAG.find(this)?.groupValues?.get(1)?.let { it in DENIED_TAGS } == true

    /** "ip lan" for private, loopback and link-local IPv4, "ip" for any other valid one, null if not IPv4. */
    private fun ipv4Kind(text: String): String? {
        val m = IPV4.matchEntire(text) ?: return null
        val o = m.groupValues.drop(1).map { it.toInt() }
        if (o.any { it > 255 }) return null
        val lan = o[0] == 10 || o[0] == 127 || (o[0] == 172 && o[1] in 16..31) ||
            (o[0] == 192 && o[1] == 168) || (o[0] == 169 && o[1] == 254)
        return if (lan) "ip lan" else "ip"
    }

    /**
     * RFC 4291 text form without an embedded IPv4 tail: 8 groups, or 2-7 around one `::` (a bare
     * `::` or `::1` reveals nothing and is often a log separator).
     */
    private fun isIpv6(text: String): Boolean {
        if (text.count { it == ':' } < 2) return false
        val halves = text.split("::")
        return when (halves.size) {
            1 -> text.split(':').let { groups -> groups.size == 8 && groups.all { HEX_GROUP.matches(it) } }
            2 -> {
                val groups = halves.flatMap { half -> if (half.isEmpty()) emptyList() else half.split(':') }
                groups.size in 2..7 && groups.all { HEX_GROUP.matches(it) }
            }
            else -> false
        }
    }

    /** `<ip>`, `<ip>:port` for 8 uncompressed groups plus a port, or null when [text] is no address. */
    private fun ipv6Marker(text: String): String? {
        if (isIpv6(text)) return "<ip>"
        val port = text.substringAfterLast(':')
        val portForm = "::" !in text && port.length in 1..5 && port.all { it.isDigit() } &&
            isIpv6(text.substringBeforeLast(':'))
        return if (portForm) "<ip>:$port" else null
    }

    private fun escapeControls(s: String): String {
        if (s.none { it.isEscapedControl() }) return s
        return buildString(s.length + 16) {
            for (c in s) {
                if (c.isEscapedControl()) append("\\u00").append("%02X".format(c.code)) else append(c)
            }
        }
    }

    private fun Char.isEscapedControl(): Boolean =
        (this < ' ' && this != '\t' && this != '\n') || this in '\u007F'..'\u009F'

    private fun percentDecode(s: String): String =
        PERCENT.replace(s) { m -> m.groupValues[1].toInt(16).toChar().toString() }

    /**
     * One alternation over [values], longest first, between the [before] and [after] lookarounds.
     * Empty values are dropped: one would match at every position.
     */
    private fun alternation(values: Collection<String>, before: String, after: String, ignoreCase: Boolean): Regex? {
        val nonEmpty = values.filter { it.isNotEmpty() }
        if (nonEmpty.isEmpty()) return null
        val body = nonEmpty.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
        val pattern = "$before(?:$body)$after"
        return if (ignoreCase) Regex(pattern, RegexOption.IGNORE_CASE) else Regex(pattern)
    }
}
