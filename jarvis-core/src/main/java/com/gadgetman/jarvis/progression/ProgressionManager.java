package com.gadgetman.jarvis.progression;

import com.gadgetman.jarvis.DatabaseManager;
import com.gadgetman.jarvis.core.platform.Config;
import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.Platform;
import com.gadgetman.jarvis.core.text.Colors;
import com.gadgetman.jarvis.core.world.Ids;
import com.gadgetman.jarvis.core.world.Item;
import com.gadgetman.jarvis.npc.ButlerService;
import com.gadgetman.jarvis.progression.ServiceRecord.Discipline;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ProgressionManager — Jarvis earns his kit.
 *
 * <p>Work done for a player raises that player's service score; the score
 * decides his rank; the rank decides the tools he is issued. Nothing here can
 * make him worse: he starts at iron, service never decreases, and every tool
 * is unbreakable.
 *
 * <p>Operators are exempt by default ({@code progression.op-bypass}). An
 * operator already equips him by dropping tools into his kit slot, and that
 * still works untouched — this only decides what he is issued when nobody has
 * chosen for him.
 */
public class ProgressionManager {

    private final Platform platform;
    private final DatabaseManager database;
    private final Map<UUID, ServiceRecord> records = new ConcurrentHashMap<>();

    private final boolean enabled;
    private final boolean opBypass;
    private final double rate;
    private final Rank opRank;

    /** Set once the butler service exists; it is what re-issues the kit and speaks. */
    private ButlerService butler;

    public ProgressionManager(Platform platform, DatabaseManager database) {
        this.platform = platform;
        this.database = database;
        Config cfg   = platform.config();
        this.enabled  = cfg.getBoolean("progression.enabled", true);
        this.opBypass = cfg.getBoolean("progression.op-bypass", true);
        this.rate     = cfg.getDouble("progression.rate", 1.0);
        // Bypass has to mean the WHOLE ladder. Pinning it to a named rank
        // silently withheld everything above that rank: operators were handed
        // Indispensable and then refused the tunnel command, which unlocks one
        // rank later. Blank means "whatever the top is", so adding a rank
        // never strands them again.
        Rank configured = Rank.byNameOrNumber(cfg.getString("progression.op-rank", ""));
        this.opRank   = configured != null ? configured : Rank.top();
        createTable();
    }

    /** The butler service, once it exists, so a promotion can re-issue his tools. */
    public void attach(ButlerService butler) {
        this.butler = butler;
    }

    public boolean isEnabled() { return enabled; }

    /** Operators are handed the top kit; they were never meant to grind for it. */
    public boolean isExempt(Owner player) {
        return !enabled || (opBypass && player.isOp());
    }

    // ==================== RECORDS ====================

    public ServiceRecord recordOf(Owner player) {
        return records.computeIfAbsent(player.id(), this::load);
    }

    public Rank rankOf(Owner player) {
        return isExempt(player) ? opRank : recordOf(player).rank();
    }

    /**
     * Credit work to a player's record, and promote him if it crosses a rank.
     *
     * <p>Safe to call from any task on the server thread; the write to disk is
     * pushed off it.
     */
    public void record(Owner player, Discipline discipline, int amount) {
        if (!enabled || player == null || amount <= 0) return;

        ServiceRecord record = recordOf(player);
        int scaled = Math.max(1, (int) Math.round(amount * rate));
        record.add(discipline, scaled);

        Rank earned = record.rank();
        if (earned.ordinal() > record.acknowledged().ordinal()) {
            record.acknowledge(earned);
            announce(player, earned);
            reissueKit(player);
        }
        saveLater(player.id(), record);
    }

    private void announce(Owner player, Rank rank) {
        player.message("");
        player.message(Colors.GOLD + "  ⏵ Jarvis is now " + Colors.YELLOW
                + rank.title() + Colors.GOLD + "  (rank " + rank.number() + "/" + Rank.values().length + ")");
        player.message(Colors.GRAY + "     Newly at his disposal: " + Colors.WHITE + rank.whatIsNew());
        player.message("");
        player.sound(Ids.SOUND_UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);

        // Let him say it himself if he has a voice.
        if (butler != null) {
            butler.speakTo(player, "You'll notice the upgrade, sir. " + rank.whatIsNew() + ".");
        }
    }

    /** Swap his current tools for the ones his new rank entitles him to. */
    public void reissueKit(Owner player) {
        if (butler == null || !butler.exists(player)) return;
        platform.scheduler().sync(() -> butler.refreshKit(player));
    }

    // ==================== KIT ====================

    /**
     * Build one of Jarvis's tools at a player's current standing.
     *
     * <p>Enchantments are applied unrestricted, because the levels come from
     * the rank table rather than from an anvil — and every tool is marked
     * unbreakable, so his kit is never a maintenance task.
     */
    public Item kitItem(Owner player, Rank.ToolKind kind) {
        Rank rank = rankOf(player);
        String id = rank.toolFor(kind);
        Item item = Item.of(id).unbreakable(true);

        for (var e : rank.enchants().entrySet()) {
            if (appliesTo(kind, id, e.getKey())) {
                item = item.enchant(e.getKey(), e.getValue());
            }
        }
        if (Ids.TRIDENT.equals(id)) {
            item = item.enchant(Ids.ENCHANT_LOYALTY, 3)
                    .enchant(Ids.ENCHANT_CHANNELING, 1)
                    .enchant(Ids.ENCHANT_IMPALING, 5);
        }
        return item.named(Colors.AQUA + "Jarvis's " + name(kind)
                + Colors.DARK_GRAY + " (" + rank.title() + ")").marked(Kit.MARKER);
    }

