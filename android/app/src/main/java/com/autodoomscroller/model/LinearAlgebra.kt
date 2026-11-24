package com.autodoomscroller.model

import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt

/** Running sums of vectors, enough to give their mean and covariance at any point. */
internal class Moments(val size: Int) {
    var count = 0
        private set
    private val sum = DoubleArray(size)
    private val outer = DoubleArray(size * size) // upper triangle of the sum of x xᵀ

    fun add(x: FloatArray) {
        count++
        for (i in 0 until size) {
            val xi = x[i].toDouble()
            sum[i] += xi
            val row = i * size
            for (j in i until size) outer[row + j] += xi * x[j]
        }
    }

    fun mean() = DoubleArray(size) { sum[it] / count }

    /** The scatter matrix, row-major: the covariance up to a factor, which its eigenvectors don't mind. */
    fun scatter(): DoubleArray {
        val mean = mean()
        val out = DoubleArray(size * size)
        for (i in 0 until size) {
            for (j in i until size) {
                val value = outer[i * size + j] - count * mean[i] * mean[j]
                out[i * size + j] = value
                out[j * size + i] = value
            }
        }
        return out
    }
}

/**
 * The [k] leading eigenvectors of the symmetric [size] x [size] matrix [a] (row-major), as rows.
 * Subspace iteration on a block a little wider than [k], with a Rayleigh-Ritz step each round,
 * so it needs a handful of multiplications by [a] instead of a full decomposition.
 */
internal fun leadingEigenvectors(a: DoubleArray, size: Int, k: Int): Array<DoubleArray> {
    val block = minOf(size, k + 8)
    val random = Random(0)
    var q = orthonormal(Array(block) { DoubleArray(size) { random.nextGaussian() } })
    var ritz = q
    repeat(MAX_ROUNDS) {
        val z = Array(block) { multiply(a, size, q[it]) }
        val small = DoubleArray(block * block)
        for (i in 0 until block) for (j in i until block) {
            val value = dot(q[i], z[j])
            small[i * block + j] = value
            small[j * block + i] = value
        }
        val (values, vectors) = symmetricEigen(small, block) // descending
        ritz = Array(block) { combine(q, vectors[it]) }
        val image = Array(block) { combine(z, vectors[it]) }
        var worst = 0.0
        for (c in 0 until k) {
            var residual = 0.0
            for (i in 0 until size) {
                val r = image[c][i] - values[c] * ritz[c][i]
                residual += r * r
            }
            worst = maxOf(worst, sqrt(residual))
        }
        if (worst <= TOLERANCE * abs(values[0])) return ritz.copyOfRange(0, k)
        q = orthonormal(image)
    }
    return ritz.copyOfRange(0, k)
}

/** Eigenvalues (descending) and eigenvectors (as rows, same order) of a small symmetric matrix, by Jacobi rotations. */
internal fun symmetricEigen(matrix: DoubleArray, size: Int): Pair<DoubleArray, Array<DoubleArray>> {
    val a = matrix.copyOf()
    val v = DoubleArray(size * size).also { for (i in 0 until size) it[i * size + i] = 1.0 }
    repeat(60) {
        var off = 0.0
        for (i in 0 until size) for (j in i + 1 until size) off += a[i * size + j] * a[i * size + j]
        if (off < 1e-22) return@repeat
        for (p in 0 until size) for (q in p + 1 until size) {
            val apq = a[p * size + q]
            if (abs(apq) < 1e-300) continue
            val theta = (a[q * size + q] - a[p * size + p]) / (2 * apq)
            val t = (if (theta >= 0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1))
            val c = 1 / sqrt(t * t + 1)
            val s = t * c
            for (i in 0 until size) {
                val aip = a[i * size + p]
                val aiq = a[i * size + q]
                a[i * size + p] = c * aip - s * aiq
                a[i * size + q] = s * aip + c * aiq
            }
            for (i in 0 until size) {
                val api = a[p * size + i]
                val aqi = a[q * size + i]
                a[p * size + i] = c * api - s * aqi
                a[q * size + i] = s * api + c * aqi
            }
            for (i in 0 until size) {
                val vip = v[i * size + p]
                val viq = v[i * size + q]
                v[i * size + p] = c * vip - s * viq
                v[i * size + q] = s * vip + c * viq
            }
        }
    }
    val order = (0 until size).sortedByDescending { a[it * size + it] }
    return DoubleArray(size) { a[order[it] * size + order[it]] } to
        Array(size) { column -> DoubleArray(size) { row -> v[row * size + order[column]] } }
}

/** Solves the symmetric positive definite system [a] x = [b] in place of [b], by Cholesky. */
internal fun solvePositiveDefinite(a: DoubleArray, size: Int, b: DoubleArray): DoubleArray {
    val l = DoubleArray(size * size)
    for (i in 0 until size) {
        for (j in 0..i) {
            var sum = a[i * size + j]
            for (m in 0 until j) sum -= l[i * size + m] * l[j * size + m]
            l[i * size + j] = if (i == j) sqrt(sum.coerceAtLeast(1e-12)) else sum / l[j * size + j]
        }
    }
    val x = b.copyOf()
    for (i in 0 until size) {
        for (m in 0 until i) x[i] -= l[i * size + m] * x[m]
        x[i] /= l[i * size + i]
    }
    for (i in size - 1 downTo 0) {
        for (m in i + 1 until size) x[i] -= l[m * size + i] * x[m]
        x[i] /= l[i * size + i]
    }
    return x
}

private const val MAX_ROUNDS = 200
private const val TOLERANCE = 1e-9

private fun dot(a: DoubleArray, b: DoubleArray): Double {
    var sum = 0.0
    for (i in a.indices) sum += a[i] * b[i]
    return sum
}

private fun multiply(a: DoubleArray, size: Int, x: DoubleArray) = DoubleArray(size) { row ->
    var sum = 0.0
    val start = row * size
    for (j in 0 until size) sum += a[start + j] * x[j]
    sum
}

/** The combination of [vectors] with [weights]. */
private fun combine(vectors: Array<DoubleArray>, weights: DoubleArray): DoubleArray {
    val out = DoubleArray(vectors[0].size)
    for (j in vectors.indices) {
        val w = weights[j]
        val vector = vectors[j]
        for (i in out.indices) out[i] += w * vector[i]
    }
    return out
}

/** Gram-Schmidt, done twice for accuracy. */
private fun orthonormal(vectors: Array<DoubleArray>): Array<DoubleArray> {
    val out = Array(vectors.size) { vectors[it].copyOf() }
    for (i in out.indices) {
        repeat(2) {
            for (j in 0 until i) {
                val overlap = dot(out[i], out[j])
                for (m in out[i].indices) out[i][m] -= overlap * out[j][m]
            }
        }
        val norm = sqrt(dot(out[i], out[i]))
        if (norm > 0) for (m in out[i].indices) out[i][m] /= norm
    }
    return out
}
