package com.gadgetman.jarvis.vanilla.butler;

import com.gadgetman.jarvis.vanilla.fake.FakePlayer;
import com.gadgetman.jarvis.vanilla.platform.VanillaItems;
import com.gadgetman.jarvis.progression.Kit;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

/**
 * The butler's inventory, opened to his owner: four rows over his own
 * slots. Loot moves freely; his hand (slot 0) and any issued gear stay
 * put, since that gear is the owner's standing made visible and not
 * theirs to wield.
 */
public final class ButlerInventoryScreen extends ChestMenu {

    private static final int BUTLER_SLOTS = 36;

    private final FakePlayer butler;

    public ButlerInventoryScreen(int id, Inventory viewerInventory, FakePlayer butler) {
        super(MenuType.GENERIC_9x4, id, viewerInventory, butler.getInventory(), 4);
        this.butler = butler;
    }

    /** A slot the owner may not take from. */
    private boolean locked(int slot) {
        if (slot < 0 || slot >= BUTLER_SLOTS) return false;
        if (slot == 0) return true;
        return Kit.isIssued(VanillaItems.toItem(butler.getInventory().getItem(slot)));
    }

    @Override
    public void clicked(int slotId, int button, ContainerInput input, Player player) {
        // Collecting-by-double-click sweeps every matching stack, locked or not.
        boolean sweep = input == ContainerInput.PICKUP_ALL;
        if (sweep || locked(slotId)) {
            if (player instanceof ServerPlayer sp && sp.containerMenu == this) sendAllDataToRemote();
            return;
        }
        super.clicked(slotId, button, input, player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return locked(index) ? ItemStack.EMPTY : super.quickMoveStack(player, index);
    }
}
