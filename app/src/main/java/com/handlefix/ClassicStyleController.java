package com.handlefix;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XposedHelpers;

/**
 * 运行时徕卡经典风格目录与切换控制器。
 *
 * <p>已验证的 Phoenix-0.3.0 与 NRV-1.6.1 作者 LSP 都把风格入口注入到相机进程的
 * {@code com.prometheus.camera.filters.ClassicStyleStore}。两版的目录并不完全相同，
 * 因此不能在模块中硬编码“第几个风格”，也不能把作者的 {@code selected_model} 偏好
 * 当成切换 API。这里统一通过真实的 {@code modelIds/modelLabels/selectedModel/
 * selectModel} 读取、设置并读回，保证 LUT 刷新与 UI 状态由作者 LSP 完成。</p>
 *
 * <p>设置页无法直接访问相机进程的 ClassLoader。相机进程首次发现目录后，将目录写入
 * 自身私有缓存，由设置页通过模块已有的 Root 能力读取。用户保存的只是稳定 ID 白名单，
 * 并且每次切换前都会和当前目录求交集，作者升级/删改风格不会产生未知 ID 或越界访问。</p>
 */
public final class ClassicStyleController {
    private static final String STORE_CLASS =
            "com.prometheus.camera.filters.ClassicStyleStore";

    private static final String LSP_PACKAGE = "com.prometheus.camera.rev";
    private static final int CATALOG_PROBE_ATTEMPTS = 6;
    private static final long CATALOG_PROBE_INTERVAL_MS = 1000L;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    /** ClassLoader 会随相机进程重建；只在当前进程内缓存，失败不缓存。 */
    private static volatile Class<?> sStoreClass;
    private static volatile ClassLoader sStoreClassLoader;
    private static volatile boolean sCatalogProbeInstalled;
    private static volatile boolean sCatalogPublished;
    /** 缓存暂不可写时只记一次原因；探测仍按既定次数重试，避免刷 LSPosed 日志。 */
    private static volatile boolean sCatalogPublishFailureLogged;

    private ClassicStyleController() {
    }

    /**
     * 版本候选门。Phoenix 后续同基线包不能因 versionCode 改变而在设置页消失；NRV
     * 仍只接受 1.6.1。真正的运行时能力由 {@link #isRuntimeSupported(ClassLoader, long)}
     * 继续检查，避免把版本号猜测变成强制 Hook。
     */
    public static boolean isSupportedVersion(long cameraVersionCode) {
        return CameraBuilds.isClassicStyleSwitchSupported(cameraVersionCode);
    }

