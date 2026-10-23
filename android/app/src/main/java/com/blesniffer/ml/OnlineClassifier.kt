package com.blesniffer.ml

import kotlin.math.exp
import kotlin.math.max

/**
 * Multinomial logistic regression fitted by stochastic gradient descent.
 *
 * Chosen for what it can do rather than for what it can represent. The
 * requirement is a model that can be trained once from what has already been
 * captured and then keep learning, one packet at a time, on a phone, with no
 * training run and nothing to ship. That rules out anything needing a full pass
 * over a fixed dataset, and it rules out anything whose parameters cannot be
 * updated from a single example - which is most of what "an ML model" usually
 * means.
 *
 * What it costs: the decision boundary is linear in the features, so the
 * features have to carry the structure. That work is done in [Features], and
 * deliberately so - a burst and a scatter differ in one number there
 * (contiguity), and no amount of network depth would find a cleaner separation
 * than the one the physics already provides.
 *
 * The whole state is `classes x features` doubles - 60 numbers for the five
 * classes here - so a snapshot is a short string and restoring one is exact.
 */
class OnlineClassifier(
    val classes: Int,
    val features: Int,
    /** Step size at the first update; decays with experience. */
    private val learningRate: Double = 0.5,
    /** Ridge penalty, the only thing keeping weights finite on collinear inputs. */
    private val l2: Double = 1e-6,
) {

    private val weights = Array(classes) { DoubleArray(features) }

    var updates: Long = 0L
        private set

    /** True once enough examples have been seen for the output to mean anything. */
    val trained: Boolean get() = updates >= MIN_UPDATES

    /**
     * Class probabilities for [x].
     *
     * The maximum logit is subtracted before exponentiating. Without it a
     * confident model overflows to NaN and every downstream comparison silently
     * becomes false, which is the kind of failure that looks like "the model is
     * refusing everything" rather than like a bug.
     */
    fun predict(x: DoubleArray): DoubleArray {
        val logits = DoubleArray(classes) { c ->
            var sum = 0.0
            val w = weights[c]
            for (j in 0 until features) sum += w[j] * x[j]
            sum
        }
        val top = logits.max()
        var total = 0.0
        for (c in 0 until classes) {
            logits[c] = exp(logits[c] - top)
            total += logits[c]
        }
        if (total <= 0.0 || total.isNaN()) return DoubleArray(classes) { 1.0 / classes }
        for (c in 0 until classes) logits[c] /= total
        return logits
    }

    /**
     * One gradient step towards [label].
     *
     * The step size decays as 1/(1+t·λ) rather than to zero on a schedule: this
     * model is never finished learning. A capture moved to a different room is a
     * different distribution, and a learning rate annealed to nothing would go
     * on confidently reporting the old one. The floor keeps it able to move.
     */
    fun partialFit(x: DoubleArray, label: Int) {
        require(label in 0 until classes) { "class $label out of range" }

        val p = predict(x)
        val rate = max(learningRate / (1.0 + updates * DECAY), MIN_RATE)

        for (c in 0 until classes) {
            val error = p[c] - if (c == label) 1.0 else 0.0
            val w = weights[c]
            for (j in 0 until features) {
                w[j] -= rate * (error * x[j] + l2 * w[j])
            }
        }
        updates++
    }

    /**
     * The whole model as text, for carrying across a restart.
     *
     * Plain numbers rather than a serialization framework: the format has to
     * survive being read by a human deciding whether a model that started
     * behaving oddly was trained or merely restored badly.
     */
    fun snapshot(): String = buildString {
        append(classes).append(' ').append(features).append(' ').append(updates)
        for (row in weights) {
            for (w in row) append(' ').append(w)
        }
    }

    fun restore(text: String): Boolean {
        val parts = text.trim().split(' ')
        if (parts.size < 3) return false
        val c = parts[0].toIntOrNull() ?: return false
        val f = parts[1].toIntOrNull() ?: return false
        val t = parts[2].toLongOrNull() ?: return false
        // A snapshot from a build with a different feature set is not a model
        // that can be repaired by loading part of it; it is a different model.
        if (c != classes || f != features || parts.size != 3 + c * f) return false

        var k = 3
        for (row in 0 until classes) {
            for (col in 0 until features) {
                weights[row][col] = parts[k++].toDoubleOrNull() ?: return false
            }
        }
        updates = t
        return true
    }

    fun reset() {
        for (row in weights) row.fill(0.0)
        updates = 0
    }

    private companion object {
        const val DECAY = 1e-4
        const val MIN_RATE = 0.005
        const val MIN_UPDATES = 200L
    }
}
