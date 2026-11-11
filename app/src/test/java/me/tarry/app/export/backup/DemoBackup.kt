package me.tarry.app.export.backup

import me.tarry.app.segmentation.SegmentationUpgrade
import me.tarry.app.ui.theme.JobPalette
import me.tarry.core.data.model.ActivityType
import me.tarry.core.data.model.GapCause
import me.tarry.core.data.model.PingSource
import me.tarry.core.data.model.Schedule
import me.tarry.core.data.model.SessionEndReason
import me.tarry.core.data.model.SessionTrigger
import me.tarry.core.data.model.TripMode
import me.tarry.core.data.model.VisitOrigin
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64
import java.util.UUID
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Builds a Tarry backup of an invented inner-Melbourne contractor: ten working days ending on [build]'s date.
 * Test-source only, used for marketing screenshots (see `docs/handoffs/marketing-demo-backup.md`). Every name is invented
 * and no note holds an address; coordinates sit on real streets only so the map tiles look real.
 *
 * Segmentation on restore: [BackupManager.restore] calls `afterRestore`, which re-segments only if the restored
 * `segmentation_version` is behind. The file therefore carries the current version and a checkpoint at the last ping, so
 * the segmented rows written here (visits, trips, gaps) are kept as they are. Labels are job blocks (time ranges) and
 * notes sit on frozen visits, so they would survive a re-run anyway.
 */
object DemoBackup {
    const val ZONE = "Australia/Melbourne"
    const val DAYS = 10
    const val OUTPUT_PATH = "build/demo/tarry-demo.json"

    private class Place(val code: Char, val name: String, val lat: Double, val lng: Double, val radiusM: Float, val job: Int)

    private val places = listOf(
        Place('D', "Northside depot", -37.7712, 144.9618, 80f, 3),
        Place('S', "Supplier yard", -37.7893, 144.9722, 90f, 3),
        Place('Y', "Yarra Bend Apartments", -37.7967, 145.0023, 70f, 0),
        Place('W', "Collingwood warehouse fit-out", -37.8036, 144.9889, 100f, 1),
        Place('C', "Café on Smith", -37.8021, 144.9833, 50f, 3),
        Place('T', "Richmond terrace reno", -37.8187, 144.9996, 60f, 2),
    ).associateBy { it.code }

    private class JobSpec(val name: String, val slot: Int, val billable: Boolean)

    private val jobs = listOf(
        JobSpec("Yarra Bend – Stage 2", 1, true),
        JobSpec("Collingwood fit-out", 2, true),
        JobSpec("Terrace reno", 3, true),
        JobSpec("Admin & supplies", 7, false),
    )

    /** Place code + minutes on site. Mon–Thu use [longDays], Friday [shortDays]. */
    private val longDays = listOf(
        "D25 S30 Y165 C35 Y110 D15",
        "D20 W170 C40 W120 S25",
        "D25 T180 C35 T105",
        "D20 S25 W150 C30 Y135",
        "D25 Y150 C40 T150 S20",
        "D30 W120 C35 W90 T100",
    )
    private val shortDays = listOf("D20 W110 C30 W90", "D20 Y120 C30 T60")

    private val notes = listOf("Measured kitchen", "Waiting on tiles", "Plasterer ran late", "Delivery due Thursday")

    private class Vis(val place: Place, val start: Long, val end: Long) {
        var note = ""
        var review = false
        var tripAfter: Trp? = null
    }

    private class Trp(val start: Long, val end: Long, val distanceM: Float)

    private class PingSpec(val ts: Long, val lat: Double, val lng: Double, val moving: Boolean, val source: PingSource, val speed: Float?)

    private class Day {
        val visits = mutableListOf<Vis>()
        val pings = mutableListOf<PingSpec>()
        var noFix: LongRange? = null
        var kill: LongRange? = null
    }

    private fun id(kind: String, n: Int) = UUID.nameUUIDFromBytes("tarry-demo-$kind-$n".toByteArray()).toString()

    private fun jobId(n: Int) = id("job", n)

    private fun placeId(p: Place) = id("place", p.code.code)

    /** The [DAYS] latest Monday-to-Friday dates up to and including [today], oldest first. */
    fun workingDays(today: LocalDate): List<LocalDate> =
        generateSequence(today) { it.minusDays(1) }
            .filter { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY }
            .take(DAYS).toList().reversed()