    /**
     * 检查作者 LSP 是否真的注入了当前版本所需的 ClassicStyleStore API。
     * 版本族只负责决定“可以尝试”，方法签名才决定“此进程可以执行”；检查失败时
     * 选择页仍可保留配置，但按键路径返回 null 并放行相机原生行为。
     */
    public static boolean isRuntimeSupported(ClassLoader classLoader, long cameraVersionCode) {
        if (!isSupportedVersion(cameraVersionCode)) {
            return false;
        }
        try {
            Class<?> store = getStoreClass(classLoader);
            return hasStaticMethod(store, "modelIds")
                    && hasStaticMethod(store, "selectedModel", Context.class)
                    && hasStaticMethod(store, "selectModel", Context.class, String.class)
                    && hasStaticMethod(store, "label", String.class);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 供 Provider/UI 记录版本来源，避免把两个风格目录混在一起。 */
    public static String profileForVersion(long cameraVersionCode) {
        return ClassicStyleCatalogBridge.profileForVersion(cameraVersionCode);
    }

    /**
     * 相机进程加载后异步探测作者 LSP。LSP 可能晚于 HandleFix 注入，故只重试有限次数；
     * 探测只是发布目录，不改变当前风格，也不影响任何按键热路径。
     */
    public static void installCatalogProbe(final ClassLoader classLoader,
            final long cameraVersionCode) {
        if (!isSupportedVersion(cameraVersionCode) || sCatalogProbeInstalled) {
            return;
        }
        sCatalogProbeInstalled = true;
        final int[] attempts = new int[]{0};
        final Runnable probe = new Runnable() {
            @Override
            public void run() {
                if (publishCurrentCatalog(classLoader, cameraVersionCode)) {
                    return;
                }
                attempts[0]++;
                if (attempts[0] < CATALOG_PROBE_ATTEMPTS) {
                    MAIN_HANDLER.postDelayed(this, CATALOG_PROBE_INTERVAL_MS);
                }
            }
        };
        MAIN_HANDLER.post(probe);
    }

    /**
     * 按当前作者目录循环下一项。返回已读回的显示名称；任何前置条件失败都返回 null，
     * 让调用方保留原生按键行为。
     */
    public static String selectNextStyle(ClassLoader classLoader, long cameraVersionCode) {
        if (!isRuntimeSupported(classLoader, cameraVersionCode)
                || Looper.myLooper() != Looper.getMainLooper()) {
            return null;
        }
        Context context = currentApplication();
        if (context == null) {
            return null;
        }
        try {
            Class<?> store = getStoreClass(classLoader);
            String[] ids = readStringArray(XposedHelpers.callStaticMethod(store, "modelIds"));
            if (ids.length == 0) {
                return null;
            }
            String[] labels = readStringArraySafe(store, "modelLabels");
            publishCatalog(context, cameraVersionCode, ids, labels);

            List<String> candidates = configuredCandidates(cameraVersionCode, ids);
            if (candidates.isEmpty()) {
                return null;
            }
            Object currentValue = XposedHelpers.callStaticMethod(store, "selectedModel", context);
            String current = currentValue instanceof String ? (String) currentValue : null;
            String next = nextId(candidates, current);
            if (next == null) {
                return null;
            }

            // setter 是作者 LSP 的真实 LUT 刷新入口；禁止用 SharedPreferences 直写替代。
            XposedHelpers.callStaticMethod(store, "selectModel", context, next);
            Object actualValue = XposedHelpers.callStaticMethod(store, "selectedModel", context);
            String actual = actualValue instanceof String ? (String) actualValue : null;
            if (!next.equals(actual)) {
                HandleFixModule.log("classic-style: readback mismatch expected=" + next
                        + " actual=" + actual);
                return null;
            }
            Object labelValue = XposedHelpers.callStaticMethod(store, "label", next);
            if (labelValue instanceof String && ((String) labelValue).trim().length() > 0) {
                return ((String) labelValue).trim();
            }
            int index = indexOf(ids, next);
            return index >= 0 && index < labels.length && labels[index] != null
                    && labels[index].trim().length() > 0 ? labels[index].trim() : next;
        } catch (Throwable t) {
            // 反射失败只放行原生按键；不在每次拨动时刷 LSPosed 日志。
            if (!sCatalogPublished) {
                HandleFixModule.debug("classic-style: runtime store unavailable");
            }
            return null;
        }
    }

    /**
     * 只读取当前作者风格，不改变目录或选择。用于原生经典/生动切换完成后补发
     * “当前经典风格”提示；读取失败返回 null，绝不伪造或把生动模式当成 LUT。
     */
    public static String currentStyleLabel(ClassLoader classLoader, long cameraVersionCode) {
        if (!isRuntimeSupported(classLoader, cameraVersionCode)) {
            return null;
        }
        Context context = currentApplication();
        if (context == null) return null;
        try {
            Class<?> store = getStoreClass(classLoader);
            Object selected = XposedHelpers.callStaticMethod(store, "selectedModel", context);
            if (!(selected instanceof String) || ((String) selected).trim().length() == 0) {
                return null;
            }
            Object label = XposedHelpers.callStaticMethod(store, "label", selected);
            if (label instanceof String && ((String) label).trim().length() > 0) {
                return ((String) label).trim();
            }
            return ((String) selected).trim();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 当前进程发现目录后发布给设置页；返回是否成功。 */
    private static boolean publishCurrentCatalog(ClassLoader classLoader, long cameraVersionCode) {
        Context context = currentApplication();
        if (context == null) {
            return false;
        }
        try {
            Class<?> store = getStoreClass(classLoader);
            String[] ids = readStringArray(XposedHelpers.callStaticMethod(store, "modelIds"));
            if (ids.length == 0) {
                return false;
            }
            String[] labels = readStringArraySafe(store, "modelLabels");
            // “读到作者目录”和“设置页可读取目录”是两件事；只有私有缓存落盘成功才停止
            // 重试，不能在仅仅读到运行时数组后误报完成。
            return publishCatalog(context, cameraVersionCode, ids, labels);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean publishCatalog(Context context, long cameraVersionCode,
            String[] ids, String[] labels) {
        String profile = profileForVersion(cameraVersionCode);
        if (profile == null) {
            return false;
        }
        boolean published = ClassicStyleCatalogBridge.publish(context, profile,
                cameraVersionCode, readLspVersion(context), ids, labels);
        if (published) {
            boolean firstPublish = !sCatalogPublished;
            sCatalogPublished = true;
            sCatalogPublishFailureLogged = false;
            if (firstPublish) {
                HandleFixModule.log("classic-style: catalog published profile=" + profile
                        + " count=" + ids.length);
            }
            return true;
        }
        // 设置页目录属于辅助能力；写入失败不影响相机内真实切换，但必须把失败返回给
        // 探测器，否则首次失败后不会再尝试发布。
        logCatalogPublishFailure("camera private cache write failed");
        return false;
    }

    private static void logCatalogPublishFailure(String reason) {
        if (sCatalogPublishFailureLogged) {
            return;
        }
        sCatalogPublishFailureLogged = true;
        HandleFixModule.log("classic-style: catalog publish pending -> " + reason);
    }

    private static long readLspVersion(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(LSP_PACKAGE, 0);
            return info.getLongVersionCode();
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    private static List<String> configuredCandidates(long cameraVersionCode, String[] ids) {
        List<String> all = new ArrayList<String>();
        Set<String> available = new HashSet<String>();
        for (String id : ids) {
            if (id != null && id.trim().length() > 0 && available.add(id.trim())) {
                all.add(id.trim());
            }
        }
        List<String> configured = ModeConfig.getClassicStyleSelection(cameraVersionCode);
        if (configured == null || configured.isEmpty()) {
            // 首次使用或损坏配置：安全默认作者当前全部目录。
            return all;
        }
        List<String> result = new ArrayList<String>();
        for (String id : configured) {
            if (id != null && available.contains(id.trim()) && !result.contains(id.trim())) {
                result.add(id.trim());
            }
        }
        // 旧配置与新目录完全无交集时恢复全部，避免按键“看似成功但不变化”。
        return result.isEmpty() ? all : result;
    }

    private static String nextId(List<String> ids, String current) {
        if (ids.isEmpty()) {
            return null;
        }
        int index = ids.indexOf(current);
        return ids.get(index < 0 ? 0 : (index + 1) % ids.size());
    }

    private static int indexOf(String[] values, String target) {
        for (int i = 0; i < values.length; i++) {
            if (target != null && target.equals(values[i])) {
                return i;
            }
        }
        return -1;
    }

    private static String[] readStringArraySafe(Class<?> store, String method) {
        try {
            return readStringArray(XposedHelpers.callStaticMethod(store, method));
        } catch (Throwable ignored) {
            return new String[0];
        }
    }

    /** 兼容作者不同重打包中 String[]/List<String> 的返回容器。 */
    private static String[] readStringArray(Object value) {
        if (value instanceof String[]) {
            return (String[]) value;
        }
        if (value instanceof List) {
            List<?> source = (List<?>) value;
            List<String> result = new ArrayList<String>();
            for (Object item : source) {
                if (item instanceof String) {
                    result.add((String) item);
                }
            }
            return result.toArray(new String[0]);
        }
        if (value instanceof Object[]) {
            Object[] source = (Object[]) value;
            List<String> result = new ArrayList<String>();
            for (Object item : source) {
                if (item instanceof String) {
                    result.add((String) item);
                }
            }
            return result.toArray(new String[0]);
        }
        return new String[0];
    }

    private static Class<?> getStoreClass(ClassLoader classLoader) throws ClassNotFoundException {
        Class<?> cached = sStoreClass;
        if (cached != null && sStoreClassLoader == classLoader) {
            return cached;
        }
        Class<?> resolved = Class.forName(STORE_CLASS, false, classLoader);
        sStoreClassLoader = classLoader;
        sStoreClass = resolved;
        return resolved;
    }

    private static boolean hasStaticMethod(Class<?> owner, String name, Class<?>... parameterTypes) {
        Method method;
        try {
            method = owner.getDeclaredMethod(name, parameterTypes);
        } catch (NoSuchMethodException ignored) {
            try {
                method = owner.getMethod(name, parameterTypes);
            } catch (NoSuchMethodException ignoredPublic) {
                return false;
            }
        }
        return Modifier.isStatic(method.getModifiers());
    }

    private static Context currentApplication() {
        try {
            Class<?> activityThread = XposedHelpers.findClass("android.app.ActivityThread", null);
            Object application = XposedHelpers.callStaticMethod(activityThread, "currentApplication");
            return application instanceof Context ? (Context) application : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
