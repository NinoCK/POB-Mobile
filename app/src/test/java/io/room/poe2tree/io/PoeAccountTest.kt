package io.room.poe2tree.io

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import kotlin.concurrent.thread

/** The sign-in up to the token exchange: the authorization URL and the local server receiving the browser's redirect. */
class PoeAccountTest {
    private val file = File("build/tmp/poe-account-test.json").apply { parentFile.mkdirs(); delete() }

    private fun query(url: String): Map<String, String> = URI(url).rawQuery.split('&').associate {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
    }

    private fun get(url: String): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        val code = conn.responseCode
        val body = (if (code >= 400) conn.errorStream else conn.inputStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        return code to body
    }

    /** Signs in with a browser that is sent back with [answer] (query parameters, given the sign-in's). */
    private fun signIn(answer: (Map<String, String>) -> String): Pair<Throwable?, List<Pair<Int, String>>> {
        val account = PoeAccount(file, "test")
        val pages = ArrayList<Pair<Int, String>>()
        val error = runCatching {
            runBlocking {
                account.signIn { url ->
                    val q = query(url)
                    assertTrue(url.startsWith("https://www.pathofexile.com/oauth/authorize?"))
                    assertEquals("pob", q["client_id"])
                    assertEquals("code", q["response_type"])
                    assertEquals("account:characters", q["scope"])
                    assertEquals("S256", q["code_challenge_method"])
                    assertEquals(43, q.getValue("code_challenge").length)
                    val redirect = q.getValue("redirect_uri")
                    assertTrue(redirect, redirect.matches(Regex("http://localhost:4908[234]")))
                    thread {
                        // Something else connecting first, then the browser
                        pages += get("${redirect.replace("localhost", "127.0.0.1")}/favicon.ico")
                        pages += get("${redirect.replace("localhost", "127.0.0.1")}/?${answer(q)}")
                    }
                }
            }
        }.exceptionOrNull()
        return error to pages
    }

    @Test
    fun deniedAccess() {
        val (error, pages) = signIn { q -> "error=access_denied&error_description=The%20user%20denied&state=${q["state"]}" }
        assertEquals("The access was not granted.", error?.message)
        assertEquals(404, pages[0].first)
        assertEquals(200, pages[1].first)
        assertTrue(pages[1].second.contains("Sign-in not completed"))
        assertTrue(pages[1].second.contains(PoeAccount.RETURN_URI))
        assertFalse(file.exists())
    }

    @Test
    fun rejectsAnotherSignInsResponse() {
        val (error, pages) = signIn { "code=abc&state=forged" }
        assertEquals("Sign-in failed: the response does not belong to this sign-in.", error?.message)
        assertTrue(pages[1].second.contains("Signed in to Path of Exile"))
        assertFalse(file.exists())
    }

    @Test
    fun portIsFreedAfterSignIn() {
        repeat(2) { signIn { q -> "error=access_denied&state=${q["state"]}" } }
        // The first port is free again
        java.net.ServerSocket(49082, 1, java.net.InetAddress.getByName("127.0.0.1")).close()
    }
}
