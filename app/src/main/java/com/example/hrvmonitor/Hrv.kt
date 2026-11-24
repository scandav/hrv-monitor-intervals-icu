package com.example.hrvmonitor

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/** One RR interval. [breakBefore] = data was lost just before this beat (BLE dropout). */
data class Beat(val rrMs: Double, val breakBefore: Boolean = false)

data class HrvResult(
    val rmssd: Double,
    val lnRmssd: Double,
    val sdnn: Double,
    val meanRr: Double,
    val meanHr: Double,
    val totalBeats: Int,
    val cleanBeats: Int,
    val pairsUsed: Int,
) {
    val artifactPercent: Double
        get() = if (totalBeats == 0) 0.0 else 100.0 * (totalBeats - cleanBeats) / totalBeats

    val quality: String
        get() = when {
            artifactPercent <= 5 -> "good"
            artifactPercent <= 15 -> "fair"
            else -> "poor – consider repeating"
        }
}

/**
 * Short-term (ultra-short) HRV from RR intervals, cleaned the way most HRV apps do it:
 *
 * 1. Range filter – drop physiologically impossible RRs (< 300 ms or > 2000 ms, i.e. > 200 or < 30 bpm).
 * 2. Local median filter – drop a beat if it deviates more than 20 % from the median of the
 *    surrounding ±5 beats. This catches missed beats (RR ~2× too long), extra/ectopic beats
 *    (RR too short) and electrode noise. It's the common "percentage filter" (Kubios-style
 *    threshold / Malik 20 % rule) but against a local median, which is more robust than the
 *    previous beat alone.
 * 3. Artifacts are REMOVED, not interpolated. For RMSSD only pairs of beats that are both
 *    clean and truly consecutive (no BLE gap between them) are used, so a removed beat or a
 *    dropout never creates a fake big successive difference.
 *
 * RMSSD is the standard metric for daily readiness (what HRV4Training, Elite HRV and
 * intervals.icu's "HRV" field use). SDNN is reported too.
 */
object Hrv {
    const val MIN_RR_MS = 300.0
    const val MAX_RR_MS = 2000.0
    const val HALF_WINDOW = 5
    const val MAX_DEVIATION = 0.20
    const val MIN_BEATS = 20

    fun analyze(beats: List<Beat>): HrvResult? {
        val n = beats.size
        if (n < MIN_BEATS) return null
        val rr = DoubleArray(n) { beats[it].rrMs }

        val inRange = BooleanArray(n) { rr[it] in MIN_RR_MS..MAX_RR_MS }

        val valid = BooleanArray(n)
        for (i in 0 until n) {
            if (!inRange[i]) continue
            val neighbours = ArrayList<Double>(2 * HALF_WINDOW)
            for (j in maxOf(0, i - HALF_WINDOW)..minOf(n - 1, i + HALF_WINDOW)) {
                if (j != i && inRange[j]) neighbours.add(rr[j])
            }
            valid[i] = if (neighbours.size < 3) true else {
                val m = median(neighbours)
                abs(rr[i] - m) <= MAX_DEVIATION * m
            }
        }

        val clean = (0 until n).filter { valid[it] }.map { rr[it] }
        val diffs = ArrayList<Double>()
        for (i in 1 until n) {
            if (valid[i] && valid[i - 1] && !beats[i].breakBefore) diffs.add(rr[i] - rr[i - 1])
        }
        if (clean.size < MIN_BEATS || diffs.size < MIN_BEATS) return null

        val meanRr = clean.average()
        val sdnn = sqrt(clean.sumOf { (it - meanRr).pow(2) } / (clean.size - 1))
        val rmssd = sqrt(diffs.sumOf { it * it } / diffs.size)
        return HrvResult(
            rmssd = rmssd,
            lnRmssd = ln(rmssd),
            sdnn = sdnn,
            meanRr = meanRr,
            meanHr = 60_000.0 / meanRr,
            totalBeats = n,
            cleanBeats = clean.size,
            pairsUsed = diffs.size,
        )
    }

    private fun median(v: List<Double>): Double {
        val s = v.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
    }
}
