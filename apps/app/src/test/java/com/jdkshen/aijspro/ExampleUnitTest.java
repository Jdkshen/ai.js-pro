package com.jdkshen.aijspro;

import org.junit.Assert;
import org.junit.Test;

/**
 * 占位测试类。
 *
 * <p>原本含两个不验证任何东西的方法（一个只 {@code System.out.println}、一个空方法体）。
 * 空测试带来的"绿"是误导性的——它让人以为这里有覆盖。已移除那两个方法，
 * 本类只作为 {@code src/test} 源集的存在标记保留。
 *
 * <p>真正的断言在 {@code src/testMiuix}：{@code McpHttpServerTest}、
 * {@code McpWorkspaceStoreTest}、{@code ExplorerListRowsTest}、
 * {@code ExplorerSorterParityTest}、{@code ExplorerViewHelperTest} 等。
 *
 * <p>如果将来不再需要这个占位类，可以连同本文件一起删除；
 * 届时应确认 {@code src/test} 源集没有其他文件依赖它存在。
 */
public class ExampleUnitTest {

    @Test
    public void sourceSetIsWired() {
        // 唯一目的：确认 src/test 源集仍被 Gradle 收集。
        // 不要在这里加"看起来在测什么"的假断言。
        Assert.assertTrue(true);
    }
}
