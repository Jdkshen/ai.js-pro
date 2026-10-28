package com.jdkshen.aijspro.inrt;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 轻量 JVM 测试：固定 inrt 各进程名 -> 主进程/:script 的映射。
 */
public class InrtProcessRoleTest {

    private static final String PKG = "com.jdkshen.aijspro.inrt";

    @Test
    public void mainProcessNames() {
        assertTrue(InrtProcessRole.isMain(PKG, PKG));
        assertTrue(InrtProcessRole.isMain(PKG, null));
        assertTrue(InrtProcessRole.isMain(PKG, ""));
        // 进程名读取失败时保守按主进程处理，避免丢失紧急停止入口。
        assertTrue(InrtProcessRole.isMain(PKG, "unknown"));
    }

    @Test
    public void childProcessesAreNotMain() {
        assertFalse(InrtProcessRole.isMain(PKG, PKG + ":script"));
        assertFalse(InrtProcessRole.isMain(PKG, PKG + ":node24"));
        assertFalse(InrtProcessRole.isMain(PKG, PKG + ":node24_1"));
        assertFalse(InrtProcessRole.isMain(PKG, PKG + ":node24_2"));
        assertFalse(InrtProcessRole.isMain(PKG, PKG + ":node24_3"));
        assertFalse(InrtProcessRole.isMain(PKG, "com.example.other"));
    }

    @Test
    public void onlyExactScriptSuffixIsScript() {
        assertTrue(InrtProcessRole.isScript(PKG, PKG + ":script"));
        assertFalse(InrtProcessRole.isScript(PKG, PKG));
        assertFalse(InrtProcessRole.isScript(PKG, PKG + ":script_2"));
        assertFalse(InrtProcessRole.isScript(PKG, PKG + ":node24"));
        assertFalse(InrtProcessRole.isScript(PKG, null));
    }
}
