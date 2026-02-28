package kr.kjh9211.remoteConsoleControl.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.javalin.Javalin
import io.javalin.http.staticfiles.Location
import io.javalin.websocket.WsContext
import kr.kjh9211.remoteConsoleControl.platform.Platform
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.layout.PatternLayout
import org.jose4j.jwe.ContentEncryptionAlgorithmIdentifiers
import org.jose4j.jwe.JsonWebEncryption
import org.jose4j.jwe.KeyManagementAlgorithmIdentifiers
import org.jose4j.keys.AesKey
import java.security.SecureRandom
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class RCCCore(private val platform: Platform) {

    private var app: Javalin? = null
    private val sessions = Collections.newSetFromMap(ConcurrentHashMap<WsContext, Boolean>())
    private val httpClient = OkHttpClient()
    private val objectMapper = ObjectMapper()
    private val pendingStates = ConcurrentHashMap<String, Long>() // State -> ExpiryTime
    
    lateinit var jwtKey: AesKey
    lateinit var clientId: String
    lateinit var clientSecret: String
    lateinit var redirectUri: String
    lateinit var allowedUsers: List<String>

    fun onEnable() {
        val log4jLogger = LogManager.getRootLogger() as org.apache.logging.log4j.core.Logger
        val appender = object : AbstractAppender("RemoteConsoleAppender", null,
            PatternLayout.createDefaultLayout(), false, null) {
            override fun append(event: LogEvent) {
                if (sessions.isNotEmpty()) {
                    val message = layout.toSerializable(event).toString()
                    sessions.filter { it.session.isOpen }.forEach { it.send(message) }
                }
            }
        }
        appender.start()
        log4jLogger.addAppender(appender)

        app = Javalin.create { config ->
            config.staticFiles.add{
                it.hostedPath = "/"
                it.directory = "/web"
                it.location = Location.CLASSPATH
            }
            config.spaRoot.addFile("/", "/web/index.html", Location.CLASSPATH)
            config.plugins.enableCors { cors ->
                cors.add {
                    it.anyHost()
                }
            }
        }
        
        app?.exception(Exception::class.java) { e, ctx ->
            platform.logSevere("Unhandled exception in Javalin handler: ${e.message}")
            e.printStackTrace()
            ctx.status(500).result("Internal Server Error: ${e.message}")
        }
        
        app?.start(7070)

        // API endpoints
        app?.get("/api/auth/url") { ctx ->
            val state = UUID.randomUUID().toString()
            ctx.cookie("oauth_state", state)
            pendingStates[state] = System.currentTimeMillis() + 600000 // 10 minutes expiry
            
            platform.logInfo("Generated state: $state")
            val discordAuthUrl = "https://discord.com/api/oauth2/authorize?client_id=$clientId&redirect_uri=$redirectUri&response_type=code&scope=identify&state=$state"
            ctx.json(mapOf("url" to discordAuthUrl))
        }

        app?.get("/api/auth/callback") { ctx ->
            val code = ctx.queryParam("code")
            val state = ctx.queryParam("state")
            val savedState = ctx.cookie("oauth_state")

            platform.logInfo("Received callback - state: $state, cookieState: $savedState")

            val isValid = (state != null && (state == savedState || pendingStates.containsKey(state)))
            
            if (!isValid) {
                platform.logWarning("State mismatch! External state: $state, Cookie: $savedState, Pending: ${pendingStates.keys()}")
                ctx.status(403).result("Invalid state (Authentication failed)")
                return@get
            }
            
            platform.logInfo("State validated. Exchanging code for token... Code: $code")
            
            // Remove state once used or expired
            state?.let { pendingStates.remove(it) }
            // Cleanup old states
            pendingStates.entries.removeIf { it.value < System.currentTimeMillis() }
            if (code == null) {
                ctx.status(400).result("Code not found")
                return@get
            }

            // Exchange code for token
            val tokenResponse = exchangeCodeForToken(code)
            if (tokenResponse == null) {
                platform.logSevere("exchangeCodeForToken returned null")
                ctx.status(500).result("Failed to get token from Discord")
                return@get
            }

            platform.logInfo("Token exchanged successfully. Getting user info...")
            val accessToken = tokenResponse["access_token"]?.asText()
            if (accessToken == null) {
                platform.logSevere("access_token is missing in response: $tokenResponse")
                ctx.status(500).result("access_token not found")
                return@get
            }

            // Get user info
            val userResponse = getDiscordUser(accessToken)
            if (userResponse == null) {
                platform.logSevere("getDiscordUser returned null")
                ctx.status(500).result("Failed to get user from Discord")
                return@get
            }

            platform.logInfo("User info received. Validating user ID...")
            val userId = userResponse["id"]?.asText()
            if (userId == null) {
                platform.logWarning("Discord user info response is missing 'id' field: $userResponse")
                ctx.status(500).result("Failed to identify user from Discord response")
                return@get
            }
            
            if (!allowedUsers.contains(userId)) {
                platform.logWarning("Unauthorized login attempt by Discord ID: $userId")
                ctx.status(403).result("User $userId is not in the allowed-users list.")
                return@get
            }

            // User is allowed, create a session token (JWE)
            val jwe = JsonWebEncryption().apply {
                setPayload(mapOf("sub" to userId, "iat" to System.currentTimeMillis()).let { objectMapper.writeValueAsString(it) })
                setAlgorithmHeaderValue(KeyManagementAlgorithmIdentifiers.A256KW)
                setEncryptionMethodHeaderParameter(ContentEncryptionAlgorithmIdentifiers.AES_128_CBC_HMAC_SHA_256)
                key = jwtKey
            }
            val token = jwe.compactSerialization
            ctx.redirect("/auth/callback?token=$token")
        }


        app?.ws("/console") { ws ->
            ws.onConnect { ctx: WsContext ->
                ctx.session.idleTimeout = java.time.Duration.ofMinutes(15)
                val token = ctx.queryParam("token")
                if (token == null || !validateToken(token)) {
                    ctx.session.close(403, "Unauthorized")
                    return@onConnect
                }
                sessions.add(ctx)
                platform.logInfo("Authenticated WebSocket connected")
            }
            ws.onClose { ctx ->
                sessions.remove(ctx)
                platform.logInfo("Authenticated WebSocket disconnected")
            }
            ws.onMessage { ctx ->
                val command = ctx.message().trim()
                if (command.isNotEmpty()) {
                    platform.runAsync(Runnable {
                        platform.dispatchCommand(command)
                    })
                }
            }
        }

        platform.logInfo("RCCCore enabled and listening on port 7070")
    }

    private fun exchangeCodeForToken(code: String): JsonNode? {
        val formBody = FormBody.Builder()
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", redirectUri)
            .build()
        val request = Request.Builder()
            .url("https://discord.com/api/oauth2/token")
            .post(formBody)
            .build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string()
                if (!response.isSuccessful) {
                    platform.logWarning("Discord token exchange failed! Code: ${response.code}, Body: $body")
                    return null
                }
                objectMapper.readTree(body)
            }
        } catch (e: Exception) {
            platform.logSevere("Exception during Discord token exchange: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    private fun getDiscordUser(accessToken: String): JsonNode? {
        val request = Request.Builder()
            .url("https://discord.com/api/users/@me")
            .header("Authorization", "Bearer $accessToken")
            .build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string()
                if (!response.isSuccessful) {
                    platform.logWarning("Discord user info fetch failed! Code: ${response.code}, Body: $body")
                    return null
                }
                objectMapper.readTree(body)
            }
        } catch (e: Exception) {
            platform.logSevere("Exception during Discord user info fetch: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    private fun validateToken(token: String): Boolean {
        return try {
            val jwe = JsonWebEncryption().apply {
                key = jwtKey
                compactSerialization = token
            }
            val payload = objectMapper.readTree(jwe.payload)
            val userId = payload["sub"].asText()
            allowedUsers.contains(userId)
        } catch (e: Exception) {
            false
        }
    }

    fun onDisable() {
        app?.stop()
        platform.logInfo("RCCCore has been disabled.")
    }
}
