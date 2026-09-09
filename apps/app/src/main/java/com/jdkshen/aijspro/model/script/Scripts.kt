package com.jdkshen.aijspro.model.script

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

import com.stardust.app.GlobalAppContext
import com.stardust.autojs.execution.ExecutionConfig
import com.stardust.autojs.execution.ScriptExecution
import com.stardust.autojs.execution.ScriptExecutionListener
import com.stardust.autojs.execution.SimpleScriptExecutionListener
import com.stardust.autojs.runtime.exception.ScriptInterruptedException
import com.stardust.autojs.script.ScriptSource
import com.stardust.util.IntentUtil

import com.jdkshen.aijspro.Pref
import com.jdkshen.aijspro.R
import com.jdkshen.aijspro.autojs.AutoJs
import com.jdkshen.aijspro.external.ScriptIntents
import com.jdkshen.aijspro.external.fileprovider.AppFileProvider
import com.jdkshen.aijspro.external.shortcut.Shortcut
import com.jdkshen.aijspro.external.shortcut.ShortcutActivity
import com.jdkshen.aijspro.ui.editor.ProCodeEditorActivity

import org.mozilla.javascript.RhinoException

import java.io.File
import java.io.FileFilter

/**
 * Created by Stardust on 2017/5/3.
 */

object Scripts {

    const val ACTION_ON_EXECUTION_FINISHED = "ACTION_ON_EXECUTION_FINISHED"
    const val EXTRA_EXCEPTION_MESSAGE = "message"
    const val EXTRA_EXCEPTION_LINE_NUMBER = "lineNumber"
    const val EXTRA_EXCEPTION_COLUMN_NUMBER = "columnNumber"

    val FILE_FILTER = FileFilter { file ->
        file.isDirectory || file.name.endsWith(".js")
                || file.name.endsWith(".auto")
    }

    private fun executionFinishedIntent(): Intent =
            Intent(ACTION_ON_EXECUTION_FINISHED)
                    .setPackage(GlobalAppContext.get().packageName)

    private val BROADCAST_SENDER_SCRIPT_EXECUTION_LISTENER = object : SimpleScriptExecutionListener() {

        override fun onSuccess(execution: ScriptExecution, result: Any?) {
            GlobalAppContext.get().sendBroadcast(executionFinishedIntent())
        }

        override fun onException(execution: ScriptExecution, e: Throwable) {
            val rhinoException = getRhinoException(e)
            var line = -1
            var col = 0
            if (rhinoException != null) {
                line = rhinoException.lineNumber()
                col = rhinoException.columnNumber()
            }
            if (ScriptInterruptedException.causedByInterrupted(e)) {
                GlobalAppContext.get().sendBroadcast(executionFinishedIntent()
                        .putExtra(EXTRA_EXCEPTION_LINE_NUMBER, line)
                        .putExtra(EXTRA_EXCEPTION_COLUMN_NUMBER, col))
            } else {
                GlobalAppContext.get().sendBroadcast(executionFinishedIntent()
                        .putExtra(EXTRA_EXCEPTION_MESSAGE, e.message)
                        .putExtra(EXTRA_EXCEPTION_LINE_NUMBER, line)
                        .putExtra(EXTRA_EXCEPTION_COLUMN_NUMBER, col))
            }
        }

    }


    fun openByOtherApps(uri: Uri) {
        IntentUtil.viewFile(GlobalAppContext.get(), uri, "text/plain", AppFileProvider.AUTHORITY)
    }

    fun openByOtherApps(file: File) {
        openByOtherApps(Uri.fromFile(file))
    }

    fun createShortcut(scriptFile: ScriptFile) {
        Shortcut(GlobalAppContext.get()).name(scriptFile.simplifiedName)
                .targetClass(ShortcutActivity::class.java)
                .iconRes(R.drawable.ic_node_js_black)
                .extras(Intent().putExtra(ScriptIntents.EXTRA_KEY_PATH, scriptFile.path))
                .send()
    }


    fun edit(context: Context, file: ScriptFile) {
        // Unified editor entry: open the Auto.js Pro-style editor (file tree + tabs + log panel)
        // for all "open script for editing" paths (file list, new-file, external edits).
        context.startActivity(ProCodeEditorActivity.intent(context, File(file.path)))
    }

    fun edit(context: Context, path: String) {
        edit(context, ScriptFile(path))
    }

    fun run(file: ScriptFile): ScriptExecution? {
        return try {
            AutoJs.getInstance().scriptEngineService.execute(file.toSource(),
                    ExecutionConfig(workingDirectory = workingDirectory(file)))
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(GlobalAppContext.get(), e.message, Toast.LENGTH_LONG).show()
            null
        }

    }


    fun run(source: ScriptSource): ScriptExecution? {
        return try {
            AutoJs.getInstance().scriptEngineService.execute(source, ExecutionConfig(workingDirectory = Pref.getScriptDirPath()))
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(GlobalAppContext.get(), e.message, Toast.LENGTH_LONG).show()
            null
        }

    }

    fun runWithBroadcastSender(file: File): ScriptExecution {
        return AutoJs.getInstance().scriptEngineService.execute(ScriptFile(file).toSource(), BROADCAST_SENDER_SCRIPT_EXECUTION_LISTENER,
                ExecutionConfig(workingDirectory = workingDirectory(file)))
    }


    fun runRepeatedly(scriptFile: ScriptFile, loopTimes: Int, delay: Long, interval: Long): ScriptExecution {
        val source = scriptFile.toSource()
        val directoryPath = workingDirectory(scriptFile)
        return AutoJs.getInstance().scriptEngineService.execute(source, ExecutionConfig(workingDirectory = directoryPath,
                delay = delay, loopTimes = loopTimes, interval = interval))
    }

    private fun workingDirectory(file: File): String =
            file.absoluteFile.parent ?: Pref.getScriptDirPath()

    fun getRhinoException(throwable: Throwable?): RhinoException? {
        var e = throwable
        while (e != null) {
            if (e is RhinoException) {
                return e
            }
            e = e.cause
        }
        return null
    }

    fun send(file: ScriptFile) {
        val context = GlobalAppContext.get()
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, IntentUtil.getUriOfFile(context, file.path, AppFileProvider.AUTHORITY)),
                GlobalAppContext.getString(R.string.text_send)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    }
}
