package com.jdkshen.aijspro.inrt

import android.Manifest
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.widget.ImageView
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import android.widget.TextView
import android.widget.Toast

import com.jdkshen.aijspro.inrt.autojs.AutoJs
import com.jdkshen.aijspro.inrt.launch.GlobalProjectLauncher
import com.stardust.autojs.project.LaunchConfig
import com.stardust.autojs.project.ProjectConfig

import java.util.ArrayList

import android.content.pm.PackageManager.PERMISSION_DENIED

/**
 * Created by Stardust on 2018/2/2.
 */

class SplashActivity : AppCompatActivity() {

    private var launchConfig: LaunchConfig? = null

    override fun onCreate(@Nullable savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launchConfig = readLaunchConfig()
        this.launchConfig = launchConfig
        // "显示启动界面" can be disabled at packaging time: the runtime then starts the
        // script immediately instead of flashing a splash screen.
        if (launchConfig != null && !launchConfig.shouldShowSplash()) {
            main()
            return
        }
        setContentView(R.layout.activity_splash)
        findViewById<TextView>(R.id.slug).typeface =
            Typeface.createFromAsset(assets, "roboto_medium.ttf")
        applyLaunchConfig(launchConfig)
        if (!Pref.isFirstUsing) {
            main()
        } else {
            Handler().postDelayed({ this@SplashActivity.main() }, INIT_TIMEOUT)
        }
    }

    private fun readLaunchConfig(): LaunchConfig? {
        return try {
            ProjectConfig.fromAssets(this, ProjectConfig.configFileOfDir(PROJECT_ASSET_DIR))?.launchConfig
        } catch (e: Exception) {
            null
        }
    }

    private fun applyLaunchConfig(launchConfig: LaunchConfig?) {
        if (launchConfig == null) {
            return
        }
        val text = launchConfig.splashText
        if (!text.isNullOrEmpty()) {
            findViewById<TextView>(R.id.slug).text = text
        }
        // The packaging step drops the user image here, next to the other project assets.
        try {
            assets.open(SPLASH_ASSET).use { stream ->
                BitmapFactory.decodeStream(stream)?.let { bitmap ->
                    findViewById<ImageView>(R.id.logo).setImageBitmap(bitmap)
                }
            }
        } catch (e: Exception) {
            // No custom image packaged: keep the built-in logo.
        }
    }

    private fun main() {
        // "启动时自动申请权限" 由打包页配置；未配置时沿用旧行为（存储 + 手机状态）。
        val configured = launchConfig?.requestPermissions
        android.util.Log.d(TAG, "launchConfig=" + launchConfig + " request=" + configured)
        if (configured.isNullOrEmpty()) {
            checkPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_PHONE_STATE)
        } else {
            checkPermission(*configured.toTypedArray())
        }
    }


    private fun runScript() {
        Thread {
            try {
                GlobalProjectLauncher.launch(this)
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this@SplashActivity, e.message, Toast.LENGTH_LONG).show()
                    startActivity(Intent(this@SplashActivity, LogActivity::class.java))
                    AutoJs.instance!!.globalConsole.printAllStackTrace(e)
                }
            }
        }.start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, @NonNull permissions: Array<String>, @NonNull grantResults: IntArray) {
        runScript()
    }

    private fun checkPermission(vararg permissions: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val requestPermissions = getRequestPermissions(permissions)
            if (requestPermissions.isNotEmpty()) {
                requestPermissions(requestPermissions, PERMISSION_REQUEST_CODE)
            } else {
                runScript()
            }
        } else {
            runScript()
        }
    }


    @RequiresApi(api = Build.VERSION_CODES.M)
    private fun getRequestPermissions(permissions: Array<out String>): Array<String> {
        val list = ArrayList<String>()
        for (permission in permissions) {
            if (checkSelfPermission(permission) == PERMISSION_DENIED) {
                list.add(permission)
            }
        }
        return list.toTypedArray()
    }

    companion object {

        private const val TAG = "InrtSplash"
        private const val PERMISSION_REQUEST_CODE = 11186
        private const val INIT_TIMEOUT: Long = 2500
        private const val PROJECT_ASSET_DIR = "project"
        private const val SPLASH_ASSET = "project/splash.png"
    }

}

