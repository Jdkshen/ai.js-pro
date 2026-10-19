package com.jdkshen.aijspro.pluginclient;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 电脑连接纯 JVM 测试：地址/端口/协议解析、状态选择、出站 generation、
 * mDNS 回调（含晚到的旧回调）。
 */
public class ProComputerLinkTest {

    // ------------------------------------------------------------ target

    @Test
    public void plainHostDefaultsToWsRpc21029() throws Exception {
        ProComputerLink.Target target = ProComputerLink.parseTarget("192.168.1.20");
        assertEquals(ProComputerLink.PROTOCOL_WS_RPC, target.protocol);
        assertEquals(ProComputerLink.WS_RPC_CLIENT_PORT, target.port);
        assertFalse(target.isLegacy());
        assertEquals("192.168.1.20", target.displayAddress());
        assertEquals("ws://192.168.1.20:21029", target.endpointUri());
    }

    @Test
    public void explicit8327KeepsLegacy() throws Exception {
        ProComputerLink.Target target = ProComputerLink.parseTarget("192.168.1.20:8327");
        assertEquals(ProComputerLink.PROTOCOL_LEGACY, target.protocol);
        assertEquals(ProComputerLink.LEGACY_CLIENT_PORT, target.port);
        assertTrue(target.isLegacy());
        assertEquals("192.168.1.20:8327", target.displayAddress());
        assertEquals("tcp://192.168.1.20:8327", target.endpointUri());
    }

    @Test
    public void customPortUsesWsRpc() throws Exception {
        ProComputerLink.Target target = ProComputerLink.parseTarget("pc.lan:30001");
        assertEquals(ProComputerLink.PROTOCOL_WS_RPC, target.protocol);
        assertEquals(30001, target.port);
        assertEquals("pc.lan:30001", target.displayAddress());
    }

    @Test
    public void explicitProtocolIsAccepted() throws Exception {
        ProComputerLink.Target legacy = ProComputerLink.parseTarget("pc.lan", "legacy");
        assertEquals(ProComputerLink.PROTOCOL_LEGACY, legacy.protocol);
        assertEquals(8327, legacy.port);
        assertTrue(legacy.explicitProtocol);

        ProComputerLink.Target ws = ProComputerLink.parseTarget("pc.lan", "ws_rpc", 30002);
        assertEquals(ProComputerLink.PROTOCOL_WS_RPC, ws.protocol);
        assertEquals(30002, ws.port);

        // 显式协议优先于端口推断，允许未来把 ws-rpc 放到 8327。
        ProComputerLink.Target wsOn8327 = ProComputerLink.parseTarget("pc.lan:8327", "ws-rpc");
        assertEquals(ProComputerLink.PROTOCOL_WS_RPC, wsOn8327.protocol);
        assertEquals(8327, wsOn8327.port);
    }

    @Test
    public void ipv6BareAndBracketed() throws Exception {
        ProComputerLink.Target bare = ProComputerLink.parseTarget("::1");
        assertEquals("::1", bare.host);
        assertEquals(21029, bare.port);
        assertTrue(bare.isIpv6());
        assertEquals("[::1]:21029", bare.displayAddress());
        assertEquals("ws://[::1]:21029", bare.endpointUri());

        ProComputerLink.Target bracket = ProComputerLink.parseTarget("[fe80::1]:8327");
        assertEquals("fe80::1", bracket.host);
        assertEquals(8327, bracket.port);
        assertEquals(ProComputerLink.PROTOCOL_LEGACY, bracket.protocol);
        assertEquals("[fe80::1]:8327", bracket.displayAddress());
    }

    @Test
    public void invalidTargetsHaveActionableChineseReasons() {
        assertBad("", "请输入电脑 IP");
        assertBad("   ", "请输入电脑 IP");
        assertBad("ws://192.168.1.2", "不要带 ws://");
        assertBad("192.168.1 2", "不能包含空格");
        assertBad("192.168.1.300", "0 到 255");
        assertBad("192.168.1", "a.b.c.d");
        assertBad("host:0", "1 到 65535");
        assertBad("host:65536", "1 到 65535");
        assertBad("host:abc", "端口格式");
        assertBad("[::1", "右方括号");
        assertBad("[::1]:0", "1 到 65535");
        assertBad("-bad.example", "电脑地址格式");
    }

    @Test
    public void legacyWithCustomPortIsRejected() {
        assertBad("pc.lan:30001", "legacy 调试通道只支持电脑端 8327 端口",
                ProComputerLink.PROTOCOL_LEGACY);
    }

    @Test
    public void unknownProtocolAndDuplicatePortAreRejected() {
        assertBad("pc.lan", "未知的连接协议", "bogus");
        try {
            ProComputerLink.parseTarget("pc.lan:21029", null, 21029);
            fail("expected duplicate port rejection");
        } catch (ProComputerLink.InvalidTargetException expected) {
            assertTrue(expected.getMessage().contains("端口已在地址中填写"));
        }
    }

