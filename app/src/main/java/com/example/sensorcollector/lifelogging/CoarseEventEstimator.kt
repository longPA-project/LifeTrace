package com.example.sensorcollector.lifelogging

class CoarseEventEstimator {
    fun estimate(
        current: ContextSnapshot,
        samplingDecision: SamplingDecision,
        hasFreshPhoto: Boolean
    ): LocalEventEstimate {
        val reasons = mutableListOf<String>()
        val place = current.placeName.orEmpty().lowercase()
        val state = current.motion.state

        val eventType = when {
            state == ActivityState.IN_VEHICLE -> {
                reasons += "vehicle_motion"
                CoarseEventType.COMMUTING
            }
            place.contains("canteen") || place.contains("restaurant") || place.contains("food") -> {
                reasons += "food_related_place"
                CoarseEventType.EATING
            }
            place.contains("library") || place.contains("classroom") || place.contains("lab") -> {
                reasons += "study_related_place"
                CoarseEventType.STUDYING
            }
            place.contains("shop") || place.contains("market") || place.contains("mall") -> {
                reasons += "shopping_related_place"
                CoarseEventType.SHOPPING
            }
            place.contains("repair") || place.contains("garage") || place.contains("bike") -> {
                reasons += "repair_related_place"
                CoarseEventType.REPAIRING
            }
            else -> CoarseEventType.UNKNOWN
        }

        if (samplingDecision.reasons.any { it == "place_changed" }) {
            reasons += "place_changed"
        }
        if (samplingDecision.reasons.any { it == "state_changed" }) {
            reasons += "state_changed"
        }
        if (hasFreshPhoto) {
            reasons += "fresh_photo"
        }

        val possibleNewEvent = samplingDecision.mode == SamplingMode.HIGH_FREQUENCY ||
            reasons.any { it == "place_changed" || it == "state_changed" }
        val worthUploading = hasFreshPhoto && (
            possibleNewEvent ||
                eventType != CoarseEventType.UNKNOWN ||
                samplingDecision.reasons.any { it == "location_shifted" }
            )

        val confidence = when {
            eventType == CoarseEventType.UNKNOWN && possibleNewEvent -> 0.35
            eventType == CoarseEventType.UNKNOWN -> 0.15
            reasons.size >= 3 -> 0.8
            reasons.size == 2 -> 0.65
            else -> 0.5
        }

        return LocalEventEstimate(
            eventType = eventType,
            confidence = confidence,
            worthUploading = worthUploading,
            possibleNewEvent = possibleNewEvent,
            reasons = reasons.ifEmpty { listOf("context_stable") }
        )
    }
}
