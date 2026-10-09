package com.application.sensorforge.sdk.train

import kotlinx.serialization.Serializable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/** One labelled feature vector. Sessions are the split unit; `heldOut` is never used for fitting. */
data class Example(val x: FloatArray, val label: String, val session: String, val heldOut: Boolean = false)

/** Feature standardiser fitted on training data only (tech spec §16.4). */
@Serializable
data class Standardiser(val mean: List<Float>, val std: List<Float>) {
    fun apply(x: FloatArray) = FloatArray(x.size) { (x[it] - mean[it]) / std[it] }

    companion object {
        fun fit(xs: List<FloatArray>): Standardiser {
            val d = xs.first().size
            val mean = DoubleArray(d); val sq = DoubleArray(d)
            for (x in xs) for (i in 0 until d) mean[i] += x[i].toDouble()
            for (i in 0 until d) mean[i] /= xs.size
            for (x in xs) for (i in 0 until d) { val e = x[i] - mean[i]; sq[i] += e * e }
            return Standardiser(
                mean.map { it.toFloat() },
                sq.map { max(sqrt(it / xs.size), 1e-6).toFloat() },
            )
        }
    }
}

/**
 * Softmax head: logistic regression (`hidden = 0`) or 2-layer MLP (ReLU hidden layer).
 * Weights are a flat float array so they hash and serialise trivially.
 */
@Serializable
data class Head(
    val id: String,
    val version: Int,
    val classes: List<String>,
    val inputDim: Int,
    val hidden: Int,
    val standardiser: Standardiser,
    val weights: List<Float>,
) {
    fun sha256(): String = weightsSha256(weights.toFloatArray())

    /** Class probabilities for raw (unstandardised) features. */
    fun predict(raw: FloatArray): FloatArray =
        Net(inputDim, hidden, classes.size, weights.toFloatArray()).forward(standardiser.apply(raw)).probs
}

fun weightsSha256(w: FloatArray): String {
    val buf = ByteBuffer.allocate(w.size * 4).order(ByteOrder.LITTLE_ENDIAN)
    w.forEach { buf.putFloat(it) }
    return MessageDigest.getInstance("SHA-256").digest(buf.array()).joinToString("") { "%02x".format(it) }
}

@Serializable
data class ClassMetrics(val precision: Double, val recall: Double, val support: Int)

/** Proof artifact written as `training.json` (tech spec §16.4). */
@Serializable
data class TrainingReport(
    val headId: String,
    val headVersion: Int,
    val trainedAt: String,
    val datasetVersion: String,
    val trainSamples: Int,
    val valSamples: Int,
    val heldOutSamples: Int,
    val lossCurve: List<Double>,
    val valLossCurve: List<Double>,
    val bestEpoch: Int,
    val perClassMetrics: Map<String, ClassMetrics>,
    val heldOutMetrics: Map<String, ClassMetrics>,
    val parentSha256: String,
    val trainedWeightsSha256: String,
    val changedParameterCount: Int,
    val parameterCount: Int,
    val trainingBackend: String,
    val inferenceBackend: String,
    val deviceFingerprint: String,
    val trainMillis: Long,
    val seed: Long,
)

data class TrainConfig(
    val hidden: Int = 16,
    val epochs: Int = 200,
    val batch: Int = 32,
    val lr: Double = 0.01,
    val l2: Double = 1e-4,
    val clip: Double = 5.0,
    val patience: Int = 20,
    val seed: Long = 42,
    val minPerClass: Int = 5,
    val valFraction: Double = 0.25,
)

class TrainingError(message: String) : Exception(message)

/**
 * Real head training (tech spec §16.4): session split, standardiser on train only,
 * seeded init (or warm start), mini-batch softmax cross-entropy + L2, Adam, gradient
 * clipping, early stop on val loss, restore best. Pure Kotlin; CPU.
 */
