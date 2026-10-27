package com.jdkshen.aijspro.external.foreground;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class LiveUpdateRouteTest {
    private JSONObject live(boolean api, boolean declared, Boolean allowed) throws Exception {
        return new JSONObject().put("liveUpdates", new JSONObject()
                .put("apiAvailable", api).put("permissionDeclared", declared)
                .put("canPostPromotedNotifications", allowed == null ? JSONObject.NULL : allowed));
    }
    @Test public void unregisteredXiaomiAppsCanRequestTheAllowedPublicRoute() throws Exception {
        JSONObject p = live(true, true, true);
        p.put("xiaomiRegistration", new JSONObject().put("appIdConfigured", false));
        assertTrue(LiveUpdateSupport.usePlatformRoute(p));
    }
    @Test public void userDemotionIsNotTreatedAsPermissionToPromote() throws Exception {
        assertFalse(LiveUpdateSupport.usePlatformRoute(live(true, true, false)));
    }
    @Test public void anUnknownPermissionNeverSelectsThePromotedRoute() throws Exception {
        assertFalse(LiveUpdateSupport.usePlatformRoute(live(true, true, null)));
        assertFalse(LiveUpdateSupport.usePlatformRoute(new JSONObject()));
    }
    @Test public void missingManifestDeclarationAndOlderFrameworkKeepTheFallback() throws Exception {
        assertFalse(LiveUpdateSupport.usePlatformRoute(live(true, false, true)));
        assertFalse(LiveUpdateSupport.usePlatformRoute(live(false, true, true)));
    }
}
