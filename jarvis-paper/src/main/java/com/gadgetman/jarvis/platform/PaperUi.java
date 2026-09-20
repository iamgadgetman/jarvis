package com.gadgetman.jarvis.platform;

import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.Ui;
import com.gadgetman.jarvis.core.ui.Menu;
import com.gadgetman.jarvis.core.ui.MenuClick;
import com.gadgetman.jarvis.core.ui.MenuItem;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core's {@link Ui} over chest inventories and boss bars.
 *
 * <p>A menu is drawn into an inventory whose holder is the {@link Menu}
 * itself, so a click is matched to its item exactly rather than by comparing
 * titles, and any other plugin's inventory is left alone.
 */
public final class PaperUi implements Ui, Listener {

    /** The inventory's holder: the model it was drawn from. */
    private static final class Holder implements InventoryHolder {
        final Menu menu;
        Inventory inventory;

        Holder(Menu menu) {
            this.menu = menu;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final Map<UUID, BossBar> bars = new ConcurrentHashMap<>();

    private static Player player(Owner owner) {
        return Bukkit.getPlayer(owner.id());
    }

    @Override
    public void open(Owner viewer, Menu menu) {
        Player p = player(viewer);
        if (p == null) return;
        Holder holder = new Holder(menu);
        Inventory inv = Bukkit.createInventory(holder, menu.size(),
                LegacyComponentSerializer.legacySection().deserialize(menu.title()));
        holder.inventory = inv;
        ItemStack filler = menu.filler() == null ? null : PaperItems.toStack(menu.filler());
        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.at(i);
            if (item != null) inv.setItem(i, PaperItems.toStack(item.icon()));
            else if (filler != null) inv.setItem(i, filler);
        }
        p.openInventory(inv);
    }

    @Override
    public void close(Owner viewer) {
        Player p = player(viewer);
        if (p != null) p.closeInventory();
    }

    @Override
    public void progressBar(Owner viewer, String title, double progress, boolean warn) {
        Player p = player(viewer);
        if (p == null) return;
        BossBar bar = bars.computeIfAbsent(viewer.id(), k -> {
            BossBar b = Bukkit.createBossBar(title, BarColor.BLUE, BarStyle.SEGMENTED_10);
            b.addPlayer(p);
            return b;
        });
        bar.setTitle(title);
        bar.setProgress(Math.max(0, Math.min(1, progress)));
        bar.setColor(warn ? BarColor.YELLOW : BarColor.BLUE);
    }

    @Override
    public void hideProgressBar(Owner viewer) {
        BossBar bar = bars.remove(viewer.id());
        if (bar != null) bar.removeAll();
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!(e.getInventory().getHolder() instanceof Holder holder)) return;

        // Every click in one of our views is ours — including the player's own
        // inventory rows, so nothing can be dragged in or shift-clicked out.
        e.setCancelled(true);
        if (e.getRawSlot() < 0 || e.getRawSlot() >= e.getInventory().getSize()) return;

        MenuItem item = holder.menu.at(e.getRawSlot());
        if (item == null || item.onClick() == null) return;
        item.onClick().accept(new MenuClick(new PaperOwner(p.getUniqueId()), e.isRightClick()));
    }
}
