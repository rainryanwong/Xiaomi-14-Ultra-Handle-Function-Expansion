package com.handlefix;

import android.content.Context;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Locale;

import de.robv.android.xposed.XposedHelpers;

/**
 * 经典/生动二态的只读适配器。
 *
 * <p>作者的多 LUT 目录不属于这里：{@link ClassicStyleController} 仍独立负责
 * ClassicStyleStore。此类只读取相机原生 CV_TYPE 组件，用于在用户按下多 LUT
 * 风格键时判断是否处于“徕卡生动”，以及在原生二态切换后决定是否补发当前经典风格提示。</p>
 */
public final class LeicaStateAdapter {
    public enum State { CLASSIC, VIBRANT, UNKNOWN }

    private static volatile String sLastUnknown;

    private LeicaStateAdapter() {
    }

    public static State read(ClassLoader classLoader, int mode) {
        try {
            ComponentSpec spec = componentSpec(HandleFixModule.CAMERA_VERSION_CODE);
            Class<?> repositoryClass = XposedHelpers.findClass(spec.repositoryClass, classLoader);
            Object repository = XposedHelpers.callStaticMethod(repositoryClass, "a");
            Class<?> componentClass = XposedHelpers.findClass(spec.componentClass, classLoader);
            Object component = XposedHelpers.callMethod(repository, spec.repositoryGetter,
                    componentClass);
            if (component == null) {
                return State.UNKNOWN;
            }
            Object rawValue = XposedHelpers.callMethod(component, "getComponentValue", mode);
            String raw = rawValue instanceof String ? (String) rawValue : null;
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
        if (CameraBuilds.isPhoenixOs4Family(versionCode)
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
