package me.robin.levels

import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerExpChangeEvent
import org.bukkit.attribute.Attribute
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import kotlin.math.sqrt
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.scheduler.BukkitRunnable

class Levels : JavaPlugin(), Listener {
    override fun onEnable() {
        server.pluginManager.registerEvents(this, this)
        this.saveDefaultConfig()
        this.getCommand("addxp")?.setExecutor(AddXPCommand(this))
        this.getCommand("removexp")?.setExecutor(RemoveXPCommand(this))
        startAutoSaveTask()
        if (server.pluginManager.getPlugin("PlaceholderAPI") != null) {
            LevelsExpansion(this).register()
        } else {
            logger.warning("PlaceholderAPI not found. Placeholder expansion will not be registered.")
        }
        logger.info("Levels plugin has been enabled.")
    }

    override fun onDisable() {
        server.onlinePlayers.forEach(this::savePlayerData)
        logger.info("Levels data saved and plugin disabled.")
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        initializePlayerData(event.player)
    }

    @EventHandler
    fun onPlayerRespawn(event: PlayerRespawnEvent) {
        initializePlayerData(event.player)
    }

    @EventHandler
    fun onPlayerExpChange(event: PlayerExpChangeEvent) {
        event.amount = 0  // Disable traditional XP gain
    }

    @EventHandler
    fun onPlayerDeath(event: PlayerDeathEvent) {
        // Keep XP and level after respawn
        event.keepLevel = true
        event.keepInventory = true
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        savePlayerData(event.player)
    }

    private fun initializePlayerData(player: Player) {
        val currentXP = player.persistentDataContainer.getOrDefault(customXPKey(), PersistentDataType.INTEGER, 0)
        checkLevelUp(player, currentXP)  // Recalculate and set level based on XP
    }

    private fun savePlayerData(player: Player) {
        val xp = player.persistentDataContainer.getOrDefault(customXPKey(), PersistentDataType.INTEGER, 0)
        player.persistentDataContainer.set(customXPKey(), PersistentDataType.INTEGER, xp)
        logger.info("Saved player data!")
    }

    private fun startAutoSaveTask() {
        val interval = config.getLong("autosave_interval_ticks", 6000) // Default to 5 minutes if not set
        object : BukkitRunnable() {
            override fun run() {
                server.onlinePlayers.forEach { player ->
                    savePlayerData(player)
                }
            }
        }.runTaskTimer(this, interval, interval)
    }

    fun addCustomXP(player: Player, xpToAdd: Int) {
        val xpDataContainer = player.persistentDataContainer
        val currentXP = xpDataContainer.getOrDefault(customXPKey(), PersistentDataType.INTEGER, 0)
        val newXP = currentXP + xpToAdd
        xpDataContainer.set(customXPKey(), PersistentDataType.INTEGER, newXP)
        player.sendMessage("$xpToAdd XP added.")
        checkLevelUp(player, newXP)
    }

    fun removeCustomXP(player: Player, xpToRemove: Int) {
        val xpDataContainer = player.persistentDataContainer
        val currentXP = xpDataContainer.getOrDefault(customXPKey(), PersistentDataType.INTEGER, 0)
        val newXP = (currentXP - xpToRemove).coerceAtLeast(0)  // Prevents XP from going negative
        xpDataContainer.set(customXPKey(), PersistentDataType.INTEGER, newXP)
        player.sendMessage("$xpToRemove XP removed. Total XP now: $newXP")
        checkLevelUp(player, newXP)  // Check if level needs to be adjusted
    }

    private fun checkLevelUp(player: Player, totalXP: Int) {
        val currentLevel = calculateLevel(totalXP)
        if (currentLevel != player.level) {
            player.level = currentLevel
            player.sendMessage("Congratulations! You've reached level $currentLevel")
            updatePlayerHealth(player, currentLevel)
        }
    }

    fun calculateLevel(xp: Int): Int {
        // Example: Simple square root level calculation
        return sqrt(xp.toDouble()).toInt()
    }

    private fun updatePlayerHealth(player: Player, level: Int) {
        val baseHealth = this.config.getDouble("baseHealth")
        val healthPerLevel = this.config.getDouble("healthPerLevel")
        val newHealth = baseHealth + healthPerLevel * level

        player.getAttribute(Attribute.GENERIC_MAX_HEALTH)?.let {
            it.baseValue = newHealth
            if (player.health > it.value) {
                player.health = it.value
            }
        } ?: player.sendMessage("Error setting health.")
    }

    fun customXPKey() = org.bukkit.NamespacedKey(this, "custom_xp")
}

class AddXPCommand(private val plugin: Levels) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (args.size < 2) {
            sender.sendMessage("Usage: /addxp <player> <amount>")
            return true
        }
        val player = plugin.server.getPlayer(args[0])
        if (player == null) {
            sender.sendMessage("Player not found.")
            return true
        }
        val xpAmount = args[1].toIntOrNull()
        if (xpAmount == null) {
            sender.sendMessage("Invalid XP amount.")
            return true
        }
        plugin.addCustomXP(player, xpAmount)
        sender.sendMessage("${xpAmount} XP added to ${player.name}.")
        return true
    }
}

class RemoveXPCommand(private val plugin: Levels) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (args.size < 2) {
            sender.sendMessage("Usage: /removexp <player> <amount>")
            return true
        }
        val player = plugin.server.getPlayer(args[0])
        if (player == null) {
            sender.sendMessage("Player not found.")
            return true
        }
        val xpAmount = args[1].toIntOrNull()
        if (xpAmount == null || xpAmount <= 0) {
            sender.sendMessage("Invalid XP amount. Enter a positive number.")
            return true
        }
        plugin.removeCustomXP(player, xpAmount)
        sender.sendMessage("${xpAmount} XP removed from ${player.name}.")
        return true
    }
}


class LevelsExpansion(private val plugin: Levels) : PlaceholderExpansion() {

    override fun getIdentifier(): String {
        return "levels"
    }

    override fun getAuthor(): String {
        return plugin.description.authors.joinToString(", ")
    }

    override fun getVersion(): String {
        return plugin.description.version
    }

    override fun canRegister(): Boolean {
        return true
    }

    override fun onPlaceholderRequest(player: Player?, identifier: String): String? {
        if (player == null) {
            return ""
        }

        when (identifier) {
            "xp" -> {
                val xp = player.persistentDataContainer.get(plugin.customXPKey(), PersistentDataType.INTEGER) ?: 0
                return xp.toString()
            }
            "level" -> return player.level.toString()
            "health" -> return String.format("%.2f", player.health)
            "max_health" -> player.getAttribute(Attribute.GENERIC_MAX_HEALTH)?.let {
                return String.format("%.2f", it.value)
            }
            "xp_to_next_level" -> {
                val currentXP = player.persistentDataContainer.get(plugin.customXPKey(), PersistentDataType.INTEGER) ?: 0
                val currentLevel = plugin.calculateLevel(currentXP)
                val nextLevel = currentLevel + 1
                val nextLevelXP = nextLevel * nextLevel
                return (nextLevelXP - currentXP).toString()
            }
        }

        return null
    }
}