    private static String name(Rank.ToolKind kind) {
        return switch (kind) {
            case PICKAXE -> "Pickaxe";
            case SWORD   -> "Sword";
            case AXE     -> "Axe";
            case HOE     -> "Hoe";
            case ROD     -> "Rod";
            case TRIDENT -> "Trident";
            case BOW     -> "Bow";
        };
    }

    /** Does this player's standing grant a capability? */
    public boolean has(Owner player, Rank.Capability capability) {
        return rankOf(player).grants(capability);
    }

    /** Only put an enchantment where it does something. */
    private static boolean appliesTo(Rank.ToolKind kind, String itemId, String enchantId) {
        String id = Ids.key(enchantId);
        // A trident takes none of the sword enchantments -- Sharpness on one is
        // simply ignored -- so it gets its own set below rather than inheriting.
        if (Ids.TRIDENT.equals(itemId)) return id.equals("fire_aspect");
        return switch (kind) {
            case PICKAXE, AXE -> id.equals("efficiency") || id.equals("fortune");
            case SWORD        -> id.equals("sharpness")  || id.equals("looting")
                                 || id.equals("fire_aspect");
            case HOE          -> id.equals("efficiency") || id.equals("fortune");
            case ROD          -> false;
            case TRIDENT      -> id.equals("impaling") || id.equals("loyalty")
                                 || id.equals("channeling");
            // Infinity is deliberately absent: his arrows are conjured rather
            // than drawn from a quiver, so there is nothing for it to save.
            case BOW          -> id.equals("power") || id.equals("punch")
                                 || id.equals("flame");
        };
    }

    // ==================== PERSISTENCE ====================

    private void createTable() {
        if (database == null) return;
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "CREATE TABLE IF NOT EXISTS service_record (" +
                     "player_id VARCHAR(36) PRIMARY KEY, " +
                     "ores INTEGER NOT NULL DEFAULT 0, " +
                     "trees INTEGER NOT NULL DEFAULT 0, " +
                     "crops INTEGER NOT NULL DEFAULT 0, " +
                     "fish INTEGER NOT NULL DEFAULT 0, " +
                     "threats INTEGER NOT NULL DEFAULT 0, " +
                     "blocks INTEGER NOT NULL DEFAULT 0, " +
                     "acknowledged VARCHAR(32) NOT NULL DEFAULT 'HIRED')")) {
            ps.executeUpdate();
        } catch (Exception e) {
            platform.log().warn("Could not create service_record table: " + e.getMessage());
        }
    }

    private ServiceRecord load(UUID id) {
        if (database == null) return new ServiceRecord();
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ores, trees, crops, fish, threats, blocks, acknowledged "
                     + "FROM service_record WHERE player_id = ?")) {
            ps.setString(1, id.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Rank ack;
                    try { ack = Rank.valueOf(rs.getString("acknowledged")); }
                    catch (Exception e) { ack = Rank.HIRED; }
                    return new ServiceRecord(
                            rs.getInt("ores"), rs.getInt("trees"), rs.getInt("crops"),
                            rs.getInt("fish"), rs.getInt("threats"), rs.getInt("blocks"), ack);
                }
            }
        } catch (Exception e) {
            platform.log().warn("Could not load service record: " + e.getMessage());
        }
        return new ServiceRecord();
    }

    private void saveLater(UUID id, ServiceRecord record) {
        platform.scheduler().async(() -> save(id, record));
    }

    public void save(UUID id, ServiceRecord r) {
        if (database == null) return;
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO service_record "
                     + "(player_id, ores, trees, crops, fish, threats, blocks, acknowledged) "
                     + "VALUES (?,?,?,?,?,?,?,?) "
                     + "ON CONFLICT(player_id) DO UPDATE SET "
                     + "ores=excluded.ores, trees=excluded.trees, crops=excluded.crops, "
                     + "fish=excluded.fish, threats=excluded.threats, blocks=excluded.blocks, "
                     + "acknowledged=excluded.acknowledged")) {
            ps.setString(1, id.toString());
            ps.setInt(2, r.oresMined());
            ps.setInt(3, r.treesFelled());
            ps.setInt(4, r.cropsHarvested());
            ps.setInt(5, r.fishCaught());
            ps.setInt(6, r.threatsFelled());
            ps.setInt(7, r.blocksPlaced());
            ps.setString(8, r.acknowledged().name());
            ps.executeUpdate();
        } catch (Exception e) {
            platform.log().warn("Could not save service record: " + e.getMessage());
        }
    }

    public void saveAll() {
        records.forEach(this::save);
    }
}
