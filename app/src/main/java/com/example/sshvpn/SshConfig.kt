package com.example.sshvpn

import android.util.Base64
import org.json.JSONObject
import java.net.URLDecoder

data class SshConfig(
    val host: String,
    val port: Int = 22,
    val username: String,
    val password: String = "",
    val privateKey: String = "",
    val passphrase: String = "",
    val name: String = "",
) {
    fun toJson(): String = JSONObject().apply {
        put("host", host)
        put("port", port)
        put("username", username)
        put("password", password)
        put("privateKey", privateKey)
        put("passphrase", passphrase)
        put("name", name)
    }.toString()

    companion object {
        fun fromJson(text: String): SshConfig? = try {
            val o = JSONObject(text)
            val host = o.optString("host", o.optString("server", o.optString("ip", ""))).trim()
            val user = o.optString("username", o.optString("user", "")).trim()
            if (host.isEmpty() || user.isEmpty()) null
            else SshConfig(
                host = host,
                port = o.optInt("port", 22).let { if (it in 1..65535) it else 22 },
                username = user,
                password = o.optString("password", o.optString("pass", "")),
                privateKey = o.optString("privateKey", o.optString("key", "")),
                passphrase = o.optString("passphrase", ""),
                name = o.optString("name", o.optString("remarks", "")),
            )
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Parses the common "ssh direct" config shapes:
 *   ssh://user:pass@host:port#name
 *   host:port@user:pass
 *   host:port:user:pass
 *   host@user:pass         (port defaults to 22)
 *   {"host":"..","port":22,"username":"..","password":".."}
 *   key=value lines (host/port/user/pass)
 *   any of the above wrapped in base64
 * Returns null when nothing usable is found.
 */
object ConfigParser {

    fun parse(raw: String): SshConfig? = parse(raw, allowBase64 = true)

    private fun parse(raw: String, allowBase64: Boolean): SshConfig? {
        val text = raw.trim().trim('"', '\'')
        if (text.isEmpty()) return null

        if (text.startsWith("{")) return SshConfig.fromJson(text)

        if (text.contains('\n') && text.contains('=')) {
            parseKeyValue(text)?.let { return it }
        }

        val line = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null

        val lower = line.lowercase()
        for (scheme in listOf("ssh://", "ssh-direct://", "sshdirect://")) {
            if (lower.startsWith(scheme)) return parseUri(line.substring(scheme.length))
        }

        if (allowBase64 && looksLikeBase64(line)) {
            decodeBase64(line)?.let { decoded -> parse(decoded, allowBase64 = false)?.let { return it } }
        }

        return parsePlain(line)
    }

    private fun urlDecode(s: String) = try {
        URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    } catch (e: Exception) {
        s
    }

    // user:pass@host:port#name
    private fun parseUri(rest: String): SshConfig? {
        var body = rest
        var name = ""
        val hash = body.indexOf('#')
        if (hash >= 0) {
            name = urlDecode(body.substring(hash + 1))
            body = body.substring(0, hash)
        }
        val query = body.indexOf('?')
        if (query >= 0) body = body.substring(0, query)
        body = body.trimEnd('/')

        val at = body.lastIndexOf('@') // passwords may contain '@', hosts never do
        if (at < 0) return null
        val cred = body.substring(0, at)
        val hostPart = body.substring(at + 1)

        val colon = cred.indexOf(':')
        val user = urlDecode(if (colon >= 0) cred.substring(0, colon) else cred)
        val pass = if (colon >= 0) urlDecode(cred.substring(colon + 1)) else ""

        val (host, port) = splitHostPort(hostPart) ?: return null
        if (user.isEmpty()) return null
        return SshConfig(host = host, port = port, username = user, password = pass, name = name)
    }

    private fun parsePlain(line: String): SshConfig? {
        var name = ""
        var body = line
        val hash = body.indexOf('#')
        if (hash >= 0) {
            name = body.substring(hash + 1).trim()
            body = body.substring(0, hash)
        }

        // host:port@user:pass  or  host@user:pass
        val at = body.indexOf('@')
        if (at > 0) {
            val hostPart = body.substring(0, at)
            val cred = body.substring(at + 1)
            val (host, port) = splitHostPort(hostPart) ?: return null
            val colon = cred.indexOf(':')
            val user = if (colon >= 0) cred.substring(0, colon) else cred
            val pass = if (colon >= 0) cred.substring(colon + 1) else ""
            if (user.isEmpty()) return null
            return SshConfig(host = host, port = port, username = user, password = pass, name = name)
        }

        // host:port:user:pass  (pass may contain ':')
        val parts = body.split(':', limit = 4)
        if (parts.size == 4) {
            val port = parts[1].toIntOrNull()
            if (port != null && port in 1..65535 && parts[0].isNotEmpty() && parts[2].isNotEmpty()) {
                return SshConfig(
                    host = parts[0], port = port, username = parts[2], password = parts[3], name = name,
                )
            }
        }
        return null
    }

    private fun parseKeyValue(text: String): SshConfig? {
        val map = HashMap<String, String>()
        text.lineSequence().forEach { l ->
            val i = l.indexOf('=')
            if (i > 0) map[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        val host = map["host"] ?: map["server"] ?: map["ip"] ?: return null
        val user = map["user"] ?: map["username"] ?: return null
        val port = map["port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 22
        return SshConfig(
            host = host,
            port = port,
            username = user,
            password = map["pass"] ?: map["password"] ?: "",
            name = map["name"] ?: map["remarks"] ?: "",
        )
    }

    private fun splitHostPort(s: String): Pair<String, Int>? {
        val t = s.trim()
        if (t.isEmpty()) return null
        if (t.startsWith("[")) { // [ipv6]:port
            val end = t.indexOf(']')
            if (end < 0) return null
            val host = t.substring(1, end)
            val port = t.substring(end + 1).removePrefix(":").toIntOrNull() ?: 22
            return if (host.isEmpty() || port !in 1..65535) null else host to port
        }
        val colon = t.lastIndexOf(':')
        if (colon < 0) return t to 22
        val host = t.substring(0, colon)
        val port = t.substring(colon + 1).toIntOrNull() ?: return null
        return if (host.isEmpty() || port !in 1..65535) null else host to port
    }

    private fun looksLikeBase64(s: String): Boolean =
        s.length >= 12 && s.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' || it == '-' || it == '_' }

    private fun decodeBase64(s: String): String? = try {
        String(Base64.decode(s, Base64.DEFAULT or Base64.URL_SAFE), Charsets.UTF_8)
    } catch (e: Exception) {
        try {
            String(Base64.decode(s, Base64.URL_SAFE), Charsets.UTF_8)
        } catch (e2: Exception) {
            null
        }
    }
}
