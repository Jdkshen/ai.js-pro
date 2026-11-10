package com.stardust.autojs.core.ui.inflater;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * task-130(b)：资源包与当前宿主不匹配时，**必须给我们自己的可执行文案**（原生侧那句难懂的话我们改不了）。
 * 判据：摘要一致时**不进**这条路径（行为逐字不变）；不一致时文案可照着做、且不静默。
 */
public class CompiledProjectResourcesMessageTest {

    @Test
    public void messageIsActionableAndCarriesBothDigests() {
        String message = CompiledProjectResources.staleResourcePackMessage("8115cbb9", "ab77cf32",
                new File("/data/app/x/files/project/build/project-res.apk"));
        assertTrue("要点明是资源包与宿主不匹配", message.contains("工程资源包与当前宿主不匹配"));
        assertTrue("要给出期望/当前两个摘要", message.contains("8115cbb9") && message.contains("ab77cf32"));
        assertTrue("要给出可执行修法（重编）", message.contains("重新编译 project-res.apk"));
        assertTrue("要点明工具入口", message.contains("android_resources_compile"));
        assertTrue("要禁止伪改 sidecar", message.contains("不要伪改"));
        assertTrue("要说明回落行为（不静默）", message.contains("已忽略该资源包"));
    }

    /** task-133：文案是**唯一来源** ⇒ 必须 public，供打包期（com.jdkshen.aijspro.autojs.build 等跨包）复用，别抄第二份。 */
    @Test
    public void helperIsPublicForCrossPackageReuse() throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/stardust/autojs/core/ui/inflater/CompiledProjectResources.java")),
                StandardCharsets.UTF_8);
        assertTrue("必须是 public static（跨包复用）",
                source.contains("public static String staleResourcePackMessage(String expectedHostDigest, String actualHostDigest, File apk)"));
        assertTrue("判定路径不许动（仍由 matchesAppApk 门控）",
                source.contains("!sidecar.matchesAppApk(appApkPath)") && source.contains("!sidecar.matchesAppApk(host)"));
    }

    /** task-133: staging (W2) reuses the SAME host-digest algorithm - must be public static. */
    @Test
    public void hostDigestHelperIsPublicForStagingReuse() throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/stardust/autojs/core/ui/inflater/CompiledProjectResources.java")),
                StandardCharsets.UTF_8);
        assertTrue("host digest helper must be public static",
                source.contains("public static String hostResourcesSha256OrNull(String appApkPath)"));
        assertTrue("gating unchanged", source.contains("!sidecar.matchesAppApk(appApkPath)"));
    }

    @Test
    public void messageIsNullSafe() {
        String message = CompiledProjectResources.staleResourcePackMessage(null, null, new File("/x.apk"));
        assertTrue(message.contains("legacy-size") && message.contains("unknown"));
    }

    /** 源码钉：两处不匹配分支都必须用同一句；且判定仍由 sidecar.matchesAppApk 门控（一致 ⇒ 不进）。 */
    @Test
    public void bothMismatchBranchesUseTheSharedMessage() throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/stardust/autojs/core/ui/inflater/CompiledProjectResources.java")),
                StandardCharsets.UTF_8);
        int calls = source.split("staleResourcePackMessage\\(", -1).length - 2;
        assertTrue("两处不匹配分支都要有可执行文案（当前 " + calls + " 处）", calls >= 2);
        assertTrue("判定仍由 matchesAppApk 门控", source.contains("!sidecar.matchesAppApk(appApkPath)"));
        assertTrue("摘要拿不到不得影响判定", source.contains("hostResourcesSha256OrNull("));
    }
}