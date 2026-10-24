package me.tarry.app.ui.places

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import me.tarry.core.data.db.TarryDatabase
import me.tarry.core.data.db.entity.Place
import me.tarry.core.data.db.entity.Visit
import me.tarry.core.data.model.VisitOrigin
import me.tarry.core.data.repository.PlaceRepository
import me.tarry.core.data.repository.TimelineRepository
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PlacesViewModelTest {
    private lateinit var db: TarryDatabase
    private lateinit var viewModel: PlacesViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TarryDatabase::class.java).allowMainThreadQueries().build()
        viewModel = PlacesViewModel(
            PlaceRepository(db.placeDao()),
            TimelineRepository(db.visitDao(), db.tripDao(), db.gapDao(), db.excludedRangeDao()),
        )
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun place(id: String, lastVisitAt: Long?, name: String? = null, address: String? = null, ignored: Boolean = false) =
        Place(id = id, centroidLat = 1.0, centroidLng = 2.0, name = name, address = address, ignored = ignored, lastVisitAt = lastVisitAt, updatedAt = 0)

    private fun visit(id: String, placeId: String, deleted: Boolean = false) = Visit(
        id = id, placeId = placeId, start = 0, end = 1000, zoneId = "UTC", origin = VisitOrigin.DETECTED, confidence = 1f, updatedAt = 0, deleted = deleted,
    )

    private fun awaitState(predicate: (PlacesUiState) -> Boolean = { true }): PlacesUiState =
        runBlocking { withTimeout(10_000) { viewModel.state.first { it != null && predicate(it) } } }!!

    @Test
    fun listsLivePlacesNewestVisitFirstWithLabelsCountsAndIgnoredFlag() = runBlocking<Unit> {
        db.placeDao().upsert(place("old", lastVisitAt = 100, address = "1 Main St", ignored = true))
        db.placeDao().upsert(place("new", lastVisitAt = 200, name = "Shop", address = "9 Side St"))
        db.placeDao().upsert(place("gone", lastVisitAt = 300, name = "Deleted").copy(deleted = true))
        db.visitDao().upsert(visit("v1", "new"))
        db.visitDao().upsert(visit("v2", "new"))
        db.visitDao().upsert(visit("v3", "new", deleted = true))
        db.visitDao().upsert(visit("v4", "old"))

        val rows = awaitState { it.rows.size == 2 && it.rows.first().visitCount == 2 }.rows

        assertThat(rows.map { it.id }).containsExactly("new", "old").inOrder()
        assertThat(rows.map { it.label }).containsExactly("Shop", "1 Main St").inOrder()
        assertThat(rows.map { it.visitCount }).containsExactly(2, 1).inOrder()
        assertThat(rows.map { it.ignored }).containsExactly(false, true).inOrder()
    }

    @Test
    fun aPlaceWithNoNameOrAddressIsLabelledByCoordinates() = runBlocking<Unit> {
        db.placeDao().upsert(place("p", lastVisitAt = null))

        val row = awaitState { it.rows.isNotEmpty() }.rows.single()

        assertThat(row.label).isEqualTo("1.00000, 2.00000")
        assertThat(row.visitCount).isEqualTo(0)
    }
}
