/**
 * EnvHooks：版本探测 + 环境记录 + 模式缓存（始终挂载）。
 * - probe*：探测关键类/方法是否存在，写入 ENV_PROBE_TEXT（供 HookProbe 诊断）
 * - env-sink：把环境信息写入相机 shared_prefs 与外部目录（供模块 App 读取）
 * - hookModeCache：缓存当前模式（CURRENT_MODE），供模式感知读取；支持 WHEEL_MODE_SPOOF 伪装
 */
package com.handlefix;

import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.hardware.input.InputManager;
import android.net.Uri;
import android.provider.MediaStore;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import static com.handlefix.HandleFixModule.*;

/**
 * 基础设施：版本探测、环境记录写入、模式缓存（始终挂载）。
 */
public final class EnvHooks {

    private EnvHooks() {
    }

    /** 主类 install 注册表按版本条件调用 */
    public static void install(final ClassLoader cl) {
        HandleFixModule.install("probe-version", new Runnable() {
            public void run() { hookVersionProbe(cl); }
        });
        HandleFixModule.install("env-sink", new Runnable() {
            public void run() { hookEnvSink(cl); }
        });
        HandleFixModule.install("mode-cache", new Runnable() {
            public void run() { hookModeCache(cl); }
        });
    }
    public static void hookEnvSink(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod("android.app.Application", classLoader, "onCreate", new Object[]{new XC_MethodHook() {
                protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) throws Throwable {
                    String str;
                    String str2;
                    PackageInfo packageInfo;
                    try {
                        Object obj = methodHookParam.thisObject;
                        if (obj instanceof Context) {
                            Context context = (Context) obj;
                            try {
                                str = "env-sink: env=";
                                str2 = "env-sink ext write error: ";
                                try {
                                    Object objCallStaticMethod = XposedHelpers.callStaticMethod(XposedHelpers.findClass("android.app.ActivityThread", (ClassLoader) null), "currentApplication", new Object[0]);
                                    if ((objCallStaticMethod instanceof Context) && (packageInfo = ((Context) objCallStaticMethod).getPackageManager().getPackageInfo(HandleFixModule.CAM_PKG, 0)) != null) {
                                        String str3 = "-";
                                        try {
                                            str3 = ((Context) objCallStaticMethod).getApplicationInfo().sourceDir;
                                        } catch (Throwable unused) {
                                        }
                                        long appInfoVersionCode = CameraBuilds.readVersionCode(context.getApplicationInfo());
                                        boolean phoenixOs4Family = CameraBuilds.isPhoenixOs4Family(appInfoVersionCode);
                                        String mappingProfile = ModeConfig.isCamera68Mapping() ? "camera68"
                                                : phoenixOs4Family ? "phoenix-os4"
                                                : (HandleFixModule.promFix() ? "prometheus" : "official");
                                        HandleFixModule.ENV_LOAD_LINE = "pkg=com.android.camera ver=" + packageInfo.versionName
                                                + "(" + packageInfo.versionCode + ") appInfoVcode=" + appInfoVersionCode
                                                + " profile=" + mappingProfile + " promFix=" + HandleFixModule.promFix()
                                                + " src=" + str3 + " upd=" + packageInfo.lastUpdateTime;
                                    }
                                } catch (Throwable unused2) {
                                }
                            } catch (Throwable unused3) {
                                str = "env-sink: env=";
                                str2 = "env-sink ext write error: ";
                            }
                            String strBuildEnvRecord = HandleFixModule.buildEnvRecord();
                            try {
                                context.getSharedPreferences("handlefix_env", 0).edit().putString("env", strBuildEnvRecord).putLong("ts", System.currentTimeMillis()).commit();
                                HandleFixModule.log("env-sink: written default: " + context.getFilesDir().getParent() + "/shared_prefs/handlefix_env.xml");
                            } catch (Throwable th) {
                                HandleFixModule.log("env-sink default write error: " + th);
                            }
                            try {
                                Context contextCreateDeviceProtectedStorageContext = context.createDeviceProtectedStorageContext();
                                contextCreateDeviceProtectedStorageContext.getSharedPreferences("handlefix_env", 0).edit().putString("env", strBuildEnvRecord).putLong("ts", System.currentTimeMillis()).commit();
                                HandleFixModule.log("env-sink: written de: " + contextCreateDeviceProtectedStorageContext.getFilesDir().getParent() + "/shared_prefs/handlefix_env.xml");
                            } catch (Throwable th2) {
                                HandleFixModule.log("env-sink de write error: " + th2);
                            }
                            try {
                                File externalFilesDir = context.getExternalFilesDir(null);
                                if (externalFilesDir != null) {
                                    File file = new File(externalFilesDir, "handlefix_env.xml");
                                    String str4 = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n<string name=\"env\">" + strBuildEnvRecord + "</string>\n<long name=\"ts\" value=\"" + System.currentTimeMillis() + "\" />\n</map>";
                                    FileOutputStream fileOutputStream = new FileOutputStream(file);
                                    fileOutputStream.write(str4.getBytes("UTF-8"));
                                    fileOutputStream.close();
                                    HandleFixModule.log("env-sink: written ext: " + file.getAbsolutePath());
                                }
                            } catch (Throwable th3) {
                                HandleFixModule.log(str2 + th3);
                            }
                            HandleFixModule.log(str + HandleFixModule.ENV_LOAD_LINE + " ok=" + HandleFixModule.INSTALL_OK + " fail=" + HandleFixModule.INSTALL_FAIL);
                        }
                    } catch (Throwable th4) {
                        HandleFixModule.log("env-sink error: " + th4);
                    }
                }
            }});
        } catch (Throwable th) {
            log("env-sink hook failed: " + th);
        }
    }
    public static void hookVersionProbe(ClassLoader classLoader) {
        String str3 = "com.android.camera.module.Camera2Module";
        String str4 = "com.android.camera.module.BaseModule";
        String[] strArr = {"C3.F1", "A3.Q1", "W9.a", "U9.a", "i3.e", "g3.c", "H3.a", "H3.f", "com.android.camera.data.data.r", "com.android.camera.data.data.s", "Wh.a", "com.android.camera.Camera", "com.android.camera.module.Camera2Module", "com.android.camera.module.BaseModule", "com.android.camera.features.mode.street.StreetModule"};
        for (String str5 : strArr) {
            try {
                XposedHelpers.findClass(str5, classLoader);
                log("probe: class OK " + str5);
                PROBE_LINES.add("probe: class OK " + str5);
            } catch (Throwable unused) {
                log("probe: class MISSING " + str5);
                PROBE_LINES.add("probe: class MISSING " + str5);
            }
        }
        probeMethods(classLoader, "C3.F1", new String[]{"onKeyDown", "onKeyUp", "onGenericMotionEvent", ModeConfig.F_SOUND});
        probeMethods(classLoader, "A3.Q1", new String[]{"onKeyDown", "onKeyUp", "onGenericMotionEvent", ModeConfig.F_FOCUSP});
        probeMethods(classLoader, "W9.a", new String[]{"l"});
        probeMethods(classLoader, "U9.a", new String[]{"l"});
        probeMethods(classLoader, "i3.e", new String[]{"p4"});
        probeMethods(classLoader, "g3.c", new String[]{"n4"});
        probeMethods(classLoader, "com.android.camera.data.data.r", new String[]{"c", "e"});
        probeMethods(classLoader, "com.android.camera.data.data.s", new String[]{"c", "e"});
        probeMethods(classLoader, "H3.a", new String[]{"d", "i"});
        probeMethods(classLoader, "H3.f", new String[]{"f"});
        probeMethods(classLoader, "com.android.camera.data.data.s", new String[]{"c", "e"});
        probeMethods(classLoader, str3, new String[]{"performKeyClicked", "performMiHandlePressed", "doKeyShutterLongPress", "doKeyShutterSnap", "doShutterLongPressAction"});
        probeMethods(classLoader, str4, new String[]{"getModuleIndex"});
        log("probe-version done");
        StringBuilder sb = new StringBuilder();
        Iterator<String> it = PROBE_LINES.iterator();
        while (it.hasNext()) {
            sb.append(it.next()).append("\n");
        }
        ENV_PROBE_TEXT = sb.toString();
    }


    /**
     * 版本探测辅助：检查指定类的指定方法是否存在，结果写入 ENV_PROBE_TEXT。
     * @param str 待探测的类名（字符串，如 "C3.F1"）
     * @param strArr 待探测的方法名列表
     */
    public static void probeMethods(ClassLoader classLoader, String str, String[] strArr) {
        try {
            Class clsFindClass = XposedHelpers.findClass(str, classLoader);
            HashSet hashSet = new HashSet();
            for (Method method : clsFindClass.getDeclaredMethods()) {
                hashSet.add(method.getName());
            }
            StringBuilder sb = new StringBuilder("probe: methods " + str + " :");
            for (String str2 : strArr) {
                sb.append(" ").append(str2).append("=").append(hashSet.contains(str2) ? ModeConfig.F_DEFZOOM : ModeConfig.F_ET);
            }
            log(sb.toString());
            PROBE_LINES.add(sb.toString());
        } catch (Throwable unused) {
            log("probe: methods MISSING " + str);
            PROBE_LINES.add("probe: methods MISSING " + str);
        }
    }
    public static void hookModeCache(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod("com.android.camera.module.BaseModule", classLoader, "getModuleIndex", new Object[]{new XC_MethodHook() {
                protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) throws Throwable {
                    Integer num = (Integer) HandleFixModule.WHEEL_MODE_SPOOF.get();
                    if (num != null) {
                        methodHookParam.setResult(num);
                    }
                }

                protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) throws Throwable {
                    int iIntValue;
                    if (HandleFixModule.WHEEL_MODE_SPOOF.get() == null && (iIntValue = ((Integer) methodHookParam.getResult()).intValue()) > 0 && iIntValue != HandleFixModule.CURRENT_MODE) {
                        HandleFixModule.CURRENT_MODE = iIntValue;
                    }
                }
            }});
        } catch (Throwable th) {
            log("mode-cache: BaseModule.getModuleIndex hook failed: " + th);
        }
        try {
        // OS4 6.6.000460.0/000510.0：模块基类从 BaseModule 改名为 com.android.camera.module.r（getModuleIndex 在此定义）
        try {
            XposedHelpers.findAndHookMethod("com.android.camera.module.r", classLoader, "getModuleIndex", new Object[]{new XC_MethodHook() {
                protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) throws Throwable {
                    int iIntValue = ((Integer) methodHookParam.getResult()).intValue();
                    if (iIntValue <= 0 || iIntValue == HandleFixModule.CURRENT_MODE) {
                        return;
                    }
                    HandleFixModule.CURRENT_MODE = iIntValue;
                }
            }});
            log("mode-cache: com.android.camera.module.r.getModuleIndex hooked (OS4)");
        } catch (Throwable th) {
            log("mode-cache: com.android.camera.module.r.getModuleIndex hook failed: " + th);
        }
            XposedHelpers.findAndHookMethod("com.android.camera.features.mode.pixel.PixelModule", classLoader, "getModuleIndex", new Object[]{new XC_MethodHook() {
                protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) throws Throwable {
                    int iIntValue = ((Integer) methodHookParam.getResult()).intValue();
                    if (iIntValue <= 0 || iIntValue == HandleFixModule.CURRENT_MODE) {
                        return;
                    }
                    HandleFixModule.CURRENT_MODE = iIntValue;
                }
            }});
        } catch (Throwable th2) {
            log("mode-cache: PixelModule.getModuleIndex hook failed: " + th2);
        }
        try {
            XposedHelpers.findAndHookMethod("K3.u1", classLoader, ModeConfig.F_ET, new Object[]{new XC_MethodHook() {
                protected void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) throws Throwable {
                    int iIntValue;
                    try {
                        Object result = methodHookParam.getResult();
                        if (result == null || !((Boolean) XposedHelpers.callMethod(result, "isPresent", new Object[0])).booleanValue() || (iIntValue = ((Integer) XposedHelpers.callMethod(XposedHelpers.callMethod(result, "get", new Object[0]), "getModuleIndex", new Object[0])).intValue()) <= 0 || iIntValue == HandleFixModule.CURRENT_MODE) {
                            return;
                        }
                        HandleFixModule.CURRENT_MODE = iIntValue;
                    } catch (Throwable unused) {
                    }
                }
            }});
        } catch (Throwable th3) {
            log("mode-cache: K3.u1.N hook failed: " + th3);
        }
        log("mode-cache installed");
    }
}
