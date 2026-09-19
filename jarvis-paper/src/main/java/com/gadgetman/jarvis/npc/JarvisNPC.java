package com.gadgetman.jarvis.npc;

import com.gadgetman.jarvis.Jarvis;
import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.npc.provider.CitizensButlers;
import com.gadgetman.jarvis.npc.provider.CitizensNPCProvider;
import com.gadgetman.jarvis.recovery.TaskFailure;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * The Paper face of the butler.
 *
 * <p>Everything he decides lives in core's {@link ButlerService}; this class
 * owns the Citizens backend that gives him a body, hands core a
 * {@code Butlers} over it, and translates the plugin's {@link Player}s into
 * core's {@link Owner}s for the commands, menus and listeners that still
 * speak Bukkit. Nothing here reasons about the world.
 */
public class JarvisNPC {

    private final Jarvis plugin;
    private final CitizensNPCProvider provider;
    private final CitizensButlers butlers;
    private final ButlerService core;

    public JarvisNPC(Jarvis plugin) {
        this.plugin = plugin;
        this.provider = new CitizensNPCProvider(plugin);
        this.butlers = new CitizensButlers(plugin, provider);
        this.core = new ButlerService(plugin.getPlatform(), butlers, plugin.getTaskRecoveryHandler());
        // So a blow landed on him reaches his Defender as a ButlerDamagedEvent.
        plugin.getPlatform().paperEvents().butlerResolver(butlers::ownerOf);
    }

    /** The platform-free service behind this facade. */
    public ButlerService core() {
        return core;
    }

    private Owner o(Player player) {
        return plugin.owner(player);
    }

    // ==================== LIFECYCLE ====================

    public void summon(Player player)  { core.summon(o(player)); }
    public void dismiss(Player player) { core.dismiss(o(player)); }
    public void dismissAll()           { core.dismissAll(); }

    public boolean isSpawned(Player player) { return core.isSpawned(o(player)); }
    public boolean exists(Player player)    { return core.exists(o(player)); }

    /** The backend's name, for the debug screen. */
    public String backendName() { return core.backendName(); }

    // ==================== SPEECH ====================

    public void speakTo(Player player, String text) { core.speakTo(o(player), text); }

    // ==================== TASKS ====================

    public void mine(Player player, String[] args) { core.mine(o(player), args); }
    public void mine(Player player)                { core.mine(o(player)); }
    public void attack(Player player)              { core.attack(o(player)); }
    public void guard(Player player, String stance) { core.guard(o(player), stance); }
    public void watch(Player player, String stance) { core.watch(o(player), stance); }
    public void farm(Player player, String crop, boolean tend) { core.farm(o(player), crop, tend); }
    public void chop(Player player, int trees)     { core.chop(o(player), trees); }
    public void fish(Player player)                { core.fish(o(player)); }
    public void dance(Player player)               { core.dance(o(player)); }
    public void light(Player player, int radius, String type, int spacing) { core.light(o(player), radius, type, spacing); }
    public void patrol(Player player, String sub)  { core.patrol(o(player), sub); }
    public void returnToPlayer(Player player)      { core.returnToPlayer(o(player)); }
    public void follow(Player player)              { core.follow(o(player)); }
    public void startBranchMining(Player player)   { core.startBranchMining(o(player)); }
    public void tunnel(Player player, int length)  { core.tunnel(o(player), length); }
    public void tunnel(Player player, int length, String direction) { core.tunnel(o(player), length, direction); }
    public void digDown(Player player, int depth)  { core.digDown(o(player), depth); }
    public void stop(Player player)                { core.stop(o(player)); }
    public void stopTask(Player player)            { core.stopTask(o(player)); }
    public void reportFailure(TaskFailure failure) { core.reportFailure(failure); }

    // ==================== KIT AND LOOT ====================

    public void refreshKit(Player player)     { core.refreshKit(o(player)); }
    public void openInventory(Player player)  { core.openInventory(o(player)); }
    public void clearInventory(Player player) { core.clearInventory(o(player)); }
    public int lootSlotsUsed(Player player)   { return core.lootSlotsUsed(o(player)); }
    public boolean isSubmerged(Player player) { return core.isSubmerged(o(player)); }
    public double distanceToOwner(Player player) { return core.distanceToOwner(o(player)); }

    // ==================== SERVICES ====================

    public DepositManager getDepositManager()   { return core.deposits(); }
    public RecoveryService getRecoveryService() { return core.getRecoveryService(); }
    public EscortService getEscortService()     { return core.getEscortService(); }

    // ==================== STATUS ====================

    public int getActiveNpcCount()  { return core.getActiveNpcCount(); }
    public int getActiveTaskCount() { return core.getActiveTaskCount(); }
    public String describeCurrentTask(UUID playerId) { return core.describeCurrentTask(playerId); }

    // ==================== CITIZENS ====================

    /** The Citizens NPC behind a player's butler, for the menu's click handler. Null when not summoned. */
    public NPC getNPCForPlayer(UUID ownerId) {
        return provider.getCitizensNPC(ownerId);
    }

    public NPC getNPC(Player player) {
        return getNPCForPlayer(player.getUniqueId());
    }
}
