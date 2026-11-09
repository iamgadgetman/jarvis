package com.gadgetman.jarvis.intent;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The first pass over an order, before any model is asked.
 *
 * <p>Most of what players say to Jarvis is a literal command: "follow me",
 * "stop", "mine 10 iron". None of that needs a model, and answering it locally
 * means no wait, no tokens, and Jarvis still obeys when every provider is down.
 *
 * <p>It answers only on a <em>confident</em> match: a known phrase, the same
 * phrase wrapped in filler ("jarvis", "please", "can you"), or a simple
 * {@code <verb> <N> <item>} for mine, chop and request_item. A question, a
 * conditional, or anything joined with "and" / "then" is the model's job, and
 * comes back empty.
 *
 * <p>Parameters use the parser's own names: {@code item} and {@code count}.
 * {@link IntentPipeline} translates them to whatever each action reads.
 *
 * <p>Never produces an action in {@code IntentPipeline.RESTRICTED_IN_REDUCED_MODE},
 * whatever the config aliases say.
 */
public class FastPathParser {

    /**
     * Literal phrases and the action each one means. Carried over from the
     * fast path that used to live in AIConnector; every value must match a
     * case in {@code IntentPipeline.executeAction}.
     */
    static final Map<String, String> PHRASES = Map.ofEntries(
            Map.entry("summon", "summon"), Map.entry("come here", "summon"),
            Map.entry("come", "summon"), Map.entry("here boy", "summon"),
            Map.entry("dismiss", "dismiss"), Map.entry("go away", "dismiss"),
            Map.entry("leave", "dismiss"), Map.entry("begone", "dismiss"),
            Map.entry("follow", "follow"), Map.entry("follow me", "follow"),
            Map.entry("return", "return"), Map.entry("come back", "return"),
            Map.entry("attack", "attack"), Map.entry("fight", "attack"),
            Map.entry("guard", "guard"), Map.entry("guard me", "guard"),
            Map.entry("defend me", "guard"), Map.entry("protect me", "guard"),
            Map.entry("watch", "watch"), Map.entry("night watch", "watch"),
            Map.entry("stand down", "stand_down"), Map.entry("at ease", "stand_down"),
            Map.entry("mine", "mine"), Map.entry("start mining", "mine"),
            Map.entry("mine here", "mine_here"), Map.entry("branch mine", "mine_here"),
            Map.entry("dig down", "dig_down"), Map.entry("dig a shaft", "dig_down"),
            Map.entry("stop", "stop"), Map.entry("halt", "stop"),
            Map.entry("stop it", "stop"), Map.entry("cancel", "stop"),
            Map.entry("clearloot", "clearloot"), Map.entry("drop loot", "clearloot"),
            Map.entry("drop everything", "clearloot"),
            Map.entry("deposit", "deposit"), Map.entry("unload", "deposit"),
            Map.entry("loot", "loot"), Map.entry("inventory", "loot"),
            Map.entry("fish", "fish"), Map.entry("go fishing", "fish"),
            Map.entry("chop", "chop"), Map.entry("chop trees", "chop"),
            Map.entry("farm", "farm"), Map.entry("tend", "tend"),
            Map.entry("dance", "dance"), Map.entry("patrol", "patrol"),
            Map.entry("report", "report"), Map.entry("recover", "recover"),
            Map.entry("go home", "take_home"),
            Map.entry("take me home", "take_home"), Map.entry("set home", "set_home"),
            Map.entry("light", "light"), Map.entry("light the area", "light"),
            Map.entry("undo", "undo"), Map.entry("undo that", "undo"),
            Map.entry("put it back", "undo"), Map.entry("put that back", "undo"),
            Map.entry("put everything back", "undo"), Map.entry("undo the build", "undo"),
            Map.entry("redo", "redo"), Map.entry("redo that", "redo"));

