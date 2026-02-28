package kr.kjh9211.remoteConsoleControl

import kr.kjh9211.remoteConsoleControl.core.RCCCore
import kr.kjh9211.remoteConsoleControl.platform.Platform
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.jose4j.keys.AesKey
import java.io.File
import java.util.*

class RemoteConsoleControl : JavaPlugin(), Platform {

    private lateinit var core: RCCCore

    override fun onLoad() {
        core = RCCCore(this)
        loadPluginConfig()
    }

    private fun loadPluginConfig() {
        reloadConfig()
        saveDefaultConfig()
        
        val keyBytes: ByteArray = config.getString("jwt-key")?.let { Base64.getDecoder().decode(it) } ?: run {
            val key = ByteArray(32)
            java.security.SecureRandom().nextBytes(key)
            key
        }
        if (!config.contains("jwt-key")) {
            config.set("jwt-key", Base64.getEncoder().encodeToString(keyBytes))
            saveConfig()
        }
        
        core.jwtKey = AesKey(keyBytes)
        core.clientId = config.getString("discord.client-id") ?: ""
        core.clientSecret = config.getString("discord.client-secret") ?: ""
        core.redirectUri = config.getString("discord.redirect-uri") ?: ""
        core.allowedUsers = config.getStringList("allowed-users")

        if(core.clientId.isEmpty() || core.clientSecret.isEmpty() || core.redirectUri.isEmpty()){
            logger.severe("Discord OAuth2 credentials are not configured in config.yml!")
        }
    }


    override fun onEnable() {
        core.onEnable()
        logger.info("RemoteConsoleControl (Paper) enabled and listening on port 7070")
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (command.name.equals("rcc", ignoreCase = true)) {
            if (args.isNotEmpty() && args[0].equals("reload", ignoreCase = true)) {
                if (!sender.hasPermission("rcc.admin")) {
                    sender.sendMessage("§cYou do not have permission to use this command.")
                    return true
                }
                loadPluginConfig()
                sender.sendMessage("§aRemoteConsoleControl configuration reloaded.")
                return true
            }
        }
        return false
    }

    override fun onDisable() {
        core.onDisable()
        logger.info("RemoteConsoleControl (Paper) has been disabled.")
    }

    // Platform implementation
    override fun logInfo(message: String) = logger.info(message)
    override fun logWarning(message: String) = logger.warning(message)
    override fun logSevere(message: String) = logger.severe(message)
    
    override fun runAsync(runnable: Runnable) {
        server.scheduler.runTask(this, runnable)
    }
    
    override fun dispatchCommand(command: String) {
        server.dispatchCommand(server.consoleSender, command)
    }
    
    override fun getPluginDataFolder(): File = dataFolder
}
