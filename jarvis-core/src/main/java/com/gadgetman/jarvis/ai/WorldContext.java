package com.gadgetman.jarvis.ai;

import com.gadgetman.jarvis.core.platform.Butler;
import com.gadgetman.jarvis.core.platform.Config;
import com.gadgetman.jarvis.core.platform.Entity;
import com.gadgetman.jarvis.core.platform.EntityKind;
import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.World;
import com.gadgetman.jarvis.core.world.BlockPos;
import com.gadgetman.jarvis.core.world.Ids;
import com.gadgetman.jarvis.core.world.Item;
import com.gadgetman.jarvis.core.world.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * A short, plain-text snapshot of the player's situation and Jarvis's own,
 * sent with every chat request so the model can answer "what should I do?"
 * with real facts (FB-03).
 *
 * <p>{@link #capture} reads the world and so runs on the server thread only;
 * {@link #render} only formats what was captured and can run anywhere.
 *
 * <p>How much is sent is {@code ai.context.level}: 0 nothing, 1 the player,
 * 2 adds the world and Jarvis, 3 adds nearby hostiles and players. The text is
 * a handful of short lines, not JSON, to keep the token cost low.
 */
public final class WorldContext {

    /** Settings under {@code ai.context}. */
    public record Settings(int level, double hostileRadius, double playerRadius, String knowledgeFile) {
        public static Settings from(Config config) {
            int level = Math.max(0, Math.min(3, config.getInt("ai.context.level", 2)));
            return new Settings(level,
                    config.getDouble("ai.context.hostile-radius", 16),
                    config.getDouble("ai.context.player-radius", 32),
                    config.getString("ai.context.knowledge-file", "knowledge.yml"));
        }
    }

    /** At most this many mob types are listed; the rest are summed as "N more". */
    static final int MAX_MOB_TYPES = 5;
    /** At most this many nearby players are named. */
    static final int MAX_PLAYERS = 5;

    private final Settings settings;
    private final List<String> playerLines = new ArrayList<>();
    private final List<String> worldLines = new ArrayList<>();
    private final List<String> nearbyLines = new ArrayList<>();
    /**
     * Dangers spelled out in words. A small local model reads "health 6/20"
     * and still says nothing about it; "low health (3 hearts)" it acts on.
     */
    private final List<String> warnings = new ArrayList<>();
    private boolean noWeapon;
    private boolean danger;

    private WorldContext(Settings settings) {
        this.settings = settings;
    }

    /**
     * Read everything the chosen level needs. Server thread only.
     *
     * @param world   the player's world, or null if it can't be read
     * @param butler  the player's Jarvis, or null
     * @param task    what Jarvis is doing, or null when idle
     * @param online  everyone online, for the nearby-players line
     * @param edible  whether an item id is food, for Jarvis's cargo line
     */
    public static WorldContext capture(Owner player, World world, Butler butler, String task,
                                       Settings settings, Collection<? extends Owner> online,
                                       Predicate<String> edible) {
        WorldContext c = new WorldContext(settings);
        if (settings.level() <= 0 || player == null) return c;
        c.readPlayer(player);
        if (settings.level() >= 2) c.readWorld(player, world, butler, task, edible);
        if (settings.level() >= 3) c.readNearby(player, world, online);
        return c;
    }

    // ---- reading (server thread) ----

    private void readPlayer(Owner p) {
        double hearts = Math.ceil(p.health()) / 2.0;
        String heartText = (hearts == Math.floor(hearts) ? String.valueOf((int) hearts) : String.valueOf(hearts))
                + (hearts == 1 ? " heart" : " hearts");
        if (p.health() <= Math.max(6, p.maxHealth() * 0.3)) {
            warnings.add("low health (" + heartText + ")");
            danger = true;
        }
        if (p.foodLevel() <= 6) warnings.add("hungry (food " + p.foodLevel() + "/20)");
        noWeapon = !isWeapon(p.heldItem());
        playerLines.add("You: health " + (int) Math.ceil(p.health()) + "/" + (int) Math.round(p.maxHealth())
                + " (" + heartText + ")"
                + ", food " + p.foodLevel() + "/20"
                + ", holding " + itemName(p.heldItem())
                + ", " + p.gameMode().toLowerCase(Locale.ROOT) + " mode");
    }

    private void readWorld(Owner p, World world, Butler butler, String task, Predicate<String> edible) {
        BlockPos feet = p.pos().block();
        if (world != null) {
            boolean underground = world.highestY(feet.x(), feet.z()) > feet.y() + 2;
            worldLines.add("Where: world " + world.name()
                    + " (" + world.environment().name().toLowerCase(Locale.ROOT) + ")"
                    + ", biome " + Ids.pretty(world.biome(feet)).toLowerCase(Locale.ROOT)
                    + ", y=" + feet.y() + (underground ? ", underground" : ", surface"));
            String weather = world.isThundering() ? "thunderstorm" : world.isRaining() ? "rain" : "clear";
            String time = timeOfDay(world.time());
            int light = lightAt(world, feet, time);
            worldLines.add("Time: " + time + ", weather " + weather
                    + ", light at your feet " + light + "/15");
            if (time.equals("night")) { warnings.add("it is night: monsters spawn"); danger = true; }
            else if (light <= 7) { warnings.add("dark here (light " + light + "): monsters can spawn"); danger = true; }
        }
        worldLines.add(jarvisLine(butler, task, edible));
    }

    private void readNearby(Owner p, World world, Collection<? extends Owner> online) {
        Vec3 at = p.pos();
        if (world != null) {
            Map<String, Integer> counts = new TreeMap<>();
            double nearest = Double.MAX_VALUE;
            for (Entity e : world.nearby(at, settings.hostileRadius(), EntityKind.HOSTILE)) {
                if (!e.isAlive()) continue;
                counts.merge(mobName(e.typeId()), 1, Integer::sum);
                nearest = Math.min(nearest, e.pos().distance(at));
            }
            if (!counts.isEmpty()) {
                warnings.add(mobList(counts) + " nearby, nearest " + Math.round(nearest) + "m");
                danger = true;
            }
            nearbyLines.add("Hostiles within " + (int) settings.hostileRadius() + "m: "
                    + (counts.isEmpty() ? "none" : mobList(counts) + ", nearest " + Math.round(nearest) + "m"));
        }
        List<String> names = new ArrayList<>();
        if (online != null) {
            for (Owner o : online) {
                if (o.id().equals(p.id()) || !o.world().equals(p.world())) continue;
                if (o.pos().distance(at) <= settings.playerRadius()) names.add(o.name());
            }
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        String shown = names.isEmpty() ? "none"
                : String.join(", ", names.subList(0, Math.min(MAX_PLAYERS, names.size())))
                  + (names.size() > MAX_PLAYERS ? " and " + (names.size() - MAX_PLAYERS) + " more" : "");
        nearbyLines.add("Players within " + (int) settings.playerRadius() + "m: " + shown);
    }

    private static String jarvisLine(Butler b, String task, Predicate<String> edible) {
        if (b == null || !b.isSpawned()) return "Jarvis: not summoned";
        StringBuilder sb = new StringBuilder("Jarvis: at your side, ");
        sb.append(task == null || task.isBlank() ? "idle" : task);
        b.entity().ifPresent(e -> sb.append(", health ").append((int) Math.ceil(e.health())));
        sb.append(", holding ").append(itemName(b.heldItem()));
        String cargo = cargo(b.inventory(), edible);
        if (!cargo.isEmpty()) sb.append(", carrying ").append(cargo);
        return sb.toString();
    }

    /** Only what matters to advice: torches, food, ores and gems. Not every stack. */
    private static String cargo(List<Item> inventory, Predicate<String> edible) {
        Map<String, Integer> counts = new TreeMap<>();
        for (Item it : inventory) {
            if (it == null || it.isEmpty()) continue;
            String key = Ids.key(it.id());
            boolean useful = key.equals("torch")
                    || key.endsWith("_ore") || key.startsWith("raw_") || key.endsWith("_ingot")
                    || key.equals("diamond") || key.equals("emerald") || key.equals("coal")
                    || (edible != null && edible.test(it.id()));
            if (useful) counts.merge(key.replace('_', ' '), it.count(), Integer::sum);
        }
        List<String> parts = new ArrayList<>();
        counts.forEach((name, n) -> parts.add(n + " " + name));
        if (parts.size() > 6) {
            int more = parts.size() - 6;
            parts.subList(6, parts.size()).clear();
            parts.add(more + " more kinds");
        }
        return String.join(", ", parts);
    }

    // ---- formatting ----

    /** The snapshot as short lines, or "" at level 0. */
    public String render() {
        List<String> all = new ArrayList<>();
        List<String> w = new ArrayList<>(warnings);
        // No weapon only matters when something is dangerous; mining with a pickaxe is fine.
        if (noWeapon && danger) w.add("no weapon in hand");
        if (!w.isEmpty()) all.add("WARNINGS: " + String.join("; ", w));
        all.addAll(playerLines);
        all.addAll(worldLines);
        all.addAll(nearbyLines);
        return String.join("\n", all);
    }

    public int level() {
        return settings.level();
    }

    /** Day time in ticks (any full time; it wraps) as words. */
    static String timeOfDay(long ticks) {
        long t = Math.floorMod(ticks, 24000L);
        if (t < 1000 || t >= 23000) return "dawn";
        if (t < 11500) return "day";
        if (t < 13000) return "dusk";
        return "night";
    }

    /** What a mob spawns at: block light, or sky light dimmed for the hour. */
    private static int lightAt(World world, BlockPos feet, String time) {
        int sky = world.skyLight(feet);
        int dim = switch (time) {
            case "night" -> 11;
            case "dusk", "dawn" -> 5;
            default -> 0;
        };
        if (world.isThundering()) dim = Math.max(dim, 10);
        else if (world.isRaining()) dim = Math.max(dim, 3);
        return Math.max(world.blockLight(feet), Math.max(0, sky - dim));
    }

    private static boolean isWeapon(Item item) {
        if (item == null || item.isEmpty()) return false;
        String k = Ids.key(item.id());
        return k.endsWith("_sword") || k.endsWith("_axe") || k.equals("trident") || k.equals("bow")
                || k.equals("crossbow") || k.equals("mace") || k.endsWith("_spear");
    }

    private static String itemName(Item item) {
        return item == null || item.isEmpty() ? "nothing" : Ids.pretty(item.id());
    }

    private static String mobName(String typeId) {
        return Ids.key(typeId == null ? "unknown" : typeId).replace('_', ' ');
    }

    private static String mobList(Map<String, Integer> counts) {
        List<Map.Entry<String, Integer>> byCount = new ArrayList<>(counts.entrySet());
        byCount.sort((a, b) -> b.getValue() - a.getValue());
        List<String> parts = new ArrayList<>();
        int more = 0;
        for (int i = 0; i < byCount.size(); i++) {
            var e = byCount.get(i);
            if (i < MAX_MOB_TYPES) parts.add(e.getValue() + " " + (e.getValue() == 1 ? e.getKey() : plural(e.getKey())));
            else more += e.getValue();
        }
        if (more > 0) parts.add(more + " others");
        return String.join(", ", parts);
    }

    static String plural(String noun) {
        if (noun.endsWith("fish") || noun.endsWith("sheep")) return noun;
        if (noun.endsWith("man")) return noun.substring(0, noun.length() - 3) + "men";
        if (noun.endsWith("s") || noun.endsWith("sh") || noun.endsWith("ch") || noun.endsWith("x")) return noun + "es";
        return noun + "s";
    }
}
