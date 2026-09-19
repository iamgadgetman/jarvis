package com.gadgetman.jarvis.vanilla;

import com.gadgetman.jarvis.commands.ActionExecutor;
import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.text.Colors;
import com.gadgetman.jarvis.core.world.Item;
import com.gadgetman.jarvis.vanilla.fake.FakePlayer;
import com.gadgetman.jarvis.vanilla.platform.VanillaItems;
import com.gadgetman.jarvis.vanilla.platform.VanillaWorlds;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;
import java.util.Set;

/**
 * Core's {@link ActionExecutor} on a vanilla server. Where the Paper one dispatched
 * a console command it does the same; where Paper reached for Bukkit, this
 * reaches for the server. Always called on the server thread.
 */
public final class VanillaActionExecutor implements ActionExecutor {

    public static final Set<String> DANGEROUS_ACTIONS = Set.of(
            "set_gamemode", "lp_group_add", "lp_group_remove",
            "broadcast", "discord_broadcast", "summon",
            "console_command", "console_commands",
            "clear_mobs", "set_difficulty", "schedule_broadcast");

    private final JarvisMod mod;

    public VanillaActionExecutor(JarvisMod mod) {
        this.mod = mod;
    }

    private MinecraftServer server() {
        return mod.platform().server();
    }

    @Override
    public boolean handles(String actionType) {
        return switch (actionType) {
            case "give_item", "enchant", "potion_effect", "heal", "feed",
                 "set_gamemode", "teleport", "set_time", "set_weather", "set_gamerule",
                 "summon", "broadcast", "server_say", "lp_group_add", "lp_group_remove",
                 "warp", "discord_broadcast", "paste_schematic",
                 "console_command", "console_commands", "clear_mobs", "clear_drops",
                 "save_world", "set_difficulty", "announce_all", "schedule_broadcast",
                 "request_item" -> true;
            default -> false;
        };
    }

    @Override
    public Set<String> dangerousActions() {
        return DANGEROUS_ACTIONS;
    }

