package com.gadgetman.jarvis.schematics;

import java.util.Locale;

/**
 * What a build request is asking for, in three named parts.
 *
 * <p>Named parts rather than a flat tag list because the parts are not worth
 * the same. Measured against llama3.2:3b, a style word landing on a schematic
 * name by coincidence -- "small" hitting {@code small_warehouse} -- outscored
 * the purpose that actually answered the request, and "a cosy little cottage"
 * came back as a warehouse when the plain name match had it right. Separating
 * them lets {@link SchematicManager} weigh the structure above the use, and the
 * style not at all except to break a tie.
 *
 * @param purpose what it is used for -- storage, shelter, farming, defence
 * @param kind    the structure serving that purpose -- shed, cottage, tower
 * @param style   size or material, only when the request said so; often blank
 */
public record RequestFeatures(String purpose, String kind, String style) {

    private static final RequestFeatures NONE = new RequestFeatures("", "", "");

    /** No decomposition available — scoring falls back to the raw wording. */
    public static RequestFeatures none() {
        return NONE;
    }

    public RequestFeatures {
        purpose = clean(purpose);
        kind = clean(kind);
        style = clean(style);
    }

    /**
     * True when neither of the two parts that decide a match is present. A
     * style on its own is not a decomposition -- it cannot pick a schematic,
     * only rank two that already matched.
     */
    public boolean isEmpty() {
        return purpose.isEmpty() && kind.isEmpty();
    }

    /** The cache and log form: {@code storage|shed|small}. */
    @Override
    public String toString() {
        return purpose + "|" + kind + "|" + style;
    }

    /** Parse the cache form back. Anything unrecognised reads as none(). */
    public static RequestFeatures parse(String stored) {
        if (stored == null || stored.isBlank()) return NONE;
        String[] parts = stored.split("\\|", -1);
        if (parts.length != 3) return NONE;
        RequestFeatures f = new RequestFeatures(parts[0], parts[1], parts[2]);
        return f.isEmpty() ? NONE : f;
    }

    /**
     * One word, lowercase, letters and digits only. The scorer ignores anything
     * shorter than three characters, so those are dropped here rather than
     * carried around looking meaningful.
     *
     * <p>A model that answers with two words survives this as one run-together
     * token ("storing crops" becomes "storingcrops"), which matches no
     * schematic name and so costs a match rather than causing a wrong one. The
     * prompt's examples are what stop it happening; this is only the backstop.
     */
    private static String clean(String word) {
        if (word == null) return "";
        String out = word.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return out.length() >= 3 ? out : "";
    }
}
