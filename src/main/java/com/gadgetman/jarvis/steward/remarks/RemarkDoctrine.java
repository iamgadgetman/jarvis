package com.gadgetman.jarvis.steward.remarks;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Whether anything about the present moment deserves a sentence, and if so
 * which one.
 *
 * <p><b>The hard part of idle commentary is not producing a line — it is
 * declining to.</b> Seventy-two iron ore is worth remarking on; forty-one
 * cobblestone is not, and neither is seventy-two iron ore again four minutes
 * later. That filter is what separates a butler from a status bar, and it is
 * ordinary code with thresholds that can be read and argued with — not a
 * model's judgement, and not a prompt.
 *
 * <p>A pure function of an {@link Observation}, the subjects already spoken of
 * recently, and a variant number for phrasing. It returns {@code null} far more
 * often than not, which is correct: silence is the default and a remark has to
 * earn its way out.
 *
 * <p>Two things it deliberately never mentions: <b>hunger and tool wear</b>.
 * The valet in {@code startSupplyMonitor} already speaks to both, and hands
 * over food or a replacement while doing it. Two subsystems observing the same
 * fact is precisely how a charming feature becomes a tiresome one.
 */
public final class RemarkDoctrine {

    private RemarkDoctrine() { }

    /** A subject and the words for it. */
    public record Remark(RemarkSubject subject, String line) { }

    /**
     * Evaluation order. Not importance exactly — the subject cooldown means
     * whatever fires here is suppressed next time round, so lower entries get
     * their turn. It is the order in which two true things compete: something
     * that could hurt you before something you are merely carrying.
     */
    private static final List<RemarkSubject> PRIORITY = List.of(
            RemarkSubject.COMPANY,
            RemarkSubject.STORM,
            RemarkSubject.DARKNESS,
            RemarkSubject.DEPTH,
            RemarkSubject.ALTITUDE,
            RemarkSubject.FULL_BAGS,
            RemarkSubject.HOARD,
            RemarkSubject.BULK_HAUL,
            RemarkSubject.EXPERIENCE,
            RemarkSubject.FAR_FROM_HOME,
            RemarkSubject.ELSEWHERE,
            RemarkSubject.NIGHTFALL);

    // ---- Thresholds. Chosen, not fitted; see the roadmap. ----

    /** Ore, ingots and gems. Two stacks of iron is a haul worth naming. */
    static final int PRECIOUS_AT = 32;
    /** Copper, coal, redstone, quartz — you come home with these by the crate. */
    static final int MODEST_AT = 128;
    /** Cobblestone and its cousins. Only funny once it is absurd. */
    static final int BULK_AT = 384;

    static final int DEEP_Y = -32;
    static final int HIGH_Y = 160;
    static final int DARK_LIGHT = 3;
    static final int SPENDABLE_LEVELS = 30;
    static final double FAR_FROM_HOME_M = 1000.0;
    static final int A_CROWD = 3;

    private static final String[] PRECIOUS = {
            "diamond", "emerald", "netherite", "ancient debris", "gold", "iron",
            "lapis", "amethyst", "echo shard", "nether star", "totem", "shulker"
    };
    private static final String[] MODEST = {
            "copper", "coal", "quartz", "redstone"
    };
    private static final String[] BULK = {
            "cobblestone", "cobbled deepslate", "deepslate", "stone", "dirt",
            "gravel", "sand", "netherrack", "tuff", "granite", "diorite",
            "andesite", "rotten flesh", "kelp", "seeds"
    };

    /**
     * The one entry point.
     *
     * @param o       what he can see
     * @param recent  subjects already remarked on inside their cooldown
     * @param variant any number; selects between phrasings of the same subject
     * @return a remark, or {@code null} — the usual answer
     */
    public static Remark choose(Observation o, Set<RemarkSubject> recent, int variant) {
        if (o == null) return null;
        for (RemarkSubject subject : PRIORITY) {
            if (recent.contains(subject)) continue;
            String line = render(subject, o, variant);
            if (line != null) return new Remark(subject, line);
        }
        return null;
    }

