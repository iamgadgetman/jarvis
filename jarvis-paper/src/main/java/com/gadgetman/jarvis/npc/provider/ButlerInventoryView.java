package com.gadgetman.jarvis.npc.provider;

import com.gadgetman.jarvis.platform.PaperItems;
import com.gadgetman.jarvis.progression.Kit;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.trait.Equipment;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * The butler's inventory, opened to his owner: a chest view over his
 * slots. Loot moves freely; his hand (slot 0) and any issued gear stay
 * put, since that gear is the owner's standing made visible and not
 * theirs to wield. What the owner changes is written back to the NPC
 * when the view closes.
 */
public final class ButlerInventoryView implements Listener {

    private static final int SLOTS = 36;

    /** The view's holder: which NPC it shows. */
    private static final class Holder implements InventoryHolder {
        final NPC npc;
        Inventory inventory;

        Holder(NPC npc) {
            this.npc = npc;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    public void open(NPC npc, Player viewer) {
        net.citizensnpcs.api.trait.trait.Inventory trait = npc.getOrAddTrait(net.citizensnpcs.api.trait.trait.Inventory.class);
        ItemStack[] contents = trait.getContents();
        Holder holder = new Holder(npc);
        Inventory view = Bukkit.createInventory(holder, SLOTS, Component.text(npc.getName() + "'s Inventory"));
        holder.inventory = view;
        for (int i = 0; i < Math.min(SLOTS, contents.length); i++) {
            if (contents[i] != null) view.setItem(i, contents[i]);
        }
        viewer.openInventory(view);
    }

    /** A slot the owner may not take from. */
    private static boolean locked(Inventory view, int rawSlot) {
        if (rawSlot < 0 || rawSlot >= SLOTS) return false;
        if (rawSlot == 0) return true;
        return Kit.isIssued(PaperItems.toItem(view.getItem(rawSlot)));
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder)) return;
        // Collecting-by-double-click sweeps every matching stack, locked or not.
        if (e.getAction() == InventoryAction.COLLECT_TO_CURSOR || locked(e.getInventory(), e.getRawSlot())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder)) return;
        for (int raw : e.getRawSlots()) {
            if (locked(e.getInventory(), raw)) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder holder)) return;
        NPC npc = holder.npc;
        if (npc == null) return;
        net.citizensnpcs.api.trait.trait.Inventory trait = npc.getOrAddTrait(net.citizensnpcs.api.trait.trait.Inventory.class);
        ItemStack[] contents = trait.getContents();
        for (int i = 0; i < Math.min(SLOTS, contents.length); i++) {
            contents[i] = e.getInventory().getItem(i);
        }
        trait.setContents(contents);
        if (contents.length > 0) {
            npc.getOrAddTrait(Equipment.class).set(Equipment.EquipmentSlot.HAND, contents[0]);
        }
    }
}
