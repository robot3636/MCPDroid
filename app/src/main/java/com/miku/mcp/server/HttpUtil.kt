package com.miku.mcp.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import kotlinx.serialization.json.JsonElement
import java.security.MessageDigest

// Public base URL of this server exactly as the caller sees it (e.g. https://abc.trycloudflare.com).
// Derived from the Host header so it follows the tunnel URL automatically, even when it changes.
fun ApplicationCall.baseUrl(): String {
    val hostHeader = request.headers[HttpHeaders.Host] ?: "127.0.0.1"
    val hostName = hostHeader.substringBefore(':')
    val isLocal = hostName == "localhost" || hostName.startsWith("127.")
    return (if (isLocal) "http://" else "https://") + hostHeader
}

// Constant-time string comparison (prevents timing attacks on secrets)
fun constantTimeEquals(a: String, b: String): Boolean =
    MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

// Sends a JSON value with the right content type
suspend fun ApplicationCall.respondJson(json: JsonElement, status: HttpStatusCode = HttpStatusCode.OK) {
    respondText(json.toString(), ContentType.Application.Json, status)
}

// Tells proxies and browsers never to cache a response that carries credentials
fun ApplicationCall.noStore() {
    response.header(HttpHeaders.CacheControl, "no-store")
    response.header("Pragma", "no-cache")
}