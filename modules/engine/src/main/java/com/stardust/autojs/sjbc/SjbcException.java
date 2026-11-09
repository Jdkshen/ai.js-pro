package com.stardust.autojs.sjbc;

/** SJBC 容器/字节码错误（具名，便于脚本侧给出可抄文案）。 */
public class SjbcException extends RuntimeException {

    public SjbcException(String message) {
        super(message);
    }

    public SjbcException(String message, Throwable cause) {
        super(message, cause);
    }
}