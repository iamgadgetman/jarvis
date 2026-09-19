package com.gadgetman.jarvis.building;

import com.gadgetman.jarvis.core.world.BlockPos;
import com.gadgetman.jarvis.core.world.Ids;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A build plan as a short list of shapes, expanded into blocks here.
 *
 * <p>The first JSON planner asked the model for every block by hand:
 * {@code {"x":3,"y":2,"z":0,"material":"minecraft:oak_planks"}}, a few hundred
 * times over. Models are bad at that. They tire of it a wall and a half in,
 * they leave the door as a "gap" because a door is two blocks with states,
 * and the reply is long enough to run into the output cap. The script
 * planner fixed all of this with JavaScript, but that needs GraalJS, which
 * the Fabric mod does not carry.
 *
 * <p>So this is the middle: the model writes a dozen operations,
 * {@code fill}, {@code walls}, {@code roof}, {@code door}, {@code bed}, and
 * core does the enumerating. A cottage is a few hundred bytes of JSON, a
 * wall is whole by construction, and the pitched roof is one op that
 * follows the same recipe the script prompt teaches. Later ops overwrite
 * earlier ones at the same spot, so a doorway is a wall and then a door.
 *
 * <p>Coordinates are relative to the build origin. As for the script
 * planner, y is the space the player stands in and the ground is y-1.
 */
public final class ShapePlan {

    /** One block of the expanded plan, relative to the origin. */
    public record Block(int x, int y, int z, String spec) { }

    /** The expanded blocks and anything worth telling the log. */
    public record Result(List<Block> blocks, List<String> warnings, boolean truncated) { }

    /** Widest any one op may be on an axis; bigger is a typo or a runaway. */
    public static final int MAX_SPAN = 128;

    private ShapePlan() { }

    /**
     * @param ops      the plan's {@code ops} array
     * @param maxBlocks stop expanding past this many distinct positions
     */
    public static Result expand(JSONArray ops, int maxBlocks) {
        Map<BlockPos, String> out = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        boolean truncated = false;

        for (int i = 0; i < ops.length() && !truncated; i++) {
            JSONObject op = ops.optJSONObject(i);
            if (op == null) continue;
            String kind = op.optString("op", "").trim().toLowerCase(Locale.ROOT);
            try {
                switch (kind) {
                    case "fill", "box", "solid" -> box(out, op, Mode.SOLID);
                    case "walls" -> box(out, op, Mode.WALLS);
                    case "hollow", "shell" -> box(out, op, Mode.HOLLOW);
                    case "outline" -> box(out, op, Mode.OUTLINE);
                    case "clear", "air" -> box(out, op.put("block", "minecraft:air"), Mode.SOLID);
                    case "set", "block", "place" -> set(out, op, warnings);
                    case "door" -> door(out, op, warnings);
                    case "bed" -> bed(out, op);
                    case "roof" -> roof(out, op);
                    default -> warnings.add("op " + (i + 1) + ": unknown op '" + kind + "' skipped");
                }
            } catch (IllegalArgumentException e) {
                warnings.add("op " + (i + 1) + " (" + kind + "): " + e.getMessage());
            }
            if (out.size() > maxBlocks) {
                warnings.add("plan exceeds " + maxBlocks + " blocks; stopped after op " + (i + 1));
                truncated = true;
            }
        }

        List<Block> blocks = new ArrayList<>(out.size());
        for (Map.Entry<BlockPos, String> e : out.entrySet()) {
            BlockPos p = e.getKey();
            blocks.add(new Block(p.x(), p.y(), p.z(), e.getValue()));
            if (blocks.size() >= maxBlocks) break;
        }
        return new Result(blocks, warnings, truncated);
    }

    private enum Mode { SOLID, WALLS, HOLLOW, OUTLINE }