    @Override
    public String execute(String actionType, JSONObject params, Owner owner) {
        ServerPlayer requester = server().getPlayerList().getPlayer(owner.id());
        if (requester == null || requester instanceof FakePlayer) return "You must be online for that.";
        JSONObject p = params == null ? new JSONObject() : params;
        try {
            return switch (actionType) {
                case "give_item"          -> giveItem(p, requester);
                case "enchant"            -> {
                    ServerPlayer t = resolve(p.optString("player", ""), requester);
                    String ench = p.getString("enchantment").toLowerCase(Locale.ROOT).replace(' ', '_');
                    int level = p.optInt("level", 1);
                    if (t.getMainHandItem().isEmpty()) yield t.getName().getString() + " isn't holding anything.";
                    console("enchant " + t.getName().getString() + " " + ench + " " + level);
                    yield "Applied " + ench + " " + level + " to " + t.getName().getString() + "'s item.";
                }
                case "potion_effect"      -> {
                    ServerPlayer t = resolve(p.optString("player", ""), requester);
                    String effect = p.getString("effect").toLowerCase(Locale.ROOT).replace(' ', '_');
                    int seconds = p.optInt("duration_seconds", 30);
                    int amplifier = p.optInt("amplifier", 0);
                    console("effect give " + t.getName().getString() + " " + effect + " " + seconds + " " + amplifier);
                    yield "Applied " + effect + " " + (amplifier + 1) + " to " + t.getName().getString() + " for " + seconds + "s.";
                }
                case "heal"               -> {
                    ServerPlayer t = resolve(p.optString("player", ""), requester);
                    t.setHealth(t.getMaxHealth());
                    yield "Healed " + t.getName().getString() + " to full health.";
                }
                case "feed"               -> {
                    ServerPlayer t = resolve(p.optString("player", ""), requester);
                    t.getFoodData().setFoodLevel(20);
                    t.getFoodData().setSaturation(5.0f);
                    yield "Fed " + t.getName().getString() + ".";
                }
                case "set_gamemode"       -> {
                    ServerPlayer t = resolve(p.optString("player", ""), requester);
                    String mode = p.getString("mode").toLowerCase(Locale.ROOT);
                    GameType type = GameType.byName(mode, null);
                    if (type == null) yield "Unknown gamemode: " + mode;
                    t.setGameMode(type);
                    yield "Set " + t.getName().getString() + "'s gamemode to " + mode + ".";
                }
                case "teleport"           -> {
                    ServerPlayer t = resolve(p.optString("player", ""), requester);
                    double x = p.getDouble("x"), y = p.getDouble("y"), z = p.getDouble("z");
                    String worldName = p.optString("world", "");
                    ServerLevel level = worldName.isEmpty() ? t.level()
                            : VanillaWorlds.level(server(), new com.gadgetman.jarvis.core.world.WorldId(worldName));
                    if (level == null) yield "Unknown world: " + worldName;
                    t.teleportTo(level, x, y, z, Set.of(), t.getYRot(), t.getXRot(), true);
                    yield "Teleported " + t.getName().getString() + " to " + (int) x + "," + (int) y + "," + (int) z
                            + " in " + level.dimension().identifier().getPath() + ".";
                }
                case "set_time"           -> {
                    String value = p.optString("value", "day");
                    long time = switch (value.toLowerCase(Locale.ROOT)) {
                        case "day" -> 1000L;
                        case "noon" -> 6000L;
                        case "night" -> 13000L;
                        case "midnight" -> 18000L;
                        default -> {
                            try { yield Long.parseLong(value); } catch (NumberFormatException e) { yield 1000L; }
                        }
                    };
                    console("time set " + time);
                    yield "Set time to " + value + ".";
                }
                case "set_weather"        -> {
                    String type = p.optString("type", "clear").toLowerCase(Locale.ROOT);
                    switch (type) {
                        case "clear" -> server().setWeatherParameters(6000, 0, false, false);
                        case "rain" -> server().setWeatherParameters(0, 6000, true, false);
                        case "thunder" -> server().setWeatherParameters(0, 6000, true, true);
                        default -> { yield "Unknown weather type: " + type; }
                    }
                    yield "Set weather to " + type + ".";
                }
                case "set_gamerule"       -> {
                    console("gamerule " + p.getString("rule") + " " + p.getString("value"));
                    yield "Set gamerule " + p.getString("rule") + " = " + p.getString("value") + ".";
                }
                case "summon"             -> {
                    String entity = p.optString("entity", "minecraft:zombie");
                    double x = p.optDouble("x", requester.getX());
                    double y = p.optDouble("y", requester.getY());
                    double z = p.optDouble("z", requester.getZ());
                    String world = p.optString("world", requester.level().dimension().identifier().toString());
                    console("execute in " + world + " run summon " + entity + " " + x + " " + y + " " + z);
                    yield "Summoned " + entity + " near " + requester.getName().getString() + ".";
                }
                case "broadcast"          -> {
                    mod.platform().players().broadcast(p.getString("message"));
                    yield "Broadcast sent.";
                }
                case "server_say"         -> {
                    console("say " + p.getString("message"));
                    yield "Said it.";
                }
                case "lp_group_add"       -> {
                    console("lp user " + p.getString("player") + " group add " + p.getString("group"));
                    yield "Added " + p.getString("player") + " to group '" + p.getString("group") + "'.";
                }
                case "lp_group_remove"    -> {
                    console("lp user " + p.getString("player") + " group remove " + p.getString("group"));
                    yield "Removed " + p.getString("player") + " from group '" + p.getString("group") + "'.";
                }
                case "warp"               -> {
                    ServerPlayer t = resolve(p.optString("player", ""), requester);
                    server().getCommands().performPrefixedCommand(t.createCommandSourceStack(), "warp " + p.getString("warp"));
                    yield "Warped " + t.getName().getString() + " to " + p.getString("warp") + ".";
                }
                case "discord_broadcast"  -> {
                    console("discord broadcast " + p.getString("message"));
                    yield "Discord broadcast sent.";
                }
                case "paste_schematic"    -> {
                    String schematic = p.optString("schematic", "");
                    if (schematic.isEmpty()) yield "No schematic name provided.";
                    if (mod.core().schematics() == null) yield "Schematic manager not available.";
                    mod.core().schematics().pasteSchematic(owner, schematic);
                    yield "Pasting schematic '" + schematic + "' at your location.";
                }
                case "console_command"    -> {
                    console(p.getString("command"));
                    yield "Executed: " + p.getString("command");
                }
                case "console_commands"   -> {
                    JSONArray commands = p.getJSONArray("commands");
                    for (int i = 0; i < commands.length(); i++) console(commands.getString(i));
                    yield "Executed " + commands.length() + " command" + (commands.length() == 1 ? "" : "s") + ".";
                }
                case "clear_mobs"         -> clearMobs(p, requester);
                case "clear_drops"        -> {
                    int radius = p.optInt("radius", 0);
                    String world = p.optString("world", requester.level().dimension().identifier().toString());
                    String selector = radius > 0 ? "@e[type=item,distance=.." + radius + "]" : "@e[type=item]";
                    console("execute in " + world + " run kill " + selector);
                    yield "Cleared ground drops" + (radius > 0 ? " within " + radius + " blocks" : "") + ".";
                }
                case "save_world"         -> {
                    console("save-all");
                    mod.platform().players().broadcast(Colors.GOLD + "[Jarvis] World saved by " + requester.getName().getString() + ".");
                    yield "World saved.";
                }
                case "set_difficulty"     -> {
                    String name = p.getString("difficulty").toLowerCase(Locale.ROOT);
                    Difficulty difficulty = Difficulty.byName(name);
                    if (difficulty == null) yield "Unknown difficulty: " + name + ". Use peaceful, easy, normal, or hard.";
                    server().setDifficulty(difficulty, true);
                    yield "Difficulty set to " + name + ".";
                }
                case "announce_all"       -> announceAll(p);
                case "schedule_broadcast" -> {
                    String message = p.getString("message");
                    int delay = p.optInt("delay_seconds", 30);
                    int interval = p.optInt("interval_seconds", 0);
                    int count = p.optInt("count", 1);
                    if (mod.core().duties() == null) yield "Scheduler unavailable.";
                    if (interval > 0 && count != 1) {
                        mod.core().duties().addBroadcast(message, delay, interval, count > 1 ? count : -1, "chat");
                        yield "Duty scheduled: \"" + message + "\" every " + interval + "s"
                                + (count > 1 ? " x" + count : " (until removed; /jarvis duties)");
                    }
                    mod.core().duties().addBroadcast(message, delay, 0, 1, "chat");
                    yield "Broadcast scheduled in " + delay + "s: \"" + message + "\"";
                }
                case "request_item"       -> requestItem(p, requester);
                default                   -> null;
            };
        } catch (Exception e) {
            mod.platform().log().warn("Action '" + actionType + "' failed: " + e.getMessage());
            return "Action failed: " + e.getMessage();
        }
    }

