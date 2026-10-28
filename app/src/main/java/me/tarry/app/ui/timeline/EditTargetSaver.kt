package me.tarry.app.ui.timeline

import androidx.compose.runtime.saveable.Saver
import me.tarry.app.ui.drawer.EditTarget

/** Saves the open [EditTarget] across rotation and process death (the sealed type itself is not Parcelable). */
internal val EditTargetSaver: Saver<EditTarget?, Any> = Saver(
    save = { target ->
        when (target) {
            null -> emptyList<Any>()
            is EditTarget.Visit -> listOf("visit", target.id)
            is EditTarget.Trip -> listOf("trip", target.id)
            is EditTarget.NewVisit -> listOf("new", target.start, target.end, target.contextTripId ?: "")
            is EditTarget.Job -> listOf("job", target.id)
            is EditTarget.NewJob -> listOf("newjob", target.start, target.end, target.jobId ?: "")
        }
    },
    restore = { saved ->
        val l = saved as List<*>
        when (l.firstOrNull()) {
            "visit" -> EditTarget.Visit(l[1] as String)
            "trip" -> EditTarget.Trip(l[1] as String)
            "new" -> EditTarget.NewVisit(l[1] as Long, l[2] as Long, (l[3] as String).ifEmpty { null })
            "job" -> EditTarget.Job(l[1] as String)
            "newjob" -> EditTarget.NewJob(l[1] as Long, l[2] as Long, (l[3] as String).ifEmpty { null })
            else -> null
        }
    },
)
