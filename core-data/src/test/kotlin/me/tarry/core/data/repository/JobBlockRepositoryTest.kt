package me.tarry.core.data.repository

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import me.tarry.core.data.db.DbTestBase
import me.tarry.core.data.db.entity.Job
import me.tarry.core.data.db.entity.JobBlock
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JobBlockRepositoryTest : DbTestBase() {
    private lateinit var repo: JobBlockRepository

    @Before
    fun setUp() {
        repo = JobBlockRepository(db.jobBlockDao())
    }

    private suspend fun seedJob() = db.jobDao().upsert(Job(id = "j", name = "J", colorArgb = 1, updatedAt = 1))

    private fun block(id: String, start: Long, end: Long, deleted: Boolean = false) =
        JobBlock(id = id, jobId = "j", start = start, end = end, zoneId = "UTC", billable = true, updatedAt = 1, deleted = deleted)

    private suspend fun ids(from: Long, to: Long) = repo.observeOverlapping(from, to).first().map { it.id }

    @Test
    fun overlapQueryIsHalfOpenAndOrderedByStart() = runTest {
        seedJob()
        listOf(
            block("late", 1500, 1800), block("early", 500, 1200), block("before", 100, 1000),
            block("after", 2000, 2500), block("spans", 0, 5000),
        ).forEach { repo.save(it, now = 5) }

        // [1000, 2000): "before" ends exactly at the window start and "after" starts exactly at its end, so neither overlaps.
        assertThat(ids(1000, 2000)).containsExactly("spans", "early", "late").inOrder()
    }

    @Test
    fun saveInsertsThenUpdatesAndStampsUpdatedAt() = runTest {
        seedJob()
        repo.save(block("b", 0, 10), now = 50)
        assertThat(repo.byId("b")!!.updatedAt).isEqualTo(50)

        repo.save(repo.byId("b")!!.copy(notes = "site", billable = false), now = 60)

        val row = repo.byId("b")!!
        assertThat(row.notes).isEqualTo("site")
        assertThat(row.billable).isFalse()
        assertThat(row.updatedAt).isEqualTo(60)
    }

    @Test
    fun deleteIsSoftExcludedFromTheQueryAndRestoreBringsItBack() = runTest {
        seedJob()
        repo.save(block("b", 0, 10), now = 1)

        repo.delete("b", now = 70)

        assertThat(ids(0, 100)).isEmpty()
        val gone = repo.byId("b")!!
        assertThat(gone.deleted).isTrue()
        assertThat(gone.updatedAt).isEqualTo(70)

        repo.restore("b", now = 80)

        assertThat(ids(0, 100)).containsExactly("b")
        assertThat(repo.byId("b")!!.updatedAt).isEqualTo(80)
    }

    @Test
    fun deleteAndRestoreOfAMissingBlockDoNothing() = runTest {
        repo.delete("nope")
        repo.restore("nope")
        assertThat(repo.byId("nope")).isNull()
    }

    @Test
    fun observeSeesBlocksSavedAfterwards() = runTest {
        seedJob()
        val flow = repo.observeOverlapping(0, 100)
        assertThat(flow.first()).isEmpty()
        repo.save(block("b", 10, 20))
        assertThat(flow.first().map { it.id }).containsExactly("b")
    }

    @Test
    fun replaceSavesAndSoftDeletesTogether() = runTest {
        seedJob()
        listOf(block("a", 0, 10), block("b", 20, 30), block("c", 40, 50)).forEach { repo.save(it, now = 1) }

        repo.replace(block("a", 0, 30), listOf("b", "c"), now = 90)

        assertThat(repo.overlapping(0, 100).map { it.id to it.end }).containsExactly("a" to 30L)
        assertThat(repo.byId("a")!!.updatedAt).isEqualTo(90)
        assertThat(repo.byId("b")!!.deleted).isTrue()
        assertThat(repo.byId("b")!!.updatedAt).isEqualTo(90)
        assertThat(repo.byId("c")!!.deleted).isTrue()
    }

    @Test
    fun replaceWithNoDeletionsJustSaves() = runTest {
        seedJob()
        repo.replace(block("a", 0, 10), emptyList(), now = 5)
        assertThat(ids(0, 100)).containsExactly("a")
    }

    @Test
    fun replaceRollsBackTheSaveWhenTheDeletionFails() = runTest {
        // A block for a job that does not exist violates the foreign key, so the save fails; nothing else may change.
        seedJob()
        repo.save(block("keep", 0, 10), now = 1)

        runCatching { repo.replace(block("x", 0, 5).copy(jobId = "ghost"), listOf("keep"), now = 9) }

        assertThat(repo.byId("keep")!!.deleted).isFalse()
        assertThat(repo.byId("x")).isNull()
    }
}
