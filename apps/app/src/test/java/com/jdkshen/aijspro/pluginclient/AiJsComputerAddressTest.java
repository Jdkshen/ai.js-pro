package com.jdkshen.aijspro.pluginclient;

import org.junit.Test;
import static org.junit.Assert.*;

public class AiJsComputerAddressTest {
    @Test public void ownExtensionDefaultAndCustomPort() {
        AiJsComputerAddress normal = AiJsComputerAddress.parse(" 192.168.1.100 ", null);
        assertEquals("192.168.1.100", normal.host);
        assertEquals(8327, normal.port);
        assertEquals("desktop.local:9000", AiJsComputerAddress.parse("desktop.local:9000", null).displayAddress());
        assertEquals(9001, AiJsComputerAddress.parse("desktop.local:9000", 9001).port);
    }

    @Test public void ipv6DoesNotMistakeLastSegmentForPort() {
        assertEquals("[::1]:8327", AiJsComputerAddress.parse("::1", null).displayAddress());
        assertEquals("[2001:db8::5]:9000", AiJsComputerAddress.parse("[2001:db8::5]:9000", null).displayAddress());
    }

    @Test public void rejectsInvalidInputBeforeConnecting() {
        for (String input : new String[]{"", "256.1.1.1", "1.1.1", "host:", "host:0", "host:65536", "host:NaN",
                "host/path", "ws://host:21029", "a b", "host..local", "[::1", "[:::1]:8327", "[::1]:", "host:1.5"}) {
            try { AiJsComputerAddress.parse(input, null); fail("Accepted: " + input); }
            catch (IllegalArgumentException expected) { }
        }
    }
}
