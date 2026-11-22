package com.handlefix;

import android.content.Context;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import de.robv.android.xposed.XposedHelpers;

/**
 * 相机原生摄影风格状态和 6.8 的提交入口。
 *
 * <p>作者的多 LUT 目录不属于这里：{@link ClassicStyleController} 仍独立负责
 * ClassicStyleStore。双风格拍摄使用经典/生动状态，6.8 的录像键使用原生风格列表。</p>
 */
public final class LeicaStateAdapter {
    public enum State { CLASSIC, VIBRANT, UNKNOWN }

    private static volatile String sLastUnknown;

    private LeicaStateAdapter() {
    }

    public static State read(ClassLoader classLoader, int mode) {
        try {
            Object component = component(classLoader);
            if (component == null) {
                return State.UNKNOWN;
            }
            Object rawValue = XposedHelpers.callMethod(component, "getComponentValue", mode);
            String raw = rawValue instanceof String ? (String) rawValue : null;
            if (CameraBuilds.isCamera68Port(HandleFixModule.CAMERA_VERSION_CODE)) {
                // 6.8 的 2 是原色，不能用其他标签字段把它判成经典或生动。
                return camera68State(raw);
            }
            State byLabel = stateFromItemLabel(component, raw);
            if (byLabel != State.UNKNOWN) {
                return byLabel;
            }
            // 6.6.510 的 r2.m 实际项顺序是 value=0（默认/徕卡生动）、value=1（经典）、
            // value=2（自然）。旧 f0.l 同样使用 0/1 二态；只对这两个已核实值回退，
            // 其它值保持 UNKNOWN，不能把自然/第三方模式误判成生动。
            if ("0".equals(raw)) return State.VIBRANT;
            if ("1".equals(raw)) return State.CLASSIC;
            logUnknown(raw);
            return State.UNKNOWN;
        } catch (Throwable ignored) {
            return State.UNKNOWN;
        }
    }

    /**
     * 按真实 dex 描述符选择 CV 组件；JADX 为解决大小写冲突生成的包名前缀不能使用。
     */
    private static ComponentSpec componentSpec(long versionCode) {
        if (CameraBuilds.isCamera68Port(versionCode)
                || CameraBuilds.isPhoenixOs4Family(versionCode)
                || versionCode == CameraBuilds.OFFICIAL_OS4_6_6_510_VERSION_CODE) {
            return new ComponentSpec("g2.a", "r2.m", "x");
        }
        if (versionCode == CameraBuilds.OFFICIAL_OS3_6_4_370_VERSION_CODE) {
            return new ComponentSpec("Z.a", "b0.x", "v");
        }
        if (versionCode == CameraBuilds.OFFICIAL_OS3_6_4_530_VERSION_CODE) {
            return new ComponentSpec("b0.a", "d0.z", "v");
        }
        // Prometheus V5.1/NRV 共用旧仓库链；运行时类缺失会返回 UNKNOWN。
        return new ComponentSpec("d0.a", "f0.l", "v");
    }

    private static final class ComponentSpec {
        final String repositoryClass;
        final String componentClass;
        final String repositoryGetter;

        ComponentSpec(String repositoryClass, String componentClass,
                String repositoryGetter) {
            this.repositoryClass = repositoryClass;
            this.componentClass = componentClass;
            this.repositoryGetter = repositoryGetter;
        }
    }

    public static boolean isVibrant(ClassLoader classLoader, int mode) {
        return read(classLoader, mode) == State.VIBRANT;
    }

    private static State camera68State(String raw) {
        if ("0".equals(raw)) return State.VIBRANT;
        if ("1".equals(raw)) return State.CLASSIC;
        return State.UNKNOWN;
    }

    /** Dual-style capture switches only between its two capture states. */
    static boolean toggleCamera68CvType(ClassLoader loader, int mode) {
        State current = read(loader, mode);
        if (current == State.UNKNOWN) return false;
        return applyCamera68CvType(loader, mode, current == State.CLASSIC ? "0" : "1");
    }

    static void cycleCamera68CvType(ClassLoader loader, int mode) {
        try {
            Object component = editableCamera68Component(loader, mode);
            if (component == null) return;
            // r2.m.m follows the native list for the active mode and lens.
            String next = (String) XposedHelpers.callMethod(component, "m", mode);
            if (next == null || next.equals(XposedHelpers.callMethod(
                    component, "getComponentValue", mode))) return;
            if (!commitCamera68CvType(loader, mode, component, next)) return;

            Class<?> topBarProtocol = XposedHelpers.findClass("R6.o1", loader);
            Optional<?> topBar = (Optional<?>) XposedHelpers.callStaticMethod(topBarProtocol, "a");
            if (topBar.isPresent()) {
                XposedHelpers.callMethod(topBar.get(), "X0", new int[] {190});
            }
        } catch (RuntimeException | LinkageError error) {
            HandleFixModule.log("leica-state: 6.8 native CV cycle failed", error);
        }
    }