object HeadTrainer {
    fun train(
        headId: String,
        classes: List<String>,
        data: List<Example>,
        config: TrainConfig = TrainConfig(),
        parent: Head? = null,
        version: Int = (parent?.version ?: 0) + 1,
        datasetVersion: String,
        trainedAt: String,
        deviceFingerprint: String,
        clockMillis: () -> Long = { 0L },
    ): Pair<Head, TrainingReport> {
        val t0 = clockMillis()
        val dev = data.filter { !it.heldOut }
        val heldOut = data.filter { it.heldOut }
        for (c in classes) {
            val n = dev.count { it.label == c }
            if (n < config.minPerClass) throw TrainingError("class '$c' has $n dev examples, need ≥ ${config.minPerClass}")
        }
        val unknown = data.map { it.label }.toSet() - classes.toSet()
        if (unknown.isNotEmpty()) throw TrainingError("unknown labels $unknown")

        val (trainSet, valSet) = splitBySession(dev, classes, config)
        val std = Standardiser.fit(trainSet.map { it.x })
        val d = trainSet.first().x.size
        val k = classes.size
        fun enc(s: List<Example>) = s.map { std.apply(it.x) to classes.indexOf(it.label) }
        val tr = enc(trainSet); val va = enc(valSet)

        val rng = java.util.Random(config.seed)
        val net = Net(d, config.hidden, k, FloatArray(Net.paramCount(d, config.hidden, k)))
        val parentW = parent?.takeIf { it.inputDim == d && it.hidden == config.hidden && it.classes == classes }
            ?.weights?.toFloatArray()
        if (parentW != null) parentW.copyInto(net.w) else net.init(rng)
        val parentSha = weightsSha256(parent?.weights?.toFloatArray() ?: net.w.copyOf())

        val adam = Adam(net.w.size, config.lr)
        val lossCurve = ArrayList<Double>(); val valCurve = ArrayList<Double>()
        var best = net.w.copyOf(); var bestVal = Double.MAX_VALUE; var bestEpoch = 0; var since = 0
        val idx = tr.indices.toMutableList()
        for (epoch in 0 until config.epochs) {
            idx.shuffle(rng)
            var epochLoss = 0.0
            for (b in idx.indices step config.batch) {
                val batch = idx.subList(b, minOf(b + config.batch, idx.size)).map { tr[it] }
                val g = FloatArray(net.w.size)
                for ((x, y) in batch) epochLoss += net.backward(x, y, g)
                for (i in g.indices) g[i] = g[i] / batch.size + (config.l2 * net.w[i]).toFloat()
                clip(g, config.clip)
                adam.step(net.w, g)
            }
            lossCurve += epochLoss / tr.size
            val vl = va.sumOf { (x, y) -> -ln(max(net.forward(x).probs[y].toDouble(), 1e-12)) } / va.size
            valCurve += vl
            if (vl < bestVal - 1e-6) { bestVal = vl; best = net.w.copyOf(); bestEpoch = epoch; since = 0 }
            else if (++since >= config.patience) break
        }
        best.copyInto(net.w)

        val head = Head(
            headId, version, classes, d, config.hidden, std, net.w.toList(),
        )
        val pw = parent?.weights
        val changed = if (pw == null || pw.size != net.w.size) net.w.size else net.w.indices.count { net.w[it] != pw[it] }
        val report = TrainingReport(
            headId = headId,
            headVersion = head.version,
            trainedAt = trainedAt,
            datasetVersion = datasetVersion,
            trainSamples = trainSet.size,
            valSamples = valSet.size,
            heldOutSamples = heldOut.size,
            lossCurve = lossCurve,
            valLossCurve = valCurve,
            bestEpoch = bestEpoch,
            perClassMetrics = metrics(head, valSet),
            heldOutMetrics = metrics(head, heldOut),
            parentSha256 = parentSha,
            trainedWeightsSha256 = head.sha256(),
            changedParameterCount = changed,
            parameterCount = net.w.size,
            trainingBackend = "CPU (Kotlin)",
            inferenceBackend = "CPU (Kotlin)",
            deviceFingerprint = deviceFingerprint,
            trainMillis = clockMillis() - t0,
            seed = config.seed,
        )
        return head to report
    }

    /** Whole sessions go to val until each class has ≥ valFraction of its examples there (when it has > 1 session). */
    internal fun splitBySession(dev: List<Example>, classes: List<String>, c: TrainConfig): Pair<List<Example>, List<Example>> {
        val valSessions = HashSet<String>()
        for (cls in classes) {
            val sessions = dev.filter { it.label == cls }.map { it.session }.distinct().sorted()
            if (sessions.size < 2) continue
            val total = dev.count { it.label == cls }
            var taken = dev.count { it.label == cls && it.session in valSessions }
            for (s in sessions.shuffled(java.util.Random(c.seed + cls.hashCode()))) {
                if (taken >= total * c.valFraction) break
                if (s in valSessions) continue
                // never move a class's last training session to val
                val remaining = sessions.count { it !in valSessions && it != s }
                if (remaining == 0) break
                valSessions += s
                taken += dev.count { it.label == cls && it.session == s }
            }
        }
        val train = dev.filter { it.session !in valSessions }
        val v = dev.filter { it.session in valSessions }
        // Single-session data: fall back to a seeded example split so early stopping has a signal.
        if (v.isEmpty()) {
            val sh = dev.shuffled(java.util.Random(c.seed))
            val n = max(1, (sh.size * c.valFraction).toInt())
            return sh.drop(n) to sh.take(n)
        }
        return train to v
    }