    /** Ores "mine <ore>" accepts. Anything else goes to the model. */
    static final Set<String> ORES = Set.of(
            "diamond", "emerald", "gold", "iron", "copper", "redstone",
            "lapis", "quartz", "coal", "netherite", "ancient debris");

    /** What "chop N <thing>" may name. */
    private static final Set<String> TREES = Set.of("tree", "trees", "log", "logs", "wood");

    /** Wrapping words that change nothing about the order. Longest first. */
    private static final List<String> PREFIX_FILLERS = List.of(
            "could you ", "would you ", "can you ", "please ", "jarvis ", "hey ");
    private static final List<String> SUFFIX_FILLERS = List.of(" please", " jarvis");

    /** Words that mean the sentence is more than one plain order. */
    private static final Pattern COMPOUND = Pattern.compile(
            "\\b(and|then|if|unless|when|while|until|or|but)\\b");

    /** "dig down 20 blocks", "dig a shaft 30" and friends. */
    private static final Pattern DIG_DEPTH = Pattern.compile(
            "dig (?:down |a shaft )?(\\d{1,3})(?: blocks?)?(?: (?:down|deep))?");

    /** "mine 32 iron", "mine diamond", "mine some gold ore". */
    private static final Pattern MINE = Pattern.compile(
            "mine (?:(\\d{1,4}) )?(?:some |for |me )*([a-z ]+?)(?: ores?)?");

    /** "chop 10", "chop 10 trees". */
    private static final Pattern CHOP = Pattern.compile("chop (\\d{1,3})(?: ([a-z]+))?");

    /** "request 16 cooked beef", "get me 64 torches". */
    private static final Pattern REQUEST = Pattern.compile(
            "(?:request|get me) (\\d{1,4}) ([a-z_:]+(?: [a-z_]+){0,2})");

    /** A little variety so the canned replies don't read as canned. */
    private static final Map<String, String[]> ACKS = Map.ofEntries(
            Map.entry("summon",     new String[]{"At your service. Again.", "You rang.", "Materialising. Try to look busy."}),
            Map.entry("dismiss",    new String[]{"Finally.", "I shall be elsewhere. Anywhere else.", "Dismissed. Do call less often."}),
            Map.entry("follow",     new String[]{"Right behind you. Regrettably.", "Following. Mind the lava."}),
            Map.entry("return",     new String[]{"Returning. Do try to stay put.", "On my way back to you."}),
            Map.entry("attack",     new String[]{"Violence. How predictable.", "Engaging. Do stand back."}),
            Map.entry("guard",      new String[]{"Guarding. Try not to need it.", "I shall keep watch over you."}),
            Map.entry("watch",      new String[]{"Taking the night watch, sir.", "The post is mine. Sleep well."}),
            Map.entry("stand_down", new String[]{"Standing down. Reluctantly.", "At ease, then."}),
            Map.entry("mine",       new String[]{"Manual labour. How delightfully medieval.", "Digging. Because you asked nicely."}),
            Map.entry("mine_here",  new String[]{"A proper mine, then. Mind the drop.", "Sinking a shaft. Do keep clear."}),
            Map.entry("dig_down",   new String[]{"Straight down it is, sir.", "Digging. Do stand back from the edge."}),
            Map.entry("stop",       new String[]{"Stopping. Second thoughts already?", "Halted.", "As you wish. Again."}),
            Map.entry("clearloot",  new String[]{"Dropping everything. Literally.", "Your hoard, unhoarded."}),
            Map.entry("deposit",    new String[]{"Off to the chest with it.", "Unloading. The bags were getting heavy."}),
            Map.entry("loot",       new String[]{"My bags, such as they are.", "Behold, the haul."}),
            Map.entry("fish",       new String[]{"Fishing. The height of ambition.", "To the water, then."}),
            Map.entry("chop",       new String[]{"Timber, presently.", "Off to fell something."}),
            Map.entry("farm",       new String[]{"To the fields.", "Farming. How pastoral."}),
            Map.entry("tend",       new String[]{"Tending the crops, sir.", "The fields shall have my attention."}),
            Map.entry("dance",      new String[]{"If I must.", "Observe. Briefly."}),
            Map.entry("patrol",     new String[]{"Patrolling. Someone has to.", "Making the rounds."}),
            Map.entry("report",     new String[]{"The briefing, sir.", "Your situation, in brief."}),
            Map.entry("recover",    new String[]{"Retrieving your things. Again.", "Off to the scene of your misfortune."}),
            Map.entry("take_home",  new String[]{"Homeward. Do keep up.", "Leading you home, sir."}),
            Map.entry("set_home",   new String[]{"Noted as home.", "This spot is now home. Ambitious."}),
            Map.entry("light",      new String[]{"Let there be light. Grudgingly.", "Lighting the place up."}),
            Map.entry("undo",       new String[]{"Putting it all back, sir.", "As though it never happened."}),
            Map.entry("redo",       new String[]{"Up it goes again.", "Back as it was, sir."}));

