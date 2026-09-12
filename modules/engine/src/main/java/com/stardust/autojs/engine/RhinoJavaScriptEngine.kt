package com.stardust.autojs.engine

import android.util.Log
import android.view.View
import com.stardust.autojs.core.ui.ViewExtras
import com.stardust.autojs.engine.module.AssetAndUrlModuleSourceProvider
import com.stardust.autojs.execution.ExecutionConfig
import com.stardust.autojs.project.ScriptConfig
import com.stardust.autojs.rhino.AndroidClassLoader
import com.stardust.autojs.rhino.PreferIntJavaObject
import com.stardust.autojs.rhino.RhinoAndroidHelper
import com.stardust.autojs.rhino.TopLevelScope
import com.stardust.autojs.runtime.ScriptRuntime
import com.stardust.autojs.script.CompiledJavaScriptSource
import com.stardust.autojs.script.JavaScriptSource
import com.stardust.automator.UiObjectCollection
import com.stardust.pio.UncheckedIOException
import org.mozilla.javascript.*
import org.mozilla.javascript.commonjs.module.RequireBuilder
import org.mozilla.javascript.commonjs.module.provider.SoftCachingModuleScriptProvider
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.Reader
import java.net.URI
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Created by Stardust on 2017/4/2.
 */

open class RhinoJavaScriptEngine(private val mAndroidContext: android.content.Context) : JavaScriptEngine() {

    val context: Context
    private val mScriptable: TopLevelScope
    lateinit var thread: Thread
        private set

    private val initScript: Script
        get() {
            return sInitScript ?: {
                try {
                    val reader = InputStreamReader(mAndroidContext.assets.open("init.js"))
                    val script = context.compileReader(reader, SOURCE_NAME_INIT, 1, null)
                    sInitScript = script
                    script
                } catch (e: IOException) {
                    throw UncheckedIOException(e)
                }
            }()
        }

    val scriptable: Scriptable
        get() = mScriptable

    init {
        this.context = enterContext()
        mScriptable = createScope(this.context)
    }

    override fun put(name: String, value: Any?) {
        ScriptableObject.putProperty(mScriptable, name, Context.javaToJS(value, mScriptable))
    }

    override fun setRuntime(runtime: ScriptRuntime) {
        super.setRuntime(runtime)
        runtime.topLevelScope = mScriptable
    }

    public override fun doExecution(source: JavaScriptSource): Any? {
        // 打包加密等级 ≥ 2 的产物是编译好的 class（没有源码），这里换成加载类再执行。
        if (source is CompiledJavaScriptSource) {
            return executeCompiledScript(source)
        }
        var reader = source.nonNullScriptReader
        try {
            reader = preprocess(reader)
            val script = context.compileReader(reader, source.toString(), 1, null)
            return runScript(script)
        } catch (e: IOException) {
            throw UncheckedIOException(e)
        }

    }

    /**
     * continuation 只决定脚本入口用哪套 API 执行：Rhino 在解释器模式下天然支持挂起/恢复，
     * 不需要 project.json 里配置 features —— 否则普通脚本里 continuation.await 会报
     * "Cannot capture continuation from JavaScript code not called directly by executeScriptWithContinuations"。
     */
    private fun runScript(script: Script): Any? {
        return try {
            context.executeScriptWithContinuations(script, mScriptable)
        } catch (e: IllegalArgumentException) {
            if (e.message?.startsWith("Script argument was not a script") == true) {
                script.exec(context, mScriptable)
            } else {
                throw e
            }
        }
    }

    /**
     * 每个引擎一个类加载器：同名类在不同引擎间互不影响（类加载器彼此隔离），
     * 缓存目录按引擎实例区分，避免其中一个加载器清目录时把别人的 dex 删了。
     */
    private val compiledClassLoader: AndroidClassLoader by lazy {
        val dir = File(mAndroidContext.cacheDir,
                "compiled-scripts-" + Integer.toHexString(System.identityHashCode(this)))
        AndroidClassLoader(javaClass.classLoader, dir)
    }

    private fun executeCompiledScript(source: CompiledJavaScriptSource): Any? {
        try {
            val clazz = compiledClassLoader.defineClass(source.className, source.classBytes)
            val script = clazz.newInstance() as Script
            return runScript(script)
        } catch (e: Exception) {
            throw IllegalStateException("编译脚本加载失败：" + source.className, e)
        }
    }