    /** The line for this subject, or {@code null} if the moment does not warrant one. */
    private static String render(RemarkSubject subject, Observation o, int variant) {
        return switch (subject) {

            case COMPANY -> o.nearbyMonsters() < A_CROWD ? null : pick(variant,
                    "I count " + o.nearbyMonsters() + " of them about us, sir. Say the word.",
                    "We have company, sir. " + o.nearbyMonsters() + " of it.",
                    "There are " + o.nearbyMonsters() + " unpleasant things within earshot, sir.");

            case STORM -> !o.thundering() ? null : pick(variant,
                    "It is thundering, sir. Do mind the high ground.",
                    "Lightning, sir. An umbrella would be optimistic.");

            case DARKNESS -> o.lightLevel() > DARK_LIGHT ? null : pick(variant,
                    "It is rather dark here, sir. Things take that as an invitation.",
                    "I can barely see you, sir, which means something else can.",
                    "A torch would not go amiss here, sir.");

            case DEPTH -> !(o.underground() && o.y() <= DEEP_Y) ? null : pick(variant,
                    "We are " + Math.abs(o.y()) + " below the sea, sir. The good rock, at least.",
                    "Y " + o.y() + ", sir. Diamond country, and rather a long climb.",
                    "Deep work today, sir. Mind the lava.");

            case ALTITUDE -> (o.underground() || o.y() < HIGH_Y) ? null : pick(variant,
                    "Y " + o.y() + ", sir. A splendid view and a poor landing.",
                    "We are very high up, sir. I shall not be catching you.");

            case FULL_BAGS -> o.slotsFree() > 2 ? null : pick(variant,
                    "Your pockets are full, sir. Something will have to go.",
                    "There is no room left in your bags, sir. Shall I hold something?");

            case HOARD -> {
                int worth = worthAt(o.hoardName());
                yield (worth < 0 || o.hoardCount() < worth) ? null : pick(variant,
                        "I see you have " + o.hoardCount() + " " + o.hoardName()
                                + ", sir. Your pockets must be quite heavy.",
                        o.hoardCount() + " " + o.hoardName() + ", sir. A respectable morning's work.",
                        "That is " + o.hoardCount() + " " + o.hoardName() + " you are carrying, sir. "
                                + "A chest would sleep easier than you will.");
            }

            case BULK_HAUL -> !(isAny(o.hoardName(), BULK) && o.hoardCount() >= BULK_AT) ? null : pick(variant,
                    "You are hauling " + o.hoardCount() + " " + o.hoardName()
                            + ", sir. I admire the ambition.",
                    o.hoardCount() + " " + o.hoardName() + ", sir. Whatever it is, it will be large.");

            case EXPERIENCE -> o.xpLevel() < SPENDABLE_LEVELS ? null : pick(variant,
                    "Level " + o.xpLevel() + ", sir. The anvil is not getting any cheaper.",
                    "You are carrying " + o.xpLevel() + " levels about, sir. Spend some before something bites.");

            case FAR_FROM_HOME -> o.homeDistance() < FAR_FROM_HOME_M ? null : pick(variant,
                    "We are some " + Math.round(o.homeDistance() / 100) * 100
                            + " metres from your bed, sir. Worth remembering.",
                    "Home is a long walk from here, sir. Do mind how you go.");

            case ELSEWHERE -> switch (o.dimension() == null ? "" : o.dimension()) {
                case "nether" -> pick(variant,
                        "The " + (o.biome() == null ? "nether" : o.biome().replace('_', ' '))
                                + ", sir. Charming, in its way.",
                        "It is warm work down here, sir. Mind the ledges.");
                case "the_end" -> pick(variant,
                        "The End, sir. There is nothing to breathe out there and a great deal of it.",
                        "Do stay away from the edge, sir. It goes on rather.");
                default -> null;
            };

            case NIGHTFALL -> !(o.night() && !o.underground()) ? null : pick(variant,
                    "It is dark out, sir. The night shift will be along shortly.",
                    "Night, sir. Everything unpleasant is now awake.");
        };
    }

    /**
     * How many of a thing is worth mentioning — {@code -1} for a material that
     * never is. Planks, cobblestone and dirt are excluded here rather than
     * given a huge threshold; bulk gets its own subject and its own joke.
     */
    static int worthAt(String material) {
        if (material == null || material.isBlank()) return -1;
        String m = material.toLowerCase(Locale.ROOT);
        if (isAny(m, PRECIOUS)) return PRECIOUS_AT;
        if (isAny(m, MODEST)) return MODEST_AT;
        return -1;
    }

    private static boolean isAny(String material, String[] keywords) {
        if (material == null) return false;
        String m = material.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (m.contains(keyword)) return true;
        }
        return false;
    }

    private static String pick(int variant, String... lines) {
        return lines[Math.floorMod(variant, lines.length)];
    }
}
