package org.connectbot.portal

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit

class HubClient(
    private val store: PortalStore,
    private val http: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .build(),
) {
    data class Pkce(
        val state: String,
        val verifier: String,
        val challenge: String,
    )

    fun newPkce(): Pkce {
        val state = randomUrlToken(24)
        val verifier = randomUrlToken(32)
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
        val challenge = Base64.encodeToString(
            digest,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        return Pkce(state, verifier, challenge)
    }

    fun authorizeUrl(hubUrl: String, pkce: Pkce): String {
        val redirect = ANDROID_REDIRECT_URI.urlEncode()
        return "${hubUrl.trimEnd('/')}/oauth/authorize" +
            "?response_type=code" +
            "&client_id=portal-android" +
            "&redirect_uri=$redirect" +
            "&code_challenge=${pkce.challenge.urlEncode()}" +
            "&code_challenge_method=S256" +
            "&state=${pkce.state.urlEncode()}"
    }

    suspend fun requireAndroidOAuthSupport(hubUrl: String, pkce: Pkce) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(authorizeUrl(hubUrl, pkce))
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            val bodyText = response.body?.string().orEmpty()
            if (response.isSuccessful || response.isRedirect) {
                return@withContext
            }
            if (bodyText.contains("unknown client_id", ignoreCase = true)) {
                throw IllegalStateException(
                    "This Portal Hub build does not support Portal Android sign-in. Update portal-hub to a build that registers client_id portal-android.",
                )
            }
            throw IllegalStateException("Portal Hub Android sign-in check failed (${response.code}): $bodyText")
        }
    }

    suspend fun fetchInfo(hubUrl: String): HubInfo = withContext(Dispatchers.IO) {
        val json = executeJson(
            Request.Builder()
                .url("${hubUrl.trimEnd('/')}/api/info")
                .get()
                .build(),
        )
        val capabilities = json.optJSONObject("capabilities") ?: JSONObject()
        HubInfo(
            apiVersion = json.optInt("api_version"),
            version = json.optString("version"),
            publicUrl = json.optString("public_url"),
            webProxy = capabilities.optBoolean("web_proxy"),
            syncV2 = capabilities.optBoolean("sync_v2"),
            syncEvents = capabilities.optBoolean("sync_events"),
            keyVault = capabilities.optBoolean("key_vault"),
            vaultEnrollment = capabilities.optBoolean("vault_enrollment"),
        )
    }

    suspend fun exchangeCode(hubUrl: String, code: String, verifier: String): HubTokens =
        withContext(Dispatchers.IO) {
            val body = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", ANDROID_REDIRECT_URI)
                .add("client_id", "portal-android")
                .add("code_verifier", verifier)
                .build()
            val json = executeJson(
                Request.Builder()
                    .url("${hubUrl.trimEnd('/')}/oauth/token")
                    .post(body)
                    .build(),
            )
            HubTokens(
                accessToken = json.getString("access_token"),
                refreshToken = json.getString("refresh_token"),
            ).also(store::saveTokens)
        }

    suspend fun me(): String {
        val json = authorizedJson { token ->
            Request.Builder()
                .url("${store.hubUrl}/api/me")
                .header("Authorization", "Bearer $token")
                .get()
                .build()
        }
        return json.optString("username")
    }

    suspend fun syncState(): HubSyncState {
        val json = authorizedJson { token ->
            Request.Builder()
                .url("${store.hubUrl}/api/sync/v2")
                .header("Authorization", "Bearer $token")
                .get()
                .build()
        }
        store.saveSyncSnapshot(json.toString())
        return json.toHubSyncState()
    }

    suspend fun putVault(vault: HubVaultConfig): HubSyncState {
        return putSyncService("vault", vault.toJson())
    }

    suspend fun putHosts(payload: JSONObject): HubSyncState {
        return putSyncService("hosts", payload)
    }

    private suspend fun putSyncService(name: String, payload: JSONObject): HubSyncState {
        val current = syncState()
        val currentService = current.services[name]
        val body = JSONObject()
            .put(
                "services",
                JSONObject()
                    .put(
                        name,
                        JSONObject()
                            .put("expected_revision", currentService?.revision ?: "0")
                            .put("payload", payload)
                            .put("tombstones", currentService?.tombstones ?: emptyList<String>()),
                    ),
            )
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val json = authorizedJson { token ->
            Request.Builder()
                .url("${store.hubUrl}/api/sync/v2")
                .header("Authorization", "Bearer $token")
                .put(body)
                .build()
        }
        store.saveSyncSnapshot(json.toString())
        return json.toHubSyncState()
    }

    suspend fun createVaultEnrollment(
        deviceName: String,
        publicKeyDerBase64: String,
        pairingId: String?,
    ): VaultEnrollment {
        val body = JSONObject()
            .put("device_name", deviceName)
            .put("public_key_algorithm", "RSA-OAEP-SHA256")
            .put("public_key_der_base64", publicKeyDerBase64)
            .also { if (!pairingId.isNullOrBlank()) it.put("pairing_id", pairingId) }
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val json = authorizedJson { token ->
            Request.Builder()
                .url("${store.hubUrl}/api/vault/enrollments")
                .header("Authorization", "Bearer $token")
                .post(body)
                .build()
        }
        return VaultEnrollment.fromJson(json)
    }

    suspend fun loadVaultEnrollment(id: String): VaultEnrollment {
        val json = authorizedJson { token ->
            Request.Builder()
                .url("${store.hubUrl}/api/vault/enrollments/${id.urlEncode()}")
                .header("Authorization", "Bearer $token")
                .get()
                .build()
        }
        return VaultEnrollment.fromJson(json)
    }

    suspend fun streamVaultEnrollmentEvents(
        id: String,
        onEnrollment: (VaultEnrollment) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val tokens = store.loadTokens() ?: throw IllegalStateException("Portal Hub is not authenticated")
        val response = http.newCall(
            Request.Builder()
                .url("${store.hubUrl}/api/vault/enrollments/${id.urlEncode()}/events")
                .header("Authorization", "Bearer ${tokens.accessToken}")
                .get()
                .build(),
        ).execute()
        response.use {
            it.throwIfFailed()
            it.body?.charStream()?.buffered()?.useLines { lines ->
                lines.forEach { line ->
                    if (line.startsWith("data:")) {
                        val payload = JSONObject(line.removePrefix("data:").trim())
                        val enrollment = payload.optJSONObject("enrollment") ?: return@forEach
                        onEnrollment(VaultEnrollment.fromJson(enrollment))
                    }
                }
            }
        }
    }

    suspend fun listSessions(): List<HubSession> {
        val json = authorizedJson { token ->
            Request.Builder()
                .url("${store.hubUrl}/api/sessions?active=true")
                .header("Authorization", "Bearer $token")
                .get()
                .build()
        }
        val sessions = json.optJSONArray("sessions") ?: return emptyList()
        return (0 until sessions.length())
            .mapNotNull { sessions.optJSONObject(it) }
            .map {
                HubSession(
                    sessionId = it.optString("session_id"),
                    targetHost = it.optString("target_host"),
                    targetPort = it.optInt("target_port", 22),
                    targetUser = it.optString("target_user"),
                    createdAt = it.optString("created_at"),
                    updatedAt = it.optString("updated_at"),
                )
            }
    }

    suspend fun killSession(sessionId: String) {
        authorizedUnit { token ->
            Request.Builder()
                .url("${store.hubUrl}/api/sessions/${sessionId.urlEncode()}")
                .header("Authorization", "Bearer $token")
                .delete()
                .build()
        }
    }

    fun openTerminal(
        target: TerminalTarget,
        listener: TerminalListener,
    ): HubTerminal {
        val token = store.loadTokens()?.accessToken
            ?: throw IllegalStateException("Portal Hub is not authenticated")
        val request = Request.Builder()
            .url(terminalWsUrl(store.hubUrl))
            .header("Authorization", "Bearer $token")
            .build()
        val terminal = HubTerminal(target, listener)
        terminal.attach(http.newWebSocket(request, terminal))
        return terminal
    }

    private suspend fun authorizedJson(builder: (String) -> Request): JSONObject =
        withContext(Dispatchers.IO) {
            val tokens = store.loadTokens() ?: throw IllegalStateException("Portal Hub is not authenticated")
            val first = http.newCall(builder(tokens.accessToken)).execute()
            if (first.code != 401) {
                return@withContext first.use { it.jsonOrThrow() }
            }
            first.close()
            val refreshed = refresh(tokens.refreshToken)
            executeJson(builder(refreshed.accessToken))
        }

    private suspend fun authorizedUnit(builder: (String) -> Request) =
        withContext(Dispatchers.IO) {
            val tokens = store.loadTokens() ?: throw IllegalStateException("Portal Hub is not authenticated")
            val first = http.newCall(builder(tokens.accessToken)).execute()
            if (first.code != 401) {
                first.use { it.throwIfFailed() }
                return@withContext
            }
            first.close()
            val refreshed = refresh(tokens.refreshToken)
            http.newCall(builder(refreshed.accessToken)).execute().use { it.throwIfFailed() }
        }

    private fun refresh(refreshToken: String): HubTokens {
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", "portal-android")
            .build()
        val json = executeJson(
            Request.Builder()
                .url("${store.hubUrl}/oauth/token")
                .post(body)
                .build(),
        )
        return HubTokens(
            accessToken = json.getString("access_token"),
            refreshToken = json.getString("refresh_token"),
        ).also(store::saveTokens)
    }

    private fun executeJson(request: Request): JSONObject =
        http.newCall(request).execute().use { it.jsonOrThrow() }

    private fun Response.jsonOrThrow(): JSONObject {
        val bodyText = body?.string().orEmpty()
        if (!isSuccessful) {
            throw IllegalStateException("Portal Hub request failed (${code}): $bodyText")
        }
        return JSONObject(bodyText)
    }

    private fun Response.throwIfFailed() {
        val bodyText = body?.string().orEmpty()
        if (!isSuccessful) {
            throw IllegalStateException("Portal Hub request failed (${code}): $bodyText")
        }
    }

    private fun randomUrlToken(bytes: Int): String {
        val buffer = ByteArray(bytes)
        SecureRandom().nextBytes(buffer)
        return Base64.encodeToString(buffer, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    companion object {
        const val ANDROID_REDIRECT_URI = "com.digitalpals.portal.android:/oauth2redirect"

        fun terminalTarget(host: PortalHost) = TerminalTarget(
            sessionId = UUID.randomUUID().toString(),
            targetHost = host.hostname,
            targetPort = host.port,
            targetUser = host.targetUser,
        )

        fun terminalTarget(session: HubSession) = TerminalTarget(
            sessionId = session.sessionId,
            targetHost = session.targetHost,
            targetPort = session.targetPort,
            targetUser = session.targetUser,
        )

        fun terminalWsUrl(hubUrl: String): String = when {
            hubUrl.startsWith("https://") -> "wss://${hubUrl.removePrefix("https://").trimEnd('/')}/api/sessions/terminal"
            hubUrl.startsWith("http://") -> "ws://${hubUrl.removePrefix("http://").trimEnd('/')}/api/sessions/terminal"
            else -> throw IllegalArgumentException("Portal Hub URL must start with http:// or https://")
        }
    }
}

data class TerminalTarget(
    val sessionId: String,
    val targetHost: String,
    val targetPort: Int,
    val targetUser: String,
    val cols: Int = 80,
    val rows: Int = 24,
    val privateKey: String? = null,
)

data class VaultEnrollment(
    val id: String,
    val deviceName: String,
    val status: String,
    val encryptedSecretBase64: String?,
    val pairingId: String?,
    val createdAt: String,
    val updatedAt: String,
    val approvedAt: String?,
    val revokedAt: String?,
) {
    companion object {
        fun fromJson(json: JSONObject) = VaultEnrollment(
            id = json.getString("id"),
            deviceName = json.optString("device_name"),
            status = json.optString("status"),
            encryptedSecretBase64 = if (json.has("encrypted_secret_base64") && !json.isNull("encrypted_secret_base64")) {
                json.optString("encrypted_secret_base64").ifBlank { null }
            } else {
                null
            },
            pairingId = if (json.has("pairing_id") && !json.isNull("pairing_id")) {
                json.optString("pairing_id").ifBlank { null }
            } else {
                null
            },
            createdAt = json.optString("created_at"),
            updatedAt = json.optString("updated_at"),
            approvedAt = if (json.has("approved_at") && !json.isNull("approved_at")) json.optString("approved_at") else null,
            revokedAt = if (json.has("revoked_at") && !json.isNull("revoked_at")) json.optString("revoked_at") else null,
        )
    }
}

interface TerminalListener {
    fun onStarted()

    fun onOutput(text: String)

    fun onOutput(bytes: ByteArray) {
        onOutput(bytes.toString(Charsets.UTF_8))
    }

    fun onError(message: String)

    fun onClosed()
}

class HubTerminal(
    private val target: TerminalTarget,
    private val terminalListener: TerminalListener,
) : WebSocketListener() {
    private var webSocket: WebSocket? = null

    fun attach(webSocket: WebSocket) {
        this.webSocket = webSocket
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        val start = JSONObject()
            .put("session_id", target.sessionId)
            .put("target_host", target.targetHost)
            .put("target_port", target.targetPort)
            .put("target_user", target.targetUser)
            .put("cols", target.cols)
            .put("rows", target.rows)
        target.privateKey?.let { start.put("private_key", it) }
        webSocket.send(start.toString())
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        val json = runCatching { JSONObject(text) }.getOrNull()
        when (json?.optString("type")) {
            "started" -> terminalListener.onStarted()
            "error" -> terminalListener.onError(json.optString("message", text))
            "closed", "exit", "exited" -> terminalListener.onClosed()
            else -> terminalListener.onOutput(text)
        }
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        terminalListener.onOutput(bytes.toByteArray())
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        terminalListener.onError(t.message ?: "Portal Hub terminal failed")
        terminalListener.onClosed()
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        terminalListener.onClosed()
    }

    fun send(text: String) {
        webSocket?.send(ByteString.of(*text.toByteArray(Charsets.UTF_8)))
    }

    fun send(bytes: ByteArray) {
        webSocket?.send(ByteString.of(*bytes))
    }

    fun resize(cols: Int, rows: Int) {
        webSocket?.send(JSONObject().put("type", "resize").put("cols", cols).put("rows", rows).toString())
    }

    fun close() {
        webSocket?.close(1000, "detached")
    }
}

private fun String.urlEncode(): String = java.net.URLEncoder.encode(this, "UTF-8")
