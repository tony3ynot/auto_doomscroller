package com.autodoomscroller.adapter

import kotlin.math.roundToLong

/** Parses counts like "좋아요 27482개", "1,234 likes", "1.2만", "12.3K". Null when there is no number. */
object CountParser {
    private val NUMBER = Regex("""(\d[\d,]*(?:\.\d+)?)\s*(천|만|억|[KkMmBb])?""")

    fun parse(text: CharSequence): Long? {
        val match = NUMBER.find(text) ?: return null
        val value = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        val multiplier = when (match.groupValues[2]) {
            "천", "K", "k" -> 1_000.0
            "만" -> 10_000.0
            "M", "m" -> 1_000_000.0
            "억" -> 100_000_000.0
            "B", "b" -> 1_000_000_000.0
            else -> 1.0
        }
        return (value * multiplier).roundToLong()
    }
}
