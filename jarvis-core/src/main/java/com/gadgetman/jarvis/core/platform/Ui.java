package com.gadgetman.jarvis.core.platform;

import com.gadgetman.jarvis.core.ui.Menu;

/**
 * The bits of a player's screen core draws on: chest menus and the boss bar.
 *
 * <p>The adapter owns the widgets; core describes what should be on them.
 * Everything here is server-thread only.
 */
public interface Ui {

    /** Show a menu, replacing whatever the viewer had open. */
    void open(Owner viewer, Menu menu);

    /** Close any menu or container the viewer has open. */
    void close(Owner viewer);

    /**
     * Show or update a progress bar across the top of the viewer's screen.
     *
     * @param progress 0 to 1
     * @param warn     true to draw it in the warning colour
     */
    void progressBar(Owner viewer, String title, double progress, boolean warn);

    void hideProgressBar(Owner viewer);
}
