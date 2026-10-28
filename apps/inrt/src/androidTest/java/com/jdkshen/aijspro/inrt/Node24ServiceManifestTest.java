package com.jdkshen.aijspro.inrt;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.content.pm.ActivityInfo;
import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;

/** Check the merged, installed manifest rather than assuming declarations survive merging. */
@RunWith(AndroidJUnit4.class)
public class Node24ServiceManifestTest {
    @Test public void nodeServicesArePrivateAndInExpectedProcesses() throws Exception {
        Context context = InstrumentationRegistry.getTargetContext();
        String[][] services = {
                {"org.autojs.source.engine.android.Node24Service", ":node24"},
                {"org.autojs.source.engine.android.Node24PoolService1", ":node24_1"},
                {"org.autojs.source.engine.android.Node24PoolService2", ":node24_2"},
                {"org.autojs.source.engine.android.Node24PoolService3", ":node24_3"},
                {"com.stardust.autojs.engine.node24.NodeEngineBridgeService", ""},
                {"com.stardust.autojs.engine.node24.NodeEngineBridgeScriptService", ":script"},
                {"com.jdkshen.aijspro.automation.AutomationHostService", ""}
        };
        for (String[] service : services) {
            ServiceInfo info = context.getPackageManager().getServiceInfo(
                    new ComponentName(context.getPackageName(), service[0]), 0);
            assertFalse(service[0], info.exported);
            assertEquals(service[0], context.getPackageName() + service[1], info.processName);
        }
    }

    @Test public void nodeUiActivitiesArePrivateThemedAndInTheirSlotProcesses() throws Exception {
        Context context = InstrumentationRegistry.getTargetContext();
        for (int slot = 1; slot <= 3; slot++) {
            String name = "com.jdkshen.aijspro.engine.NodeUiLocalActivity$Slot" + slot;
            ActivityInfo info = context.getPackageManager().getActivityInfo(
                    new ComponentName(context.getPackageName(), name), 0);
            assertFalse(name, info.exported);
            assertEquals(name, context.getPackageName() + ":node24_" + slot, info.processName);
            assertNotEquals(name, 0, info.theme);
        }
    }
}
