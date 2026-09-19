package com.gadgetman.jarvis.progression;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;

import java.util.List;
import java.util.Map;

/**
 * Jarvis's standing in your service, and the kit that comes with it.
 *
 * <p>The ladder is deliberately front-loaded. The first several ranks arrive
 * quickly and exist to teach — each one lands while you are still working out
 * what he can do, and rewards you for trying a discipline rather than for
 * grinding one. The tail is where the real prizes sit, so there is a reason to
 * keep him working long after the tutorial is over.
 *
 * <p>Kit is never worse than iron: he starts there. Nothing in the ladder can
 * take equipment away, and every tool he is issued is unbreakable — a butler
 * who needs his pickaxe replaced is a chore, not a servant.
 */
public enum Rank {

    HIRED("Hired", 0, Material.IRON_PICKAXE, Map.of()),

    ACQUAINTED("Acquainted", 25, Material.IRON_PICKAXE,
            Map.of(Enchantment.EFFICIENCY, 1)),

    RELIABLE("Reliable", 75, Material.DIAMOND_PICKAXE,
            Map.of(Enchantment.EFFICIENCY, 1)),

    PRACTISED("Practised", 150, Material.DIAMOND_PICKAXE,
            Map.of(Enchantment.EFFICIENCY, 3, Enchantment.SHARPNESS, 1)),

    TRUSTED("Trusted", 300, Material.DIAMOND_PICKAXE,
            Map.of(Enchantment.EFFICIENCY, 3, Enchantment.SHARPNESS, 1,
                   Enchantment.FORTUNE, 1, Enchantment.LOOTING, 1)),

    SEASONED("Seasoned", 500, Material.DIAMOND_PICKAXE,
            Map.of(Enchantment.EFFICIENCY, 5, Enchantment.SHARPNESS, 3,
                   Enchantment.FORTUNE, 1, Enchantment.LOOTING, 1)),

    VALUED("Valued", 800, Material.DIAMOND_PICKAXE,
            Map.of(Enchantment.EFFICIENCY, 5, Enchantment.SHARPNESS, 3,
                   Enchantment.FORTUNE, 2, Enchantment.LOOTING, 2)),

    /** Where combat stops being one escalating melee stat and gains a second shape. */
    INDISPENSABLE("Indispensable", 1500, Material.NETHERITE_PICKAXE,
            Map.of(Enchantment.EFFICIENCY, 5, Enchantment.SHARPNESS, 4,
                   Enchantment.FORTUNE, 3, Enchantment.LOOTING, 3,
                   Enchantment.POWER, 3),
            java.util.EnumSet.of(Capability.ARCHERY)),

    /** The first rank that buys a capability rather than a better metal. */
    PEERLESS("Peerless", 2500, Material.NETHERITE_PICKAXE,
            Map.of(Enchantment.EFFICIENCY, 5, Enchantment.SHARPNESS, 5,
                   Enchantment.FORTUNE, 3, Enchantment.LOOTING, 3,
                   Enchantment.POWER, 4, Enchantment.PUNCH, 1),
            java.util.EnumSet.of(Capability.ARCHERY, Capability.WIDE_BORE)),

    WITHOUT_EQUAL("Without Equal", 4000, Material.NETHERITE_PICKAXE,
            Map.of(Enchantment.EFFICIENCY, 5, Enchantment.SHARPNESS, 5,
                   Enchantment.FORTUNE, 3, Enchantment.LOOTING, 3,
                   Enchantment.FIRE_ASPECT, 2,
                   Enchantment.POWER, 5, Enchantment.PUNCH, 2, Enchantment.FLAME, 1),
            java.util.EnumSet.of(Capability.ARCHERY, Capability.WIDE_BORE, Capability.TRIDENT));

    /**
     * Things a rank grants that are not simply better equipment. These are the
     * reason to keep him working once the metal has run out.
     */
    public enum Capability {
        /** Branch mines are carved 3x3 instead of 1x2. */
        WIDE_BORE,
        /** A returning trident, and the reach to use it. */
        TRIDENT,
        /**
         * A bow, and the sense to stand off with it. Granted before the
         * trident: it is the broader skill, and useful everywhere, where the
         * trident only earns its place in water.
         */
        ARCHERY
    }

    private final String title;
    private final int service;
    private final Material pickaxe;
    private final Map<Enchantment, Integer> enchantments;
    private final java.util.Set<Capability> capabilities;

    Rank(String title, int service, Material pickaxe, Map<Enchantment, Integer> enchantments) {
        this(title, service, pickaxe, enchantments, java.util.EnumSet.noneOf(Capability.class));
    }

