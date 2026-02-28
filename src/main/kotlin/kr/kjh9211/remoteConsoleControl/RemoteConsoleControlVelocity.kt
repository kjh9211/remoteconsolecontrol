package kr.kjh9211.remoteConsoleControl

import com.google.inject.Inject
import com.velocitypowered.api.command.SimpleCommand
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ProxyServer
import kr.kjh9211.remoteConsoleControl.core.RCCCore
import kr.kjh9211.remoteConsoleControl.platform.Platform
import net.kyori.adventure.text.Component
import org.jose4j.keys.AesKey
import org.slf4j.Logger
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.nio.file.Path
import java.util.*
import kotlin.io.path.exists

@Plugin(id = "remoteconsolecontrol", name = "remoteConsoleControl", version = "1.0", authors = ["kjh9211"])
class RemoteConsoleControlVelocity @Inject constructor(
    private val proxy: ProxyServer,
    private val logger: Logger,
    @DataDirectory private val dataDirectory: Path
) : Platform {

    private lateinit var core: RCCCore

    @Subscribe
    fun onProxyInitialization(event: ProxyInitializeEvent) {
        core = RCCCore(this)
        loadPluginConfig()
        core.onEnable()
        logger.info("RemoteConsoleControl (Velocity) enabled and listening on port 7070")
        
        // Register command
        val commandManager = proxy.commandManager
        val commandMeta = commandManager.metaBuilder("rcc").build()
        commandManager.register(commandMeta, VelocityRCCCommand(this))
    }

    @Subscribe
    fun onProxyShutdown(event: ProxyShutdownEvent) {
        if (::core.isInitialized) {
            core.onDisable()
        }
        logger.info("RemoteConsoleControl (Velocity) has been disabled.")
    }

    @Suppress("UNCHECKED_CAST")
    fun loadPluginConfig() {
        val configFile = dataDirectory.resolve("config.yml").toFile()
        if (!dataDirectory.exists()) {
            dataDirectory.toFile().mkdirs()
        }
        if (!configFile.exists()) {
            javaClass.getResourceAsStream("/config.yml")?.use { input ->
                configFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }

        val yaml = Yaml()
        val configMap: Map<String, Any?> = try {
            configFile.inputStream().use { yaml.load(it) } ?: emptyMap()
        } catch (e: Exception) {
            logger.error("Failed to load config.yml: ${e.message}")
            emptyMap()
        }

        val jwtKeyStr = configMap["jwt-key"] as? String
        val keyBytes: ByteArray = jwtKeyStr?.let { 
            try { Base64.getDecoder().decode(it) } catch (e: Exception) { null }
        } ?: run {
            val key = ByteArray(32)
            java.security.SecureRandom().nextBytes(key)
            val newKeyStr = Base64.getEncoder().encodeToString(key)
            
            // Save new key back to file (simple append or rewrite)
            try {
                val mutableConfig = configMap.toMutableMap()
                mutableConfig["jwt-key"] = newKeyStr
                configFile.writeText(yaml.dump(mutableConfig))
            } catch (e: Exception) {
                logger.error("Failed to save jwt-key to config: ${e.message}")
            }
            key
        }

        core.jwtKey = AesKey(keyBytes)
        
        val discord = configMap["discord"] as? Map<String, Any?>
        core.clientId = discord?.get("client-id") as? String ?: ""
        core.clientSecret = discord?.get("client-secret") as? String ?: ""
        core.redirectUri = discord?.get("redirect-uri") as? String ?: ""
        core.allowedUsers = configMap["allowed-users"] as? List<String> ?: emptyList()

        if(core.clientId.isEmpty() || core.clientSecret.isEmpty() || core.redirectUri.isEmpty()){
            logger.error("Discord OAuth2 credentials are not configured in config.yml!")
        }
    }

    // Platform implementation
    override fun logInfo(message: String) = logger.info(message)
    override fun logWarning(message: String) = logger.warn(message)
    override fun logSevere(message: String) = logger.error(message)

    override fun runAsync(runnable: Runnable) {
        proxy.scheduler.buildTask(this, runnable).schedule()
    }

    override fun dispatchCommand(command: String) {
        proxy.commandManager.executeAsync(proxy.consoleCommandSource, command)
    }

    override fun getPluginDataFolder(): File = dataDirectory.toFile()
}

class VelocityRCCCommand(private val plugin: RemoteConsoleControlVelocity) : SimpleCommand {
    override fun execute(invocation: SimpleCommand.Invocation) {
        val args = invocation.arguments()
        val sender = invocation.source()
        if (args.isNotEmpty() && args[0].equals("reload", ignoreCase = true)) {
            if (!sender.hasPermission("rcc.admin")) {
                sender.sendMessage(Component.text("§cYou do not have permission to use this command."))
                return
            }
            plugin.loadPluginConfig()
            sender.sendMessage(Component.text("§aRemoteConsoleControl configuration reloaded."))
        }
    }
}
