package com.handlefix.activation;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.ResultReceiver;
import com.handlefix.all.BuildConfig;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Method;

/**
 * Read-only liveness endpoint in existing scopes. LSPosed7854 does not deliver the modern
 * XposedService to API82 modules. Never fake API metadata to obtain that service.
 * Observe initialization only, not key/capture callbacks. No privileged operation is exposed.
 * Failed installation must never prevent the actual camera/system features from installing.
 */
public final class ActivationProbeHooks {
    private static boolean registered;

    public static void install(ClassLoader loader, String scope) {
        try {
            final Method init;
            if ("android".equals(scope)) {
                Class<?> pwm = XposedHelpers.findClass("com.android.server.policy.PhoneWindowManager", loader);
                Class<?> wm = XposedHelpers.findClass("android.view.IWindowManager", loader);
                Class<?> funcs = XposedHelpers.findClass(
                    "com.android.server.policy.WindowManagerPolicy$WindowManagerFuncs", loader);
                // Device services.jar (OS4 Android16) defines Context,Funcs,IWindowManager;
                // older platforms use Context,IWindowManager,Funcs. Match exact descriptors,
                // never hook every init overload: Injector overload has no Context argument.
                init = findSystemInit(pwm, wm, funcs);
            } else if ("com.android.camera".equals(scope)) {
                init = Application.class.getDeclaredMethod("attach", Context.class);
            } else {
                return;
            }
            if (init.getReturnType() != void.class) throw new NoSuchMethodException("Unexpected init return type");
            XposedBridge.hookMethod(init, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable()) return;
                    try {
                        register((Context) param.args[0], scope);
                    } catch (RuntimeException error) {
                        XposedBridge.log("[HandleFixActivation] register failed scope=" + scope + " " + error);
                    }
                }
            });
        } catch (Throwable error) {
            XposedBridge.log("[HandleFixActivation] probe unavailable scope=" + scope + " " + error);
        }
    }

    private static Method findSystemInit(Class<?> pwm, Class<?> wm, Class<?> funcs)
            throws NoSuchMethodException {
        try {
            return pwm.getDeclaredMethod("init", Context.class, funcs, wm);
        } catch (NoSuchMethodException modernAbsent) {
            return pwm.getDeclaredMethod("init", Context.class, wm, funcs);
        }
    }

    private static synchronized void register(Context context, String scope) {
        if (registered || !scope.equals(context.getPackageName())) return;
        context.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                if (!ActivationProbeProtocol.ACTION.equals(intent.getAction())) return;
                try {
                    String nonce = intent.getStringExtra("nonce");
                    ResultReceiver reply = intent.getParcelableExtra("reply", ResultReceiver.class);
                    if (nonce == null || nonce.length() != 36 || reply == null) return;
                    Bundle result = new Bundle();
                    result.putString("nonce", nonce);
                    result.putInt("version", BuildConfig.VERSION_CODE);
                    result.putString("scope", scope);
                    reply.send(1, result);
                } catch (RuntimeException error) {
                    XposedBridge.log("[HandleFixActivation] invalid probe: " + error);
                }
            }
        }, new IntentFilter(ActivationProbeProtocol.ACTION), ActivationProbeProtocol.PERMISSION,
            null, Context.RECEIVER_EXPORTED);
        registered = true;
        XposedBridge.log("[HandleFixActivation] live probe registered scope=" + scope
            + " version=" + BuildConfig.VERSION_CODE);
    }

    private ActivationProbeHooks() {}
}
