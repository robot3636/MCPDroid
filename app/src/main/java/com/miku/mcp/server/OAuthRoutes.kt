package com.miku.mcp.server

import com.miku.mcp.mcp.McpJson
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.*
import java.net.URI
import java.security.MessageDigest
import java.util.Base64

// OAuth 2.1 endpoints that let Claude connect: discovery, registration, consent, and tokens
fun Route.oauthRoutes(oauth: OAuthStore, ownerToken: String) {

    // ---------- Discovery (RFC 9728): tells Claude where the authorization server is ----------
    // Claude tries the path-suffixed URL first, then the plain one
    get("/.well-known/oauth-protected-resource/mcp") { call.respondResourceMetadata() }
    get("/.well-known/oauth-protected-resource") { call.respondResourceMetadata() }

    // ---------- Discovery (RFC 8414): describes the endpoints below ----------
    get("/.well-known/oauth-authorization-server") {
        val base = call.baseUrl()
        call.respondJson(buildJsonObject {
            put("issuer", base)
            put("authorization_endpoint", "$base/authorize")
            put("token_endpoint", "$base/token")
            put("registration_endpoint", "$base/register")
            putJsonArray("response_types_supported") { add("code") }
            putJsonArray("grant_types_supported") { add("authorization_code"); add("refresh_token") }
            putJsonArray("code_challenge_methods_supported") { add("S256") }
            // Public clients (no secret): PKCE protects the code exchange instead
            putJsonArray("token_endpoint_auth_methods_supported") { add("none") }
            // Lets Claude ask for a refresh token
            putJsonArray("scopes_supported") { add("offline_access") }
        })
    }

    // ---------- Dynamic Client Registration (RFC 7591), JSON body ----------
    post("/register") {
        val client = try {
            val body = McpJson.parseToJsonElement(call.receiveText()).jsonObject
            val uris = body["redirect_uris"]!!.jsonArray.map { it.jsonPrimitive.content }
            // Refuse any redirect target that is not one of Claude's callbacks
            if (uris.isEmpty() || !uris.all { OAuthStore.isAllowedRedirectUri(it) }) {
                call.oauthError(HttpStatusCode.BadRequest, "invalid_redirect_uri", "Redirect URI is not allowed")
                return@post
            }
            val name = body["client_name"]?.jsonPrimitive?.contentOrNull?.take(100) ?: "MCP client"
            oauth.registerClient(name, uris)
        } catch (e: Exception) {
            call.oauthError(HttpStatusCode.BadRequest, "invalid_client_metadata", "Invalid registration request")
            return@post
        }

        call.noStore()
        call.respondJson(buildJsonObject {
            put("client_id", client.clientId)
            put("client_id_issued_at", client.createdAt / 1000)
            put("client_name", client.name)
            putJsonArray("redirect_uris") { client.redirectUris.forEach { add(it) } }
            putJsonArray("grant_types") { add("authorization_code"); add("refresh_token") }
            putJsonArray("response_types") { add("code") }
            put("token_endpoint_auth_method", "none")
        }, HttpStatusCode.Created)
    }

    // ---------- Consent page: shown in the browser when the user connects the connector ----------
    get("/authorize") {
        val req = try {
            parseAuthRequest(oauth, call.request.queryParameters)
        } catch (e: IllegalArgumentException) {
            call.respondHtml(errorPage(e.message ?: "Invalid request"), HttpStatusCode.BadRequest)
            return@get
        }
        call.respondHtml(consentPage(req, null))
    }

    // ---------- Consent form submission ----------
    post("/authorize") {
        val form = call.receiveParameters()
        val req = try {
            parseAuthRequest(oauth, form)
        } catch (e: IllegalArgumentException) {
            call.respondHtml(errorPage(e.message ?: "Invalid request"), HttpStatusCode.BadRequest)
            return@post
        }

        // "Deny" needs no token: send the user back with an error
        if (form["decision"] != "allow") {
            call.respondRedirect(redirectWith(req.redirectUri, "error" to "access_denied", "state" to req.state))
            return@post
        }

        if (oauth.isLockedOut()) {
            call.respondHtml(consentPage(req, "Too many attempts. Wait a minute and try again."), HttpStatusCode.TooManyRequests)
            return@post
        }

        // The person approving must know the token shown inside the app on the phone
        if (!constantTimeEquals(form["owner_token"].orEmpty().trim(), ownerToken)) {
            oauth.recordFailure()
            call.respondHtml(consentPage(req, "Wrong token."), HttpStatusCode.Forbidden)
            return@post
        }
        oauth.recordSuccess()

        val code = oauth.createCode(req.client.clientId, req.redirectUri, req.challenge)
        call.respondRedirect(redirectWith(req.redirectUri, "code" to code, "state" to req.state))
    }

    // ---------- Token endpoint: form-urlencoded, as RFC 6749 requires ----------
    post("/token") {
        val form = try {
            call.receiveParameters()
        } catch (e: Exception) {
            call.oauthError(HttpStatusCode.BadRequest, "invalid_request", "Expected a form-urlencoded body")
            return@post
        }

        val clientId = form["client_id"].orEmpty()
        if (oauth.findClient(clientId) == null) {
            call.oauthError(HttpStatusCode.Unauthorized, "invalid_client", "Unknown client")
            return@post
        }

        when (form["grant_type"]) {
            "authorization_code" -> {
                val entry = oauth.consumeCode(form["code"].orEmpty())
                val redirectUri = form["redirect_uri"]
                val valid = entry != null &&
                    entry.clientId == clientId &&
                    (redirectUri == null || redirectUri == entry.redirectUri) &&
                    pkceMatches(form["code_verifier"].orEmpty(), entry.codeChallenge)
                if (!valid) {
                    call.oauthError(HttpStatusCode.BadRequest, "invalid_grant", "Invalid or expired authorization code")
                    return@post
                }
                call.respondTokens(oauth.issueTokens(clientId))
            }
            "refresh_token" -> {
                val pair = oauth.refreshTokens(form["refresh_token"].orEmpty(), clientId)
                if (pair == null) {
                    // RFC 6749 error code that makes Claude start a fresh sign-in
                    call.oauthError(HttpStatusCode.BadRequest, "invalid_grant", "Refresh token is invalid or expired")
                } else {
                    call.respondTokens(pair)
                }
            }
            else -> call.oauthError(HttpStatusCode.BadRequest, "unsupported_grant_type", "Unsupported grant_type")
        }
    }
}

