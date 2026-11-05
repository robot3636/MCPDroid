package com.miku.mcp.server

import com.miku.mcp.mcp.JsonRpcRequest
import com.miku.mcp.mcp.JsonRpcResponse
import com.miku.mcp.mcp.McpHandler
import com.miku.mcp.mcp.McpJson
import com.miku.mcp.service.LogBuffer
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.*
import java.net.InetAddress
import java.net.ServerSocket

// Browser origins allowed to call /mcp (the MCP spec requires validating Origin).
// Server-to-server calls send no Origin header at all and are always accepted.
private val ALLOWED_ORIGINS = setOf("https://claude.ai", "https://claude.com")

// Logs every request (method, path, status) into the in-app log window.
// Only the path is logged, never the query string, because it can carry OAuth codes.
private val RequestLogger = createApplicationPlugin("RequestLogger") {
    on(ResponseSent) { call ->
        LogBuffer.append("${call.request.httpMethod.value} ${call.request.path()} -> ${call.response.status()?.value}")
    }
}

// Embedded HTTP server exposing the MCP endpoint and the OAuth endpoints Claude needs
class McpServer(
    private val port: Int,
    private val ownerToken: String,   // the token shown in the app: also the password on the consent page
    private val handler: McpHandler,
    private val oauth: OAuthStore
) {
    private var engine: EmbeddedServer<*, *>? = null

    fun start() {
        if (engine != null) return   // already running

        // Fail fast with a readable error if the port is taken.
        // Ktor binds in a background coroutine, so a bind failure there would crash the app.
        try {
            ServerSocket(port, 0, InetAddress.getByName("127.0.0.1")).close()
        } catch (e: Exception) {
            throw IllegalStateException("Port $port is already in use. Pick another port.", e)
        }

        // Bind to loopback only: nothing outside the phone can reach it except through the tunnel
        engine = embeddedServer(CIO, port = port, host = "127.0.0.1") {
            install(RequestLogger)
            routing {
                get("/health") { call.respondText("ok") }   // public liveness check
                oauthRoutes(oauth, ownerToken)               // discovery, registration, consent, tokens
                mcpRoutes(handler, oauth, ownerToken)        // the MCP endpoint itself
            }
        }.also { it.start(wait = false) }   // wait=false so it runs in the background
    }

    fun stop() {
        engine?.stop(gracePeriodMillis = 500, timeoutMillis = 1000)
        engine = null
    }
}

// Streamable HTTP endpoint: one JSON-RPC message (or a batch) per POST
private fun Route.mcpRoutes(handler: McpHandler, oauth: OAuthStore, ownerToken: String) {

    post("/mcp") {
        // 1) Origin validation (protects against DNS-rebinding attacks from web pages)
        val origin = call.request.headers[HttpHeaders.Origin]
        if (origin != null && origin !in ALLOWED_ORIGINS) {
            call.respond(HttpStatusCode.Forbidden)
            return@post
        }

        // 2) Authentication: the owner token (scripts) or an OAuth access token (Claude)
        val presented = call.bearerToken()
        val valid = presented != null &&
            (constantTimeEquals(presented, ownerToken) || oauth.isAccessTokenValid(presented))
        if (!valid) {
            call.rejectUnauthorized(tokenWasPresented = presented != null)
            return@post
        }

        // 3) After the handshake, clients announce the protocol version in a header
        val version = call.request.headers["MCP-Protocol-Version"]
        if (version != null && version !in McpHandler.SUPPORTED_VERSIONS) {
            call.respondText("Unsupported MCP-Protocol-Version: $version", status = HttpStatusCode.BadRequest)
            return@post
        }

        // 4) Parse the body: a single message or a batch (array)
        val parsed = try {
            McpJson.parseToJsonElement(call.receiveText())
        } catch (e: Exception) {
            call.respondJson(errorJson(-32700, "Parse error"), HttpStatusCode.BadRequest)
            return@post
        }
        val isBatch = parsed is JsonArray
        val messages = if (parsed is JsonArray) parsed.toList() else listOf(parsed)
        if (messages.isEmpty()) {
            call.respondJson(errorJson(-32600, "Invalid Request"), HttpStatusCode.BadRequest)
            return@post
        }

        // 5) Answer each message that is a request; skip notifications and client responses
        val replies = mutableListOf<JsonElement>()
        for (message in messages) {
            val obj = message as? JsonObject
            if (obj == null) {
                replies += errorJson(-32600, "Invalid Request")
                continue
            }
            if ("method" !in obj) continue   // a response sent by the client: nothing to answer

            val request = try {
                McpJson.decodeFromJsonElement(JsonRpcRequest.serializer(), obj)
            } catch (e: Exception) {
                replies += errorJson(-32600, "Invalid Request")
                continue
            }
            LogBuffer.append("mcp: ${request.method}")
            handler.handle(request)?.let {
                replies += McpJson.encodeToJsonElement(JsonRpcResponse.serializer(), it)
            }
        }

        // 6) Reply: 202 when there was nothing to answer, otherwise the JSON result
        when {
            replies.isEmpty() -> call.respond(HttpStatusCode.Accepted)
            isBatch -> call.respondJson(JsonArray(replies))
            else -> call.respondJson(replies.first())
        }
    }

    // We do not offer a server-initiated SSE stream or sessions, so these are not allowed
    get("/mcp") { call.respond(HttpStatusCode.MethodNotAllowed) }
    delete("/mcp") { call.respond(HttpStatusCode.MethodNotAllowed) }
}

// Extracts the token from "Authorization: Bearer <token>"
private fun ApplicationCall.bearerToken(): String? {
    val header = request.headers[HttpHeaders.Authorization] ?: return null
    return if (header.length > 7 && header.substring(0, 7).equals("Bearer ", ignoreCase = true)) {
        header.substring(7).trim()
    } else null
}

// 401 with the pointer Claude needs to find the OAuth metadata and start the sign-in flow
private suspend fun ApplicationCall.rejectUnauthorized(tokenWasPresented: Boolean) {
    val metadata = "${baseUrl()}/.well-known/oauth-protected-resource"
    val challenge = if (tokenWasPresented) {
        "Bearer error=\"invalid_token\", resource_metadata=\"$metadata\""
    } else {
        "Bearer resource_metadata=\"$metadata\""
    }
    response.header(HttpHeaders.WWWAuthenticate, challenge)
    respond(HttpStatusCode.Unauthorized)
}

// A JSON-RPC error object that has no request id
private fun errorJson(code: Int, message: String): JsonObject = buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", JsonNull)
    putJsonObject("error") {
        put("code", code)
        put("message", message)
    }
}