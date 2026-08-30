package com.stardust.autojs.engine;

/** Exception reported by the native QuickJS runtime, including its JS stack. */
public class QuickJsException extends RuntimeException {

    public QuickJsException(String message) {
        super(message);
    }
}
