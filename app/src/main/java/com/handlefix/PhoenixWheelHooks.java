package com.handlefix;

import android.view.MotionEvent;

import java.util.Optional;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Phoenix OS4 版本族的专业拍照波轮适配。
 *
 * <p>Prometheus/NRV 旧链是在 {@code K3.u1.i0(MotionEvent, boolean, String)}
 * 的第三个参数改写功能值。Phoenix 0.3.0 的同一条原生链改名为
 * {@code q6.U0.S0(MotionEvent, boolean, String)}，而且 Phoenix 分支没有挂载
 * {@link ModeAwareHooks}，所以以前的 J（专业模式参数预设）不会生效。</p>
 *
 * <p>这里只改写真实的 S0 入参，不恢复旧版整套按键/变焦 hook。这样参数预设面板、
 * 波轮步进和 UI 更新仍由 Phoenix 相机自身完成，模块只把 J 映射为原生
 * {@code attr_workspace}。录像键短按打开工作台也复用同一条入口，但“再次按键关闭”
 * 不属于波轮映射：关闭必须由工作台 Fragment 的原生 {@code onBackEvent(4)} 完成，
 * 不能把第二次 {@code S0} 调用误当成 toggle。</p>
 */
public final class PhoenixWheelHooks {
    private static final int MODULE_MANUAL = 167;

    private PhoenixWheelHooks() {
    }

    /** 同一 OS4 基线版本族先尝试；q6.U0/S0 不存在时由安装异常安全放行。 */
    public static void installIfSupported(final ClassLoader classLoader, long cameraVersionCode) {
        if (!CameraBuilds.isPhoenixOs4Family(cameraVersionCode)) {
            return;
        }
        HandleFixModule.install("phoenix-wheel-workspace", new Runnable() {
            @Override
            public void run() {
                hookWorkspaceWheel(classLoader);
            }
        });
    }

    /**
     * Phoenix 6.6.510 的真实波轮入口。第三个参数就是相机准备消费的 attr 名称；
     * 与 JADX 展示名不同，真实 dex 方法是 q6.U0.S0，必须按 dex 名称挂载。
     */
    private static void hookWorkspaceWheel(final ClassLoader classLoader) {
        XposedHelpers.findAndHookMethod("q6.U0", classLoader, "S0",
                MotionEvent.class, Boolean.TYPE, String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (!HandleFixModule.promFix()) {
                                return;
                            }
                            int mode = resolveCurrentMode(param.thisObject);
                            if (mode != MODULE_MANUAL) {
                                return;
                            }
                            String func = ModeConfig.getFunc(ModeConfig.KEY_WHEEL, mode);
                            if (!ModeConfig.F_WORKSP.equals(func)) {
                                return;
                            }
                            // J 是模块配置字母，不是相机原生 attr；交给 Phoenix 的 S0/G1
                            // 继续走原生工作区切换，避免复制一套参数预设 UI。
                            param.args[2] = ModeConfig.funcToAttr(func);
                            HandleFixModule.debug("Phoenix S0 wheel: mode=167 -> attr_workspace");
                        } catch (Throwable t) {
                            // 映射失败时保留相机原始 attr，不能吞掉实体波轮事件。
                        }
                    }
                });
        HandleFixModule.log("Phoenix wheel: q6.U0.S0 hooked (J -> attr_workspace)");
    }

    /** Phoenix U0.h0() -> Optional<module> -> getModuleIndex；失败时使用统一模式缓存。 */
    private static int resolveCurrentMode(Object keyEventImpl) {
        try {
            Object optional = XposedHelpers.callMethod(keyEventImpl, "h0");
            if (optional instanceof Optional && ((Optional<?>) optional).isPresent()) {
                Object module = ((Optional<?>) optional).get();
                Object value = XposedHelpers.callMethod(module, "getModuleIndex");
                if (value instanceof Number && ((Number) value).intValue() > 0) {
                    int mode = ((Number) value).intValue();
                    HandleFixModule.CURRENT_MODE = mode;
                    HandleFixModule.OEM_CURRENT_MODE = mode;
                    return mode;
                }
            }
        } catch (Throwable ignored) {
            // 相机切换瞬间 module 可能还未就绪，下面回退到 Env/Oem 缓存。
        }
        if (HandleFixModule.OEM_CURRENT_MODE > 0) {
            return HandleFixModule.OEM_CURRENT_MODE;
        }
        return HandleFixModule.CURRENT_MODE;
    }
}
