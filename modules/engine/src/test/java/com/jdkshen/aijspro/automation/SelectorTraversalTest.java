package com.jdkshen.aijspro.automation;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SelectorTraversalTest {

    private static final class Node {
        final String name;
        final List<Node> children = new ArrayList<>();

        Node(String name) {
            this.name = name;
        }
    }

    private static final SelectorTraversal.Tree<Node> TREE = new SelectorTraversal.Tree<Node>() {
        @Override
        public int childCount(Node node) {
            return node.children.size();
        }

        @Override
        public Node child(Node node, int index) {
            return node.children.get(index);
        }
    };

    private static Node sampleTree() {
        Node root = new Node("root");
        Node a = new Node("a");
        Node a1 = new Node("a1");
        a1.children.add(new Node("a1a"));
        a.children.add(a1);
        root.children.add(a);
        root.children.add(new Node("b"));
        return root;
    }

    private static List<String> names(List<SelectorTraversal.Match<Node>> matches) {
        List<String> names = new ArrayList<>();
        for (SelectorTraversal.Match<Node> match : matches) {
            names.add(match.node.name);
        }
        return names;
    }

    @Test
    public void shallowSiblingIsVisitedBeforeDeepDescendant() {
        List<SelectorTraversal.Match<Node>> matches = SelectorTraversal.findMatches(
                sampleTree(), TREE, (node, depth, indexInParent, path) -> true,
                Integer.MAX_VALUE, 64, 5000, () -> false);

        assertEquals(Arrays.asList("root", "a", "b", "a1", "a1a"), names(matches));
        assertEquals(0, matches.get(0).depth);
        assertArrayEquals(new int[0], matches.get(0).path);
        assertEquals(-1, matches.get(0).indexInParent);

        assertEquals(1, matches.get(1).depth);
        assertEquals(0, matches.get(1).indexInParent);
        assertArrayEquals(new int[]{0}, matches.get(1).path);

        assertEquals(1, matches.get(2).depth);
        assertEquals(1, matches.get(2).indexInParent);
        assertArrayEquals(new int[]{1}, matches.get(2).path);

        assertEquals(2, matches.get(3).depth);
        assertArrayEquals(new int[]{0, 0}, matches.get(3).path);
        assertEquals(3, matches.get(4).depth);
        assertArrayEquals(new int[]{0, 0, 0}, matches.get(4).path);
    }

    @Test
    public void matchLimitIsExactAndStopsTheWalk() {
        List<SelectorTraversal.Match<Node>> matches = SelectorTraversal.findMatches(
                sampleTree(), TREE, (node, depth, indexInParent, path) -> true,
                3, 64, 5000, () -> false);
        assertEquals(3, matches.size());
        assertEquals(Arrays.asList("root", "a", "b"), names(matches));

        List<SelectorTraversal.Match<Node>> none = SelectorTraversal.findMatches(
                sampleTree(), TREE, (node, depth, indexInParent, path) -> true,
                0, 64, 5000, () -> false);
        assertTrue(none.isEmpty());
    }

    @Test
    public void nodeBudgetCapsLevelOrderPrefixInsteadOfDeepPath() {
        List<SelectorTraversal.Match<Node>> matches = SelectorTraversal.findMatches(
                sampleTree(), TREE, (node, depth, indexInParent, path) -> true,
                Integer.MAX_VALUE, 64, 3, () -> false);
        assertEquals(Arrays.asList("root", "a", "b"), names(matches));
    }

    @Test
    public void canonicalAlgorithmIsCaseInsensitiveAndRejectsUnknown() {
        assertEquals("BFS", SelectorTraversal.canonicalAlgorithm("bfs"));
        assertEquals("DFS", SelectorTraversal.canonicalAlgorithm("DfS"));
        try {
            SelectorTraversal.canonicalAlgorithm("spiral");
            fail();
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("spiral"));
        }
        try {
            SelectorTraversal.canonicalAlgorithm(null);
            fail();
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("unknown algorithm"));
        }
    }
}
