package com.jdkshen.aijspro.ui.floating.layoutinspector;

import org.junit.Test;
import com.google.gson.Gson;
import java.util.*;

public class LayoutSnapshotTest {
    private LayoutSnapshot.Document tree() {
        LayoutSnapshot.Document doc = new LayoutSnapshot.Document();
        doc.format = "aijs-layout"; doc.version = 1;
        for (int parent : new int[]{-1, 0, 0, 1}) {
            LayoutSnapshot.Record r = new LayoutSnapshot.Record();
            r.parent = parent; r.screen = new int[]{-1, 0, 10, 20}; r.local = r.screen.clone();
            r.properties = new HashMap<>(); doc.nodes.add(r);
        }
        return doc;
    }
    @Test public void acceptsFlatTreeAfterJsonRoundTrip() {
        LayoutSnapshot.validate(new Gson().fromJson(new Gson().toJson(tree()), LayoutSnapshot.Document.class));
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsCycle() {
        LayoutSnapshot.Document doc = tree(); doc.nodes.get(1).parent = 3; LayoutSnapshot.validate(doc);
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsSecondRoot() {
        LayoutSnapshot.Document doc = tree(); doc.nodes.get(2).parent = -1; LayoutSnapshot.validate(doc);
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsTruncatedCoordinates() {
        LayoutSnapshot.Document doc = tree(); doc.nodes.get(2).screen = new int[]{1, 2}; LayoutSnapshot.validate(doc);
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsUnmarkedOrFutureFile() {
        LayoutSnapshot.Document doc = tree(); doc.version = 2; LayoutSnapshot.validate(doc);
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsMissingFormat() {
        LayoutSnapshot.Document doc = tree(); doc.format = null; LayoutSnapshot.validate(doc);
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsDeepChainBeforeRendering() {
        LayoutSnapshot.Document doc = tree(); doc.nodes.clear();
        for (int i = 0; i <= LayoutSnapshot.MAX_DEPTH + 1; i++) {
            LayoutSnapshot.Record r = new LayoutSnapshot.Record(); r.parent = i - 1;
            r.screen = new int[4]; r.local = new int[4]; r.properties = new HashMap<>(); doc.nodes.add(r);
        }
        LayoutSnapshot.validate(doc);
    }
}
