package com.gadgetman.jarvis.ui;

import com.gadgetman.jarvis.core.platform.events.ItemUseEvent;
import com.gadgetman.jarvis.core.world.Ids;
import com.gadgetman.jarvis.core.world.Item;
import com.gadgetman.jarvis.platform.PaperEvents;
import com.gadgetman.jarvis.platform.PaperItems;
import com.gadgetman.jarvis.platform.PaperOwner;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.persistence.PersistentDataType;

/**
 * The controller bell once it is placed as a block.
 *
 * <p>A held bell reaches core through the ordinary {@link ItemUseEvent}; a
 * placed one has no item any more, only a tile with a mark on it. This keeps
 * the mark on the tile when the bell goes down and raises the same event
 * when the tile is rung.
 */
public final class PaperBell implements Listener {

    /** The mark on a placed controller's tile state. Same key the old plugin used. */
    public static final NamespacedKey PLACED_KEY = new NamespacedKey("jarvis", "jarvis-controller");

    private final PaperEvents events;

    public PaperBell(PaperEvents events) {
        this.events = events;
    }

    @EventHandler
    public void onBellPlace(BlockPlaceEvent e) {
        if (!ControllerBell.isController(PaperItems.toItem(e.getItemInHand()))) return;
        Block placed = e.getBlockPlaced();
        if (placed.getState() instanceof TileState state) {
            state.getPersistentDataContainer().set(PLACED_KEY, PersistentDataType.BYTE, (byte) 1);
            state.update();
        }
    }

    @EventHandler
    public void onBellRing(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        Block b = e.getClickedBlock();
        if (b.getType() != Material.BELL) return;
        if (!(b.getState() instanceof TileState ts)) return;
        if (!ts.getPersistentDataContainer().has(PLACED_KEY, PersistentDataType.BYTE)) return;

        Item bell = Item.of(Ids.BELL).marked(ControllerBell.MARKER);
        events.publish(new ItemUseEvent(new PaperOwner(e.getPlayer().getUniqueId()), bell, e::setCancelled));
    }
}
