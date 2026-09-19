package com.gadgetman.jarvis.nav;

import com.gadgetman.jarvis.core.world.BlockPos;

import java.util.List;

/**
 * A route from where the search started to its goal, or as near as the
 * budget allowed.
 *
 * @param steps   the nodes to visit in order, the start excluded
 * @param partial true when the budget ran out and this ends at the node
 *                nearest the goal rather than at the goal
 * @param nodesExpanded how much work the search did, for tuning
 */
public record Path(List<Step> steps, boolean partial, int nodesExpanded) {

    /** One node and how to reach it from the one before. */
    public record Step(BlockPos pos, MoveType via) { }

    public static final Path EMPTY = new Path(List.of(), true, 0);

    public boolean isEmpty() {
        return steps.isEmpty();
    }

    public int size() {
        return steps.size();
    }

    public Step step(int index) {
        return steps.get(index);
    }

    public BlockPos end() {
        return steps.isEmpty() ? null : steps.get(steps.size() - 1).pos();
    }
}
