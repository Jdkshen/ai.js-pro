package com.stardust.autojs.rhino;

import org.mozilla.javascript.NativeJavaObject;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.UniqueTag;
import org.mozilla.javascript.Wrapper;

/**
 * 「脚本侧覆盖优先」的 Java 对象代理。
 *
 * <p>与 {@link ProxyJavaObject} 的区别在顺序：{@code ProxyJavaObject} 是「Java 成员优先，找不到才问脚本」，
 * 这里反过来 —— 脚本写在 {@code __overrides__} 上的成员优先，其余原样落到真实对象上。
 *
 * <p>用途：工程模式的资源垫片（见 {@code modules/__ui__.js}）。{@code project.json} 声明
 * {@code androidResources} 时，资源的 id 是本引擎自己生成的<b>合成 id</b>（没有 aapt2，拿不到真 id），
 * 而 {@code activity.resources.getColor(id)}、{@code activity.theme.applyStyle(id, force)} 这两个
 * framework 调用点只认真 id。脚本把这两处写在表达式里绕不开，所以把 {@code activity} 换成这个代理，
 * 只拦需要「按名字查表」的几个方法，其余成员（{@code setContentView}、{@code getWindow}、
 * {@code getEventEmitter} …）照旧可用。
 */
public class OverridingJavaObject extends NativeJavaObject {

    /** 脚本侧的覆盖表：{@code proxy.__overrides__ = { getColor: function (id) { ... } }}。 */
    public static final String OVERRIDES = "__overrides__";

    private NativeObject mOverrides;

    public OverridingJavaObject() {
    }

    public OverridingJavaObject(Scriptable scope, Object javaObject, Class<?> staticType) {
        super(scope, javaObject, staticType);
    }

    /**
     * 脚本侧的入口：{@code Packages.com.stardust.autojs.rhino.OverridingJavaObject.of(global, obj)}。
     *
     * <p>Rhino 把参数装给 {@code Object} 形参时<b>不会</b>自动解包（{@code NativeJavaMethod.convertArg}
     * 对 {@code Object} 直接原样传递），所以这里显式 {@link Wrapper#unwrap()}，
     * 保证代理的是真 Java 对象，而不是它外面那层 Rhino 包装。
     */
    public static OverridingJavaObject of(Scriptable scope, Object target) {
        Object unwrapped = target instanceof Wrapper ? ((Wrapper) target).unwrap() : target;
        return new OverridingJavaObject(scope, unwrapped,
                unwrapped == null ? Object.class : unwrapped.getClass());
    }

    @Override
    public void put(String name, Scriptable start, Object value) {
        if (OVERRIDES.equals(name)) {
            mOverrides = value instanceof NativeObject ? (NativeObject) value : null;
            return;
        }
        super.put(name, start, value);
    }

    @Override
    public Object get(String name, Scriptable start) {
        if (mOverrides != null) {
            Object override = mOverrides.get(name, mOverrides);
            if (override != null && override != UniqueTag.NOT_FOUND) {
                return override;
            }
        }
        return super.get(name, start);
    }

    @Override
    public boolean has(String name, Scriptable start) {
        if (mOverrides != null && mOverrides.has(name, mOverrides)) {
            return true;
        }
        return super.has(name, start);
    }

    @Override
    public Object getDefaultValue(Class<?> typeHint) {
        return super.getDefaultValue(typeHint);
    }
}
