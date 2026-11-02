package com.jdkshen.aijspro.tool;

import org.junit.Test;
import static org.junit.Assert.*;

public class AccessibilityServiceComponentsTest {
    @Test public void enablesWhenSettingIsMissing() {
        for (String value : new String[]{null, "", "null"})
            assertEquals("app/app.Service", AccessibilityServiceComponents.enable(value, "app/app.Service"));
    }

    @Test public void preservesOtherServicesAndTheirOrder() {
        assertEquals("other/.Reader:third/third.Service:app/app.Service",
                AccessibilityServiceComponents.enable("other/.Reader:third/third.Service", "app/app.Service"));
    }

    @Test public void shortenedComponentIsNotDuplicated() {
        String value = "other/.Reader:app/.Service:third/third.Service";
        assertEquals(value, AccessibilityServiceComponents.enable(value, "app/app.Service"));
        assertEquals("app/app.Service", AccessibilityServiceComponents.enable("app/app.Service", "app/.Service"));
    }

    @Test public void substringAndPrefixDoNotCountAsTheSameService() {
        assertEquals("app/app.ServiceExtra:app/app.Service", AccessibilityServiceComponents.enable("app/app.ServiceExtra", "app/app.Service"));
    }
}
