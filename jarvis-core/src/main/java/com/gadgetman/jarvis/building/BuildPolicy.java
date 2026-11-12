package com.gadgetman.jarvis.building;

import com.gadgetman.jarvis.core.platform.Config;
import com.gadgetman.jarvis.core.world.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * What an AI-designed build may not do (FB-07): place blocked blocks, use more
 * than {@code max-blocks}, span more than {@code max-size} on its longest edge,
 * sit more than {@code max-distance} from the player, or leave the world's
 * height limits. Checked after the cleaners and before anything is placed.
 * Platform-free: it sees block positions and ids only.
 */
public final class BuildPolicy {

    /** {@code build.guardrails} as read from config. */
    public record Limits(Set<String> blocked, int maxBlocks, int maxSize, int maxDistance) { }

    public static final List<String> DEFAULT_BLOCKED = List.of(
            "tnt", "lava", "lava_bucket", "command_block", "chain_command_block",
            "repeating_command_block", "structure_block", "barrier", "bedrock",
            "end_portal_frame", "spawner", "fire");

    private BuildPolicy() {
    }

    public static Limits from(Config cfg) {
        List<String> list = cfg.getStringList("build.guardrails.blocked-blocks");
        Set<String> blocked = new LinkedHashSet<>();
        // An empty or missing list means the defaults; [none] blocks nothing.
        for (String id : list == null || list.isEmpty() ? DEFAULT_BLOCKED : list) {
            String bare = bareId(id);
            if (!bare.isEmpty() && !bare.equals("none")) blocked.add(bare);
        }
        return new Limits(blocked,
                cfg.getInt("build.guardrails.max-blocks", 8000),
                cfg.getInt("build.guardrails.max-size", 48),
                cfg.getInt("build.guardrails.max-distance", 64));
    }

    /** "minecraft:oak_stairs[facing=north]" -> "oak_stairs". */
    static String bareId(String spec) {
        String s = spec == null ? "" : spec.trim().toLowerCase(Locale.ROOT);
        int bracket = s.indexOf('[');
        if (bracket >= 0) s = s.substring(0, bracket);
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(colon + 1);
        return s;
    }

    /**
     * The reasons this plan may not be built, in plain words; empty when it may.
     *
     * @param positions where each block goes (absolute)
     * @param specs     the block spec for each position, same order
     * @param player    the player's block position
     * @param minY      lowest buildable y
     * @param maxY      highest buildable y
     */
    public static List<String> check(Limits limits, List<BlockPos> positions, List<String> specs,
                                     BlockPos player, int minY, int maxY) {
        List<String> out = new ArrayList<>();
        if (positions.isEmpty()) return out;

        Map<String, Integer> blockedUsed = new TreeMap<>();
        int minX = Integer.MAX_VALUE, minYb = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxYb = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (int i = 0; i < positions.size(); i++) {
            BlockPos p = positions.get(i);
            minX = Math.min(minX, p.x()); maxX = Math.max(maxX, p.x());
            minYb = Math.min(minYb, p.y()); maxYb = Math.max(maxYb, p.y());
            minZ = Math.min(minZ, p.z()); maxZ = Math.max(maxZ, p.z());
            String id = bareId(i < specs.size() ? specs.get(i) : "");
            if (limits.blocked().contains(id)) blockedUsed.merge(id, 1, Integer::sum);
        }

        for (Map.Entry<String, Integer> e : blockedUsed.entrySet()) {
            String name = e.getKey().replace('_', ' ');
            out.add("the plan uses " + e.getValue() + " " + name + "; " + name + " is blocked");
        }
        if (positions.size() > limits.maxBlocks()) {
            out.add("the plan is " + positions.size() + " blocks; the limit is " + limits.maxBlocks());
        }
        int w = maxX - minX + 1, h = maxYb - minYb + 1, d = maxZ - minZ + 1;
        int longest = Math.max(w, Math.max(h, d));
        if (longest > limits.maxSize()) {
            out.add("the build is " + w + "x" + h + "x" + d + "; the longest edge may be " + limits.maxSize());
        }
        // Distance to the nearest point of the build's box: a big build that
        // starts at the player's feet is not "far away".
        double dx = Math.max(0, Math.max(minX - player.x(), player.x() - maxX));
        double dy = Math.max(0, Math.max(minYb - player.y(), player.y() - maxYb));
        double dz = Math.max(0, Math.max(minZ - player.z(), player.z() - maxZ));
        int dist = (int) Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz));
        if (dist > limits.maxDistance()) {
            out.add("the build is " + dist + " blocks away; the limit is " + limits.maxDistance());
        }
        if (minYb < minY || maxYb > maxY) {
            out.add("the build runs past the world's height limits (y " + minY + " to " + maxY + ")");
        }
        return out;
    }

    /** The box of a plan, for the preview: {min, max}. */
    public static BlockPos[] bounds(List<BlockPos> positions) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : positions) {
            minX = Math.min(minX, p.x()); maxX = Math.max(maxX, p.x());
            minY = Math.min(minY, p.y()); maxY = Math.max(maxY, p.y());
            minZ = Math.min(minZ, p.z()); maxZ = Math.max(maxZ, p.z());
        }
        return new BlockPos[]{new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ)};
    }
}
