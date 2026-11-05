package com.miku.mcp.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// Shared JSON config used by the whole server
val McpJson = Json {
    ignoreUnknownKeys = true   // tolerate extra fields sent by clients
    encodeDefaults = true      // always emit "jsonrpc":"2.0"
    explicitNulls = false      // omit null fields (e.g. result when error is set)
}

// Incoming JSON-RPC message; "id" is null for notifications
@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,   // may be a number or a string, so keep it generic
    val method: String,
    val params: JsonObject? = null
)

// Outgoing JSON-RPC message: exactly one of result / error is set
@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null
)

@Serializable
data class JsonRpcError(val code: Int, val message: String)