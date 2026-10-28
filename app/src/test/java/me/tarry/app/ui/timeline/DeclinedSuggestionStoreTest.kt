package me.tarry.app.ui.timeline

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeclinedSuggestionStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @After
    fun tearDown() = scope.cancel()

    private fun store(max: Int = 500, name: String = "declined.preferences_pb") =
        DeclinedSuggestionStore(createDeclinedSuggestionsDataStore(scope) { folder.root.resolve(name) }, DeclinedSuggestionConfig(max))

    @Test
    fun aDeclineIsReadBackFromTheFileByANewStore() = runBlocking<Unit> {
        // One DataStore per file is allowed, so a "restart" is a fresh store over the same DataStore (the file is the state).
        val ds = createDeclinedSuggestionsDataStore(scope) { folder.root.resolve("restart.preferences_pb") }
        DeclinedSuggestionStore(ds, DeclinedSuggestionConfig()).decline("v1", "j1")

        assertThat(DeclinedSuggestionStore(ds, DeclinedSuggestionConfig()).declined.first()).containsExactly("v1" to "j1")
    }

    @Test
    fun decliningTwiceKeepsOneEntryAndAnotherJobAtTheVisitIsSeparate() = runBlocking<Unit> {
        val s = store()
        s.decline("v1", "j1")
        s.decline("v1", "j1")
        s.decline("v1", "j2")

        assertThat(s.declined.first()).containsExactly("v1" to "j1", "v1" to "j2")
    }

    @Test
    fun pruneDropsPairsOfVisitsThatNoLongerExist() = runBlocking<Unit> {
        val s = store()
        s.decline("live", "j")
        s.decline("gone", "j")
        s.decline("gone", "k")

        s.prune { it == "live" }

        assertThat(s.declined.first()).containsExactly("live" to "j")
    }

    @Test
    fun theCapDropsTheOldestEntries() = runBlocking<Unit> {
        val s = store(max = 3)
        (1..5).forEach { s.decline("v$it", "j") }

        assertThat(s.declined.first()).containsExactly("v3" to "j", "v4" to "j", "v5" to "j")
    }
}