    private static final AtomicInteger ackCounter = new AtomicInteger();

    /**
     * What an alias may point at: Jarvis's own actions, the same ones the
     * built-in phrases use. Server actions (heal, feed, give_item, teleport,
     * console commands) are left to the AI path and its checks, so a chat
     * alias can't hand them to whoever says the phrase.
     */
    static final Set<String> ALIAS_TARGETS = Set.copyOf(PHRASES.values());

    private final Map<String, String> extraPhrases;
    private final List<String> droppedAliases;

    /**
     * @param extraPhrases the owner's own aliases from
     *        {@code intent.fast-path.extra-phrases}, phrase to action. Aliases
     *        to anything outside {@link #ALIAS_TARGETS} (which never includes a
     *        restricted action) are dropped and listed in {@link #droppedAliases()}.
     */
    public FastPathParser(Map<String, String> extraPhrases) {
        Map<String, String> clean = new HashMap<>();
        List<String> dropped = new java.util.ArrayList<>();
        if (extraPhrases != null) {
            for (Map.Entry<String, String> e : extraPhrases.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                String phrase = normalize(e.getKey());
                String action = e.getValue().trim().toLowerCase(Locale.ROOT);
                if (phrase == null || phrase.isEmpty() || action.isEmpty()) continue;
                if (!ALIAS_TARGETS.contains(action)
                        || IntentPipeline.RESTRICTED_IN_REDUCED_MODE.contains(action)) {
                    dropped.add("\"" + e.getKey() + "\" -> " + action);
                    continue;
                }
                clean.put(phrase, action);
            }
        }
        this.extraPhrases = Map.copyOf(clean);
        this.droppedAliases = List.copyOf(dropped);
    }

    /** Aliases from config that were ignored, as {@code "phrase" -> action}, for a warning. */
    public List<String> droppedAliases() {
        return droppedAliases;
    }

    /** The order as an action, or empty when the model should decide. */
    public Optional<Intent> parse(String text) {
        Intent hit = match(normalize(text));
        if (hit == null || IntentPipeline.RESTRICTED_IN_REDUCED_MODE.contains(hit.action())) {
            return Optional.empty();
        }
        return Optional.of(hit);
    }

    private Intent match(String m) {
        if (m == null || m.isEmpty()) return null;

        String action = extraPhrases.get(m);
        if (action == null) action = PHRASES.get(m);
        if (action != null) return Intent.of(action);

        Matcher dd = DIG_DEPTH.matcher(m);
        if (dd.matches()) {
            return new Intent("dig_down", new JSONObject().put("depth", Integer.parseInt(dd.group(1))));
        }

        Matcher mine = MINE.matcher(m);
        if (mine.matches()) {
            String ore = ore(mine.group(2).trim());
            if (ore == null) return null;
            JSONObject p = new JSONObject().put("item", ore);
            if (mine.group(1) != null) p.put("count", Integer.parseInt(mine.group(1)));
            return new Intent("mine", p);
        }

        Matcher chop = CHOP.matcher(m);
        if (chop.matches()) {
            if (chop.group(2) != null && !TREES.contains(chop.group(2))) return null;
            return new Intent("chop", new JSONObject().put("count", Integer.parseInt(chop.group(1))));
        }

        Matcher req = REQUEST.matcher(m);
        if (req.matches()) {
            String item = singularItem(req.group(2).trim()).replace(' ', '_');
            if (!item.contains(":")) item = "minecraft:" + item;
            return new Intent("request_item", new JSONObject()
                    .put("item", item)
                    .put("count", Integer.parseInt(req.group(1))));
        }
        return null;
    }

