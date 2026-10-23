package com.blesniffer.ml

/**
 * What went wrong with a corrupt packet.
 *
 * The classes are chosen by what each one implies for *repair*, not by what is
 * easiest to tell apart. A corrupt packet is only interesting because something
 * might be done about it, and these five differ in exactly that:
 *
 * - a handful of scattered flips is what syndrome decoding was built for;
 * - a burst is beyond syndrome decoding but well within voting against a repeat;
 * - a splice cannot be repaired at all as one packet, because it is not one
 *   packet - but each half can still name its sender;
 * - damaged header bytes move the frame boundary, so nothing downstream of the
 *   error is where it is supposed to be, and the length has to be searched;
 * - noise means the receiver locked onto something that was never a frame, and
 *   the honest answer is to count it and stop.
 *
 * Telling them apart is what stops the pipeline spending a six million candidate
 * search on a packet that was never one packet to begin with.
 */
enum class ErrorClass(
    val id: String,
    val label: String,
    /** What the recovery pipeline should do when it sees this. */
    val guidance: String,
) {
    SCATTERED(
        "scattered",
        "Scattered bit errors",
        "Few isolated flips. Syndrome decoding should get it; voting will too.",
    ),
    BURST(
        "burst",
        "Interference burst",
        "One contiguous damaged run, usually too many bits for syndrome " +
            "decoding. Voting against a repeat is the way in.",
    ),
    SPLICE(
        "splice",
        "Fragments of two packets",
        "Two transmissions collided in the receiver. Not repairable as one " +
            "frame; each fragment can still be attributed to a sender.",
    ),
    HEADER(
        "header",
        "Damaged header or length",
        "The frame boundary itself is wrong. The length has to be searched " +
            "before anything else can run.",
    ),
    NOISE(
        "noise",
        "No coherent frame",
        "Nothing matches any known device or any valid structure. Count it " +
            "towards the error rate and refuse.",
    ),
    ;

    companion object {
        fun fromId(id: String?): ErrorClass? = entries.firstOrNull { it.id == id }
    }
}
