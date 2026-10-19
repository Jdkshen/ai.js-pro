package com.stardust.notification

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Parcel
import android.service.notification.StatusBarNotification
import androidx.annotation.RequiresApi
import org.json.JSONObject

import com.stardust.view.accessibility.NotificationListener
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Created by Stardust on 2017/10/30.
 */

@RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
class NotificationListenerService : android.service.notification.NotificationListenerService() {

    private val mNotificationListeners = CopyOnWriteArrayList<NotificationListener>()

    @Volatile
    var connected = false
        private set

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
    }

    override fun onListenerDisconnected() {
        connected = false
        super.onListenerDisconnected()
    }

    private var listenerReceiver: BroadcastReceiver? = null
    @Volatile private var nodeSubscribed = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        val filter = IntentFilter().apply {
            addAction(ACTION_NODE_SUBSCRIBE)
            addAction(ACTION_NODE_UNSUBSCRIBE)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    ACTION_NODE_SUBSCRIBE -> {
                        nodeSubscribed = true
                        android.util.Log.i(TAG, "node notification listener subscribed")
                    }
                    ACTION_NODE_UNSUBSCRIBE -> {
                        nodeSubscribed = false
                        android.util.Log.i(TAG, "node notification listener unsubscribed")
                    }
                }
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(receiver, filter)
            }
            listenerReceiver = receiver
        } catch (error: Throwable) {
            android.util.Log.w(TAG, "register node notification listener receiver failed", error)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        onNotificationPosted(sbn)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        for (listener in mNotificationListeners) {
            listener.onNotification(com.stardust.notification.Notification.create(
                    sbn.notification, sbn.packageName))
        }
        if (!nodeSubscribed) {
            return
        }
        try {
            val extras = sbn.notification.extras
            val payload = JSONObject()
                    .put("id", sbn.id)
                    .put("packageName", sbn.packageName ?: "")
                    .put("title", extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "")
                    .put("text", extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: "")
                    .put("ticker", sbn.notification.tickerText?.toString() ?: "")
                    .put("postTime", sbn.postTime)
            sendBroadcast(Intent(ACTION_NODE_EVENT)
                    .setPackage(packageName)
                    .putExtra("json", payload.toString()))
        } catch (error: Throwable) {
            android.util.Log.w(TAG, "forward notification to node failed", error)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {}

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap) {}

    fun addListener(listener: NotificationListener) {
        mNotificationListeners.add(listener)
    }

    fun removeListener(listener: NotificationListener): Boolean {
        return mNotificationListeners.remove(listener)
    }


    override fun onDestroy() {
        connected = false
        try { listenerReceiver?.let { unregisterReceiver(it) } } catch (error: Throwable) { }
        listenerReceiver = null
        nodeSubscribed = false
        super.onDestroy()
        instance = null
    }

    companion object {
        private const val TAG = "NotificationListenerService"
        const val ACTION_NODE_SUBSCRIBE = "com.jdkshen.aijspro.NODE_NOTIFICATION_LISTENER_SUBSCRIBE"
        const val ACTION_NODE_UNSUBSCRIBE = "com.jdkshen.aijspro.NODE_NOTIFICATION_LISTENER_UNSUBSCRIBE"
        const val ACTION_NODE_EVENT = "com.jdkshen.aijspro.NODE_NOTIFICATION_LISTENER_EVENT"
        @Volatile
        var instance: NotificationListenerService? = null
            private set
    }
}
