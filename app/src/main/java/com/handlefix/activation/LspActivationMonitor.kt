package com.handlefix.activation

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.util.Log
import com.handlefix.all.BuildConfig
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Live API82 injection proof, never an old log or persisted "active" flag.
 * A reply proves this build is loaded in ONE scope, not that every camera hook works.
 * No reply is UNKNOWN: disabled scope, old process and unavailable transport cannot be
 * distinguished here. UNKNOWN must not permanently lock the configuration app.
 */
object LspActivationMonitor {
    enum class Status { CHECKING, ACTIVE, UNKNOWN }
    private val mutableState = MutableStateFlow(Status.CHECKING)
    val state = mutableState.asStateFlow()
    private var generation = 0

    // Gate and ResultReceiver run on the main thread. Cancellation invalidates late replies.
    suspend fun check(context: Context) {
        val attempt = ++generation
        mutableState.value = Status.CHECKING
        val nonce = UUID.randomUUID().toString()
        val loaded = withTimeoutOrNull(2000) {
            suspendCancellableCoroutine<Boolean> { continuation ->
                val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                    override fun onReceiveResult(code: Int, data: Bundle?) {
                        if (!continuation.isActive || attempt != generation || code != 1) return
                        if (data?.getString("nonce") != nonce
                            || data.getInt("version") != BuildConfig.VERSION_CODE) return
                        val scope = data.getString("scope")
                        if (scope != "android" && scope != "com.android.camera") return
                        Log.i("HandleFixActivation", "Live probe accepted scope=$scope version=${BuildConfig.VERSION_CODE}")
                        continuation.resume(true)
                    }
                }
                // Explicit delivery reaches existing receivers only. Never launch camera,
                // request Root, alter scope, or use an old camera environment record.
                for (target in arrayOf("android", "com.android.camera")) {
                    try {
                        context.sendBroadcast(Intent(ActivationProbeProtocol.ACTION).setPackage(target)
                            .putExtra("nonce", nonce).putExtra("reply", receiver))
                    } catch (error: RuntimeException) {
                        Log.w("HandleFixActivation", "Probe unavailable for $target", error)
                    }
                }
            }
        } == true
        if (attempt == generation) {
            mutableState.value = if (loaded) Status.ACTIVE else Status.UNKNOWN
            if (!loaded) Log.i("HandleFixActivation", "Live probe unconfirmed; settings remain accessible")
        }
    }
}
