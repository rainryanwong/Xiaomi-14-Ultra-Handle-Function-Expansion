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
        val loaded = request(context, arrayOf("android", "com.android.camera")) { _, _ ->
            attempt == generation
        } != null
        if (attempt == generation) {
            mutableState.value = if (loaded) Status.ACTIVE else Status.UNKNOWN
            if (!loaded) Log.i("HandleFixActivation", "Live probe unconfirmed; settings remain accessible")
        }
    }

    suspend fun cameraCapability(context: Context, key: String): Boolean? =
        request(context, arrayOf("com.android.camera")) { scope, result ->
            scope == "com.android.camera" && result.containsKey(key)
        }?.getBoolean(key)

    suspend fun camera68ClassicModes(context: Context): IntArray =
        request(context, arrayOf("com.android.camera")) { scope, result ->
            scope == "com.android.camera" &&
                result.containsKey(ActivationProbeProtocol.CAMERA68_CLASSIC_MODES)
        }?.getIntArray(ActivationProbeProtocol.CAMERA68_CLASSIC_MODES) ?: intArrayOf()

    private suspend fun request(
        context: Context,
        targets: Array<String>,
        accept: (String, Bundle) -> Boolean,
    ): Bundle? {
        val nonce = UUID.randomUUID().toString()
        return withTimeoutOrNull(2000) {
            suspendCancellableCoroutine { continuation ->
                val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                    override fun onReceiveResult(code: Int, data: Bundle?) {
                        if (!continuation.isActive || code != 1) return
                        if (data?.getString("nonce") != nonce
                            || data.getInt("version") != BuildConfig.VERSION_CODE) return
                        val scope = data.getString("scope")
                        if (scope != "android" && scope != "com.android.camera") return
                        if (!accept(scope, data)) return
                        Log.i("HandleFixActivation", "Live probe accepted scope=$scope version=${BuildConfig.VERSION_CODE}")
                        continuation.resume(data)
                    }
                }
                // Explicit delivery reaches existing receivers only. Never launch camera,
                // request Root, alter scope, or use an old camera environment record.
                for (target in targets) {
                    try {
                        context.sendBroadcast(Intent(ActivationProbeProtocol.ACTION).setPackage(target)
                            .putExtra("nonce", nonce).putExtra("reply", receiver))
                    } catch (error: RuntimeException) {
                        Log.w("HandleFixActivation", "Probe unavailable for $target", error)
                    }
                }
            }
        }
    }
}