    fun build(today: LocalDate): BackupFile {
        val zone = ZoneId.of(ZONE)
        val dates = workingDays(today)
        val days = dates.mapIndexed { i, d -> buildDay(i, d, zone) }
        val createdAt = dates.last().atTime(18, 0).atZone(zone).toInstant().toEpochMilli()

        val sessions = mutableListOf<SessionDto>()
        val pings = mutableListOf<PingDto>()
        val visits = mutableListOf<VisitDto>()
        val trips = mutableListOf<TripDto>()
        val blocks = mutableListOf<JobBlockDto>()
        val gaps = mutableListOf<GapDto>()
        val lastVisitAt = HashMap<Char, Long>()
        var pingId = 1L
        var visitN = 0
        var tripN = 0
        var blockN = 0
        var gapN = 0

        for (day in days) {
            val first = day.pings.first().ts
            val last = day.pings.last().ts
            val kill = day.kill
            val a = sessions.size + 1L
            val sessionA: SessionDto
            var sessionB: SessionDto? = null
            if (kill == null) {
                sessionA = SessionDto(a, first, last, ZONE, SessionTrigger.SCHEDULE, SessionEndReason.SCHEDULE_END)
            } else {
                sessionA = SessionDto(a, first, kill.first, ZONE, SessionTrigger.SCHEDULE, SessionEndReason.KILLED)
                sessionB = SessionDto(a + 1, kill.last, last, ZONE, SessionTrigger.SCHEDULE, SessionEndReason.SCHEDULE_END)
            }
            sessions += sessionA
            if (sessionB != null) sessions += sessionB
            for (p in day.pings) {
                val s = if (kill != null && p.ts >= kill.last) sessionB!! else sessionA
                val rnd = Random(p.ts)
                pings += PingDto(
                    pingId++, p.ts, ZONE, p.lat, p.lng, (50 + rnd.nextInt(101)) / 10f, p.speed, null,
                    if (p.moving) ActivityType.IN_VEHICLE else ActivityType.STILL, p.source, s.id,
                )
            }
            day.noFix?.let { gaps += GapDto(id("gap", gapN++), it.first, it.last, ZONE, GapCause.NO_FIX, sessionA.id, createdAt) }
            kill?.let { gaps += GapDto(id("gap", gapN++), it.first, it.last, ZONE, GapCause.SERVICE_KILLED, sessionA.id, createdAt) }

            val visitIds = day.visits.map { id("visit", visitN++) }
            day.visits.forEachIndexed { i, v ->
                visits += VisitDto(
                    visitIds[i], placeId(v.place), v.start, v.end, ZONE, v.note, frozen = v.note.isNotEmpty(),
                    origin = VisitOrigin.DETECTED, confidence = 0.9f, updatedAt = createdAt,
                )
                lastVisitAt.merge(v.place.code, v.end, ::maxOf)
                v.tripAfter?.let { t ->
                    trips += TripDto(
                        id("trip", tripN++), t.start, t.end, ZONE, visitIds[i], visitIds[i + 1], t.distanceM, TripMode.VEHICLE,
                        updatedAt = createdAt,
                    )
                }
            }
            // One job block per run of consecutive visits with the same job; a visit left for review breaks the run.
            var i = 0
            while (i < day.visits.size) {
                val v = day.visits[i]
                if (v.review) {
                    i++
                    continue
                }
                var j = i
                while (j + 1 < day.visits.size && !day.visits[j + 1].review && day.visits[j + 1].place.job == v.place.job) j++
                blocks += JobBlockDto(
                    id("block", blockN++), jobId(v.place.job), v.start, day.visits[j].end, ZONE, jobs[v.place.job].billable, null, createdAt,
                )
                i = j + 1
            }
        }

        var schedule = Schedule.EMPTY
        for (d in listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY)) {
            schedule = schedule.withSlots(d, 14..33, true)
        }
        schedule = schedule.withSlots(DayOfWeek.FRIDAY, 14..27, true)