// ---------- Helpers ----------

private class AuthRequest(
    val client: RegisteredClient,
    val redirectUri: String,
    val challenge: String,
    val state: String?
)

// Validates an authorization request (query string on GET, form fields on POST).
// Throws IllegalArgumentException with a message that is safe to show to the user.
private fun parseAuthRequest(oauth: OAuthStore, p: Parameters): AuthRequest {
    val client = oauth.findClient(p["client_id"].orEmpty())
        ?: throw IllegalArgumentException("Unknown client. Remove and re-add the connector in Claude.")
    val redirectUri = p["redirect_uri"] ?: client.redirectUris.singleOrNull().orEmpty()
    require(redirectUri in client.redirectUris) { "redirect_uri does not match the registered one" }
    require(p["response_type"] == "code") { "Only response_type=code is supported" }
    val challenge = p["code_challenge"].orEmpty()
    require(challenge.length in 43..128 && p["code_challenge_method"] == "S256") { "PKCE with S256 is required" }
    return AuthRequest(client, redirectUri, challenge, p["state"])
}

// PKCE: BASE64URL(SHA256(verifier)) must equal the challenge sent at /authorize
private fun pkceMatches(verifier: String, challenge: String): Boolean {
    if (verifier.length !in 43..128) return false
    val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
    val computed = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    return constantTimeEquals(computed, challenge)
}

