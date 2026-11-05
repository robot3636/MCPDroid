package com.miku.mcp.tools

import kotlinx.serialization.json.*

// Describes one tool argument: name, JSON type, help text, and whether it is mandatory
data class Param(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = false
)

// Builds a JSON Schema "object" from a list of params (saves boilerplate in every tool)
fun schemaOf(vararg params: Param): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        params.forEach { p ->
            putJsonObject(p.name) {
                put("type", p.type)
                put("description", p.description)
            }
        }
    }
    putJsonArray("required") {
        params.filter { it.required }.forEach { add(JsonPrimitive(it.name)) }
    }
}

// Typed readers for tool arguments; they return null when the key is absent
fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull
fun JsonObject.bool(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull

// Reader for mandatory string arguments; throws so the handler reports a clear error
fun JsonObject.requireString(key: String): String =
    string(key) ?: throw IllegalArgumentException("Missing '$key' argument")