        return BackupFile(
            format = BackupCodec.FORMAT,
            formatVersion = BackupCodec.CURRENT_VERSION,
            createdAt = createdAt,
            zoneId = ZONE,
            appVersionName = "demo",
            settings = listOf(PrefEntry("segmentation_version", "int", SegmentationUpgrade.SEGMENTATION_VERSION.toString())),
            tables = BackupTables(
                sessions = sessions,
                pings = pings,
                jobs = jobs.mapIndexed { n, j ->
                    JobDto(jobId(n), j.name, JobPalette.slots[j.slot].key, defaultBillable = j.billable, updatedAt = createdAt)
                },
                places = places.values.map { p ->
                    PlaceDto(
                        placeId(p), p.lat, p.lng, p.radiusM, name = p.name, defaultJobId = jobId(p.job),
                        lastVisitAt = lastVisitAt[p.code], updatedAt = createdAt,
                    )
                },
                visits = visits,
                trips = trips,
                jobBlocks = blocks,
                gaps = gaps,
                scheduleTemplates = listOf(ScheduleTemplateDto(0, Base64.getEncoder().encodeToString(schedule.toBytes()), createdAt)),
                checkpoints = listOf(CheckpointDto(0, pings.last().timestamp)),
            ),
        )
    }

    private fun buildDay(index: Int, date: LocalDate, zone: ZoneId): Day {
        val day = Day()
        val rnd = Random(date.toEpochDay())
        val friday = date.dayOfWeek == DayOfWeek.FRIDAY
        val plan = (if (friday) shortDays[index % shortDays.size] else longDays[index % longDays.size]).split(' ')
        val limit = date.atTime(if (friday) 13 else 16, 30).atZone(zone).toInstant().toEpochMilli()
        var t = date.atTime(7, 0).atZone(zone).toInstant().toEpochMilli()

        // The visit that waits out a NO_FIX (basement car park) is the first long one away from the depot; the killed
        // session lands in the middle of the longest one. Both in week one only.
        val gapAt = if (index == 2) plan.indexOfFirst { it[0] != 'D' && it.drop(1).toInt() > 60 } else -1
        val killAt = if (index == 4) plan.indices.maxBy { plan[it].drop(1).toInt() } else -1

        var prev: Vis? = null
        plan.forEachIndexed { n, stop ->
            val place = places.getValue(stop[0])
            val minutes = stop.drop(1).toInt()
            val before = prev
            if (before != null) {
                val toLat = place.lat + if (n == gapAt) 0.0004 else 0.0
                val leg = leg(before.place, toLat, place.lng, t, rnd, finalPing = n == gapAt)
                day.pings += leg.pings
                before.tripAfter = Trp(t, leg.arrive, leg.distanceM)
                t = leg.arrive
                if (n == gapAt) {
                    val resume = t + 25 * 60_000L
                    day.noFix = t..resume
                    t = resume
                }
            }
            val end = t + minutes * 60_000L
            if (n == killAt) {
                val kill = t + (minutes * 0.4).toInt() / 2 * 2 * 60_000L
                val restart = kill + 8 * 60_000L
                stillPings(day, place, t, kill, rnd)
                stillPings(day, place, restart, end, rnd)
                day.visits += Vis(place, t, kill)
                val v2 = Vis(place, restart, end)
                day.visits += v2
                day.kill = kill..restart
                prev = v2
            } else {
                val v = Vis(place, t, end)
                stillPings(day, place, t, end, rnd)
                day.visits += v
                prev = v
            }
            t = end
        }
        require(day.visits.size in 3..7) { "day $index has ${day.visits.size} visits" }
        require(t <= limit) { "day $index ends after its limit" }

        // Needs-review visits: one in the middle of each of the last three days.
        if (index >= DAYS - 3) day.visits[day.visits.size / 2].review = true
        // A note on the longest visit that is not left for review, every few days.
        if (index in setOf(1, 3, 6, 9)) {
            day.visits.filter { !it.review }.maxBy { it.end - it.start }.note = notes[(index / 2) % notes.size]
        }
        return day
    }

    private class Leg(val pings: List<PingSpec>, val arrive: Long, val distanceM: Float)

    /**
     * A drive from [from] to a point near the next place: a ping every 30 s along a gently bent line. The arrival
     * instant has no moving ping (the next stay's first ping is there) unless [finalPing]: before a NO_FIX gap the last
     * fix is the one at the car park entrance.
     */
    private fun leg(from: Place, toLat: Double, toLng: Double, start: Long, rnd: Random, finalPing: Boolean): Leg {
        val straight = haversineM(from.lat, from.lng, toLat, toLng)
        val seconds = (straight * 1.35 / 8.0).toLong().coerceAtLeast(240) // about 29 km/h through the inner suburbs
        val steps = (seconds / 30).toInt().coerceAtLeast(8)
        val bendLat = -(toLng - from.lng) * 0.12
        val bendLng = (toLat - from.lat) * 0.12
        val out = ArrayList<PingSpec>()
        var prevLat = from.lat
        var prevLng = from.lng
        var dist = 0.0
        for (k in 1..steps) {
            val f = k / steps.toDouble()
            val bend = sin(PI * f)
            val lat = from.lat + (toLat - from.lat) * f + bendLat * bend + jitter(rnd, 0.00003)
            val lng = from.lng + (toLng - from.lng) * f + bendLng * bend + jitter(rnd, 0.00003)
            dist += haversineM(prevLat, prevLng, lat, lng)
            val speed = (haversineM(prevLat, prevLng, lat, lng) / 30.0).toFloat()
            prevLat = lat
            prevLng = lng
            if (k < steps || finalPing) out += PingSpec(start + k * 30_000L, lat, lng, true, PingSource.FUSED, speed)
        }
        return Leg(out, start + steps * 30_000L, dist.toFloat())
    }

    /** A ping every 2 minutes from [start] to [end] inclusive, within a few metres of the place. */
    private fun stillPings(day: Day, place: Place, start: Long, end: Long, rnd: Random) {
        var ts = start
        while (ts < end) {
            day.pings += still(place, ts, rnd, PingSource.FUSED)
            ts += 120_000L
        }
        day.pings += still(place, end, rnd, PingSource.GEOFENCE_EXIT)
    }

    private fun still(place: Place, ts: Long, rnd: Random, source: PingSource) =
        PingSpec(ts, place.lat + jitter(rnd, 0.00005), place.lng + jitter(rnd, 0.00006), false, source, 0f)

    private fun jitter(rnd: Random, amount: Double) = (rnd.nextDouble() * 2 - 1) * amount

    private fun haversineM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * asin(sqrt(a))
    }
}
