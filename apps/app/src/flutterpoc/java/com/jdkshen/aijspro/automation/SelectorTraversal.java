package com.jdkshen.aijspro.automation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure, Android-free BFS traversal used by {@link AutomationHostService} for selector
 * {@code algorithm("BFS")}.
 *
 * <p>The host's default selector order is DFS pre-order. BFS is level-order: siblings at a
 * shallower depth are always returned before any deeper descendant. Each visit carries the same
 * {@code depth}/{@code path}/{@code indexInParent} semantics as the host DFS walk (root depth 0,
 * empty path, indexInParent -1; child {@code i} gets {@code parent.path + i}).
 *
 * <p>Budgets are applied while traversing, not after collecting: at most {@code max} matches, at
 * most {@code maxDepth} levels, at most {@code maxNodes} scheduled nodes, and the caller's stop
 * signal (deadline). A truncated BFS prefix is therefore still in true level order, unlike
 * re-sorting a capped DFS dump.
 */
final class SelectorTraversal {

    static final String ALGORITHM_BFS = "BFS";
    static final String ALGORITHM_DFS = "DFS";

    private SelectorTraversal() {
    }

    /** Case-insensitive BFS/DFS normalization; null or unknown values fail loudly. */
    static String canonicalAlgorithm(String algorithm) {
        if (ALGORITHM_BFS.equalsIgnoreCase(algorithm)) {
            return ALGORITHM_BFS;
        }
        if (ALGORITHM_DFS.equalsIgnoreCase(algorithm)) {
            return ALGORITHM_DFS;
        }
        throw new IllegalArgumentException("unknown algorithm: " + algorithm);
    }

    interface Tree<N> {
        int childCount(N node);

        N child(N node, int index);
    }

    interface Matcher<N> {
        boolean matches(N node, int depth, int indexInParent, int[] path);
    }

    /** Caller-owned cancellation/deadline check; return true to stop before the next visit. */
    interface StopSignal {
        boolean stopped();
    }

    static final class Match<N> {
        final N node;
        final int depth;
        final int indexInParent;
        final int[] path;

        private Match(N node, int depth, int indexInParent, int[] path) {
            this.node = node;
            this.depth = depth;
            this.indexInParent = indexInParent;
            this.path = path;
        }
    }

    private static final class Frame<N> {
        final N node;
        final int depth;
        final int indexInParent;
        final int[] path;

        Frame(N node, int depth, int indexInParent, int[] path) {
            this.node = node;
            this.depth = depth;
            this.indexInParent = indexInParent;
            this.path = path;
        }
    }

    /**
     * Breadth-first selector walk. The returned list is a true level-order prefix bounded by
     * {@code max}, {@code maxNodes}, {@code maxDepth} and {@code stopSignal}.
     */
    static <N> List<Match<N>> findMatches(N root, Tree<N> tree, Matcher<N> matcher,
                                          int max, int maxDepth, int maxNodes,
                                          StopSignal stopSignal) {
        List<Match<N>> matches = new ArrayList<>();
        if (root == null || tree == null || matcher == null || max <= 0
                || maxDepth <= 0 || maxNodes <= 0) {
            return matches;
        }
        ArrayDeque<Frame<N>> queue = new ArrayDeque<>();
        queue.addLast(new Frame<>(root, 0, -1, new int[0]));
        int scheduled = 1;
        while (!queue.isEmpty()) {
            if (stopSignal != null && stopSignal.stopped()) {
                break;
            }
            Frame<N> frame = queue.removeFirst();
            if (matcher.matches(frame.node, frame.depth, frame.indexInParent, frame.path)) {
                matches.add(new Match<>(frame.node, frame.depth, frame.indexInParent, frame.path));
                if (matches.size() >= max) {
                    break;
                }
            }
            if (frame.depth >= maxDepth - 1) {
                continue;
            }
            int childCount = tree.childCount(frame.node);
            for (int i = 0; i < childCount; i++) {
                if (scheduled >= maxNodes) {
                    break;
                }
                if (stopSignal != null && stopSignal.stopped()) {
                    return matches;
                }
                N child = tree.child(frame.node, i);
                if (child == null) {
                    continue;
                }
                int[] path = new int[frame.depth + 1];
                System.arraycopy(frame.path, 0, path, 0, frame.depth);
                path[frame.depth] = i;
                queue.addLast(new Frame<>(child, frame.depth + 1, i, path));
                scheduled++;
            }
        }
        return matches;
    }
}