private fun redirectWith(base: String, vararg params: Pair<String, String?>): String {
    val builder = URLBuilder(base)
    params.forEach { (key, value) -> if (value != null) builder.parameters.append(key, value) }
    return builder.buildString()
}

private suspend fun ApplicationCall.respondResourceMetadata() {
    val base = baseUrl()
    respondJson(buildJsonObject {
        put("resource", "$base/mcp")   // must equal the URL the user entered in Claude, path included
        putJsonArray("authorization_servers") { add(base) }
        putJsonArray("bearer_methods_supported") { add("header") }
    })
}

private suspend fun ApplicationCall.respondTokens(pair: TokenPair) {
    noStore()
    respondJson(buildJsonObject {
        put("access_token", pair.accessToken)
        put("token_type", "Bearer")
        put("expires_in", pair.expiresInSeconds)
        put("refresh_token", pair.refreshToken)
    })
}

private suspend fun ApplicationCall.oauthError(status: HttpStatusCode, error: String, description: String) {
    noStore()
    respondJson(buildJsonObject {
        put("error", error)
        put("error_description", description)
    }, status)
}

// HTML pages must never be framed (clickjacking) or cached
private suspend fun ApplicationCall.respondHtml(html: String, status: HttpStatusCode = HttpStatusCode.OK) {
    noStore()
    response.header("X-Frame-Options", "DENY")
    response.header("Content-Security-Policy", "frame-ancestors 'none'")
    respondText(html, ContentType.Text.Html, status)
}

// Escapes text before placing it in HTML (prevents script injection through client names etc.)
private fun esc(s: String) = s
    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    .replace("\"", "&quot;").replace("'", "&#39;")

private fun errorPage(message: String) = """<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="color-scheme" content="light dark"><title>AI MCP</title></head>
<body style="font-family:system-ui,sans-serif;max-width:480px;margin:2rem auto;padding:0 1rem">
<h2>AI MCP</h2><p>${esc(message)}</p></body></html>"""

private fun consentPage(req: AuthRequest, error: String?): String {
    // Show where the browser will be sent after approval
    val host = try { URI(req.redirectUri).host ?: req.redirectUri } catch (e: Exception) { req.redirectUri }
    val errorHtml = if (error != null) "<p class=\"err\">${esc(error)}</p>" else ""
    return """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="color-scheme" content="light dark">
<title>Authorize AI MCP</title>
<style>
body{font-family:system-ui,sans-serif;max-width:480px;margin:2rem auto;padding:0 1rem;line-height:1.5}
input,button{width:100%;box-sizing:border-box;padding:12px;margin-top:10px;font-size:16px}
code{word-break:break-all}
.err{color:#d32f2f;font-weight:bold}
</style></head><body>
<h2>AI MCP</h2>
<p><b>${esc(req.client.name)}</b> wants to read, write, delete and download files in the workspace folder of this phone.</p>
<p>After you approve, you will be sent back to: <code>${esc(host)}</code></p>
$errorHtml
<form method="post" action="/authorize">
<input type="hidden" name="client_id" value="${esc(req.client.clientId)}">
<input type="hidden" name="redirect_uri" value="${esc(req.redirectUri)}">
<input type="hidden" name="response_type" value="code">
<input type="hidden" name="code_challenge" value="${esc(req.challenge)}">
<input type="hidden" name="code_challenge_method" value="S256">
<input type="hidden" name="state" value="${esc(req.state.orEmpty())}">
<label>Enter the bearer token shown in the AI MCP app:</label>
<input type="password" name="owner_token" autocomplete="off" required>
<button type="submit" name="decision" value="allow">Allow</button>
<button type="submit" name="decision" value="deny" formnovalidate>Deny</button>
</form>
<p><small>Only approve if you just started this connection from Claude.</small></p>
</body></html>"""
}