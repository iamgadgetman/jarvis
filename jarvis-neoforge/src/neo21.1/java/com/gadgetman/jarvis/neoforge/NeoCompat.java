package com.gadgetman.jarvis.neoforge;

import com.gadgetman.jarvis.core.platform.Container;
import com.gadgetman.jarvis.core.world.Item;
import com.gadgetman.jarvis.vanilla.platform.VanillaItems;
import com.gadgetman.jarvis.vanilla.platform.VanillaWorld;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The NeoForge calls that differ between versions, for NeoForge 21.1 (Minecraft 1.21.1). The 26.x copy is in neo26. */
final class NeoCompat {

    private NeoCompat() {
    }

    /** The damage that got through. */
    static float damageDealt(LivingDamageEvent.Post event) {
        return event.getNewDamage();
    }

    /**
     * Lets the butler deposit into mods' storage (Sophisticated Storage,
     * drawers, a storage network's interface): anything that offers an item
     * handler, as hoppers see it.
     */
    static void registerStorage() {
        VanillaWorld.otherStorage = (level, pos) -> {
            IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
            return handler == null ? Optional.empty() : Optional.of(new HandlerContainer(level.getServer(), handler));
        };
    }

    /** Core's {@link Container} over an item handler. */
    private record HandlerContainer(net.minecraft.server.MinecraftServer server, IItemHandler handler) implements Container {

        @Override
        public List<Item> contents() {
            List<Item> out = new ArrayList<>(handler.getSlots());
            for (int i = 0; i < handler.getSlots(); i++) out.add(VanillaItems.toItem(handler.getStackInSlot(i)));
            return out;
        }

        @Override
        public List<Item> add(List<Item> items) {
            List<Item> rest = new ArrayList<>();
            for (Item item : items) {
                if (item.isEmpty()) continue;
                ItemStack left = ItemHandlerHelper.insertItemStacked(handler, VanillaItems.toStack(server, item), false);
                if (!left.isEmpty()) rest.add(VanillaItems.toItem(left));
            }
            return rest;
        }

        @Override
        public int freeSlots() {
            int free = 0;
            for (int i = 0; i < handler.getSlots(); i++) {
                if (handler.getStackInSlot(i).isEmpty()) free++;
            }
            return free;
        }

        @Override
        public int size() {
            return handler.getSlots();
        }
    }
}