    @Override
    public String describe(String actionType, JSONObject params) {
        JSONObject p = params == null ? new JSONObject() : params;
        return switch (actionType) {
            case "give_item"       -> "Give " + p.optInt("amount", 1) + "x " + p.optString("item", "?") + " to " + p.optString("player", "you");
            case "enchant"         -> "Enchant " + p.optString("player", "your") + "'s item with " + p.optString("enchantment", "?") + " " + p.optInt("level", 1);
            case "set_gamemode"    -> "Set " + p.optString("player", "your") + "'s gamemode to " + p.optString("mode", "?");
            case "lp_group_add"    -> "Add " + p.optString("player", "?") + " to group '" + p.optString("group", "?") + "'";
            case "lp_group_remove" -> "Remove " + p.optString("player", "?") + " from group '" + p.optString("group", "?") + "'";
            case "summon"          -> "Summon " + p.optString("entity", "?") + " nearby";
            case "broadcast"       -> "Broadcast to all: \"" + p.optString("message", "?") + "\"";
            case "discord_broadcast" -> "Discord: \"" + p.optString("message", "?") + "\"";
            case "paste_schematic" -> "Paste schematic '" + p.optString("schematic", "?") + "' at your location";
            case "console_command" -> "Run console command: " + p.optString("command", "?");
            case "console_commands" -> {
                JSONArray cmds = p.optJSONArray("commands");
                yield "Run " + (cmds != null ? cmds.length() : "?") + " console command(s): "
                        + (cmds != null && cmds.length() > 0 ? cmds.getString(0) : "?")
                        + (cmds != null && cmds.length() > 1 ? " (+" + (cmds.length() - 1) + " more)" : "");
            }
            case "clear_mobs"      -> "Clear" + (p.has("type") ? " " + p.getString("type") : " all") + " mobs"
                    + (p.has("radius") ? " within " + p.getInt("radius") + " blocks" : " in world");
            case "set_difficulty"  -> "Set difficulty to " + p.optString("difficulty", "?");
            case "schedule_broadcast" -> "Schedule: \"" + p.optString("message", "?") + "\" in " + p.optInt("delay_seconds", 0) + "s";
            default                -> actionType + " " + p;
        };
    }