    Rank(String title, int service, Material pickaxe, Map<Enchantment, Integer> enchantments,
         java.util.Set<Capability> capabilities) {
        this.title        = title;
        this.service      = service;
        this.pickaxe      = pickaxe;
        this.enchantments = enchantments;
        this.capabilities = capabilities;
    }

    public boolean grants(Capability capability) { return capabilities.contains(capability); }

    public String title()                       { return title; }
    public int serviceRequired()                { return service; }
    public Map<Enchantment, Integer> enchants() { return enchantments; }

    /** Ordinal position, 1-based, for display. */
    public int number() { return ordinal() + 1; }

    /** The material family for this rank, mapped onto a given tool type. */
    public Material toolFor(ToolKind kind) {
        String tier = switch (pickaxe) {
            case NETHERITE_PICKAXE -> "NETHERITE";
            case DIAMOND_PICKAXE   -> "DIAMOND";
            default                -> "IRON";
        };
        return switch (kind) {
            case PICKAXE -> Material.valueOf(tier + "_PICKAXE");
            case SWORD   -> Material.valueOf(tier + "_SWORD");
            case TRIDENT -> Material.TRIDENT;
            case BOW     -> Material.BOW;               // has no tiers either
            case AXE     -> Material.valueOf(tier + "_AXE");
            case HOE     -> Material.valueOf(tier + "_HOE");
            case ROD     -> Material.FISHING_ROD;      // has no tiers
        };
    }

    /**
     * The trident is a separate kind rather than a better sword: it is drawn
     * only underwater, where Impaling actually applies, and the sword is what
     * he carries on dry land.
     */
    public enum ToolKind { PICKAXE, SWORD, AXE, HOE, ROD, TRIDENT, BOW }

    /** The top of the ladder, whatever it currently is. */
    public static Rank top() {
        return values()[values().length - 1];
    }

    /** Look up by name or 1-based number; null if neither matches. */
    public static Rank byNameOrNumber(String text) {
        if (text == null || text.isBlank()) return null;
        String key = text.trim();
        try {
            int n = Integer.parseInt(key);
            return (n >= 1 && n <= values().length) ? values()[n - 1] : null;
        } catch (NumberFormatException ignored) { }
        for (Rank r : values()) {
            if (r.name().equalsIgnoreCase(key) || r.title().equalsIgnoreCase(key)) return r;
        }
        return null;
    }

    /** Highest rank earned by this much service. */
    public static Rank forService(int service) {
        Rank earned = HIRED;
        for (Rank r : values()) {
            if (service >= r.serviceRequired()) earned = r;
        }
        return earned;
    }

    /** The rank after this one, or null at the top. */
    public Rank next() {
        List<Rank> all = List.of(values());
        int i = all.indexOf(this);
        return i < all.size() - 1 ? all.get(i + 1) : null;
    }

    /** What this rank granted that the previous one did not — for the "promoted" line. */
    public String whatIsNew() {
        Rank prev = ordinal() == 0 ? null : values()[ordinal() - 1];
        if (prev == null) return "an iron kit, unbreakable";

        StringBuilder sb = new StringBuilder();
        if (prev.pickaxe != pickaxe) {
            sb.append(switch (pickaxe) {
                case NETHERITE_PICKAXE -> "a netherite kit";
                case DIAMOND_PICKAXE   -> "a diamond kit";
                default                -> "an iron kit";
            });
        }
        for (var e : enchantments.entrySet()) {
            int before = prev.enchantments.getOrDefault(e.getKey(), 0);
            if (e.getValue() > before) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(pretty(e.getKey())).append(' ').append(roman(e.getValue()));
            }
        }
        for (Capability c : capabilities) {
            if (prev.capabilities.contains(c)) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(switch (c) {
                case WIDE_BORE -> "3x3 excavation";
                case TRIDENT   -> "a trident, for fighting in water";
                case ARCHERY   -> "a bow, and the sense to stand off with it";
            });
        }
        return sb.length() == 0 ? "nothing he'll admit to" : sb.toString();
    }

    public static String pretty(Enchantment enchantment) {
        String key = enchantment.getKey().getKey().replace('_', ' ');
        return Character.toUpperCase(key.charAt(0)) + key.substring(1);
    }

    public static String roman(int n) {
        return switch (n) {
            case 1 -> "I"; case 2 -> "II"; case 3 -> "III";
            case 4 -> "IV"; case 5 -> "V"; default -> String.valueOf(n);
        };
    }
}
