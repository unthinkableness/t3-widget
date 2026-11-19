package dev.local.t3widget.data

/**
 * Setup link / QR payload:
 *
 *     t3widget://setup#v=1&url=<tunnel base url>&lan=<optional lan base url>&token=<token>
 *
 * Values are percent-encoded (encodeURIComponent / urllib.parse.quote(safe="")). The parameters live in the
 * fragment so they are never sent anywhere if the link is opened by something other than this app. A `+` is
 * kept literally (none of the values contain spaces). The query form (`?v=1&…`) is accepted as a fallback.
 */
object SetupLink {
    const val SCHEME = "t3widget"
    const val HOST = "setup"
    const val VERSION = 1

    data class Config(val url: String, val lanUrl: String, val token: String)

    sealed interface Result {
        data class Ok(val config: Config) : Result
        data class Invalid(val reason: String) : Result
    }

    fun parse(link: String?): Result {
        val s = link?.trim().orEmpty()
        if (!s.lowercase().startsWith("$SCHEME://$HOST")) return Result.Invalid("Not a T3 widget setup link")
        val rest = s.substring("$SCHEME://$HOST".length).trimStart('/')
        val params = when {
            rest.contains('#') -> rest.substringAfter('#')
            rest.startsWith("?") -> rest.substring(1)
            else -> ""
        }
        if (params.isBlank()) return Result.Invalid("Setup link has no parameters")

        val map = HashMap<String, String>()
        for (part in params.split('&')) {
            if (part.isEmpty()) continue
            val k = part.substringBefore('=')
            val v = if (part.contains('=')) part.substringAfter('=') else ""
            val dk = percentDecode(k) ?: return Result.Invalid("Bad encoding in setup link")
            val dv = percentDecode(v) ?: return Result.Invalid("Bad encoding in setup link")
            map.putIfAbsent(dk, dv)
        }

        val v = map["v"]?.toIntOrNull() ?: return Result.Invalid("Setup link has no version")
        if (v > VERSION) return Result.Invalid("Setup link v$v is newer than this widget; update the app")
        if (v < 1) return Result.Invalid("Unsupported setup link version")

        val url = normalizeBaseUrl(map["url"].orEmpty()) ?: return Result.Invalid("Setup link has no valid url")
        val lanRaw = map["lan"].orEmpty()
        val lan = if (lanRaw.isBlank()) "" else normalizeBaseUrl(lanRaw) ?: return Result.Invalid("Setup link has an invalid lan url")
        val token = map["token"].orEmpty().trim()
        if (token.isEmpty()) return Result.Invalid("Setup link has no token")
        if (token.any { it.isWhitespace() || it.code < 0x21 }) return Result.Invalid("Setup link token is malformed")
        return Result.Ok(Config(url, lan, token))
    }

    /** Builds a link in exactly the format above (used by tests and docs). */
    fun build(url: String, lanUrl: String, token: String): String = buildString {
        append("$SCHEME://$HOST#v=$VERSION&url=").append(percentEncode(url))
        if (lanUrl.isNotBlank()) append("&lan=").append(percentEncode(lanUrl))
        append("&token=").append(percentEncode(token))
    }

    /** "https://host/" → "https://host"; null unless http(s) with a host. */
    fun normalizeBaseUrl(raw: String): String? {
        val s = raw.trim().trimEnd('/')
        val m = Regex("^(https?)://([^/?#\\s]+)(/[^?#\\s]*)?$", RegexOption.IGNORE_CASE).matchEntire(s) ?: return null
        val scheme = m.groupValues[1].lowercase()
        val host = m.groupValues[2]
        if (host.startsWith(":") || host.endsWith(":")) return null
        return "$scheme://$host${m.groupValues[3].trimEnd('/')}"
    }

    private fun percentDecode(s: String): String? {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%') {
                if (i + 2 >= s.length) return null
                val b = s.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
                out.write(b)
                i += 3
            } else {
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        return out.toString("UTF-8")
    }

    private fun percentEncode(s: String): String = buildString {
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            if (c.toChar().isLetterOrDigit() && c < 0x80 || c.toChar() in "-_.!~*'()") append(c.toChar())
            else append('%').append("%02X".format(c))
        }
    }
}
