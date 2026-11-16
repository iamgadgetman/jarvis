package com.gadgetman.jarvis.npc.provider;

import com.gadgetman.jarvis.core.platform.events.ButlerInteractEvent;
import com.gadgetman.jarvis.platform.PaperEvents;
import com.gadgetman.jarvis.platform.PaperOwner;
import net.citizensnpcs.api.event.CitizensEnableEvent;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.citizensnpcs.api.event.NPCSpawnEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.UUID;

/**
 * Citizens events about our NPCs: a right-click, republished as core's
 * {@link ButlerInteractEvent}; each spawn, so the provider can keep him
 * out of the sleep count; and Citizens finishing its load, to sweep leftovers.
 */
public final class CitizensInteractListener implements Listener {

    private final CitizensNPCProvider provider;
    private final PaperEvents events;

    public CitizensInteractListener(CitizensNPCProvider provider, PaperEvents events) {
        this.provider = provider;
        this.events = events;
    }

    @EventHandler
    public void onRightClickNPC(NPCRightClickEvent e) {
        UUID ownerId = null;
        for (var entry : provider.registry().entrySet()) {
            if (entry.getValue().equals(e.getNPC())) {
                ownerId = entry.getKey();
                break;
            }
        }
        if (ownerId == null) return;
        events.publish(new ButlerInteractEvent(ownerId, new PaperOwner(e.getClicker().getUniqueId()),
                e.getClicker().isSneaking(), e::setCancelled));
    }

    /** Citizens has loaded saves.yml: clear out butlers left from an earlier run (FB-16). */
    @EventHandler
    public void onCitizensEnable(CitizensEnableEvent e) {
        provider.sweepOrphans(CitizensNPCProvider.BUTLER_NAME);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onNpcSpawn(NPCSpawnEvent e) {
        provider.onNpcSpawned(e.getNPC());
    }
}