    private static void box(Map<BlockPos, String> out, JSONObject op, Mode mode) {
        int[] a = corner(op, "from");
        int[] b = corner(op, "to");
        String block = block(op, null);
        int x0 = Math.min(a[0], b[0]), x1 = Math.max(a[0], b[0]);
        int y0 = Math.min(a[1], b[1]), y1 = Math.max(a[1], b[1]);
        int z0 = Math.min(a[2], b[2]), z1 = Math.max(a[2], b[2]);
        checkSpan(x0, x1, y0, y1, z0, z1);

        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    boolean xEdge = x == x0 || x == x1;
                    boolean yEdge = y == y0 || y == y1;
                    boolean zEdge = z == z0 || z == z1;
                    boolean keep = switch (mode) {
                        case SOLID -> true;
                        case WALLS -> xEdge || zEdge;
                        case HOLLOW -> xEdge || yEdge || zEdge;
                        case OUTLINE -> (xEdge && yEdge) || (xEdge && zEdge) || (yEdge && zEdge);
                    };
                    if (keep) out.put(new BlockPos(x, y, z), block);
                }
            }
        }
    }

    private static void set(Map<BlockPos, String> out, JSONObject op, List<String> warnings) {
        int[] at = corner(op, "at");
        String block = block(op, null);
        BlockPos pos = new BlockPos(at[0], at[1], at[2]);
        // Glass goes in a wall. A pane one block off the wall's line hangs in
        // the air beside it, which is the "front slightly messed up" look.
        if (block.contains("glass")) pos = snapIntoWall(out, pos, "window", warnings);
        out.put(pos, block);
    }

    /** Two halves, stacked, agreeing on facing and hinge. */
    private static void door(Map<BlockPos, String> out, JSONObject op, List<String> warnings) {
        int[] at = corner(op, "at");
        String facing = facing(op, "north");
        String id = bareId(block(op, "minecraft:oak_door"));
        String hinge = op.optString("hinge", "left").trim().toLowerCase(Locale.ROOT);
        if (!hinge.equals("left") && !hinge.equals("right")) hinge = "left";
        BlockPos lower = snapIntoWall(out, new BlockPos(at[0], at[1], at[2]), "door", warnings);
        out.put(lower, id + "[facing=" + facing + ",half=lower,hinge=" + hinge + "]");
        out.put(lower.offset(0, 1, 0), id + "[facing=" + facing + ",half=upper,hinge=" + hinge + "]");
    }

    /**
     * Where a door or a window really belongs: in the wall the model meant.
     * Models get the wall's line off by one now and then, and a door beside
     * its doorway is the most visible mistake a plan can make. If the spot
     * is not in something already placed but a horizontal neighbour is, the
     * fitting moves to the neighbour; otherwise it stays where asked.
     */
    private static BlockPos snapIntoWall(Map<BlockPos, String> out, BlockPos pos, String what, List<String> warnings) {
        if (isSolid(out, pos)) return pos;
        BlockPos[] around = {
                pos.offset(0, 0, -1), pos.offset(0, 0, 1), pos.offset(-1, 0, 0), pos.offset(1, 0, 0) };
        for (BlockPos n : around) {
            if (isSolid(out, n)) {
                warnings.add(what + " at " + pos.x() + "," + pos.y() + "," + pos.z()
                        + " was beside the wall; moved into it at " + n.x() + "," + n.y() + "," + n.z());
                return n;
            }
        }
        return pos;
    }

    private static boolean isSolid(Map<BlockPos, String> out, BlockPos pos) {
        String s = out.get(pos);
        return s != null && !s.equals("minecraft:air") && !s.contains("glass") && !s.contains("door");
    }

    /** Foot at {@code at}, head one block along {@code facing}. */
    private static void bed(Map<BlockPos, String> out, JSONObject op) {
        int[] at = corner(op, "at");
        String facing = facing(op, "north");
        String id = bareId(block(op, "minecraft:red_bed"));
        int[] d = offset(facing);
        out.put(new BlockPos(at[0], at[1], at[2]), id + "[facing=" + facing + ",part=foot]");
        out.put(new BlockPos(at[0] + d[0], at[1], at[2] + d[1]), id + "[facing=" + facing + ",part=head]");
    }

    /**
     * A pitched roof over the rectangle {@code from}..{@code to}, whose y is
     * the first course: one above the wall tops. Courses of stairs step in
     * and up from each eave to a ridge of the gable block, with the two end
     * triangles closed. Each course overhangs the ends of the ridge by one,
     * as eaves do. The ridge runs along the longer side unless {@code ridge}
     * says "x" or "z".
     */
    private static void roof(Map<BlockPos, String> out, JSONObject op) {
        int[] a = corner(op, "from");
        int[] b = corner(op, "to");
        int x0 = Math.min(a[0], b[0]), x1 = Math.max(a[0], b[0]);
        int z0 = Math.min(a[2], b[2]), z1 = Math.max(a[2], b[2]);
        int y0 = Math.min(a[1], b[1]);
        checkSpan(x0, x1, y0, y0, z0, z1);
        String stairs = bareId(block(op, "minecraft:oak_stairs"));
        String gable = Ids.of(op.optString("gable", "minecraft:oak_planks").trim().toLowerCase(Locale.ROOT));
        String ridge = op.optString("ridge", "").trim().toLowerCase(Locale.ROOT);
        boolean alongX = ridge.equals("x") || (ridge.isEmpty() && (x1 - x0) >= (z1 - z0));

        if (alongX) {
            int d = z1 - z0 + 1;
            int mid = (d - 1) / 2;
            for (int i = 0; i < mid; i++) {
                int yy = y0 + i;
                for (int z = z0 + i + 1; z <= z1 - i - 1; z++) {
                    out.put(new BlockPos(x0, yy, z), gable);
                    out.put(new BlockPos(x1, yy, z), gable);
                }
                for (int x = x0 - 1; x <= x1 + 1; x++) {
                    // z0 is the north eave: the slope descends toward the
                    // north, so its stairs face south, back up to the ridge.
                    out.put(new BlockPos(x, yy, z0 + i), stairs + "[facing=south]");
                    out.put(new BlockPos(x, yy, z1 - i), stairs + "[facing=north]");
                }
            }
            for (int x = x0 - 1; x <= x1 + 1; x++) {
                for (int z = z0 + mid; z <= z1 - mid; z++) {
                    out.put(new BlockPos(x, y0 + mid, z), gable);
                }
            }
        } else {
            int d = x1 - x0 + 1;
            int mid = (d - 1) / 2;
            for (int i = 0; i < mid; i++) {
                int yy = y0 + i;
                for (int x = x0 + i + 1; x <= x1 - i - 1; x++) {
                    out.put(new BlockPos(x, yy, z0), gable);
                    out.put(new BlockPos(x, yy, z1), gable);
                }
                for (int z = z0 - 1; z <= z1 + 1; z++) {
                    out.put(new BlockPos(x0 + i, yy, z), stairs + "[facing=east]");
                    out.put(new BlockPos(x1 - i, yy, z), stairs + "[facing=west]");
                }
            }
            for (int z = z0 - 1; z <= z1 + 1; z++) {
                for (int x = x0 + mid; x <= x1 - mid; x++) {
                    out.put(new BlockPos(x, y0 + mid, z), gable);
                }
            }
        }
    }

    // ==================== FIELD READING ====================

    /** {@code [x, y, z]} or {@code {"x":..,"y":..,"z":..}}. */
    private static int[] corner(JSONObject op, String key) {
        Object v = op.opt(key);
        if (v instanceof JSONArray arr && arr.length() >= 3) {
            return new int[] { arr.optInt(0), arr.optInt(1), arr.optInt(2) };
        }
        if (v instanceof JSONObject o && o.has("x") && o.has("y") && o.has("z")) {
            return new int[] { o.optInt("x"), o.optInt("y"), o.optInt("z") };
        }
        throw new IllegalArgumentException("missing '" + key + "' (an [x, y, z] triple)");
    }

    private static String block(JSONObject op, String def) {
        String s = op.optString("block", op.optString("material", ""));
        if (s.isBlank()) {
            if (def == null) throw new IllegalArgumentException("missing 'block'");
            s = def;
        }
        return Ids.of(s.trim().toLowerCase(Locale.ROOT));
    }

    /** The id without any {@code [state]} the model attached, so ours can be added. */
    private static String bareId(String spec) {
        int bracket = spec.indexOf('[');
        return bracket < 0 ? spec : spec.substring(0, bracket);
    }

    private static String facing(JSONObject op, String def) {
        String f = op.optString("facing", def).trim().toLowerCase(Locale.ROOT);
        return switch (f) {
            case "north", "south", "east", "west" -> f;
            default -> def;
        };
    }

    /** dx, dz for a horizontal direction. */
    private static int[] offset(String facing) {
        return switch (facing) {
            case "south" -> new int[] { 0, 1 };
            case "east" -> new int[] { 1, 0 };
            case "west" -> new int[] { -1, 0 };
            default -> new int[] { 0, -1 };
        };
    }

    private static void checkSpan(int x0, int x1, int y0, int y1, int z0, int z1) {
        if (x1 - x0 >= MAX_SPAN || y1 - y0 >= MAX_SPAN || z1 - z0 >= MAX_SPAN) {
            throw new IllegalArgumentException("spans more than " + MAX_SPAN + " blocks; skipped");
        }
    }
}
