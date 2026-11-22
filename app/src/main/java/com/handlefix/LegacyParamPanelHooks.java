package com.handlefix;

import android.content.Context;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.lang.ref.WeakReference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Prometheus/V5.1 与 NRV 旧版的“专业模式参数预设（新）”录像键适配。
 *
 * <p>旧版没有 Phoenix 的 {@code q6.U0.S0(...)}，但 K3.u1 仍保留完整的
 * {@code i0(MotionEvent, boolean, String)} 原生工作区入口。该入口会先通过
 * {@code f4.f0} 加载 {@code attr_workspace} 对应的工作区，再调用同一个类里的
 * {@code h1(...)} 发送 {@code key_common} 埋点。{@code h1} 本身只是统计入口，不能
 * 单独打开面板，所以录像键必须复用 i0，而不是直接调用 h1。</p>
 *
 * <p>二次按键关闭也复用相机原生 Fragment。已反查的旧版工作区实现是
 * {@code FragmentManualWorkspaceBottom}，它在 onAttach/onDetach 管理
 * {@code attr_workspace}，并在 {@code onBackEvent(4)} 中负责动画、卸载和状态清理。
 * 只保存弱引用；若 Fragment 已经被用户界面关闭或正在重建，则放弃关闭并重新走原生
 * 打开入口，避免误隐藏主界面或遗留工作区状态。</p>
 *
 * <p>本类只在 Prometheus 映射方案（promFix=true）安装，当前覆盖已反查出同一
 * {@code K3.u1} 链的 V5.1/V5.0、NRV-1.5.0 与 NRV-1.6.1；更早的移植包若类或签名
 * 不同会在安装阶段失败并放行原生按键。Phoenix 有自己的 {@link PhoenixClassicStyleHooks}
 * 入口；官方 370/530 则由 {@link OfficialParamPanelHooks} 使用各自的 FeatureUI 协议，
 * 三类相机互不复用混淆入口。</p>
 */
public final class LegacyParamPanelHooks {
    private static final String KEY_IMPL_CLASS = "K3.u1";
    private static final String WORKSPACE_FRAGMENT_CLASS =
            "com.android.camera2.compat.theme.custom.mm.manually.FragmentManualWorkspaceBottom";
    private static final int KEYCODE_RECORD = 313;
    private static final int MODULE_MANUAL = 167;

    /** 工作区由相机异步创建/销毁，弱引用不会阻止 Fragment 回收。 */
    private static volatile WeakReference<Object> sWorkspaceFragment = new WeakReference<>(null);

    private LegacyParamPanelHooks() {
    }

    /** 旧版 Prometheus/NRV 共用的 K3.u1 + FragmentManualWorkspaceBottom 链。 */
    public static void installIfSupported(final ClassLoader classLoader) {
        if (!HandleFixModule.promFix()) {
            HandleFixModule.log("legacy param-panel: promFix OFF, hooks skipped");
            return;
        }
        HandleFixModule.install("legacy-param-panel-record", new Runnable() {
            @Override
            public void run() {
                hookRecordButton(classLoader);
            }
        });
        HandleFixModule.install("legacy-param-panel-workspace", new Runnable() {
            @Override
            public void run() {
                hookWorkspaceFragment(classLoader);
            }
        });
    }

    private static void hookRecordButton(final ClassLoader classLoader) {
        XposedHelpers.findAndHookMethod(KEY_IMPL_CLASS, classLoader, "onKeyUp",
                int.class, KeyEvent.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (!shouldToggle(param, classLoader)) {
                                return;
                            }
                            if (closeParamPresetPanel()) {
                                param.setResult(Boolean.TRUE);
                                return;
                            }
                            if (openParamPresetPanel(param.thisObject)) {
                                param.setResult(Boolean.TRUE);
                            }
                        } catch (Throwable t) {
                            // 反射失败时放行，保留相机原生录像键行为。
                            HandleFixModule.log("legacy param-panel: record handler failed", t);
                        }
                    }
                });
        HandleFixModule.log("legacy param-panel: K3.u1.onKeyUp hooked");
    }

    private static void hookWorkspaceFragment(final ClassLoader classLoader) {
        XposedHelpers.findAndHookMethod(WORKSPACE_FRAGMENT_CLASS, classLoader,
                "onAttach", Context.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        sWorkspaceFragment = new WeakReference<>(param.thisObject);
                    }
                });
        XposedHelpers.findAndHookMethod(WORKSPACE_FRAGMENT_CLASS, classLoader,
                "onDetach", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        clearWorkspaceFragment(param.thisObject);
                    }
                });
        HandleFixModule.log("legacy workspace: FragmentManualWorkspaceBottom lifecycle hooked");
    }

    /** 只处理 313 的短按抬起；长按预览照片仍交给既有长按逻辑。 */
    private static boolean shouldToggle(XC_MethodHook.MethodHookParam param,
            ClassLoader classLoader) {
        if (!HandleFixModule.promFix() || param.args == null || param.args.length < 2
                || !(param.args[0] instanceof Integer) || !(param.args[1] instanceof KeyEvent)
                || ((Integer) param.args[0]).intValue() != KEYCODE_RECORD) {
            return false;
        }
        if (!RecordLongPressDispatcher.isShortRelease(param)) {
            return false;
        }
        int mode = HandleFixModule.currentModuleIndex(param.thisObject, classLoader);
        return mode == MODULE_MANUAL
                && ModeConfig.F_PARAM_PANEL.equals(
                        ModeConfig.getFunc(ModeConfig.KEY_RECORD, mode));
    }

    /**
     * 复用 K3.u1.i0 的原生工作区分支。
     *
     * <p>i0 的第一个参数只用于判断波轮来源；录像键没有真实的 MotionEvent，使用
     * 短生命周期的 ACTION_DOWN 探针满足签名，不注入系统事件，调用完成后立即回收。
     * 直接调用 h1 只会延迟发送 key_common 统计，不会创建工作区 Fragment。</p>
     */
    private static boolean openParamPresetPanel(Object keyEventImpl) {
        MotionEvent probe = null;
        try {
            long now = SystemClock.uptimeMillis();
            probe = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 0f, 0f, 0);
            XposedHelpers.callMethod(keyEventImpl, "i0", probe, Boolean.FALSE, "attr_workspace");
            return true;
        } catch (Throwable t) {
            HandleFixModule.log("legacy param-panel: K3.u1.i0 failed", t);
            return false;
        } finally {
            if (probe != null) {
                probe.recycle();
            }
        }
    }

    /** 让旧版工作区自己处理关闭动画、卸载和 attr_workspace 清理。 */
    private static boolean closeParamPresetPanel() {
        Object fragment = sWorkspaceFragment.get();
        if (fragment == null) {
            return false;
        }
        try {
            Object result = XposedHelpers.callMethod(fragment, "onBackEvent", 4);
            return result instanceof Boolean && ((Boolean) result).booleanValue();
        } catch (Throwable t) {
            HandleFixModule.debug("legacy param-panel: native close unavailable");
            return false;
        }
    }

    private static void clearWorkspaceFragment(Object candidate) {
        Object current = sWorkspaceFragment.get();
        if (current == candidate) {
            sWorkspaceFragment = new WeakReference<>(null);
        }
    }
}
