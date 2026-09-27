package io.room.poe2tree

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.room.poe2tree.io.PoeAccount
import io.room.poe2tree.io.PoeApiException
import io.room.poe2tree.io.PoeCharacter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject

/** What to import and how: the options of the Character Import section of PoB's Import/Export tab. */
data class CharacterImportOptions(
    /** Into a new build (named after the character), or into the open build. */
    val newBuild: Boolean = true,
    /** "Passive Tree and Jewels" */
    val tree: Boolean = true,
    /** "Items and Skills" */
    val items: Boolean = true,
    val clearJewels: Boolean = true,
    val clearSkills: Boolean = true,
    val clearItems: Boolean = true,
    val ignoreWeaponSwap: Boolean = false,
) {
    fun toEngineJson(): JSONObject = JSONObject()
        .put("tree", tree).put("items", items)
        .put("clearJewels", clearJewels).put("clearSkills", clearSkills).put("clearItems", clearItems)
        .put("ignoreWeaponSwap", ignoreWeaponSwap)
}

/**
 * The Path of Exile account for character import: sign-in state and the character list, for the
 * import dialog. [openUrl] opens a page in the browser (and throws if it cannot).
 */
class CharacterImport(private val account: PoeAccount, private val scope: CoroutineScope, private val openUrl: (String) -> Unit) {

    var signedIn by mutableStateOf(account.signedIn)
        private set
    var username by mutableStateOf(account.username)
        private set
    /** Waiting for the sign-in in the browser. */
    var signingIn by mutableStateOf(false)
        private set
    /** The sign-in page, while [signingIn] (to open it again). */
    var signInUrl by mutableStateOf<String?>(null)
        private set
    /** The account's characters (null until loaded). */
    var characters by mutableStateOf<List<PoeCharacter>?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    /** The league and character chosen last (kept while the app runs). */
    var league by mutableStateOf<String?>(null)
    var selected by mutableStateOf<String?>(null)

    private var job: Job? = null

    fun signIn() {
        job?.cancel()
        error = null
        signingIn = true
        job = scope.launch {
            try {
                account.signIn { url ->
                    signInUrl = url
                    openUrl(url)
                }
                refreshAccount()
                fetchCharacters()
            } catch (e: PoeApiException) {
                error = e.message
            } catch (e: android.content.ActivityNotFoundException) {
                error = "No web browser is installed to sign in with."
            } finally {
                signingIn = false
                signInUrl = null
            }
        }
    }

    fun reopenSignInPage() {
        val url = signInUrl ?: return
        runCatching { openUrl(url) }.onFailure { error = "No web browser is installed to sign in with." }
    }

    fun cancel() {
        job?.cancel()
    }

    fun signOut() {
        job?.cancel()
        account.signOut()
        refreshAccount()
        characters = null
        error = null
    }

    /** Loads the character list (again). */
    fun loadCharacters() {
        if (!signedIn || loading) return
        job?.cancel()
        job = scope.launch { fetchCharacters() }
    }

    private suspend fun fetchCharacters() {
        loading = true
        error = null
        try {
            val list = account.characters().sortedBy { it.name.lowercase() }
            characters = list
            // PoB selects the league of the last import; the character last played otherwise
            val current = list.firstOrNull { it.current } ?: list.firstOrNull()
            if (league == null || list.none { it.league == league }) league = current?.league
            if (selected == null || list.none { it.name == selected }) selected = current?.name
            if (list.isEmpty()) error = "The account has no Path of Exile 2 characters."
        } catch (e: PoeApiException) {
            error = e.message
        } finally {
            loading = false
            refreshAccount()
        }
    }

    /** Downloads a character; null with [error] set on failure. */
    suspend fun download(name: String): JSONObject? {
        error = null
        return try {
            account.character(name)
        } catch (e: PoeApiException) {
            error = e.message
            null
        } finally {
            refreshAccount()
        }
    }

    private fun refreshAccount() {
        signedIn = account.signedIn
        username = account.username
    }
}
