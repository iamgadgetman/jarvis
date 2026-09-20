package com.gadgetman.jarvis.core.text;

/**
 * Section-sign colour codes, the one text markup every platform renders.
 *
 * <p>Core writes plain strings with these codes in them. The Paper adapter
 * turns them into components with the legacy serializer; the game client
 * renders them as they are inside literal text on Fabric.
 */
public final class Colors {

    private Colors() { }

    public static final String BLACK = "§0";
    public static final String DARK_BLUE = "§1";
    public static final String DARK_GREEN = "§2";
    public static final String DARK_AQUA = "§3";
    public static final String DARK_RED = "§4";
    public static final String DARK_PURPLE = "§5";
    public static final String GOLD = "§6";
    public static final String GRAY = "§7";
    public static final String DARK_GRAY = "§8";
    public static final String BLUE = "§9";
    public static final String GREEN = "§a";
    public static final String AQUA = "§b";
    public static final String RED = "§c";
    public static final String LIGHT_PURPLE = "§d";
    public static final String YELLOW = "§e";
    public static final String WHITE = "§f";
    public static final String BOLD = "§l";
    public static final String ITALIC = "§o";
    public static final String RESET = "§r";

    /** "Jarvis: " in gold, then white for what he says. */
    public static String jarvis(String line) {
        return GOLD + "Jarvis: " + WHITE + line;
    }

    /** Strip every colour code, for logs and plain-text sinks. */
    public static String strip(String text) {
        return text == null ? null : text.replaceAll("§[0-9a-fk-or]", "");
    }
}
