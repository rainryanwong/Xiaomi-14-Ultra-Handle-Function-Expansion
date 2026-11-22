package com.handlefix;

import android.view.KeyEvent;
import android.view.View;

import java.lang.ref.WeakReference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Phoenix OS4 版本族原生徕卡经典/生动二态切换。
 *
 * <p>Phoenix 与 OS4 6.6.510 共用顶栏真实入口 {@code V9.i0.s9(View)}，但它的
 * DataRepository/色彩组件是 {@code g2.a -> r2.m}，不能复用官方相机的
 * {@code p213g2.a -> p595r2.C4164m}，也不能把作者 LSP 的多 LUT 风格当成同一状态。</p>
 *
 * <p>本类负责沿用同一 OS4 6.6.510 基线的 Phoenix 版本族：捕获原生顶栏实例，在录像键长按配置为
 * {@link ModeConfig#F_LEICA} 时重放原生 {@code s9(null)}。状态读回失败就放行按键，
 * 不写偏好、不复制 Toast/UI，也不影响短按的 Phoenix 风格链。</p>
 */
public final class PhoenixLeicaHooks {
    private static final String TOP_BAR_CLASS = "V9.i0";
    private static final String KEY_HANDLER_CLASS = "q6.U0";
    private static final String EXTRA_SHORT_MODE = "handlefix.shortLeicaMode";
    private static final String EXTRA_SHORT_BEFORE = "handlefix.shortLeicaBefore";

    private static volatile WeakReference<Object> sTopBar = new WeakReference<>(null);
    private static volatile boolean sTopBarHookReady;
    private static volatile boolean sKeyHookReady;

    private PhoenixLeicaHooks() {
    }

    /** 只对已用真实 Phoenix APK/dex 核实的 versionCode 安装。 */
    public static void installIfSupported(final ClassLoader classLoader, long cameraVersionCode) {
        if (!CameraBuilds.isPhoenixOs4Family(cameraVersionCode)) {
            return;
        }
        HandleFixModule.install("phoenix-native-leica", new Runnable() {
            @Override
            public void run() {
                hookTopBar(classLoader);
                hookRecordButton(classLoader);
            }
        });
    }

    /** 捕获 Phoenix 的真实顶栏；不替换 s9 返回值，避免破坏菜单刷新链。 */
    private static void hookTopBar(final ClassLoader classLoader) {
        try {
            final Class<?> topBar = XposedHelpers.findClass(TOP_BAR_CLASS, classLoader);
            XposedBridge.hookAllConstructors(topBar, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    sTopBar = new WeakReference<>(param.thisObject);
                }
            });
            XposedHelpers.findAndHookMethod(topBar, "s9", View.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    // 手动点击或模块重放都会刷新缓存，避免复用旧 Fragment 的顶栏实例。
                    sTopBar = new WeakReference<>(param.thisObject);
                }
            });
            sTopBarHookReady = true;
            HandleFixModule.log("phoenix-leica: V9.i0.<init>/s9(View) captured");
        } catch (Throwable t) {
            HandleFixModule.log("phoenix-leica: V9.i0 native entry unavailable", t);
        }
    }

    /** Phoenix 的真实录像键入口 q6.U0.onKeyUp(313, KeyEvent)。 */
    private static void hookRecordButton(final ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(KEY_HANDLER_CLASS, classLoader, "onKeyUp",
                    int.class, KeyEvent.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            // Phoenix OS4 基线的原生 CV_TYPE 入口属于相机本身，不随模块
                            // 首页的“Prometheus Cam/官方相机”映射选择开关。后者只控制
                            // Phoenix 专属的多 LUT、参数面板等消费 hook；这里必须保留，
                            // 否则把方案切到“官方相机”会连原生经典/生动 A 一起隐藏。
                            try {
                                int mode = PhoenixClassicStyleHooks.resolveCurrentMode(param.thisObject);
                                // 229 的短按 A 继续交给相机原生 attr_leica_style 入口；这里只
                                // 保存切换前状态，after 中读回确认后补发当前经典 LUT，不复制
                                // 或提前执行原生切换。
                                if (ModeConfig.isStreetSpecialConfigAliasMode(mode)
                                        && RecordLongPressDispatcher.isShortRelease(param)
                                        && ModeConfig.F_LEICA.equals(ModeConfig.getFunc(
                                        ModeConfig.KEY_RECORD, mode))) {
                                    param.setObjectExtra(EXTRA_SHORT_MODE, Integer.valueOf(mode));
                                    param.setObjectExtra(EXTRA_SHORT_BEFORE,
                                            LeicaStateAdapter.read(classLoader, mode));
                                }
                                RecordLongPressDispatcher.dispatch(param, classLoader, mode,
                                        new RecordLongPressDispatcher.CvToggle() {
                                            @Override
                                            public boolean toggle() {
                                                return toggleCvType(classLoader, mode);
                                            }
                                        });
                            } catch (Throwable t) {
                                // 反射失败时保留相机原生行为，不吞录像键。
                                HandleFixModule.log("phoenix-leica: long record hook failed", t);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object modeValue = param.getObjectExtra(EXTRA_SHORT_MODE);
                                Object beforeValue = param.getObjectExtra(EXTRA_SHORT_BEFORE);
                                if (!(modeValue instanceof Integer)
                                        || beforeValue != LeicaStateAdapter.State.VIBRANT) {
                                    return;
                                }
                                int mode = ((Integer) modeValue).intValue();
                                if (LeicaStateAdapter.read(classLoader, mode)
                                        != LeicaStateAdapter.State.CLASSIC) {
                                    return;
                                }
                                String label = LeicaStateAdapter.currentClassicStyleLabel(
                                        classLoader, HandleFixModule.CAMERA_VERSION_CODE);
                                if (label != null && label.length() > 0) {
                                    PhoenixClassicStyleHooks.showStyleFeedback(label, mode);
                                    HandleFixModule.debug("phoenix-leica: short 229 vibrant -> "
                                            + "classic, style=" + label);
                                }
                            } catch (Throwable t) {
                                // 原生切换已经完成；提示失败不得改变相机按键结果。
                                HandleFixModule.log("phoenix-leica: short style feedback failed", t);
                            }
                        }
                    });
            sKeyHookReady = true;
            HandleFixModule.log("phoenix-leica: q6.U0.onKeyUp record-long dispatcher installed");
        } catch (Throwable t) {
            HandleFixModule.log("phoenix-leica: q6.U0.onKeyUp hook unavailable", t);
        }
    }

    /**
     * 调用 Phoenix 原生顶栏动作并校验 r2.m 的状态变化。
     * 真实 dex 方法名是 g2.a.a()、DataRepository.x(Class)、r2.m.m(int)。
     */
    public static boolean toggleCvType(ClassLoader classLoader, int mode) {
        if (!sTopBarHookReady || !sKeyHookReady
                || !CameraBuilds.isPhoenixOs4Family(HandleFixModule.CAMERA_VERSION_CODE)
                || !ModeConfig.isLeicaStyleMode(mode)) {
            return false;
        }
        Object topBar = sTopBar.get();
        if (topBar == null) {
            HandleFixModule.log("phoenix-leica: top bar instance is not ready");
            return false;
        }
        try {
            Class<?> repositoryClass = XposedHelpers.findClass("g2.a", classLoader);
            Object repository = XposedHelpers.callStaticMethod(repositoryClass, "a");
            Class<?> componentClass = XposedHelpers.findClass("r2.m", classLoader);
            Object component = XposedHelpers.callMethod(repository, "x", componentClass);
            if (component == null) {
                return false;
            }
            String before = (String) XposedHelpers.callMethod(component, "getComponentValue", mode);
            String next = (String) XposedHelpers.callMethod(component, "m", mode);
            if (before == null || next == null || next.equals(before)) {
                return false;
            }

            XposedHelpers.callMethod(topBar, "s9", new Class<?>[]{View.class}, (Object) null);
            String after = (String) XposedHelpers.callMethod(component, "getComponentValue", mode);
            boolean changed = after != null && !before.equals(after);
            if (changed) {
                HandleFixModule.log("phoenix-leica: CV_TYPE mode=" + mode
                        + " " + before + " -> " + after + " (via V9.i0.s9)");
            }
            return changed;
        } catch (Throwable t) {
            HandleFixModule.log("phoenix-leica: native toggle failed", t);
            return false;
        }
    }
}
