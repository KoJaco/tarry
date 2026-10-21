package me.tarry.app.segmentation

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import me.tarry.app.tracking.FakeClock
import me.tarry.app.tracking.TrackingConfig
import me.tarry.core.data.db.TarryDatabase
import me.tarry.core.data.db.entity.Place
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class FakeGeocoder(
    var available: Boolean = true,
    val results: MutableMap<Double, GeocodeResult> = mutableMapOf(),
) : PlaceGeocoder {
    val calls = mutableListOf<Double>()

    override fun isAvailable() = available

    override suspend fun lookup(lat: Double, lng: Double): GeocodeResult {
        calls += lat
        return results[lat] ?: GeocodeResult.NotFound
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class GeocodeWorkerTest {
    private lateinit var db: TarryDatabase
    private val geocoder = FakeGeocoder()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TarryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    /** Latitude doubles as the place key for the fake geocoder; a higher [lastVisitAt] is looked up first. */
    private suspend fun place(lat: Double, lastVisitAt: Long, address: String? = null) =
        db.placeDao().upsert(
            Place(id = "p$lat", centroidLat = lat, centroidLng = 0.0, address = address, lastVisitAt = lastVisitAt, updatedAt = 0),
        )

    private fun worker(config: TrackingConfig = TrackingConfig()): ListenableWorker =
        TestListenableWorkerBuilder<GeocodeWorker>(ApplicationProvider.getApplicationContext())
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    GeocodeWorker(appContext, workerParameters, geocoder, db.placeDao(), FakeClock(now = 5_000L), config)
            })
            .build()

    private suspend fun stored(lat: Double) = db.placeDao().getById("p$lat")!!

    @Test
    fun foundSavesAddressAndUpdatedAt() = runBlocking<Unit> {
        place(1.0, 10)
        geocoder.results[1.0] = GeocodeResult.Found("1 Main St")

        assertThat(worker().startWork().get()).isEqualTo(ListenableWorker.Result.success())

        assertThat(stored(1.0).address).isEqualTo("1 Main St")
        assertThat(stored(1.0).updatedAt).isEqualTo(5_000L)
    }

    @Test
    fun notFoundSavesEmptyAddressAndIsNeverRetried() = runBlocking<Unit> {
        place(1.0, 10)

        assertThat(worker().startWork().get()).isEqualTo(ListenableWorker.Result.success())
        assertThat(stored(1.0).address).isEqualTo("")

        worker().startWork().get()
        assertThat(geocoder.calls).containsExactly(1.0)
    }

    @Test
    fun failedStopsTheBatchAndRetries() = runBlocking<Unit> {
        place(1.0, 30)
        place(2.0, 20)
        place(3.0, 10)
        geocoder.results[1.0] = GeocodeResult.Found("A")
        geocoder.results[2.0] = GeocodeResult.Failed(retryable = true)

        assertThat(worker().startWork().get()).isEqualTo(ListenableWorker.Result.retry())

        assertThat(stored(1.0).address).isEqualTo("A")
        assertThat(stored(2.0).address).isNull()
        assertThat(stored(3.0).address).isNull()
        assertThat(geocoder.calls).containsExactly(1.0, 2.0).inOrder()
    }

    @Test
    fun unavailableGeocoderDoesNothing() = runBlocking<Unit> {
        place(1.0, 10)
        geocoder.available = false

        assertThat(worker().startWork().get()).isEqualTo(ListenableWorker.Result.success())

        assertThat(geocoder.calls).isEmpty()
        assertThat(stored(1.0).address).isNull()
    }

    @Test
    fun batchIsLimitedAndSkipsPlacesWithAnAddress() = runBlocking<Unit> {
        place(1.0, 50, address = "Already")
        place(2.0, 40)
        place(3.0, 30)
        place(4.0, 20)

        assertThat(worker(TrackingConfig(geocodeBatchSize = 2)).startWork().get()).isEqualTo(ListenableWorker.Result.success())

        assertThat(geocoder.calls).containsExactly(2.0, 3.0).inOrder()
        assertThat(stored(4.0).address).isNull()
    }
}
