package me.tarry.app.ui

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import me.tarry.app.R
import me.tarry.app.ui.shell.ShellStatusViewModel
import me.tarry.app.ui.undo.UndoEvent
import me.tarry.core.data.repository.Snapshot
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShellStatusViewModelTest {
    private val events = MutableSharedFlow<UndoEvent>(extraBufferCapacity = 1)
    private val undone = mutableListOf<UndoEvent>()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun undoCallsThroughToTheUndoHandler() {
        val event = UndoEvent(R.string.nav_timeline, Snapshot(null, emptyList(), emptyList(), emptyList(), emptyList(), emptyList()))
        ShellStatusViewModel(events) { undone += it }.undo(event)
        assertThat(undone).containsExactly(event)
    }
}
