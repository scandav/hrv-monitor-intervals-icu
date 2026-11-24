package com.example.hrvmonitor

import android.util.Base64
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate

/**
 * Writes the morning reading into intervals.icu's wellness record for [date]:
 *   PUT https://intervals.icu/api/v1/athlete/{id}/wellness/{yyyy-MM-dd}
 * Auth: HTTP Basic, username "API_KEY", password = your API key
 * (intervals.icu → Settings → Developer Settings). Athlete id "0" means "the key's owner".
 * Must be called off the main thread.
 */
object IntervalsIcu {
    fun uploadWellness(
        athleteId: String,
        apiKey: String,
        date: LocalDate,
        restingHr: Int,
        rmssd: Double,
        sdnn: Double,
    ) {
        val id = URLEncoder.encode(athleteId.trim().ifEmpty { "0" }, "UTF-8")
        val url = URL("https://intervals.icu/api/v1/athlete/$id/wellness/$date")
        val body = JSONObject()
            .put("id", date.toString())
            .put("restingHR", restingHr)
            .put("hrv", round1(rmssd))
            .put("hrvSDNN", round1(sdnn))
            .toString()
        val auth = Base64.encodeToString("API_KEY:${apiKey.trim()}".toByteArray(), Base64.NO_WRAP)

        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "PUT"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Authorization", "Basic $auth")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(200).orEmpty()
                throw IOException(
                    when (code) {
                        401, 403 -> "HTTP $code – check API key and athlete ID"
                        else -> "HTTP $code $err"
                    }
                )
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun round1(x: Double) = Math.round(x * 10) / 10.0
}
