package com.gadgetman.jarvis.core.testing;

import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.platform.Ui;
import com.gadgetman.jarvis.core.text.Colors;
import com.gadgetman.jarvis.core.ui.Menu;
import com.gadgetman.jarvis.core.ui.MenuClick;
import com.gadgetman.jarvis.core.ui.MenuItem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Menus as data: what is open for whom, and a way to click it. */
public final class FakeUi implements Ui {

    private final Map<UUID, Menu> open = new HashMap<>();
    public final Map<UUID, String> progressBars = new HashMap<>();
    /** Every open, in order, by title (and "loot:<owner>" for his bags). */
    public final List<String> opened = new ArrayList<>();

    @Override
    public void open(Owner viewer, Menu menu) {
        open.put(viewer.id(), menu);
        opened.add(menu.title());
    }

    @Override public void close(Owner viewer) { open.remove(viewer.id()); }

    @Override
    public void progressBar(Owner viewer, String title, double progress, boolean warn) {
        progressBars.put(viewer.id(), Colors.strip(title));
    }

    @Override public void hideProgressBar(Owner viewer) { progressBars.remove(viewer.id()); }

    public Menu menuFor(Owner viewer) {
        return open.get(viewer.id());
    }

    public boolean isOpen(Owner viewer) {
        return open.containsKey(viewer.id());
    }

    /** Click a slot in the viewer's open menu. */
    public void click(Owner viewer, int slot, boolean right) {
        Menu menu = open.get(viewer.id());
        if (menu == null) throw new IllegalStateException("no menu open for " + viewer.name());
        MenuItem item = menu.at(slot);
        if (item == null) throw new IllegalStateException("nothing at slot " + slot + " of " + menu.title());
        if (item.onClick() != null) item.onClick().accept(new MenuClick(viewer, right));
    }

    /** The slot whose icon name contains this text, or -1. */
    public int slotNamed(Owner viewer, String fragment) {
        Menu menu = open.get(viewer.id());
        if (menu == null) return -1;
        for (Map.Entry<Integer, MenuItem> e : menu.slots().entrySet()) {
            String name = e.getValue().icon().displayName();
            if (name != null && Colors.strip(name).contains(fragment)) return e.getKey();
        }
        return -1;
    }
}
