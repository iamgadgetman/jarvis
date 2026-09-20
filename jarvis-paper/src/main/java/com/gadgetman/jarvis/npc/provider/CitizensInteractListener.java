package com.gadgetman.jarvis.npc.provider;

import com.gadgetman.jarvis.core.platform.events.ButlerInteractEvent;
import com.gadgetman.jarvis.platform.PaperEvents;
import com.gadgetman.jarvis.platform.PaperOwner;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.UUID;

/** A right-click on one of our NPCs, republished as core's {@link ButlerInteractEvent}. */
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
}
