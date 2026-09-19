package com.gadgetman.jarvis;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Duration;

/**
 * Executes Jarvis action commands triggered via natural language or /jarvis ask.
 * Must always be called from the main server thread.
 */
public class JarvisActionExecutor {

    private final Jarvis plugin;

    // Actions that require player confirmation before executing
    public static final java.util.Set<String> DANGEROUS_ACTIONS = java.util.Set.of(
            "set_gamemode", "lp_group_add", "lp_group_remove",
            "broadcast", "discord_broadcast", "summon",
            // New dangerous actions
            "console_command", "console_commands",
            "clear_mobs", "set_difficulty", "schedule_broadcast"
    );

    public JarvisActionExecutor(Jarvis plugin) {
        this.plugin = plugin;
    }

    /**
     * Execute an action and return a result message.
     * @return human-readable result, or null if action is unknown
     */
    public String execute(String actionType, JSONObject params, Player requester) {
        if (params == null) params = new JSONObject();
        try {
            return switch (actionType) {
                case "give_item"         -> executeGiveItem(params, requester);
                case "enchant"           -> executeEnchant(params, requester);
                case "potion_effect"     -> executePotionEffect(params, requester);
                case "heal"              -> executeHeal(params, requester);
                case "feed"              -> executeFeed(params, requester);
                case "set_gamemode"      -> executeSetGamemode(params, requester);
                case "teleport"          -> executeTeleport(params, requester);
                case "set_time"          -> executeSetTime(params, requester);
                case "set_weather"       -> executeSetWeather(params, requester);
                case "set_gamerule"      -> executeSetGamerule(params);
                case "summon"            -> executeSummon(params, requester);
                case "broadcast"         -> executeBroadcast(params);
                case "server_say"        -> executeServerSay(params);
                case "lp_group_add"      -> executeLpGroupAdd(params);
                case "lp_group_remove"   -> executeLpGroupRemove(params);
                case "warp"              -> executeWarp(params, requester);
                case "discord_broadcast" -> executeDiscordBroadcast(params);
                case "paste_schematic"   -> executePasteSchematic(params, requester);
                // New v0.0.9 actions
                case "console_command"   -> executeConsoleCommand(params);
                case "console_commands"  -> executeConsoleCommands(params);
                case "clear_mobs"        -> executeClearMobs(params, requester);
                case "clear_drops"       -> executeClearDrops(params, requester);
                case "save_world"        -> executeSaveWorld(requester);
                case "set_difficulty"    -> executeSetDifficulty(params, requester);
                case "announce_all"      -> executeAnnounceAll(params);
                case "schedule_broadcast"-> executeScheduleBroadcast(params);
                case "request_item"      -> executeRequestItem(params, requester);
                default                  -> null;
            };
        } catch (Exception e) {
            plugin.getLogger().warning("Action '" + actionType + "' failed: " + e.getMessage());
            return "Action failed: " + e.getMessage();
        }
    }

