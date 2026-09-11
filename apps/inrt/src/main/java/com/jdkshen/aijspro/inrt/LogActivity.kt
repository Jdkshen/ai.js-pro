package com.jdkshen.aijspro.inrt

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import android.view.Menu
import android.view.MenuItem
import android.view.View

import com.jdkshen.aijspro.inrt.autojs.AutoJs
import com.jdkshen.aijspro.inrt.launch.GlobalProjectLauncher
import com.stardust.autojs.core.console.ConsoleView
import com.stardust.autojs.core.console.ConsoleImpl

class LogActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupView()
        reportLaunchFailure()
        if (intent.getBooleanExtra(EXTRA_LAUNCH_SCRIPT, false)) {
            try {
                GlobalProjectLauncher.launch(this)
            } catch (e: Throwable) {
                // 同 SplashActivity：密钥/签名问题抛的是 Error，不能让它直接闪退。
                val cause = e.cause ?: e
                android.util.Log.e(TAG, "LAUNCH_FAILED " + cause, cause)
                AutoJs.instance?.globalConsole?.printAllStackTrace(cause)
            }
        }
    }

    /** 启动阶段就失败时（脚本解密不了 / 签名对不上），把原因写在日志里让用户看得到。 */
    private fun reportLaunchFailure() {
        val message = intent.getStringExtra(EXTRA_ERROR_MESSAGE) ?: return
        val console = AutoJs.instance?.globalConsole
        console?.error(message)
        console?.printAllStackTrace(IllegalStateException(message))
    }

    private fun setupView() {
        setContentView(R.layout.activity_main)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        val consoleView = findViewById<ConsoleView>(R.id.console)
        consoleView.setConsole(AutoJs.instance.globalConsole as ConsoleImpl)
        consoleView.findViewById<View>(R.id.input_container).visibility = View.GONE
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        startActivity(Intent(this, SettingsActivity::class.java))
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    companion object {

        private const val TAG = "LogActivity"

        val EXTRA_LAUNCH_SCRIPT = "launch_script"
        val EXTRA_ERROR_MESSAGE = "error_message"
    }
}
