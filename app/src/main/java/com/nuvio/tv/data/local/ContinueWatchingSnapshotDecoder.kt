package com.nuvio.tv.data.local

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonParser

/** Cache JSON is untrusted: Gson can bypass Kotlin's non-null constructors. */
internal object ContinueWatchingSnapshotDecoder {
    fun <T : Any> decode(json: String, model: Class<T>, gson: Gson): List<T> {
        val root = JsonParser.parseString(json)
        if (!root.isJsonArray) return emptyList()
        return root.asJsonArray.mapNotNull { element ->
            try {
                if (!element.isJsonObject) return@mapNotNull null
                val item = element.asJsonObject
                // Validate JSON before creating a Kotlin object. Checking an already
                // deserialized non-null property is not a serialization contract.
                for (key in listOf("contentId", "contentType", "name", "videoId")) {
                    val value = item.get(key)
                    if (value == null || !value.isJsonPrimitive ||
                        !value.asJsonPrimitive.isString ||
                        (key != "name" && value.asString.isBlank())
                    ) return@mapNotNull null
                }
                // Gson does not invoke these data classes' Kotlin defaults.
                val genres = JsonArray()
                item.get("genres")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach {
                    if (it.isJsonPrimitive && it.asJsonPrimitive.isString) genres.add(it)
                }
                item.add("genres", genres)
                for (key in listOf("ratingVisible", "hasAired")) {
                    val value = item.get(key)
                    if (value == null || value.isJsonNull) item.addProperty(key, true)
                }
                gson.fromJson(item, model)
            } catch (_: RuntimeException) {
                // One incompatible cache entry must not prevent home from loading.
                null
            }
        }
    }
}
