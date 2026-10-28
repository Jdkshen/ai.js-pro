package com.stardust.automator;
import org.junit.Test;
import static org.junit.Assert.*;
public class UiObjectCollectionArgumentsTest {
    @Test public void emptyNativeCollectionStillRejectsInvalidActionArguments() {
        UiObjectCollection c=UiObjectCollection.Companion.getEMPTY();
        Runnable[] bad={()->c.setSelection(-1,1),()->c.setSelection(3,1),()->c.scrollTo(-1,0),
                ()->c.scrollTo(0,-1),()->c.setProgress(Float.NaN),()->c.setProgress(Float.POSITIVE_INFINITY)};
        for(Runnable action:bad) { try {action.run();fail();} catch(IllegalArgumentException expected){} }
    }
}