    fun metrics(head: Head, set: List<Example>): Map<String, ClassMetrics> {
        val pred = set.map { e -> head.classes[argmax(head.predict(e.x))] }
        return head.classes.associateWith { c ->
            val tp = set.indices.count { set[it].label == c && pred[it] == c }
            val p = pred.count { it == c }
            val s = set.count { it.label == c }
            ClassMetrics(if (p == 0) 0.0 else tp.toDouble() / p, if (s == 0) 0.0 else tp.toDouble() / s, s)
        }
    }

    private fun clip(g: FloatArray, maxNorm: Double) {
        val n = sqrt(g.sumOf { (it * it).toDouble() })
        if (n > maxNorm) { val s = (maxNorm / n).toFloat(); for (i in g.indices) g[i] *= s }
    }
}

fun argmax(v: FloatArray): Int = v.indices.maxBy { v[it] }

/** Flat-parameter network. Layout: [W1 (h×d), b1 (h), W2 (k×h'), b2 (k)] where h' = h or d when h = 0. */
internal class Net(val d: Int, val h: Int, val k: Int, val w: FloatArray) {
    class Out(val hidden: FloatArray, val probs: FloatArray)

    private val inK = if (h == 0) d else h
    private val w2 = if (h == 0) 0 else h * d + h
    private val b2 = w2 + k * inK

    fun init(rng: java.util.Random) {
        if (h > 0) { val s = sqrt(2.0 / d); for (i in 0 until h * d) w[i] = (rng.nextGaussian() * s).toFloat() }
        val s = sqrt(1.0 / inK); for (i in 0 until k * inK) w[w2 + i] = (rng.nextGaussian() * s).toFloat()
    }

    fun forward(x: FloatArray): Out {
        val a = if (h == 0) x else FloatArray(h) { j ->
            var z = w[h * d + j]
            for (i in 0 until d) z += w[j * d + i] * x[i]
            max(z, 0f)
        }
        val logits = DoubleArray(k) { c ->
            var z = w[b2 + c].toDouble()
            for (i in 0 until inK) z += w[w2 + c * inK + i] * a[i]
            z
        }
        val m = logits.max()
        val e = logits.map { exp(it - m) }
        val sum = e.sum()
        return Out(a, FloatArray(k) { (e[it] / sum).toFloat() })
    }

    /** Accumulates dLoss/dw into [g]; returns the example's cross-entropy loss. */
    fun backward(x: FloatArray, y: Int, g: FloatArray): Double {
        val o = forward(x)
        val dz = FloatArray(k) { o.probs[it] - if (it == y) 1f else 0f }
        val da = FloatArray(inK)
        for (c in 0 until k) {
            g[b2 + c] += dz[c]
            for (i in 0 until inK) {
                g[w2 + c * inK + i] += dz[c] * o.hidden[i]
                da[i] += dz[c] * w[w2 + c * inK + i]
            }
        }
        if (h > 0) for (j in 0 until h) {
            if (o.hidden[j] <= 0f) continue
            g[h * d + j] += da[j]
            for (i in 0 until d) g[j * d + i] += da[j] * x[i]
        }
        return -ln(max(o.probs[y].toDouble(), 1e-12))
    }

    companion object {
        fun paramCount(d: Int, h: Int, k: Int) = if (h == 0) k * d + k else h * d + h + k * h + k
    }
}

internal class Adam(n: Int, private val lr: Double, private val b1: Double = 0.9, private val b2: Double = 0.999) {
    private val m = DoubleArray(n); private val v = DoubleArray(n); private var t = 0
    fun step(w: FloatArray, g: FloatArray) {
        t++
        val c1 = 1 - Math.pow(b1, t.toDouble()); val c2 = 1 - Math.pow(b2, t.toDouble())
        for (i in w.indices) {
            m[i] = b1 * m[i] + (1 - b1) * g[i]
            v[i] = b2 * v[i] + (1 - b2) * g[i] * g[i]
            w[i] -= (lr * (m[i] / c1) / (sqrt(v[i] / c2) + 1e-8)).toFloat()
        }
    }
}
