package com.stardust.autojs.rhino;


import com.stardust.autojs.runtime.exception.ScriptInterruptedException;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.tools.shell.ShellContextFactory;

import java.io.File;

/**
 * Created by Stardust on 2017/4/5.
 */

public class AndroidContextFactory extends ShellContextFactory {
    private final File cacheDirectory;

    /**
     * Create a new factory. It will cache generated code in the given directory
     *
     * @param cacheDirectory the cache directory
     */
    public AndroidContextFactory(File cacheDirectory) {
        // 必须在 initApplicationClassLoader 之前先初始化 Context（Rhino 1.7.15.x 升级回归修复）：
        // initApplicationClassLoader 内部会首次触碰 ScriptRuntime.ContextFactoryClass，于是
        // ScriptRuntime 先于 Context 进入初始化；而 ScriptRuntime.<clinit> 里会 Class.forName(Context)
        // 嵌套初始化 Context，此时 Context.<clinit> 读到的 ScriptRuntime.emptyArgs 还是 null，
        // Context.emptyArgs 被永久置为 null，之后所有 Java bean getter（JavaMembers.get L103
        // → MemberBox.invoke(obj, Context.emptyArgs)）都会 NPE（引擎 init 加载 __images__.js 即崩）。
        // 先摸一下 Context 即可让两者以安全顺序（Context 先）初始化。
        Context.getCurrentContext();
        this.cacheDirectory = cacheDirectory;
        initApplicationClassLoader(createClassLoader(AndroidContextFactory.class.getClassLoader()));
    }

    /**
     * Create a ClassLoader which is able to deal with bytecode
     *
     * @param parent the parent of the create classloader
     * @return a new ClassLoader
     */
    @Override
    protected AndroidClassLoader createClassLoader(ClassLoader parent) {
        return new AndroidClassLoader(parent, cacheDirectory);
    }

    @Override
    protected void observeInstructionCount(Context cx, int instructionCount) {
        if (Thread.currentThread().isInterrupted()) {
            throw new ScriptInterruptedException();
        }
    }

    @Override
    protected Context makeContext() {
        Context cx = super.makeContext();
        cx.setInstructionObserverThreshold(10000);
        return cx;
    }

    /**
     * Rhino 1.7.15+ 的 E4X（XML 字面量，如 {@code ui.layout(<xml>)}）初始化前要求平台支持
     * “安全解析”配置：DocumentBuilderFactory/TransformerFactory.setFeature(SECURE_PROCESSING)。
     * 部分 Android ROM 的 XML 实现不支持该 feature，会直接抛异常，导致任何使用 XML 字面量的
     * 脚本在启动时崩溃（Rhino 升级回归，旧 fork 无此检查）。
     *
     * <p>这里探测平台能力：不支持时关闭 {@link Context#FEATURE_ENABLE_XML_SECURE_PARSING}，
     * 让 E4X 退回旧行为（XML 可用、无安全加固）；支持时保持加固。
     */
    @Override
    protected boolean hasFeature(Context cx, int featureIndex) {
        if (featureIndex == Context.FEATURE_ENABLE_XML_SECURE_PARSING) {
            return isXmlSecureParsingSupported();
        }
        return super.hasFeature(cx, featureIndex);
    }

    /** -1=未探测，0=不支持，1=支持。 */
    private static volatile int sXmlSecureSupport = -1;

    private static boolean isXmlSecureParsingSupported() {
        int cached = sXmlSecureSupport;
        if (cached >= 0) {
            return cached == 1;
        }
        boolean supported;
        try {
            javax.xml.parsers.DocumentBuilderFactory dbf =
                    javax.xml.parsers.DocumentBuilderFactory.newInstance();
            dbf.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
            dbf.setXIncludeAware(false);
            javax.xml.transform.TransformerFactory tf =
                    javax.xml.transform.TransformerFactory.newInstance();
            tf.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
            supported = true;
        } catch (Throwable t) {
            // 平台不支持安全解析（Harmony 系 XML 实现）：关闭 Rhino 的强制安全配置。
            supported = false;
        }
        sXmlSecureSupport = supported ? 1 : 0;
        return supported;
    }


}
