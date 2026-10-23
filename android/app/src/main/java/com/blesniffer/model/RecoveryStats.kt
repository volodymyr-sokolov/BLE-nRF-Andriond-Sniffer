package com.blesniffer.model

/**
 * Session-wide totals behind the recovery statistics screen.
 *
 * Everything is keyed by string because it comes straight from SQL grouping and
 * is only ever displayed; turning channel numbers and method ids back into
 * types would buy nothing here.
 */
/**
 * What one recovery method achieved, and at what cost.
 *
 * The averages matter as much as the count: a method that fires often but
 * rewrites many bits each time is doing more guessing than one that fires rarely
 * and changes a single bit.
 */
data class MethodDetail(
    val method: String,
    val count: Int,
    val averageBits: Float,
    val averageConfidence: Float,
    val minBits: Int,
    val maxBits: Int,
    val averageCandidates: Float,
)

data class RecoveryStats(
    val methodDetail: List<MethodDetail> = emptyList(),
    val total: Long,
    val crcErrors: Int,
    val byMethod: Map<String, Int>,
    val errorsByChannel: Map<String, Int>,
    val repairedByChannel: Map<String, Int>,
    val totalByChannel: Map<String, Int>,
    val bySource: Map<String, Int>,
    val errorsBySource: Map<String, Int>,
    val bitsCorrected: Map<String, Int>,
    val averageConfidence: Float,
    val minConfidence: Float,
    val maxConfidence: Float,
    val corroborated: Int,
) {
    val repaired: Int
        get() = byMethod.filterKeys { it != RecoveryMethod.UNRECOVERABLE.id }.values.sum()

    val unrecoverable: Int get() = byMethod[RecoveryMethod.UNRECOVERABLE.id] ?: 0

    /** Share of corrupt packets that were put back together. */
    val repairRate: Float get() = if (crcErrors == 0) 0f else repaired.toFloat() / crcErrors

    companion object {
        val EMPTY = RecoveryStats(
            emptyList(), 0, 0, emptyMap(), emptyMap(), emptyMap(), emptyMap(),
            emptyMap(), emptyMap(), emptyMap(), 0f, 0f, 0f, 0,
        )
    }
}