    /** Build a short human-readable description of what an action will do. */
    public String describe(String actionType, JSONObject p) {
        if (p == null) p = new JSONObject();
        return switch (actionType) {
            case "give_item"       -> "Give " + p.optInt("amount", 1) + "x "
                                       + p.optString("item", "?") + " to " + p.optString("player", "you");
            case "enchant"         -> "Enchant " + p.optString("player", "your") + "'s item with "
                                       + p.optString("enchantment", "?") + " " + p.optInt("level", 1);
            case "set_gamemode"    -> "Set " + p.optString("player", "your") + "'s gamemode to "
                                       + p.optString("mode", "?");
            case "lp_group_add"    -> "Add " + p.optString("player", "?") + " to group '"
                                       + p.optString("group", "?") + "'";
            case "lp_group_remove" -> "Remove " + p.optString("player", "?") + " from group '"
                                       + p.optString("group", "?") + "'";
            case "summon"          -> "Summon " + p.optString("entity", "?") + " nearby";
            case "broadcast"       -> "Broadcast to all: \"" + p.optString("message", "?") + "\"";
            case "discord_broadcast" -> "Discord: \"" + p.optString("message", "?") + "\"";
            case "paste_schematic"    -> "Paste schematic '" + p.optString("schematic", "?") + "' at your location";
            case "console_command"    -> "Run console command: " + p.optString("command", "?");
            case "console_commands"   -> {
                JSONArray cmds = p.optJSONArray("commands");
                yield "Run " + (cmds != null ? cmds.length() : "?") + " console command(s): "
                        + (cmds != null && cmds.length() > 0 ? cmds.getString(0) : "?")
                        + (cmds != null && cmds.length() > 1 ? " (+" + (cmds.length() - 1) + " more)" : "");
            }
            case "clear_mobs"         -> "Clear" + (p.has("type") ? " " + p.getString("type") : " all") + " mobs"
                                         + (p.has("radius") ? " within " + p.getInt("radius") + " blocks" : " in world");
            case "set_difficulty"     -> "Set difficulty to " + p.optString("difficulty", "?");
            case "schedule_broadcast" -> "Schedule: \"" + p.optString("message", "?") + "\" in " + p.optInt("delay_seconds", 0) + "s";
            default                   -> actionType + " " + p;
        };
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Action implementations
    // ─────────────────────────────────────────────────────────────────────────

    private String executeGiveItem(JSONObject p, Player requester) {
        Player target = resolvePlayer(p.optString("player", ""), requester);
        String itemName = p.getString("item");
        int amount = p.optInt("amount", 1);

        Material mat = Material.matchMaterial(itemName);
        if (mat == null) return "Unknown item: " + itemName;

        target.getInventory().addItem(new ItemStack(mat, amount));
        return "Gave " + amount + "x " + mat.name().toLowerCase() + " to " + target.getName() + ".";
    }

    private String executeEnchant(JSONObject p, Player requester) {
        Player target = resolvePlayer(p.optString("player", ""), requester);
        String enchantName = p.getString("enchantment").toLowerCase().replace(' ', '_');
        int level = p.optInt("level", 1);

        ItemStack item = target.getInventory().getItemInMainHand();
        if (item.getType() == Material.AIR) return target.getName() + " isn't holding anything.";

        Enchantment ench = Enchantment.getByKey(NamespacedKey.minecraft(enchantName));
        if (ench == null) return "Unknown enchantment: " + enchantName;

        item.addUnsafeEnchantment(ench, level);
        return "Applied " + enchantName + " " + level + " to " + target.getName() + "'s item.";
    }

    private String executePotionEffect(JSONObject p, Player requester) {
        Player target = resolvePlayer(p.optString("player", ""), requester);
        String effectName = p.getString("effect").toUpperCase().replace(' ', '_');
        int durationTicks = p.optInt("duration_seconds", 30) * 20;
        int amplifier = p.optInt("amplifier", 0);

        PotionEffectType type = PotionEffectType.getByName(effectName);
        if (type == null) return "Unknown effect: " + effectName;

        target.addPotionEffect(new PotionEffect(type, durationTicks, amplifier));
        return "Applied " + effectName.toLowerCase() + " " + (amplifier + 1)
                + " to " + target.getName() + " for " + (durationTicks / 20) + "s.";
    }

    private String executeHeal(JSONObject p, Player requester) {
        Player target = resolvePlayer(p.optString("player", ""), requester);
        double maxHp = target.getAttribute(Attribute.MAX_HEALTH).getValue();
        target.setHealth(maxHp);
        return "Healed " + target.getName() + " to full health.";
    }

    private String executeFeed(JSONObject p, Player requester) {
        Player target = resolvePlayer(p.optString("player", ""), requester);
        target.setFoodLevel(20);
        target.setSaturation(5.0f);
        return "Fed " + target.getName() + ".";
    }

    private String executeSetGamemode(JSONObject p, Player requester) {
        Player target = resolvePlayer(p.optString("player", ""), requester);
        String modeName = p.getString("mode").toUpperCase();
        GameMode mode;
        try {
            mode = GameMode.valueOf(modeName);
        } catch (IllegalArgumentException e) {
            return "Unknown gamemode: " + modeName;
        }
        target.setGameMode(mode);
        return "Set " + target.getName() + "'s gamemode to " + modeName.toLowerCase() + ".";
    }

    private String executeTeleport(JSONObject p, Player requester) {
        Player target = resolvePlayer(p.optString("player", ""), requester);
        double x = p.getDouble("x");
        double y = p.getDouble("y");
        double z = p.getDouble("z");
        String worldName = p.optString("world", target.getWorld().getName());
        World world = Bukkit.getWorld(worldName);
        if (world == null) return "Unknown world: " + worldName;
        target.teleport(new Location(world, x, y, z));
        return "Teleported " + target.getName() + " to " + (int)x + "," + (int)y + "," + (int)z
                + " in " + worldName + ".";
    }

    private String executeSetTime(JSONObject p, Player requester) {
        String value = p.optString("value", "day");
        World world = requester.getWorld();
        long time = switch (value.toLowerCase()) {
            case "day"      -> 1000L;
            case "noon"     -> 6000L;
            case "night"    -> 13000L;
            case "midnight" -> 18000L;
            default         -> {
                try { yield Long.parseLong(value); }
                catch (NumberFormatException e) { yield 1000L; }
            }
        };
        world.setTime(time);
        return "Set time to " + value + " in " + world.getName() + ".";
    }

    private String executeSetWeather(JSONObject p, Player requester) {
        String type = p.optString("type", "clear").toLowerCase();
        World world = requester.getWorld();
        switch (type) {
            case "clear"   -> { world.setStorm(false); world.setThundering(false); }
            case "rain"    -> { world.setStorm(true);  world.setThundering(false); }
            case "thunder" -> { world.setStorm(true);  world.setThundering(true);  }
            default        -> { return "Unknown weather type: " + type; }
        }
        return "Set weather to " + type + " in " + world.getName() + ".";
    }

    private String executeSetGamerule(JSONObject p) {
        String rule  = p.getString("rule");
        String value = p.getString("value");
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "gamerule " + rule + " " + value);
        return "Set gamerule " + rule + " = " + value + ".";
    }

