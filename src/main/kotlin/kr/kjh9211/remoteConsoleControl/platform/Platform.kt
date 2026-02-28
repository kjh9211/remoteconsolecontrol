package kr.kjh9211.remoteConsoleControl.platform

import java.io.File

interface Platform {
    fun logInfo(message: String)
    fun logWarning(message: String)
    fun logSevere(message: String)
    fun runAsync(runnable: Runnable)
    fun dispatchCommand(command: String)
    fun getPluginDataFolder(): File
}