    /**
     * 复用 1770 的摄影风格提交协议。r6.W.qd 负责写 r2.m、处理 CamStyle 并通知当前模块。
     * 双风格事务只接收现有列表中可选的经典/生动，保留原色和相机的互斥选择。
     */
    static boolean applyCamera68CvType(ClassLoader loader, int mode, String target) {
        if (camera68State(target) == State.UNKNOWN) return false;
        try {
            Object component = editableCamera68Component(loader, mode);
            if (component == null) return false;
            Object current = XposedHelpers.callMethod(component, "getComponentValue", mode);
            if (!(current instanceof String) || camera68State((String) current) == State.UNKNOWN) {
                return false;
            }
            if (target.equals(current)) return true;
            List<?> items = (List<?>) XposedHelpers.callMethod(component, "getItems");
            if (items == null) return false;
            boolean offered = false;
            for (Object item : items) {
                if (target.equals(XposedHelpers.getObjectField(item, "r"))) {
                    offered = true;
                    break;
                }
            }
            if (!offered) return false;

            return commitCamera68CvType(loader, mode, component, target);
        } catch (Throwable error) {
            HandleFixModule.log("leica-state: 6.8 native CV request failed", error);
            return false;
        }
    }

    private static Object component(ClassLoader loader) {
        ComponentSpec spec = componentSpec(HandleFixModule.CAMERA_VERSION_CODE);
        Class<?> repositoryClass = XposedHelpers.findClass(spec.repositoryClass, loader);
        Object repository = XposedHelpers.callStaticMethod(repositoryClass, "a");
        Class<?> componentClass = XposedHelpers.findClass(spec.componentClass, loader);
        return XposedHelpers.callMethod(repository, spec.repositoryGetter, componentClass);
    }

    private static Object editableCamera68Component(ClassLoader loader, int mode) {
        if (!CameraBuilds.isCamera68Port(HandleFixModule.CAMERA_VERSION_CODE)
                || !ModeConfig.isCamera68Mapping()) return null;
        Object component = component(loader);
        Context context = currentApplication();
        if (component == null || context == null
                || Boolean.TRUE.equals(XposedHelpers.callMethod(component, "s", mode))
                || XposedHelpers.callMethod(component, "getDisableReasonString", context) != null) {
            return null;
        }
        return component;
    }

    private static boolean commitCamera68CvType(ClassLoader loader, int mode,
            Object component, String target) {
        Class<?> protocolType = XposedHelpers.findClass("R6.D", loader);
        Optional<?> protocol = (Optional<?>) XposedHelpers.callStaticMethod(protocolType, "a");
        if (!protocol.isPresent()) return false;
        Object owner = protocol.get();
        if (!Integer.valueOf(mode).equals(XposedHelpers.callMethod(owner, "Jc"))) return false;
        XposedHelpers.callMethod(owner, "qd", target);
        return target.equals(XposedHelpers.callMethod(component, "getComponentValue", mode));
    }

    /** 读取并显示作者当前经典风格；失败返回 null，不伪造名称。 */
    public static String currentClassicStyleLabel(ClassLoader classLoader, long cameraVersionCode) {
        return ClassicStyleController.currentStyleLabel(classLoader, cameraVersionCode);
    }

    private static State stateFromItemLabel(Object component, String raw) {
        if (raw == null) return State.UNKNOWN;
        try {
            Object itemsValue = XposedHelpers.callMethod(component, "getItems");
            if (!(itemsValue instanceof List)) return State.UNKNOWN;
            Context context = currentApplication();
            if (context == null) return State.UNKNOWN;
            for (Object item : (List<?>) itemsValue) {
                if (item == null || !containsStringField(item, raw)) continue;
                State state = stateFromResourceFields(context, item);
                if (state != State.UNKNOWN) return state;
            }
        } catch (Throwable ignored) {
            // 某些旧版本没有公开 getItems；下面的 0/1 回退仍然受限于已知值。
        }
        return State.UNKNOWN;
    }

    private static boolean containsStringField(Object item, String expected) {
        Class<?> type = item.getClass();
        while (type != null) {
            for (Field field : type.getDeclaredFields()) {
                if (field.getType() != String.class) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(item);
                    if (expected.equals(value)) return true;
                } catch (Throwable ignored) {
                }
            }
            type = type.getSuperclass();
        }
        return false;
    }

    private static State stateFromResourceFields(Context context, Object item) {
        Class<?> type = item.getClass();
        while (type != null) {
            for (Field field : type.getDeclaredFields()) {
                if (field.getType() != int.class) continue;
                try {
                    field.setAccessible(true);
                    int id = field.getInt(item);
                    if (id <= 0) continue;
                    String text = context.getResources().getString(id).toLowerCase(Locale.ROOT);
                    if (text.contains("生动") || text.contains("vibrant") || text.contains("vivid")) {
                        return State.VIBRANT;
                    }
                    if (text.contains("经典") || text.contains("classic")) {
                        return State.CLASSIC;
                    }
                } catch (Throwable ignored) {
                }
            }
            type = type.getSuperclass();
        }
        return State.UNKNOWN;
    }

    private static Context currentApplication() {
        try {
            Object app = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null),
                    "currentApplication");
            return app instanceof Context ? (Context) app : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void logUnknown(String raw) {
        String marker = String.valueOf(raw);
        if (!marker.equals(sLastUnknown)) {
            sLastUnknown = marker;
            HandleFixModule.debug("leica-state: unknown CV_TYPE value=" + marker);
        }
    }
}