    @Test
    public void sameTargetIgnoresHostCase() throws Exception {
        ProComputerLink.Target a = ProComputerLink.parseTarget("PC.LAN");
        ProComputerLink.Target b = ProComputerLink.parseTarget("pc.lan");
        assertTrue(a.sameAs(b));
        assertFalse(a.sameAs(ProComputerLink.parseTarget("pc.lan:21030")));
    }

    // ------------------------------------------------------------ status

    @Test
    public void preferredProtocolFollowsOutboundThenListenerThenLegacy() {
        // 真实活动出站协议优先。
        assertEquals(ProComputerLink.PROTOCOL_WS_RPC,
                ProComputerLink.preferredProtocol(true, true, true, true));
        assertEquals(ProComputerLink.PROTOCOL_LEGACY,
                ProComputerLink.preferredProtocol(false, true, true, true));
        // 没有出站时，ws-rpc 监听优先。
        assertEquals(ProComputerLink.PROTOCOL_WS_RPC,
                ProComputerLink.preferredProtocol(false, false, true, true));
        // 再 legacy 监听，最后 legacy 回退。
        assertEquals(ProComputerLink.PROTOCOL_LEGACY,
                ProComputerLink.preferredProtocol(false, false, false, true));
        assertEquals(ProComputerLink.PROTOCOL_LEGACY,
                ProComputerLink.preferredProtocol(false, false, false, false));
    }

    @Test
    public void stateCodesAndNamesMatchDartContract() {
        assertEquals(3, ProComputerLink.stateCode(true, true, true));
        assertEquals(2, ProComputerLink.stateCode(false, true, true));
        assertEquals(1, ProComputerLink.stateCode(false, false, true));
        assertEquals(0, ProComputerLink.stateCode(false, false, false));
        assertEquals("CONNECTED", ProComputerLink.clientStateName(true, false));
        assertEquals("CONNECTING", ProComputerLink.clientStateName(false, true));
        assertEquals("DISCONNECTED", ProComputerLink.clientStateName(false, false));
        assertEquals("LISTENING", ProComputerLink.serverStateName(2));
        assertEquals("CONNECTING", ProComputerLink.serverStateName(1));
        assertEquals("DISCONNECTED", ProComputerLink.serverStateName(0));
    }

    // ------------------------------------------------------------ outbound

    @Test
    public void outboundOnlyConnectsAfterAuthorization() throws Exception {
        ProComputerLink.Outbound outbound = new ProComputerLink.Outbound();
        ProComputerLink.Target first = ProComputerLink.parseTarget("192.168.1.10");
        int generation = outbound.begin(first);

        assertEquals(ProComputerLink.STATE_CONNECTING, outbound.state());
        assertTrue(outbound.isActive());
        assertTrue(outbound.isCurrent(generation));
        assertSame(first, outbound.target());

        // authorize 成功之前不能变成 CONNECTED。
        assertTrue(outbound.markConnected(generation));
        assertEquals(ProComputerLink.STATE_CONNECTED, outbound.state());
        assertEquals("", outbound.error());
    }

    @Test
    public void switchingTargetInvalidatesOldCallbacks() throws Exception {
        ProComputerLink.Outbound outbound = new ProComputerLink.Outbound();
        int oldGeneration = outbound.begin(ProComputerLink.parseTarget("192.168.1.10"));
        outbound.markConnected(oldGeneration);

        ProComputerLink.Target second = ProComputerLink.parseTarget("192.168.1.11:21030");
        int newGeneration = outbound.begin(second);
        assertTrue(newGeneration != oldGeneration);
        assertFalse(outbound.isCurrent(oldGeneration));
        assertFalse(outbound.markConnected(oldGeneration));
        assertFalse(outbound.markFailed(oldGeneration, "stale"));
        assertEquals(ProComputerLink.STATE_CONNECTING, outbound.state());
        assertEquals(21030, outbound.target().port);

        assertTrue(outbound.markRetry(newGeneration, "1 秒后重试 1/3"));
        assertEquals(ProComputerLink.STATE_CONNECTING, outbound.state());
        assertEquals("1 秒后重试 1/3", outbound.error());
        assertTrue(outbound.markFailed(newGeneration, "重试耗尽"));
        assertEquals(ProComputerLink.STATE_DISCONNECTED, outbound.state());
        assertFalse(outbound.isActive());
        assertEquals("重试耗尽", outbound.error());
        assertNotNull(outbound.target());
    }

    @Test
    public void cancelStopsRetriesAndClearsTarget() throws Exception {
        ProComputerLink.Outbound outbound = new ProComputerLink.Outbound();
        int generation = outbound.begin(ProComputerLink.parseTarget("192.168.1.10"));
        outbound.markRetry(generation, "正在重试");
        outbound.cancel();

        assertEquals(ProComputerLink.STATE_DISCONNECTED, outbound.state());
        assertFalse(outbound.isActive());
        assertNull(outbound.target());
        assertEquals("", outbound.error());
        assertFalse(outbound.isCurrent(generation));
        assertFalse(outbound.markConnected(generation));
    }