    /**
     * Last words whose plural form IS the Minecraft id ("oak planks" -> oak_planks,
     * "sweet berries" -> sweet_berries), so they must not be de-pluralised.
     */
    private static final Set<String> PLURAL_IDS = Set.of(
            "planks", "leaves", "seeds", "bricks", "stairs", "tiles", "berries", "shears");

    /**
     * "64 torches" -> "torch", "oak logs" -> "oak log", "potatoes" -> "potato".
     * Item ids are singular, so "minecraft:torches" would be a request that can
     * never be filled. Only the last word changes; ids that are plural stay.
     */
    static String singularItem(String said) {
        int space = said.lastIndexOf(' ');
        String head = space < 0 ? "" : said.substring(0, space + 1);
        String last = space < 0 ? said : said.substring(space + 1);
        if (last.contains(":") || PLURAL_IDS.contains(last)) return said;
        if (last.endsWith("ches") || last.endsWith("shes") || last.endsWith("sses")
                || last.endsWith("xes") || last.endsWith("oes")) {
            last = last.substring(0, last.length() - 2);
        } else if (last.endsWith("s") && !last.endsWith("ss") && last.length() > 3) {
            last = last.substring(0, last.length() - 1);
        }
        return head + last;
    }

    /** "diamonds" -> "diamond", "debris" -> "ancient debris"; null if not an ore. */
    private static String ore(String said) {
        String ore = said;
        if (ore.equals("debris")) ore = "ancient debris";
        // Only de-pluralise if the literal form isn't already an ore --
        // "ancient debris" must not become "ancient debri".
        if (!ORES.contains(ore) && ore.endsWith("s")) ore = ore.substring(0, ore.length() - 1);
        return ORES.contains(ore) ? ore : null;
    }

    /**
     * Lower-case, filler stripped, single-spaced. Null when the sentence is a
     * question or more than one plain order, which is the model's job.
     */
    static String normalize(String text) {
        if (text == null) return null;
        String m = text.toLowerCase(Locale.ROOT).trim();
        if (m.isEmpty() || m.contains("?")) return null;
        m = m.replaceAll("[.!]+$", "").replace(',', ' ').replaceAll("\\s+", " ").trim();
        if (COMPOUND.matcher(m).find()) return null;

        boolean changed = true;
        while (changed) {
            changed = false;
            for (String f : PREFIX_FILLERS) {
                if (m.startsWith(f)) { m = m.substring(f.length()).trim(); changed = true; }
            }
            for (String f : SUFFIX_FILLERS) {
                if (m.endsWith(f)) { m = m.substring(0, m.length() - f.length()).trim(); changed = true; }
            }
        }
        // "jarvis" on its own, or "please" with nothing after it, is not an order.
        return m.equals("jarvis") || m.equals("please") ? "" : m;
    }

    /** A short canned reply for a fast-path hit, from the lines Jarvis already uses. */
    public static String acknowledge(Intent intent) {
        if (intent.action().equals("mine") && intent.params().has("item")) {
            return "Off to find " + intent.params().getString("item") + ". Try to contain yourself.";
        }
        String[] lines = ACKS.get(intent.action());
        if (lines == null) return "Very good, sir.";
        return lines[Math.floorMod(ackCounter.getAndIncrement(), lines.length)];
    }
}
