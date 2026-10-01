package com.example.discogsandroidapp.pricing

import org.json.JSONObject

internal fun readSampleMap(source: JSONObject?): Map<String, List<Double>> {
    if (source == null) return emptyMap()
    return buildMap {
        val keys = source.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val values = source.optJSONArray(key) ?: continue
            val prices = (0 until minOf(values.length(), 250)).mapNotNull { index ->
                values.optDouble(index, Double.NaN).takeIf { it.isFinite() && it > 0.0 }
            }
            if (prices.isNotEmpty()) put(key, prices)
        }
    }
}
