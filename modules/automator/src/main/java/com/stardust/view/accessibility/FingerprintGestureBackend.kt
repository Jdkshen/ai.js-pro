package com.stardust.view.accessibility

import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.FingerprintGestureController
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi

/** API 26 types stay in this class so the base service can still load on older Android. */
@RequiresApi(26)
internal class FingerprintGestureBackend(
    private val service: AccessibilityService,
    private val dispatcher: FingerprintGestureDispatcher
) : FingerprintGestureDispatcher.Backend {
    private val handler = Handler(Looper.getMainLooper())
    private val controller = service.fingerprintGestureController
    private var registered = false
    private var ownsRequestFlag = false
    private val callback = object : FingerprintGestureController.FingerprintGestureCallback() {
        override fun onGestureDetected(gesture: Int) {
            dispatcher.gestureDetected(this@FingerprintGestureBackend, gesture)
        }

        override fun onGestureDetectionAvailabilityChanged(available: Boolean) {
            dispatcher.availabilityChanged(this@FingerprintGestureBackend, available)
        }
    }

    override fun setListening(listening: Boolean) {
        // Always enqueue: never call a script while it holds the shared dispatcher lock.
        handler.post {
            try {
                val info = service.serviceInfo ?: return@post
                val flag = AccessibilityServiceInfo.FLAG_REQUEST_FINGERPRINT_GESTURES
                if (listening) {
                    if (info.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_FINGERPRINT_GESTURES == 0) {
                        dispatcher.availabilityChanged(this, false)
                        return@post
                    }
                    if (info.flags and flag == 0) {
                        ownsRequestFlag = true
                        info.flags = info.flags or flag
                        service.serviceInfo = info
                    }
                    if (!registered) {
                        controller.registerFingerprintGestureCallback(callback, handler)
                        registered = true
                    }
                    dispatcher.availabilityChanged(this, controller.isGestureDetectionAvailable)
                } else {
                    if (registered) {
                        controller.unregisterFingerprintGestureCallback(callback)
                        registered = false
                    }
                    if (ownsRequestFlag) {
                        info.flags = info.flags and flag.inv()
                        service.serviceInfo = info
                        ownsRequestFlag = false
                    }
                    dispatcher.availabilityChanged(this, false)
                }
            } catch (error: RuntimeException) {
                Log.w("FingerprintGesture", "Cannot update fingerprint gesture capture", error)
                dispatcher.availabilityChanged(this, false)
            }
        }
    }
}