    fun hasFeature(feature: String): Boolean {
        // continuation 恒为 true：Rhino 解释器模式天然支持挂起/恢复，脚本入口统一走
        // executeScriptWithContinuations（见 runScript），普通脚本也能用 continuation.await。
        if (ScriptConfig.FEATURE_CONTINUATION == feature) {
            return true
        }
        val config = getTag(ExecutionConfig.tag) as ExecutionConfig?
        return config != null && config.scriptConfig.hasFeature(feature)
    }

    @Throws(IOException::class)
    protected fun preprocess(script: Reader): Reader {
        return script
    }

    override fun forceStop() {
        Log.d(LOG_TAG, "forceStop: interrupt Thread: $thread")
        thread.interrupt()
    }


    @Synchronized
    override fun destroy() {
        super.destroy()
        Log.d(LOG_TAG, "on destroy")
        sContextEngineMap.remove(context)
        Context.exit()
    }

    override fun init() {
        thread = Thread.currentThread()
        ScriptableObject.putProperty(mScriptable, "__engine__", this)
        initRequireBuilder(context, mScriptable)
        try {
            context.executeScriptWithContinuations(initScript, mScriptable)
        } catch (e: IllegalArgumentException) {
            if ("Script argument was not a script or was not created by interpreted mode " == e.message) {
                initScript.exec(context, mScriptable)
            } else {
                throw e
            }
        }
    }

    internal fun initRequireBuilder(context: Context, scope: Scriptable) {
        val provider = AssetAndUrlModuleSourceProvider(mAndroidContext, MODULES_PATH,
                listOf<URI>(File("/").toURI()))
        RequireBuilder()
                .setModuleScriptProvider(SoftCachingModuleScriptProvider(provider))
                .setSandboxed(true)
                .createRequire(context, scope)
                .install(scope)

    }

    protected fun createScope(context: Context): TopLevelScope {
        val topLevelScope = TopLevelScope()
        topLevelScope.initStandardObjects(context, false)
        return topLevelScope
    }

    fun enterContext(): Context {
        val context = RhinoAndroidHelper(mAndroidContext).enterContext()
        setupContext(context)
        sContextEngineMap[context] = this
        return context
    }

    protected fun setupContext(context: Context) {
        context.optimizationLevel = -1
        context.languageVersion = Context.VERSION_ES6
        context.locale = Locale.getDefault()
        context.wrapFactory = WrapFactory()
    }

    private inner class WrapFactory : org.mozilla.javascript.WrapFactory() {

        override fun wrap(cx: Context, scope: Scriptable, obj: Any?, staticType: Class<*>?): Any? {
            return when {
                obj is String -> runtime.bridges.toString(obj.toString())
                staticType == UiObjectCollection::class.java -> runtime.bridges.asArray(obj)
                else -> super.wrap(cx, scope, obj, staticType)
            }
        }

        override fun wrapAsJavaObject(cx: Context?, scope: Scriptable, javaObject: Any?, staticType: Class<*>?): Scriptable? {
            //Log.d(LOG_TAG, "wrapAsJavaObject: java = " + javaObject + ", result = " + result + ", scope = " + scope);
            return when {
                javaObject is View -> ViewExtras.getNativeView(scope, javaObject, staticType, runtime)
                // Paint 这类同时有 int/long 重载的类：让 JS 的数字参数优先走 int 版本
                PreferIntJavaObject.supports(javaObject) ->
                    PreferIntJavaObject(scope, javaObject as Any, staticType)
                else -> super.wrapAsJavaObject(cx, scope, javaObject, staticType)
            }
        }

    }

    companion object {

        val SOURCE_NAME_INIT = "<init>"

        private val LOG_TAG = "RhinoJavaScriptEngine"

        private val MODULES_PATH = "modules"
        private var sInitScript: Script? = null
        private val sContextEngineMap = ConcurrentHashMap<Context, RhinoJavaScriptEngine>()


        fun getEngineOfContext(context: Context): RhinoJavaScriptEngine? {
            return sContextEngineMap[context]
        }
    }


}
