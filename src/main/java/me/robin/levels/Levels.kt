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
import kotlin.math.pow

class Levels : JavaPlugin(), Listener {
    override fun onEnable() {
        server.pluginManager.registerEvents(this, this)
        this.saveDefaultConfig()
        this.getCommand("addxp")?.setExecutor(AddXPCommand(this))
        this.getCommand("removexp")?.setExecutor(RemoveXPCommand(this))
        this.getCommand("clearalldata")?.setExecutor(ClearAllDataCommand(this))
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

    fun savePlayerData(player: Player) {
        val xp = player.persistentDataContainer.getOrDefault(customXPKey(), PersistentDataType.INTEGER, 0)
        player.persistentDataContainer.set(customXPKey(), PersistentDataType.INTEGER, xp)
        logger.info("Saved player data!")
    }

    fun deletePlayerData(player: Player) {
        player.persistentDataContainer.remove(customXPKey())
        logger.info("Deleted player data!")
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
        val maxXP = calculateMaxXP()
        val newXP = (currentXP + xpToAdd).coerceAtMost(maxXP)
        xpDataContainer.set(customXPKey(), PersistentDataType.INTEGER, newXP)
        player.sendMessage("$xpToAdd XP added, total: $newXP.")
        checkLevelUp(player, newXP)
    }

    fun calculateMaxXP(): Int {
        val maxLevel = config.getInt("maxLevel", 100)
        return calculateXPForLevel(maxLevel)
    }

    fun calculateXPForLevel(level: Int): Int {
        // Adjust the XP calculation to find the XP needed for any given level
        return (level.toDouble().pow(2) * 5).toInt() // Inverse of level calculation
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
        val maxLevel = config.getInt("maxLevel", 100)
        val maxXP = calculateMaxXP()

        // If XP is exactly at max or just overflowed to it due to addition, set level to max
        if (totalXP >= maxXP) {
            player.sendMessage("You've reached the maximum level!")
            if (player.level < maxLevel) {
                player.level = maxLevel
                updatePlayerHealth(player, maxLevel)
            }
            return
        }

        if (currentLevel != player.level) {
            player.level = currentLevel
            player.sendMessage("Congratulations! You've reached level $currentLevel")
            updatePlayerHealth(player, currentLevel)
        }
    }

    fun calculateLevel(xp: Int): Int {
        // Adjust the level calculation to scale less steeply
        return (sqrt(xp.toDouble() / 5).toInt()) // Dividing XP by 5 to slow down the rate at which levels increase
    }

    private fun updatePlayerHealth(player: Player, level: Int) {
        val baseHealth = config.getDouble("baseHealth", 20.0)  // Default minimum health
        val maxReachableHealth = config.getDouble("maxReachableHealth", 40.0)  // Configurable maximum health
        val maxLevel = config.getInt("maxLevel", 100)  // Maximum level from config

        // Calculate health per level incrementally
        val healthIncrement = (maxReachableHealth - baseHealth) / (maxLevel - 1)
        val newHealth = (baseHealth + healthIncrement * (level - 1)).coerceIn(baseHealth, maxReachableHealth)

        // Get the attribute instance for max health and safely update it
        val attribute = player.getAttribute(Attribute.GENERIC_MAX_HEALTH)
        attribute?.let {
            it.baseValue = newHealth
            if (player.health > newHealth) {  // Ensure current health does not exceed max health
                player.setHealth(newHealth)
            }
        }
    }

    fun customXPKey() = org.bukkit.NamespacedKey(this, "custom_xp")
}

class ClearAllDataCommand(private val plugin: Levels) : CommandExecutor {
    // This command is for testing purposes only delete all player data
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (args.isNotEmpty()) {
            sender.sendMessage("Usage: /clearalldata")
            return true
        }
        plugin.server.onlinePlayers.forEach(plugin::deletePlayerData)
        sender.sendMessage("All player data cleared.")
        return true
    }
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