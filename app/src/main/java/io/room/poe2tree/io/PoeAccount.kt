package io.room.poe2tree.io

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** A character of the signed-in account (the Path of Exile API's character list). */
data class PoeCharacter(
    val name: String,
    /** The API's class: a class or ascendancy name, or an internal ascendancy id ("Sorceress1"). */
    val className: String,
    val league: String,
    val level: Int,
    /** The character last played. */
    val current: Boolean,
)

/** An error from pathofexile.com; [signedOut] when the account has to sign in again. */
class PoeApiException(message: String, val signedOut: Boolean = false) : Exception(message)

/**
 * The Path of Exile account for character import: sign-in on pathofexile.com and the character
 * endpoints of the Path of Exile API, as Path of Building does it (Classes/PoEAPI.lua,
 * LaunchServer.lua).
 *
 * Sign-in is OAuth 2 with PKCE for a public client: the browser signs in on pathofexile.com, which
 * redirects to a server of the app on http://localhost (ports 49082 to 49084, the redirects
 * registered for the client) with an authorization code. The code is exchanged for an access
 * token (valid for hours) and a refresh token that renews it; both are kept in [file].
 */
class PoeAccount(private val file: File, private val userAgent: String) {

    private class Tokens(val access: String, val refresh: String?, val expiresAt: Long, val username: String?) {
        fun toJson(): JSONObject = JSONObject().put("access", access).put("refresh", refresh ?: "")
            .put("expiresAt", expiresAt).put("username", username ?: "")
    }

    @Volatile
    private var tokens: Tokens? = load()
    private val mutex = Mutex()

    val signedIn get() = tokens != null
    val username get() = tokens?.username

    fun signOut() {
        tokens = null
        file.delete()
    }

    /**
     * Signs in: [openBrowser] opens pathofexile.com's authorization page, and this waits until the
     * browser comes back to the local server with the result (or [SIGN_IN_TIMEOUT_MS]).
     */
    suspend fun signIn(openBrowser: (String) -> Unit) = mutex.withLock {
        val verifier = randomUrlSafe(32)
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        val state = randomUrlSafe(12)
        val server = withContext(Dispatchers.IO) { LoopbackServer.open() }
            ?: throw PoeApiException("Could not start the sign-in: ports $FIRST_PORT to ${FIRST_PORT + 2} are in use.")
        val params = try {
            val redirect = "http://localhost:${server.port}"
            val url = "$WEB/oauth/authorize?client_id=$CLIENT_ID&response_type=code&scope=${enc(SCOPES)}&state=$state" +
                "&redirect_uri=${enc(redirect)}&code_challenge=$challenge&code_challenge_method=S256"
            openBrowser(url)
            val params = withTimeoutOrNull(SIGN_IN_TIMEOUT_MS) { server.awaitCallback() }
                ?: throw PoeApiException("The sign-in was not completed in time.")
            params + ("redirect_uri" to redirect)
        } finally {
            server.close()
        }
        params["error"]?.let { error ->
            throw PoeApiException(if (error == "access_denied") "The access was not granted." else "Sign-in failed: ${params["error_description"] ?: error}")
        }
        if (params["state"] != state) throw PoeApiException("Sign-in failed: the response does not belong to this sign-in.")
        val code = params["code"] ?: throw PoeApiException("Sign-in failed: no authorization code.")
        val response = withContext(Dispatchers.IO) {
            request(
                "$WEB/oauth/token",
                form = form(
                    "client_id" to CLIENT_ID, "grant_type" to "authorization_code", "code" to code,
                    "redirect_uri" to params.getValue("redirect_uri"), "scope" to SCOPES, "code_verifier" to verifier,
                ),
            )
        }
        if (response.code != 200) throw PoeApiException("Sign-in failed: ${response.errorMessage()}")
        withContext(Dispatchers.IO) { store(parseTokens(response.body, null)) }
    }

    /** The account's PoE2 characters (GET /character/poe2). */
    suspend fun characters(): List<PoeCharacter> {
        val arr = apiGet("/character/poe2").optJSONArray("characters") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val c = arr.getJSONObject(i)
            PoeCharacter(
                name = c.optString("name"),
                className = c.optString("class"),
                league = c.optString("league").ifEmpty { "Standard" },
                level = c.optInt("level", 1),
                current = c.optBoolean("current"),
            )
        }.filter { it.name.isNotEmpty() }
    }

    /** One character with its passives, equipment, jewels and skills (the response's "character"). */
    suspend fun character(name: String): JSONObject =
        apiGet("/character/poe2/${enc(name)}").optJSONObject("character")
            ?: throw PoeApiException("pathofexile.com sent no data for $name.")

    // ---- Tokens ----

    private fun load(): Tokens? = runCatching {
        val o = JSONObject(file.readText())
        Tokens(o.getString("access"), o.optString("refresh").ifEmpty { null }, o.optLong("expiresAt"), o.optString("username").ifEmpty { null })
    }.getOrNull()?.takeIf { it.access.isNotEmpty() }

    private fun store(t: Tokens) {
        tokens = t
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(t.toJson().toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private fun parseTokens(body: String, previousUsername: String?): Tokens {
        val o = runCatching { JSONObject(body) }.getOrNull() ?: throw PoeApiException("pathofexile.com sent an invalid sign-in response.")
        val access = o.optString("access_token").ifEmpty { throw PoeApiException("pathofexile.com sent no access token.") }
        val expiresIn = o.optLong("expires_in", 3600)
        return Tokens(
            access = access,
            refresh = o.optString("refresh_token").ifEmpty { null },
            expiresAt = System.currentTimeMillis() / 1000 + expiresIn,
            username = o.optString("username").ifEmpty { previousUsername },
        )
    }

    private fun expired(): Nothing {
        signOut()
        throw PoeApiException("Your pathofexile.com sign-in has expired. Sign in again.", signedOut = true)
    }

    /** Renews the access token with the refresh token. */
    private fun refresh(t: Tokens): String {
        val refreshToken = t.refresh ?: expired()
        val response = request("$WEB/oauth/token", form = form("client_id" to CLIENT_ID, "grant_type" to "refresh_token", "refresh_token" to refreshToken))
        if (response.code in 400..499) expired()
        if (response.code != 200) throw PoeApiException("pathofexile.com error: ${response.errorMessage()}")
        val renewed = parseTokens(response.body, t.username)
        store(renewed)
        return renewed.access
    }

    private suspend fun apiGet(path: String): JSONObject = mutex.withLock {
        withContext(Dispatchers.IO) {
            val t = tokens ?: throw PoeApiException("Not signed in.", signedOut = true)
            var access = if (t.expiresAt > System.currentTimeMillis() / 1000 + 60) t.access else refresh(t)
            var response = request(API + path, bearer = access)
            if (response.code == 401) {
                // Revoked or expired early: renew once
                access = refresh(tokens ?: expired())
                response = request(API + path, bearer = access)
            }
            when (response.code) {
                200 -> runCatching { JSONObject(response.body) }.getOrNull() ?: throw PoeApiException("pathofexile.com sent invalid data.")
                401 -> expired()
                404 -> throw PoeApiException("Character not found.")
                429 -> throw PoeApiException("Requests are being sent too fast, try again in ${response.retryAfter ?: 60} seconds.")
                else -> throw PoeApiException("pathofexile.com error: ${response.errorMessage()}")
            }
        }
    }

    // ---- HTTP ----

    private class Response(val code: Int, val body: String, val retryAfter: Long?) {
        /** The API's error message ({"error": {"message"}} or OAuth's error_description), or the status. */
        fun errorMessage(): String {
            val o = runCatching { JSONObject(body) }.getOrNull()
            val message = o?.optJSONObject("error")?.optString("message")?.ifEmpty { null }
                ?: o?.optString("error_description")?.ifEmpty { null }
                ?: o?.optString("error")?.ifEmpty { null }
            return if (message != null) "$message (HTTP $code)" else "HTTP $code"
        }
    }

    private fun request(url: String, form: String? = null, bearer: String? = null): Response {
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("User-Agent", userAgent)
                conn.setRequestProperty("Accept", "application/json")
                if (bearer != null) conn.setRequestProperty("Authorization", "Bearer $bearer")
                if (form != null) {
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    conn.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
                }
                val code = conn.responseCode
                val body = (if (code >= 400) conn.errorStream else conn.inputStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                return Response(code, body, conn.getHeaderField("Retry-After")?.trim()?.toLongOrNull())
            } finally {
                conn.disconnect()
            }
        } catch (e: IOException) {
            throw PoeApiException("Could not connect to pathofexile.com (${e.message ?: e.javaClass.simpleName}).")
        }
    }

    // ---- The local server receiving the browser's redirect ----

    private class LoopbackServer(private val sockets: List<ServerSocket>, val port: Int) : Closeable {

        /** Waits for the redirect with the authorization code (or an error) and answers it. */
        suspend fun awaitCallback(): Map<String, String> = coroutineScope {
            val result = CompletableDeferred<Map<String, String>>()
            val loops = sockets.map { socket ->
                launch(Dispatchers.IO) {
                    socket.soTimeout = 500
                    while (isActive && !result.isCompleted) {
                        val client = try {
                            socket.accept()
                        } catch (e: SocketTimeoutException) {
                            continue
                        } catch (e: IOException) {
                            break
                        }
                        // Other software may connect too: only the redirect ends the wait
                        runCatching { handle(client) }.getOrNull()?.let { result.complete(it) }
                    }
                }
            }
            try {
                result.await()
            } finally {
                loops.forEach { it.cancel() }
            }
        }

        private fun handle(client: java.net.Socket): Map<String, String>? = client.use { c ->
            c.soTimeout = 5_000
            val reader = c.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val requestLine = reader.readLine() ?: return null
            while (true) {
                val header = reader.readLine() ?: break
                if (header.isEmpty()) break
            }
            val parts = requestLine.split(' ')
            val target = parts.getOrNull(1) ?: return null
            val params = target.substringAfter('?', "").split('&').filter { '=' in it }.associate { kv ->
                URLDecoder.decode(kv.substringBefore('='), "UTF-8") to URLDecoder.decode(kv.substringAfter('='), "UTF-8")
            }
            val out = c.getOutputStream()
            if (parts[0] != "GET" || (params["code"] == null && params["error"] == null)) {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                out.flush()
                return null
            }
            val page = resultPage(params["code"] != null, params["error_description"] ?: params["error"]).toByteArray(Charsets.UTF_8)
            out.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${page.size}\r\n" +
                    "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
            )
            out.write(page)
            out.flush()
            params
        }

        override fun close() {
            sockets.forEach { runCatching { it.close() } }
        }

        companion object {
            /**
             * Listens on localhost (IPv4 and, when available, IPv6: browsers may resolve localhost to
             * either) on the first free port of the registered ones.
             */
            fun open(): LoopbackServer? {
                for (port in FIRST_PORT until FIRST_PORT + 3) {
                    val v4 = try {
                        ServerSocket(port, 4, InetAddress.getByName("127.0.0.1"))
                    } catch (e: IOException) {
                        continue
                    }
                    val v6 = try {
                        ServerSocket(port, 4, InetAddress.getByName("::1"))
                    } catch (e: IOException) {
                        null
                    }
                    return LoopbackServer(listOfNotNull(v4, v6), port)
                }
                return null
            }
        }
    }

    companion object {
        /** Path of Building's public OAuth client (PoEAPI.lua), with its localhost redirects. */
        const val CLIENT_ID = "pob"
        /** Only reading the account's characters is needed. */
        const val SCOPES = "account:characters"
        private const val FIRST_PORT = 49082
        private const val WEB = "https://www.pathofexile.com"
        private const val API = "https://api.pathofexile.com"
        /** Time for signing in in the browser. */
        const val SIGN_IN_TIMEOUT_MS = 5 * 60_000L
        /** Opens the app from the result page (MainActivity's intent filter). */
        const val RETURN_URI = "io.room.poe2tree://signed-in"

        private val random = SecureRandom()

        private fun randomUrlSafe(bytes: Int) = base64Url(ByteArray(bytes).also { random.nextBytes(it) })

        private fun base64Url(data: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(data)

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        private fun form(vararg fields: Pair<String, String>) = fields.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }

        private fun html(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        /** The page the browser shows after pathofexile.com sent it back to the app. */
        private fun resultPage(ok: Boolean, error: String?): String {
            val title = if (ok) "Signed in to Path of Exile" else "Sign-in not completed"
            val text = if (ok) "Return to PoE2 Passive Tree to choose the character to import."
            else "Path of Exile answered: ${html(error ?: "unknown error")}. Return to the app to try again."
            return """<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>$title</title>
<style>
body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:#0b0a08;color:#e6dfcf;font-family:sans-serif}
.card{max-width:420px;margin:24px;padding:24px;border:1px solid #4a4232;border-radius:12px;background:#17140f;text-align:center}
h1{font-size:20px;color:${if (ok) "#e8c77e" else "#dd4444"};margin:0 0 12px}
p{font-size:16px;line-height:1.4;margin:0 0 20px}
a{display:inline-block;padding:12px 20px;border-radius:8px;background:#c8a45a;color:#0b0a08;font-weight:bold;text-decoration:none}
</style></head>
<body><div class="card"><h1>$title</h1><p>$text</p><a href="$RETURN_URI">Return to the app</a></div></body></html>
"""
        }
    }
}
