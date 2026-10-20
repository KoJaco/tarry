package me.tarry.core.data.model

enum class SessionTrigger { SCHEDULE, MANUAL }

enum class SessionEndReason { SCHEDULE_END, USER_STOP, USER_PAUSE, KILLED, PERMISSION_LOST }

enum class ActivityType { STILL, WALKING, IN_VEHICLE, ON_BICYCLE, UNKNOWN }

enum class PingSource { FUSED, GEOFENCE_EXIT, MANUAL_START }

enum class VisitOrigin { DETECTED, MANUAL, SPLIT, MERGED }

enum class TripMode { VEHICLE, WALK, UNKNOWN }

enum class GapCause { TRACKING_STOPPED, SERVICE_KILLED, PERMISSION_LOST, LOCATION_OFF, NO_FIX, NO_SESSION }

enum class ManualOverrideType { START, PAUSE }

enum class ManualOverrideEndMode { UNTIL_STOPPED, END_OF_DAY, DURATION, NEXT_WINDOW }
