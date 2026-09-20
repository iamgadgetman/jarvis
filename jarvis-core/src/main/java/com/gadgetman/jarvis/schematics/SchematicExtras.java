package com.gadgetman.jarvis.schematics;

import com.gadgetman.jarvis.core.platform.Owner;
import com.gadgetman.jarvis.core.text.Colors;

/**
 * The two schematic features that need an editor behind them: saving a
 * clipboard selection, and pasting with a rotation. On Paper that editor is
 * WorldEdit; without one they politely decline.
 */
public interface SchematicExtras {

    void saveClipboard(Owner player, String name);

    void rotateAndPaste(Owner player, String name, int degrees);

    /** What you get without an editor installed. */
    SchematicExtras NONE = new SchematicExtras() {
        @Override
        public void saveClipboard(Owner player, String name) {
            player.message(Colors.RED + "WorldEdit is required for saving schematics.");
        }

        @Override
        public void rotateAndPaste(Owner player, String name, int degrees) {
            player.message(Colors.RED + "Rotation requires WorldEdit.");
            player.message(Colors.GRAY + "Use /jarvis schematic paste " + name + " for normal paste.");
        }
    };
}
