package lava.tracker.api.model

import kotlinx.serialization.Serializable

@Serializable
enum class TimePeriod { LAST_DAY, LAST_WEEK, LAST_MONTH, LAST_YEAR, ALL_TIME }
