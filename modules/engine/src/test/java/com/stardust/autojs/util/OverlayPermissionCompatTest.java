package com.stardust.autojs.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * task-77：悬浮窗权限 intent 的**纯判定**（不需要 android.jar，也不 new Intent —— stub 环境下会抛）。
 *
 * <p>用户实报的那条 intent 只有 action、没有 data；这里的判据决定「要不要补 package:<包名>」。
 */
public class OverlayPermissionCompatTest {

    private static final String OVERLAY = "android.settings.action.MANAGE_OVERLAY_PERMISSION";

    /** action 判定：只有这一个 action 算悬浮窗权限（大小写敏感，与框架常量一致）。 */
    @Test
    public void recognizesOnlyTheManageOverlayAction() {
        assertEquals("android.settings.action.MANAGE_OVERLAY_PERMISSION",
                OverlayPermissionCompat.ACTION_MANAGE_OVERLAY_PERMISSION);
        assertTrue(OverlayPermissionCompat.isManageOverlayAction(OVERLAY));
        assertFalse(OverlayPermissionCompat.isManageOverlayAction("android.settings.ACCESSIBILITY_SETTINGS"));
        assertFalse(OverlayPermissionCompat.isManageOverlayAction(null));
        assertFalse(OverlayPermissionCompat.isManageOverlayAction(""));
    }

    /** 核心判据：该 action + 无 data（null/空/空白）→ 必须补 package:；带了 data 就**不**动它。 */
    @Test
    public void needsPackageDataOnlyWhenActionMatchesAndDataIsMissing() {
        assertTrue("用户脚本这条（只有 action）必须补", OverlayPermissionCompat.needsPackageData(OVERLAY, null));
        assertTrue(OverlayPermissionCompat.needsPackageData(OVERLAY, ""));
        assertTrue("纯空白也算没有", OverlayPermissionCompat.needsPackageData(OVERLAY, "   "));
        assertFalse("已经带了 package: 就不动", OverlayPermissionCompat.needsPackageData(OVERLAY, "package:com.nuoyapro"));
        assertFalse("别的 action 不碰", OverlayPermissionCompat.needsPackageData("android.settings.ACCESSIBILITY_SETTINGS", null));
        assertFalse(OverlayPermissionCompat.needsPackageData(null, null));
    }

    /** 补出来的 data 形态 + 拿不到包名时不硬造。 */
    @Test
    public void buildsPackageUriAndRefusesEmptyPackageName() {
        assertEquals("package:com.nuoyapro", OverlayPermissionCompat.packageData("com.nuoyapro"));
        assertEquals("package:com.nuoyapro", OverlayPermissionCompat.packageData("  com.nuoyapro  "));
        assertNull(OverlayPermissionCompat.packageData(null));
        assertNull(OverlayPermissionCompat.packageData(""));
        assertNull(OverlayPermissionCompat.packageData("   "));
    }

    /** null intent 不崩（脚本传 null 时要打日志而不是 NPE）。 */
    @Test
    public void normalizeToleratesNullIntent() {
        assertNull(OverlayPermissionCompat.normalize(null, "com.nuoyapro"));
    }
}