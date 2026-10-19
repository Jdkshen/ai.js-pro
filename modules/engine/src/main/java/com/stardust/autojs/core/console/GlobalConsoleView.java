package com.stardust.autojs.core.console;

import android.content.Context;
import android.util.AttributeSet;
import android.content.res.TypedArray;
import com.stardust.autojs.R;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

/**
 * {@code <globalconsole/>}：Auto.js 的全局控制台组件 —— 把**所有脚本共用**的那份日志
 * （{@code ScriptEngineService.getGlobalConsole()}）显示在脚本界面里。
 *
 * <p>与控制台实现的连接靠 {@link #setGlobalConsole}：{@code ScriptEngineService} 构造时把
 * 全局控制台登记进来（Rhino / QuickJS / inrt 都是同一条路径）。登记之前创建的视图、或者
 * 全局控制台已经被回收时，视图留空即可 —— 脚本侧仍能自己调 {@code setConsole(...)}，
 * 不能让一个布局把整个脚本拖崩。
 */
public class GlobalConsoleView extends ConsoleView {

    /** 弱引用：静态字段不该把控制台（连带它的上下文）钉在内存里。 */
    private static volatile WeakReference<Object> sGlobalConsole = new WeakReference<>(null);

    /** 由 {@code ScriptEngineService} 构造时登记。 */
    public static void setGlobalConsole(Object globalConsole) {
        sGlobalConsole = new WeakReference<>(globalConsole);
    }

    public GlobalConsoleView(Context context) {
        super(context);
        bindGlobalConsole();
        applyDefaultInputVisibility(null);
    }

    public GlobalConsoleView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        bindGlobalConsole();
        applyDefaultInputVisibility(attrs);
    }

    /**
     * task-190：{@code <globalconsole>} 默认只显示日志，隐藏自带输入区
     * （避免悬浮日志示例同时出现控制台自带确定 + 脚本显式确定）；
     * 需要输入时写 {@code inputEnabled="true"}。{@code <console>} 不受影响。
     */
    private void applyDefaultInputVisibility(AttributeSet attrs) {
        boolean inputEnabled = false;
        if (attrs != null) {
            TypedArray typed = null;
            try {
                typed = getContext().obtainStyledAttributes(attrs, R.styleable.ConsoleView);
                inputEnabled = typed.getBoolean(R.styleable.ConsoleView_inputEnabled, false);
            } catch (Throwable ignored) {
            } finally {
                if (typed != null) typed.recycle();
            }
        }
        setInputEnabled(inputEnabled);
    }

    private void bindGlobalConsole() {
        Object console = sGlobalConsole.get();
        if (console instanceof ConsoleImpl) {
            setConsole((ConsoleImpl) console);
        }
    }
}