    // ---- the ones with a body ----

    private void console(String command) {
        server().getCommands().performPrefixedCommand(server().createCommandSourceStack(), command);
    }

    private ServerPlayer resolve(String name, ServerPlayer fallback) {
        if (name == null || name.isBlank()) return fallback;
        ServerPlayer p = server().getPlayerList().getPlayerByName(name);
        return p != null ? p : fallback;
    }

    private String giveItem(JSONObject p, ServerPlayer requester) {
        ServerPlayer target = resolve(p.optString("player", ""), requester);
        String itemName = p.getString("item");
        int amount = p.optInt("amount", 1);
        String id = mod.platform().items().resolve(itemName).orElse(null);
        if (id == null) return "Unknown item: " + itemName;
        ItemStack stack = VanillaItems.toStack(server(), Item.of(id, amount));
        target.getInventory().add(stack);
        if (!stack.isEmpty()) {
            ItemEntity drop = new ItemEntity(target.level(), target.getX(), target.getY(), target.getZ(), stack);
            drop.setDefaultPickUpDelay();
            target.level().addFreshEntity(drop);
        }
        return "Gave " + amount + "x " + id.substring(id.indexOf(':') + 1) + " to " + target.getName().getString() + ".";
    }

    private String clearMobs(JSONObject p, ServerPlayer requester) {
        String type = p.optString("type", "");
        int radius = p.optInt("radius", 0);
        String world = p.optString("world", requester.level().dimension().identifier().toString());
        String selector;
        if (!type.isEmpty()) {
            String fullType = type.contains(":") ? type : "minecraft:" + type;
            selector = radius > 0 ? "@e[type=" + fullType + ",distance=.." + radius + "]" : "@e[type=" + fullType + "]";
        } else {
            selector = radius > 0 ? "@e[type=!player,distance=.." + radius + "]" : "@e[type=!player]";
        }
        console("execute in " + world + " run kill " + selector);
        return "Cleared mobs" + (type.isEmpty() ? "" : " (" + type + ")") + (radius > 0 ? " within " + radius + " blocks" : "") + ".";
    }

    private String announceAll(JSONObject p) {
        String message = p.getString("message");
        String subtitle = p.optString("subtitle", "");
        mod.platform().players().broadcast(Colors.GOLD + "✦ " + Colors.WHITE + message);
        int shown = 0;
        for (ServerPlayer player : server().getPlayerList().getPlayers()) {
            if (player instanceof FakePlayer) continue;
            player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 80, 10));
            player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(subtitle)));
            player.connection.send(new ClientboundSetTitleTextPacket(Component.literal(Colors.GOLD + message)));
            shown++;
        }
        return "Announcement sent to " + shown + " player(s).";
    }

    private String requestItem(JSONObject p, ServerPlayer requester) {
        if (mod.core().requests() == null) return "Request system not available.";
        String item = p.optString("item", "");
        int amount = p.optInt("amount", 1);
        String reason = p.optString("reason", "");
        if (item.isEmpty()) return "Please specify what item you want.";
        String name = requester.getName().getString();
        int id = mod.core().requests().addRequest(requester.getUUID(), name, item, amount, reason);
        String notice = Colors.YELLOW + "[Jarvis] " + Colors.WHITE + name + " requests " + amount + "x " + item
                + (reason.isEmpty() ? "" : " (" + reason + ")")
                + Colors.GRAY + " - use /jarvis approve " + id + " or /jarvis deny " + id;
        boolean anyAdmin = false;
        for (Owner admin : mod.platform().players().online()) {
            if (admin.hasPermission("jarvis.admin")) {
                admin.message(notice);
                anyAdmin = true;
            }
        }
        if (!anyAdmin) mod.platform().log().info("Item request #" + id + " from " + name + ": " + amount + "x " + item);
        return "Request #" + id + " submitted for " + amount + "x " + item + ". An admin will review it.";
    }
}
