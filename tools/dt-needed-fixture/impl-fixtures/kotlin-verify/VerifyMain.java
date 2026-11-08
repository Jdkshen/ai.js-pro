import com.jdkshen.aijspro.mcp.ToolkitElfNeeded;

import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;

/**
 * 离线驱动**真实** ToolkitElfNeeded（直接用 Gradle 已经编出来的 class，见 run_impl_verify.ps1），
 * 把 Outcome 逐字段打出来，并把补丁结果写到 out/ 便于和 check_dt_needed.py 的内存模拟做 sha256 交叉比对。
 */
public class VerifyMain {
    public static void main(String[] args) throws Exception {
        File outDir = new File("out");
        outDir.mkdirs();
        for (String path : args) {
            File file = new File(path);
            byte[] bytes = Files.readAllBytes(file.toPath());
            ToolkitElfNeeded.Outcome outcome = ToolkitElfNeeded.INSTANCE.patch(bytes, ToolkitElfNeeded.DEFAULT_LIBRARY);
            System.out.println("=== " + file.getName() + "  size=" + bytes.length + " sha256=" + sha256(bytes));
            System.out.println("    applied=" + outcome.getApplied() + " verified=" + outcome.getVerified()
                    + " soname=" + outcome.getSoname() + " reason=" + outcome.getReason()
                    + " reasons=" + outcome.getReasons());
            StringBuilder ranges = new StringBuilder();
            for (kotlin.ranges.IntRange range : outcome.getChangedRanges()) {
                if (ranges.length() > 0) ranges.append(", ");
                ranges.append("0x").append(Integer.toHexString(range.getFirst()))
                      .append("-0x").append(Integer.toHexString(range.getLast()));
            }
            System.out.println("    strtabOffset=" + outcome.getStrtabOffset() + " dynamicIndex=" + outcome.getDynamicIndex()
                    + " dynamicOffset=" + outcome.getDynamicOffset() + " changedRanges=[" + ranges + "]");
            System.out.println("    json=" + outcome.json(file.getName()));
            byte[] patched = outcome.getBytes();
            if (patched != null) {
                File target = new File(outDir, file.getName().replaceAll("\\.so$", "") + ".patched.so");
                Files.write(target.toPath(), patched);
                System.out.println("    patched size=" + patched.length + " sha256=" + sha256(patched)
                        + " -> " + target.getPath());
            } else {
                System.out.println("    patched: (none — 未产出半坏产物)");
            }
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder builder = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            builder.append(String.format("%02x", value & 0xff));
        }
        return builder.toString();
    }
}