    @Test
    public void reportErrorOnlyWhenNoActiveAttempt() throws Exception {
        ProComputerLink.Outbound outbound = new ProComputerLink.Outbound();
        assertTrue(outbound.reportError("地址无效"));
        assertEquals("地址无效", outbound.error());
        assertTrue(outbound.begin(ProComputerLink.parseTarget("pc.lan")) > 0);
        assertFalse(outbound.reportError("不应覆盖进行中的连接"));
        assertEquals(ProComputerLink.STATE_CONNECTING, outbound.state());
    }

    // ------------------------------------------------------------ mDNS

    @Test
    public void mdnsCallbacksTrackRegistrationAndStop() {
        ProComputerLink.MdnsTracker tracker = new ProComputerLink.MdnsTracker();
        assertEquals(ProComputerLink.MDNS_STOPPED, tracker.state());
        assertFalse(tracker.isRegistered());

        int generation = tracker.beginRegistration("AutoJsProDevice-a");
        assertTrue(generation > 0);
        assertEquals(ProComputerLink.MDNS_REGISTERING, tracker.state());
        assertTrue(tracker.isCurrent(generation));

        assertFalse("stale generation must not register", tracker.onRegistered(generation + 1, "x"));
        assertTrue(tracker.onRegistered(generation, "AutoJsProDevice-a (2)"));
        assertTrue(tracker.isRegistered());
        assertEquals(ProComputerLink.MDNS_REGISTERED, tracker.state());
        assertEquals("AutoJsProDevice-a (2)", tracker.serviceName());

        assertEquals("already registered", -1, tracker.beginRegistration("duplicate"));
        tracker.stop();
        assertFalse(tracker.isRegistered());
        assertEquals(ProComputerLink.MDNS_STOPPED, tracker.state());
        assertFalse("late registration after stop must be ignored",
                tracker.onRegistered(generation, "late"));
        assertFalse(tracker.isRegistered());
        assertEquals(ProComputerLink.MDNS_STOPPED, tracker.state());
    }

    @Test
    public void lateOldMdnsCallbacksCannotPolluteNewRegistration() {
        ProComputerLink.MdnsTracker tracker = new ProComputerLink.MdnsTracker();
        int oldGeneration = tracker.beginRegistration("AutoJsProDevice-old");
        tracker.stop();

        int newGeneration = tracker.beginRegistration("AutoJsProDevice-new");
        assertTrue(newGeneration > oldGeneration);
        assertTrue(tracker.onRegistered(newGeneration, "AutoJsProDevice-new"));

        assertFalse("old success callback ignored",
                tracker.onRegistered(oldGeneration, "AutoJsProDevice-old"));
        assertFalse("old failure callback ignored",
                tracker.onRegistrationFailed(oldGeneration, 9002));
        assertFalse("old unregister callback ignored",
                tracker.onUnregistered(oldGeneration));
        assertTrue(tracker.isRegistered());
        assertEquals(ProComputerLink.MDNS_REGISTERED, tracker.state());
        assertEquals("AutoJsProDevice-new", tracker.serviceName());

        assertTrue(tracker.onRegistrationFailed(newGeneration, 9002));
        assertFalse(tracker.isRegistered());
        assertEquals(ProComputerLink.MDNS_FAILED, tracker.state());
        assertTrue(tracker.error().contains("9002"));
    }

    @Test
    public void mdnsUnregisterFailureAndUnavailableAreReported() {
        ProComputerLink.MdnsTracker tracker = new ProComputerLink.MdnsTracker();
        int generation = tracker.beginRegistration("AutoJsProDevice-a");
        tracker.onRegistered(generation, "AutoJsProDevice-a");
        assertTrue(tracker.onUnregistrationFailed(generation, 9003));
        assertFalse(tracker.isRegistered());
        assertEquals(ProComputerLink.MDNS_STOP_FAILED, tracker.state());
        assertTrue(tracker.error().contains("9003"));

        tracker.unavailable("系统没有 NSD");
        assertFalse(tracker.isRegistered());
        assertEquals(ProComputerLink.MDNS_UNAVAILABLE, tracker.state());
        assertTrue(tracker.error().contains("NSD"));
    }

    // ------------------------------------------------------------ helpers

    private static void assertBad(String host, String expectedMessage) {
        assertBad(host, expectedMessage, null);
    }

    private static void assertBad(String host, String expectedMessage, String protocol) {
        try {
            ProComputerLink.parseTarget(host, protocol);
            fail("expected InvalidTargetException for " + host);
        } catch (ProComputerLink.InvalidTargetException expected) {
            if (expectedMessage != null) {
                assertTrue("message was: " + expected.getMessage(),
                        expected.getMessage().contains(expectedMessage));
            }
        }
    }
}
