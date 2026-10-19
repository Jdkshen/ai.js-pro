package org.autojs.source.engine.android;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * MI8 dev-1095 regression contracts:
 * <ul>
 *   <li>production prelude performs a real process.chdir before user code, so relative fs writes
 *       (the axios sample's qq.com.html) land under the script project directory;</li>
 *   <li>getSensor name/number mapping and SensorDelay match the Pro Node sensors contract;</li>
 *   <li>the host-side sensor protocol (get/register/unregister) is validated independently.</li>
 * </ul>
 *
 * <p>The JS half runs in a child Node process against the actual production prelude asset. It is
 * skipped only when no Node executable is available; a present Node with a failing contract is a
 * hard test failure.</p>
 */
public class Node24SensorsCwdTest {

    @Test
    public void productionPreludeSetsRealCwdAndDeliversSensorEvents() throws Exception {
        File prelude = find("src/main/assets/node24/prelude.js",
                "modules/engine/src/main/assets/node24/prelude.js");
        File harness = find("src/test/js/node24-prelude-contract.js",
                "modules/engine/src/test/js/node24-prelude-contract.js");
        assertNotNull("找不到 production prelude.js（测试工作目录="
                + new File(".").getAbsolutePath() + "）", prelude);
        assertNotNull("找不到 node24-prelude-contract.js", harness);

        File node = findNode();
        Assume.assumeTrue("未找到 node 可执行文件；设置 NODE_BINARY 后运行此契约测试", node != null);

        Path work = Files.createTempDirectory("node24-prelude-contract-");
        Path log = Files.createTempFile("node24-prelude-contract-", ".log");
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    node.getPath(), harness.getAbsolutePath(), prelude.getAbsolutePath(), work.toString());
            builder.redirectErrorStream(true);
            builder.redirectOutput(log.toFile());
            Process process = builder.start();
            boolean finished = process.waitFor(60, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            String output = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
            int exit = finished ? process.exitValue() : -1;
            assertEquals("Node production-prelude contract failed (exit=" + exit + "):\n" + output,
                    0, exit);
            assertTrue("missing OK marker:\n" + output,
                    output.contains("OK node24 prelude contract"));
        } finally {
            deleteTree(work);
            Files.deleteIfExists(log);
        }
    }

    @Test
    public void sensorNamesMapLikeProNodeSensors() {
        assertEquals(Integer.valueOf(1), Node24HostBridge.sensorTypeForName("accelerometer"));
        assertEquals(Integer.valueOf(1), Node24HostBridge.sensorTypeForName("ACCELEROMETER"));
        assertEquals(Integer.valueOf(1), Node24HostBridge.sensorTypeForName("TYPE_ACCELEROMETER"));
        assertEquals(Integer.valueOf(2), Node24HostBridge.sensorTypeForName("magnetic_field"));
        assertEquals(Integer.valueOf(2), Node24HostBridge.sensorTypeForName("magnetic-field"));
        assertEquals(Integer.valueOf(3), Node24HostBridge.sensorTypeForName("orientation"));
        assertEquals(Integer.valueOf(5), Node24HostBridge.sensorTypeForName("light"));
        assertEquals(Integer.valueOf(10), Node24HostBridge.sensorTypeForName("linear_acceleration"));
        assertEquals(Integer.valueOf(12), Node24HostBridge.sensorTypeForName("relative_humidity"));
        assertEquals(Integer.valueOf(13), Node24HostBridge.sensorTypeForName("ambient_temperature"));
        assertEquals(Integer.valueOf(1), Node24HostBridge.sensorTypeForName("1"));
        assertNull(Node24HostBridge.sensorTypeForName("not_a_sensor"));
        assertNull(Node24HostBridge.sensorTypeForName(""));
        assertNull(Node24HostBridge.sensorTypeForName(null));
    }

    @Test
    public void sensorDelayAcceptsOnlyProEnumValues() {
        assertEquals(0, Node24HostBridge.parseSensorDelay(0));
        assertEquals(1, Node24HostBridge.parseSensorDelay(1));
        assertEquals(2, Node24HostBridge.parseSensorDelay(2));
        assertEquals(3, Node24HostBridge.parseSensorDelay(3));
        assertEquals(2, Node24HostBridge.parseSensorDelay("2"));
        assertEquals(3, Node24HostBridge.parseSensorDelay(null));

        assertBadDelay(-1);
        assertBadDelay(4);
        assertBadDelay(1.5d);
        assertBadDelay("abc");
        assertBadDelay("");
    }

    private static void assertBadDelay(Object value) {
        try {
            Node24HostBridge.parseSensorDelay(value);
            fail("delay 应该被拒绝：" + value);
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("delay 非法"));
        }
    }

    private static File find(String... relativeCandidates) {
        File base = new File(System.getProperty("user.dir", ".")).getAbsoluteFile();
        for (int depth = 0; depth < 6 && base != null; depth++, base = base.getParentFile()) {
            for (String relative : relativeCandidates) {
                File candidate = new File(base, relative);
                if (candidate.isFile()) {
                    return candidate.getAbsoluteFile();
                }
            }
        }
        for (String relative : relativeCandidates) {
            File candidate = new File(relative);
            if (candidate.isFile()) {
                return candidate.getAbsoluteFile();
            }
        }
        return null;
    }

    private static File findNode() {
        String configured = System.getenv("NODE_BINARY");
        if (configured != null && !configured.trim().isEmpty()) {
            File explicit = new File(configured);
            if (explicit.isFile()) {
                return explicit.getAbsoluteFile();
            }
            return new File(configured);
        }
        try {
            Process probe = new ProcessBuilder("node", "--version")
                    .redirectErrorStream(true).start();
            boolean done = probe.waitFor(10, TimeUnit.SECONDS);
            if (!done) {
                probe.destroyForcibly();
                return null;
            }
            return probe.exitValue() == 0 ? new File("node") : null;
        } catch (Exception unavailable) {
            return null;
        }
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Temp cleanup must not hide the actual contract result.
                }
            });
        } catch (IOException ignored) {
        }
    }
}