    private String executeSummon(JSONObject p, Player requester) {
        String entity = p.optString("entity", "minecraft:zombie");
        Location loc  = requester.getLocation();
        double x = p.optDouble("x", loc.getX());
        double y = p.optDouble("y", loc.getY());
        double z = p.optDouble("z", loc.getZ());
        String worldName = p.optString("world", requester.getWorld().getName());
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                "execute in " + worldName + " run summon " + entity + " " + x + " " + y + " " + z);
        return "Summoned " + entity + " near " + requester.getName() + ".";
    }

    private String executeBroadcast(JSONObject p) {
        String message = p.getString("message");
        Bukkit.broadcastMessage(message);
        return "Broadcast sent.";
    }

    private String executeServerSay(JSONObject p) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "say " + p.getString("message"));
        return "Said it.";
    }

    private String executeLpGroupAdd(JSONObject p) {
        String player = p.getString("player");
        String group  = p.getString("group");
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + player + " group add " + group);
        return "Added " + player + " to group '" + group + "'.";
    }

    private String executeLpGroupRemove(JSONObject p) {
        String player = p.getString("player");
        String group  = p.getString("group");
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + player + " group remove " + group);
        return "Removed " + player + " from group '" + group + "'.";
    }

    private String executeWarp(JSONObject p, Player requester) {
        String warpName = p.getString("warp");
        String playerName = p.optString("player", requester.getName());
        Player target = resolvePlayer(playerName, requester);
        target.performCommand("warp " + warpName);
        return "Warped " + target.getName() + " to " + warpName + ".";
    }

    private String executeDiscordBroadcast(JSONObject p) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "discord broadcast " + p.getString("message"));
        return "Discord broadcast sent.";
    }

    private String executePasteSchematic(JSONObject p, Player requester) {
        String schematic = p.optString("schematic", "");
        if (schematic.isEmpty()) return "No schematic name provided.";
        if (plugin.getSchematicManager() == null) return "Schematic manager not available.";
        plugin.getSchematicManager().pasteSchematic(requester, schematic);
        return "Pasting schematic '" + schematic + "' at your location.";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // New v0.0.9 action implementations
    // ─────────────────────────────────────────────────────────────────────────

    private String executeConsoleCommand(JSONObject p) {
        String command = p.getString("command");
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        return "Executed: " + command;
    }

    private String executeConsoleCommands(JSONObject p) {
        JSONArray commands = p.getJSONArray("commands");
        int count = commands.length();
        for (int i = 0; i < count; i++) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), commands.getString(i));
        }
        return "Executed " + count + " command" + (count == 1 ? "" : "s") + ".";
    }

    private String executeClearMobs(JSONObject p, Player requester) {
        String type   = p.optString("type", "");
        int radius    = p.optInt("radius", 0);
        String world  = p.optString("world", requester.getWorld().getName());

        String selector;
        if (!type.isEmpty()) {
            // Ensure type has minecraft: namespace
            String fullType = type.contains(":") ? type : "minecraft:" + type;
            selector = radius > 0
                    ? "@e[type=" + fullType + ",distance=.." + radius + "]"
                    : "@e[type=" + fullType + "]";
        } else {
            selector = radius > 0
                    ? "@e[type=!player,distance=.." + radius + "]"
                    : "@e[type=!player]";
        }

        String cmd = "execute in " + world + " run kill " + selector;
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        return "Cleared mobs" + (type.isEmpty() ? "" : " (" + type + ")")
                + (radius > 0 ? " within " + radius + " blocks" : "") + ".";
    }

    private String executeClearDrops(JSONObject p, Player requester) {
        int radius   = p.optInt("radius", 0);
        String world = p.optString("world", requester.getWorld().getName());
        String selector = radius > 0
                ? "@e[type=item,distance=.." + radius + "]"
                : "@e[type=item]";
        String cmd = "execute in " + world + " run kill " + selector;
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        return "Cleared ground drops" + (radius > 0 ? " within " + radius + " blocks" : "") + ".";
    }

    private String executeSaveWorld(Player requester) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "save-all");
        Bukkit.broadcastMessage(ChatColor.GOLD + "[Jarvis] World saved by " + requester.getName() + ".");
        return "World saved.";
    }

    private String executeSetDifficulty(JSONObject p, Player requester) {
        String name = p.getString("difficulty").toUpperCase();
        Difficulty difficulty;
        try {
            difficulty = Difficulty.valueOf(name);
        } catch (IllegalArgumentException e) {
            return "Unknown difficulty: " + name + ". Use peaceful, easy, normal, or hard.";
        }
        requester.getWorld().setDifficulty(difficulty);
        return "Difficulty set to " + name.toLowerCase() + " in " + requester.getWorld().getName() + ".";
    }

    private String executeAnnounceAll(JSONObject p) {
        String message  = p.getString("message");
        String subtitle = p.optString("subtitle", "");

        // Broadcast as chat message for all players
        Bukkit.broadcastMessage(ChatColor.GOLD + "✦ " + ChatColor.WHITE + message);

        // Also send as title to all online players
        Title title = Title.title(
                Component.text(message, NamedTextColor.GOLD),
                Component.text(subtitle, NamedTextColor.WHITE),
                Title.Times.times(
                        Duration.ofMillis(500),
                        Duration.ofSeconds(4),
                        Duration.ofMillis(500)));
        for (Player p2 : Bukkit.getOnlinePlayers()) {
            p2.showTitle(title);
        }
        return "Announcement sent to " + Bukkit.getOnlinePlayers().size() + " player(s).";
    }

    private String executeScheduleBroadcast(JSONObject p) {
        String message      = p.getString("message");
        int delaySec        = p.optInt("delay_seconds", 30);
        int intervalSec     = p.optInt("interval_seconds", 0);
        int count           = p.optInt("count", 1);

        // v0.5.0: persist as a standing duty so it survives restarts
        if (plugin.getDutyScheduler() != null) {
            if (intervalSec > 0 && count != 1) {
                plugin.getDutyScheduler().addBroadcast(message, delaySec, intervalSec,
                        count > 1 ? count : -1, "chat");
                return "Duty scheduled: \"" + message + "\" every " + intervalSec + "s"
                        + (count > 1 ? " x" + count : " (until removed — /jarvis duties)");
            }
            plugin.getDutyScheduler().addBroadcast(message, delaySec, 0, 1, "chat");
            return "Broadcast scheduled in " + delaySec + "s: \"" + message + "\"";
        }
        return "Scheduler unavailable.";
    }

    private String executeRequestItem(JSONObject p, Player requester) {
        if (plugin.getPlayerRequestManager() == null) return "Request system not available.";

        String item   = p.optString("item", "");
        int amount    = p.optInt("amount", 1);
        String reason = p.optString("reason", "");

        if (item.isEmpty()) return "Please specify what item you want.";

        int id = plugin.getPlayerRequestManager().addRequest(
                requester.getUniqueId(), requester.getName(), item, amount, reason);

        // Notify online admins
        String requestMsg = ChatColor.YELLOW + "[Jarvis] " + ChatColor.WHITE
                + requester.getName() + " requests " + amount + "x " + item
                + (reason.isEmpty() ? "" : " (" + reason + ")")
                + ChatColor.GRAY + " — use /jarvis approve " + id + " or /jarvis deny " + id;

        boolean anyAdmin = false;
        for (Player admin : Bukkit.getOnlinePlayers()) {
            if (admin.hasPermission("jarvis.admin")) {
                admin.sendMessage(requestMsg);
                anyAdmin = true;
            }
        }

        if (!anyAdmin) {
            // Log to console if no admins online
            plugin.getLogger().info("Item request #" + id + " from " + requester.getName()
                    + ": " + amount + "x " + item);
        }

        return "Request #" + id + " submitted for " + amount + "x " + item + ". An admin will review it.";
    }

    // ─────────────────────────────────────────────────────────────────────────

    private Player resolvePlayer(String name, Player fallback) {
        if (name == null || name.isBlank()) return fallback;
        Player p = Bukkit.getPlayerExact(name);
        return p != null ? p : fallback;
    }
}
