package com.jdkshen.xiangqi.plugin;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 进 native 之前的局面校验：非法 FEN 会让 Pikafish 解析非法局面并**静默带走整个进程**
 * （2026-09-25 真机实测：无墓碑、无 ANR、无 FATAL），所以插件与宿主一样在门口拦掉。
 * 这些是纯函数，JVM 上直接测。
 */
public class XiangqiPluginValidationTest {

    private static final String START_FEN =
            "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1";

    @Test
    public void acceptsCompleteStartFen() {
        assertTrue(XiangqiPlugin.isValidFen(START_FEN));
    }

    @Test
    public void rejectsFenWithoutSideAndMoveCounters() {
        assertFalse(XiangqiPlugin.isValidFen(
                "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR"));
        assertFalse(XiangqiPlugin.isValidFen(
                "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w"));
    }

    @Test
    public void rejectsFenWithUnknownSymbolsOrWrongSide() {
        assertFalse(XiangqiPlugin.isValidFen("9/9/9/9/9/9/9/9/9/9 x - - 0 1"));
        assertFalse(XiangqiPlugin.isValidFen(START_FEN.replace(" w ", " red ")));
        assertFalse(XiangqiPlugin.isValidFen(START_FEN.replace("0 1", "0 0")));
    }

    @Test
    public void rejectsEmptyAndNullFen() {
        assertFalse(XiangqiPlugin.isValidFen(null));
        assertFalse(XiangqiPlugin.isValidFen(""));
        assertFalse(XiangqiPlugin.isValidFen("   "));
    }

    @Test
    public void acceptsPositionCommandWithConfirmedMoves() {
        assertTrue(XiangqiPlugin.isValidPositionCommand(
                "position fen " + START_FEN + " moves h2e2 h7e7"));
        assertTrue(XiangqiPlugin.isValidPositionCommand("position fen " + START_FEN));
    }

    @Test
    public void rejectsPositionCommandWithMalformedMoves() {
        assertFalse(XiangqiPlugin.isValidPositionCommand(
                "position fen " + START_FEN + " moves h2"));           // 半截着法
        assertFalse(XiangqiPlugin.isValidPositionCommand(
                "position fen " + START_FEN + " moves h2e2 h2e2x"));   // 多一个字符
        assertFalse(XiangqiPlugin.isValidPositionCommand("position startpos moves h2e2"));
        assertFalse(XiangqiPlugin.isValidPositionCommand(
                "position " + START_FEN + " moves h2e2"));
    }
}
