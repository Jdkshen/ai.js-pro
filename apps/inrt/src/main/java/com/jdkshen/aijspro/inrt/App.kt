package com.jdkshen.aijspro.inrt

import android.app.Application
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.Log
import android.view.View
import android.widget.ImageView

import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.SimpleTarget
import com.bumptech.glide.request.transition.Transition
import com.stardust.app.GlobalAppContext
import com.jdkshen.aijspro.inrt.autojs.AutoJs
import com.jdkshen.aijspro.inrt.autojs.GlobalKeyObserver
import com.jdkshen.aijspro.inrt.autojs.InrtStopRelay
import com.jdkshen.aijspro.inrt.timing.InrtIntentTaskRegistry
import com.jdkshen.aijspro.inrt.script.InrtScriptThreadHost
import com.jdkshen.aijspro.inrt.timing.InrtTimedTaskScheduler
import com.stardust.autojs.core.ui.inflater.ImageLoader
import com.stardust.autojs.core.ui.inflater.util.Drawables

/**
 * Created by Stardust on 2017/7/1.
 */

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        GlobalAppContext.set(this)
        AutoJs.initInstance(this)
        // task-160：初始化定时任务上下文；主进程恢复持久化任务，:script 进程供 work_manager 排任务。
        InrtTimedTaskScheduler.init(this)
        // 进程归属只解析一次；:script 里的 App 不是按键/广播任务所有者。
        val processName = InrtScriptThreadHost.getProcessName(this)
        // task-162：广播 Intent 任务只在主进程注册动态 receiver。
        if (InrtProcessRole.isMain(packageName, processName)) {
            InrtIntentTaskRegistry.init(this)
        }
        if (InrtProcessRole.isMain(packageName, processName)) {
            // 主进程持有无障碍键通道；窗口聚焦路径 shared stopAllByVolumeUp() 的 hook 也由此
            // 中继到实际跑脚本的 :script。:script 内 App 不再注册这些主进程职责。
            InrtStopRelay.installMainVolumeUpHook(this)
            GlobalKeyObserver.init(this)
        } else {
            Log.i(TAG, "skip global key/root observer in process=$processName")
        }
        Drawables.setDefaultImageLoader(object : ImageLoader {
            override fun loadInto(imageView: ImageView, uri: Uri) {
                Glide.with(this@App)
                        .load(uri)
                        .into(imageView)
            }

            override fun loadIntoBackground(view: View, uri: Uri) {
                Glide.with(this@App)
                        .load(uri)
                        .into(object : SimpleTarget<Drawable>() {
                            override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                                view.background = resource
                            }
                        })
            }

            override fun load(view: View, uri: Uri): Drawable {
                throw UnsupportedOperationException()
            }

            override fun load(view: View, uri: Uri, drawableCallback: ImageLoader.DrawableCallback) {
                Glide.with(this@App)
                        .load(uri)
                        .into(object : SimpleTarget<Drawable>() {
                            override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                                drawableCallback.onLoaded(resource)
                            }
                        })
            }

            override fun load(view: View, uri: Uri, bitmapCallback: ImageLoader.BitmapCallback) {
                Glide.with(this@App)
                        .asBitmap()
                        .load(uri)
                        .into(object : SimpleTarget<Bitmap>() {
                            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                                bitmapCallback.onLoaded(resource)
                            }
                        })
            }
        })
    }

    companion object {
        private const val TAG = "InrtApp"
    }

}
