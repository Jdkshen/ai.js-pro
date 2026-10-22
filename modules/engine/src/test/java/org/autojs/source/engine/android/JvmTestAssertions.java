// SPDX-License-Identifier: MIT
package org.autojs.source.engine.android;

/** JUnit 4.12 没有 assertThrows（4.13 才加），engine 单测用这个本地实现。 */
public final class JvmTestAssertions {
    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Throwable;
    }

    private JvmTestAssertions() {
    }

    public static <T extends Throwable> T assertThrows(Class<T> expected, ThrowingRunnable runnable) {
        try {
            runnable.run();
        } catch (Throwable error) {
            if (expected.isInstance(error)) {
                return expected.cast(error);
            }
            throw new AssertionError("Unexpected exception type: " + error, error);
        }
        throw new AssertionError("Expected exception " + expected.getName());
    }
}
