package com.gadgetman.jarvis.fabric.platform;

import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.Ui;
import com.gadgetman.jarvis.core.ui.Menu;
import com.gadgetman.jarvis.core.ui.MenuItem;
import com.gadgetman.jarvis.fabric.fake.FakePlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Core's {@link Ui} over chest screens and boss bars. */
public final class FabricUi implements Ui {

    private final MinecraftServer server;
    private final Map<UUID, ServerBossEvent> bars = new ConcurrentHashMap<>();

    public FabricUi(MinecraftServer server) {
        this.server = server;
    }

    private ServerPlayer player(Owner owner) {
        ServerPlayer p = server.getPlayerList().getPlayer(owner.id());
        return p instanceof FakePlayer ? null : p;
    }

    @Override
    public void open(Owner viewer, Menu menu) {
        ServerPlayer p = player(viewer);
        if (p == null) return;
        SimpleContainer container = new SimpleContainer(menu.size());
        ItemStack filler = menu.filler() == null ? ItemStack.EMPTY : FabricItems.toStack(server, menu.filler());
        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.at(i);
            if (item != null) container.setItem(i, FabricItems.toStack(server, item.icon()));
            else if (!filler.isEmpty()) container.setItem(i, filler.copy());
        }
        p.openMenu(new SimpleMenuProvider(
                (id, inventory, player) -> new MenuScreen(id, inventory, container, menu, server),
                Component.literal(menu.title())));
    }

    @Override
    public void close(Owner viewer) {
        ServerPlayer p = player(viewer);
        if (p != null) p.closeContainer();
    }

    @Override
    public void progressBar(Owner viewer, String title, double progress, boolean warn) {
        ServerPlayer p = player(viewer);
        if (p == null) return;
        ServerBossEvent bar = bars.computeIfAbsent(viewer.id(), k -> {
            ServerBossEvent b = new ServerBossEvent(UUID.randomUUID(), Component.literal(title),
                    BossEvent.BossBarColor.BLUE, BossEvent.BossBarOverlay.NOTCHED_10);
            b.addPlayer(p);
            return b;
        });
        bar.setName(Component.literal(title));
        bar.setProgress((float) Math.max(0, Math.min(1, progress)));
        bar.setColor(warn ? BossEvent.BossBarColor.YELLOW : BossEvent.BossBarColor.BLUE);
    }

    @Override
    public void hideProgressBar(Owner viewer) {
        ServerBossEvent bar = bars.remove(viewer.id());
        if (bar != null) bar.removeAllPlayers();
    }

    public void shutdown() {
        bars.values().forEach(ServerBossEvent::removeAllPlayers);
        bars.clear();
    }
}
