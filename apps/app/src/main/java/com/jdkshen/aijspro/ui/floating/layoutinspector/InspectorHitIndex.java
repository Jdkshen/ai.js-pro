package com.jdkshen.aijspro.ui.floating.layoutinspector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Immutable screen-coordinate BVH. It indexes geometry, never accessibility parent bounds. */
final class InspectorHitIndex<T> {
    private static final int LEAF_SIZE = 12;

    static final class Item<T> {
        final T value;
        final int left, top, right, bottom;
        // Each span is at most 2^32-1. Their exact product fits an unsigned long.
        final long area;

        Item(T value, int left, int top, int right, int bottom) {
            this.value = Objects.requireNonNull(value);
            this.left = left; this.top = top; this.right = right; this.bottom = bottom;
            area = ((long) right - left) * ((long) bottom - top);
        }

        boolean isValid() { return right > left && bottom > top; }
        boolean contains(int x, int y) {
            return x >= left && x < right && y >= top && y < bottom;
        }
    }

    /** Optional deterministic operation counts for tests and diagnostics, not a timing benchmark. */
    static final class QueryStats {
        int visitedBranches, testedItems;
    }

    private static final class Indexed<T> {
        final Item<T> item;
        final int order;
        Indexed(Item<T> item, int order) { this.item = item; this.order = order; }
    }

    private static final class Branch {
        int left, top, right, bottom, start, end;
        Branch first, second;
        boolean contains(int x, int y) {
            return x >= left && x < right && y >= top && y < bottom;
        }
    }

    private final Indexed<T>[] items;
    private final Branch root;
    private final Comparator<Indexed<T>> byX = (a, b) -> {
        int center = Long.compare((long) a.item.left + a.item.right,
                (long) b.item.left + b.item.right);
        return center != 0 ? center : Integer.compare(a.order, b.order);
    };
    private final Comparator<Indexed<T>> byY = (a, b) -> {
        int center = Long.compare((long) a.item.top + a.item.bottom,
                (long) b.item.top + b.item.bottom);
        return center != 0 ? center : Integer.compare(a.order, b.order);
    };

    @SuppressWarnings("unchecked")
    InspectorHitIndex(List<Item<T>> source) {
        Objects.requireNonNull(source);
        ArrayList<Indexed<T>> valid = new ArrayList<>(source.size());
        for (int i = 0; i < source.size(); i++) {
            Item<T> item = Objects.requireNonNull(source.get(i));
            if (item.isValid()) valid.add(new Indexed<>(item, i));
        }
        items = valid.toArray((Indexed<T>[]) new Indexed<?>[valid.size()]);
        root = items.length == 0 ? null : build(0, items.length);
    }

    T find(int x, int y) { return find(x, y, null); }

    T find(int x, int y, QueryStats stats) {
        if (stats != null) { stats.visitedBranches = 0; stats.testedItems = 0; }
        Indexed<T> best = search(root, x, y, null, stats);
        return best == null ? null : best.item.value;
    }

    private Branch build(int start, int end) {
        Branch node = new Branch();
        node.start = start; node.end = end;
        Item<T> first = items[start].item;
        node.left = first.left; node.top = first.top;
        node.right = first.right; node.bottom = first.bottom;
        for (int i = start + 1; i < end; i++) {
            Item<T> item = items[i].item;
            node.left = Math.min(node.left, item.left); node.top = Math.min(node.top, item.top);
            node.right = Math.max(node.right, item.right); node.bottom = Math.max(node.bottom, item.bottom);
        }
        if (end - start > LEAF_SIZE) {
            boolean splitX = (long) node.right - node.left >= (long) node.bottom - node.top;
            Arrays.sort(items, start, end, splitX ? byX : byY);
            int middle = start + (end - start) / 2;
            node.first = build(start, middle); node.second = build(middle, end);
        }
        return node;
    }

    private Indexed<T> search(Branch branch, int x, int y, Indexed<T> best, QueryStats stats) {
        if (branch == null) return best;
        if (stats != null) stats.visitedBranches++;
        if (!branch.contains(x, y)) return best;
        if (branch.first != null) {
            best = search(branch.first, x, y, best, stats);
            return search(branch.second, x, y, best, stats);
        }
        for (int i = branch.start; i < branch.end; i++) {
            Indexed<T> candidate = items[i];
            if (stats != null) stats.testedItems++;
            if (!candidate.item.contains(x, y)) continue;
            int compared = best == null ? -1 : Long.compareUnsigned(candidate.item.area, best.item.area);
            // The previous breadth-first scan selected the later node for equal areas.
            if (best == null || compared < 0 || (compared == 0 && candidate.order > best.order)) best = candidate;
        }
        return best;
    }
}
