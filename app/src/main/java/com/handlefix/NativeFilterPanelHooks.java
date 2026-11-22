package com.handlefix;

import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 录像键短按的原生滤镜面板控制器。
 *
 * <p>不同相机族只在“如何取得宿主、如何打开”上使用不同描述符；可见性判断、当前
 * 滤镜子页确认和按键所有权集中在这里，避免 OemHooks/ModeAwareHooks 各自堆叠一套
 * 面板状态。每次按键都读取当前协议宿主，不缓存跨按键 UI 状态。</p>
 */
public final class NativeFilterPanelHooks {
    private static final int KEYCODE_RECORD = 313;

    private enum Profile {
        OS4_Q6,
        PROMETHEUS_OS3_K3,
        OEM_OS3_370,
        OEM_OS3_530
    }

    private NativeFilterPanelHooks() {}

    public static boolean isSupported(long versionCode, boolean prometheusProfile) {
        return resolveProfile(versionCode, prometheusProfile) != null;
    }

    public static boolean isSupportedMode(int mode) {
        return mode == 163 || mode == 225 || mode == 162 || mode == 171
                || mode == 175 || mode == 167 || mode == 180;
    }

    private static boolean isSupportedRuntimeMode(long versionCode, boolean profile, int mode) {
        return isSupportedMode(mode) || (profile && CameraBuilds.isPhoenixOs4Family(versionCode)
                && ModeConfig.isStreetSpecialConfigAliasMode(mode));
    }

    private static Profile resolveProfile(long versionCode, boolean prometheusProfile) {
        if (CameraBuilds.isPhoenixOs4Family(versionCode)) {
            return prometheusProfile ? Profile.OS4_Q6 : null;
        }
        if (versionCode == CameraBuilds.OFFICIAL_OS4_6_6_510_VERSION_CODE) {
            // 某些 Prometheus 组合直接使用官方 6.6.510 相机本体。按实际相机
            // versionCode/描述符复用 OS4 链，不能依据 LSP 的产品名称猜测入口。
            return Profile.OS4_Q6;
        }
        if (prometheusProfile && CameraBuilds.isPrometheusLegacyFilterCandidate(versionCode)) {
            return Profile.PROMETHEUS_OS3_K3;
        }
        if (!prometheusProfile && versionCode == CameraBuilds.OFFICIAL_OS3_6_4_370_VERSION_CODE) {
            return Profile.OEM_OS3_370;
        }
        if (!prometheusProfile && versionCode == CameraBuilds.OFFICIAL_OS3_6_4_530_VERSION_CODE) {
            return Profile.OEM_OS3_530;
        }
        return null;
    }

    /**
     * Prometheus 路由可能搭配未经修改的官方 6.6.510 相机本体；仅给这个精确
     * versionCode 的 q6/Q6 组合补滤镜入口。实际为 75 系列的 NRV/V5.1 仍由
     * K3.u1 分发器调用本控制器。
     */
    public static void installPrometheusOs4BridgeIfSupported(final ClassLoader loader,
            final long versionCode) {
        if (versionCode != CameraBuilds.OFFICIAL_OS4_6_6_510_VERSION_CODE) return;
        HandleFixModule.install("filter-panel-prometheus-os4", new Runnable() {
            @Override
            public void run() {
                XposedHelpers.findAndHookMethod("q6.U0", loader, "onKeyUp",
                        int.class, KeyEvent.class, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (((Integer) param.args[0]).intValue() != KEYCODE_RECORD) return;
                                OemHooks.updateOemMode(param.thisObject, loader);
                                int mode = HandleFixModule.OEM_CURRENT_MODE;
                                if (handleRecordKey(param, loader, versionCode, true, mode)) {
                                    param.setResult(Boolean.TRUE);
                                }
                            }
                        });
                // 官方 6.6.510 可能在用户保留 Prometheus 映射时进入本 bridge，因而不会
                // 安装 OemHooks 的 q6.U0 拨轮入口。这里仅为同一精确 versionCode 补上
                // ACTION_SCROLL；handleWheelEvent 内部仍严格门控前摄 162/163 和真实滤镜页。
                XposedHelpers.findAndHookMethod("q6.U0", loader, "onGenericMotionEvent",
                        MotionEvent.class, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                OemHooks.updateOemMode(param.thisObject, loader);
                                int mode = HandleFixModule.OEM_CURRENT_MODE;
                                if (handleWheelEvent(param, loader, versionCode, true, mode)) {
                                    param.setResult(Boolean.TRUE);
                                }
                            }
                        });
            }
        });
    }

    public static boolean handleRecordKey(XC_MethodHook.MethodHookParam param,
            ClassLoader loader, long versionCode, boolean prometheusProfile, int mode) {
        if (param == null || param.args == null || param.args.length < 2
                || !(param.args[1] instanceof KeyEvent)) return false;
        Profile profile = resolveProfile(versionCode, prometheusProfile);
        if (profile == null || !matchesOwner(profile, param.thisObject)
                || !RecordLongPressDispatcher.isShortRelease(param)
                || !isSupportedRuntimeMode(versionCode, prometheusProfile, mode)
                || !ModeConfig.F_FILTER.equals(ModeConfig.getFunc(ModeConfig.KEY_RECORD, mode))) {
            return false;
        }
        try {
            return profile == Profile.OS4_Q6
                    ? handleOs4(param.thisObject, loader)
                    : handleLegacy(profile, param.thisObject, loader);
        } catch (Throwable error) {
            HandleFixModule.log("[filter-panel] native host operation failed profile=" + profile,
                    error);
            return false;
        }
    }

    /**
     * 滤镜面板可见时，让手柄拨轮选择相邻滤镜。
     *
     * <p>官方 OS3 的 {@code C3.F1/A3.Q1} 只会按
     * {@code pref_camera_handle_wheel} 继续执行 EV 等固定映射；录像键映射为滤镜并
     * 不会改变该值。V5.1/NRV 的 K3 路径在前摄会于 {@code i0(attr_filter)} 的主分发
     * 前返回。这里仅在当前真实滤镜子页已经显示时接管
     * {@link MotionEvent#ACTION_SCROLL}，复用 Fragment 自己的选中入口。面板关闭、
     * 列表未初始化或反射失败时返回 false，保留相机原生拨轮行为。</p>
     */
    public static boolean handleWheelEvent(XC_MethodHook.MethodHookParam param,
            ClassLoader loader, long versionCode, boolean prometheusProfile, int mode) {
        Profile profile = resolveProfile(versionCode, prometheusProfile);
        if (profile == null
                || (profile == Profile.OS4_Q6 && (mode != 162 && mode != 163))
                || param == null || !matchesOwner(profile, param.thisObject)
                || param.args == null || param.args.length == 0
                || !(param.args[0] instanceof MotionEvent)
                || !isSupportedRuntimeMode(versionCode, prometheusProfile, mode)
                || !ModeConfig.F_FILTER.equals(
                ModeConfig.getFunc(ModeConfig.KEY_RECORD, mode))) {
            return false;
        }
        // Phoenix 与官方 6.6.510 共用 q6.U0/O9.i 描述符，但只修复已实测失败的
        // 前摄拍照/录像。后摄和其他模式继续使用相机原生拨轮分发。
        if (profile == Profile.OS4_Q6 && !isCurrentFrontCamera(param.thisObject)) {
            return false;
        }

        MotionEvent event = (MotionEvent) param.args[0];
        if (event.getActionMasked() != MotionEvent.ACTION_SCROLL) return false;
        float axis = event.getAxisValue(MotionEvent.AXIS_SCROLL);
        if (axis == 0.0f) return false;

        try {
            if (profile == Profile.OS4_Q6) {
                return handleOs4WheelEvent(loader, mode, axis);
            }
            String protocolClass;
            switch (profile) {
                case PROMETHEUS_OS3_K3: protocolClass = "f4.j"; break;
                case OEM_OS3_370: protocolClass = "V3.j"; break;
                case OEM_OS3_530: protocolClass = "X3.j"; break;
                default: return false;
            }
            Object host = optionalHost(loader, protocolClass, "impl");
            if (host == null
                    || !"com.android.camera.fragment.FragmentBeauty".equals(
                    host.getClass().getName())
                    || !panelState(host).visible()) {
                return false;
            }

            Object fragment = findVisibleLegacyFilterFragment(host);
            if (fragment == null) return false;
            Object itemsObject = XposedHelpers.getObjectField(fragment, "mFilterDataList");
            if (!(itemsObject instanceof List) || ((List<?>) itemsObject).isEmpty()) {
                return false;
            }

            int size = ((List<?>) itemsObject).size();
            Object currentObject = XposedHelpers.callMethod(fragment, "getCurrentIndex");
            if (!(currentObject instanceof Integer)) return false;
            int current = ((Integer) currentObject).intValue();
            if (current < 0 || current >= size) return false;

            // 与原生 KeyEventImpl 的 positive 语义保持一致：正轴取下一项，负轴取上一项。
            int target = Math.max(0, Math.min(size - 1, current + (axis > 0.0f ? 1 : -1)));
            if (target != current) {
                Object recycler = XposedHelpers.getObjectField(fragment, "mRecyclerView");
                if (recycler == null) return false;

                if (profile == Profile.PROMETHEUS_OS3_K3
                        || profile == Profile.OEM_OS3_370
                        || profile == Profile.OEM_OS3_530) {
                    XposedHelpers.callMethod(recycler, "stopScroll");
                    Object layoutManager = XposedHelpers.callMethod(recycler, "getLayoutManager");
                    Object snapHelper = XposedHelpers.callMethod(recycler, "getSnapHelper");
                    Object targetView = layoutManager == null ? null
                            : XposedHelpers.callMethod(layoutManager, "findViewByPosition",
                            Integer.valueOf(target + 1));
                    Object distanceObject = snapHelper == null || !(targetView instanceof View)
                            ? null : XposedHelpers.callMethod(snapHelper,
                            "calculateDistanceToFinalSnap", layoutManager, targetView);

                    if (distanceObject instanceof int[]
                            && ((int[]) distanceObject).length >= 2) {
                        int[] distance = (int[]) distanceObject;
                        // 拨轮脉冲的中位间隔远短于平滑动画。先同步两侧逻辑索引，
                        // 再按相机 SnapHelper 的精确距离即时滚动，避免动画反复取消。
                        XposedHelpers.callMethod(recycler, "setOnclickStatus", Boolean.FALSE);
                        XposedHelpers.callMethod(recycler, "updateCurrentIndex",
                                Integer.valueOf(target));
                        XposedHelpers.callMethod(fragment, "onFilterItemChange",
                                Integer.valueOf(target), Boolean.TRUE);
                        XposedHelpers.callMethod(recycler, "scrollBy",
                                Integer.valueOf(distance[0]), Integer.valueOf(distance[1]));

                    } else {
                        // 目标项通常与当前项相邻并已布局；若极少数情况下不可见，
                        // 回退到相机原生完整同步入口，避免把拨轮错误放行给 EV。
                        if (profile == Profile.PROMETHEUS_OS3_K3) {
                            // K3 的条目值字段与 530 不同。相邻项尚未布局时不猜字段名，
                            // 使用三版均已核对的 Fragment 公开选择入口作为安全回退。
                            XposedHelpers.callMethod(recycler, "setOnclickStatus", Boolean.TRUE);
                            XposedHelpers.callMethod(recycler, "smoothScrollToPosition",
                                    Integer.valueOf(target + 1));
                            XposedHelpers.callMethod(fragment, "onFilterItemChange",
                                    Integer.valueOf(target), Boolean.TRUE);
                        } else {
                            Object targetItem = ((List<?>) itemsObject).get(target);
                            Object targetValue = XposedHelpers.getObjectField(targetItem, "p");
                            if (!(targetValue instanceof String)) return false;
                            int filterId = Integer.parseInt((String) targetValue);
                            XposedHelpers.callMethod(fragment, "updateSelectFilter",
                                    Integer.valueOf(filterId));
                        }
                    }
                }
            }
            return true;
        } catch (Throwable error) {
            HandleFixModule.log("[filter-panel] native wheel operation failed profile="
                    + profile, error);
            return false;
        }
    }

    /**
     * Phoenix/OS4 滤镜页使用 O9.i 基类及 FilterSelectViewCV，adapter 索引与滤镜
     * 索引一一对应，没有旧 K3/OS3 列表的 +1 占位。同步真实选择和 RecyclerView
     * 后才消费事件；任何结构不匹配均放行相机原生逻辑。
     */
    private static boolean handleOs4WheelEvent(ClassLoader loader, int mode, float axis)
            throws Throwable {
        Object host = optionalHost(loader, "Q6.j", "a");
        if (host == null || !"com.android.camera.fragment.L".equals(
                host.getClass().getName()) || !panelState(host).visible()) {
            return false;
        }

        Object fragment = findCurrentOs4FilterPage(host, loader);
        if (fragment == null) return false;
        Object itemsObject = XposedHelpers.getObjectField(fragment, "Q");
        Object recycler = XposedHelpers.getObjectField(fragment, "L");
        Object layoutManager = XposedHelpers.getObjectField(fragment, "R");
        if (!(itemsObject instanceof List) || ((List<?>) itemsObject).isEmpty()
                || recycler == null || layoutManager == null) {
            return false;
        }

        int size = ((List<?>) itemsObject).size();
        Object currentObject = XposedHelpers.callMethod(fragment, "a0");
        if (!(currentObject instanceof Number)) return false;
        int current = ((Number) currentObject).intValue();
        if (current < 0 || current >= size) return false;
        int target = Math.max(0, Math.min(size - 1,
                current + (axis > 0.0f ? 1 : -1)));
        if (target != current) {
            XposedHelpers.callMethod(recycler, "stopScroll");
            Object snapHelper = XposedHelpers.callMethod(recycler, "getSnapHelper");
            Object targetView = XposedHelpers.callMethod(layoutManager, "findViewByPosition",
                    Integer.valueOf(target));
            Object distanceObject = snapHelper == null || !(targetView instanceof View)
                    ? null : XposedHelpers.callMethod(snapHelper,
                    "calculateDistanceToFinalSnap", layoutManager, targetView);
            if (!(distanceObject instanceof int[]) || ((int[]) distanceObject).length < 2) {
                return false;
            }

            int[] distance = (int[]) distanceObject;
            XposedHelpers.callMethod(recycler, "setOnclickStatus", Boolean.FALSE);
            // Hr 同步 Fragment/adapter 当前项，E9 复用相机真实滤镜选择入口。
            XposedHelpers.callMethod(fragment, "Hr", Integer.valueOf(target));
            XposedHelpers.callMethod(fragment, "E9", Integer.valueOf(target), Boolean.TRUE);
            XposedHelpers.callMethod(recycler, "scrollBy",
                    Integer.valueOf(distance[0]), Integer.valueOf(distance[1]));
            XposedHelpers.setIntField(recycler, "b", target);
        }
        return true;
    }

    /** 从 FragmentBeauty 的 child FragmentManager 取得真实、正在显示的滤镜子页。 */
    private static Object findVisibleLegacyFilterFragment(Object host) {
        Object manager = XposedHelpers.callMethod(host, "getChildFragmentManager");
        Object fragmentsObject = XposedHelpers.callMethod(manager, "getFragments");
        if (!(fragmentsObject instanceof List)) return null;
        for (Object fragment : (List<?>) fragmentsObject) {
            if (fragment == null) continue;
            String name = fragment.getClass().getName();
            if (!"com.android.camera.fragment.FragmentFilter".equals(name)
                    && !"com.android.camera2.compat.theme.custom.mm.filter.FragmentMasterFilterMM"
                    .equals(name)) {
                continue;
            }
            boolean added = Boolean.TRUE.equals(XposedHelpers.callMethod(fragment, "isAdded"));
            boolean hidden = Boolean.TRUE.equals(XposedHelpers.callMethod(fragment, "isHidden"));
            Object view = XposedHelpers.callMethod(fragment, "getView");
            if (added && !hidden && view instanceof View && ((View) view).isShown()) {
                return fragment;
            }
        }
        return null;
    }

    private static boolean matchesOwner(Profile profile, Object owner) {
        if (owner == null) return false;
        String name = owner.getClass().getName();
        switch (profile) {
            case OS4_Q6: return "q6.U0".equals(name);
            case PROMETHEUS_OS3_K3: return "K3.u1".equals(name);
            case OEM_OS3_370: return "A3.Q1".equals(name);
            case OEM_OS3_530: return "C3.F1".equals(name);
            default: return false;
        }
    }

    private static boolean isCurrentFrontCamera(Object owner) {
        try {
            Object optional = XposedHelpers.callMethod(owner, "h0");
            if (!(optional instanceof Optional) || !((Optional<?>) optional).isPresent()) {
                return false;
            }
            Object module = ((Optional<?>) optional).get();
            Object cameraId = XposedHelpers.callMethod(module, "getActualCameraId");
            return cameraId instanceof Number && ((Number) cameraId).intValue() == 1;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean handleOs4(Object owner, ClassLoader loader) throws Throwable {
        Object host = optionalHost(loader, "Q6.j", "a");
        if (host != null) {
            if (!"com.android.camera.fragment.L".equals(host.getClass().getName())
                    || host.getClass().getMethod("dr", boolean.class).getReturnType() != void.class
                    || host.getClass().getMethod("er").getReturnType() != void.class) {
                return false;
            }
            PanelState state = panelState(host);
            if (state.visible()) {
                if (!isCurrentOs4FilterPage(host, loader)) {
                    return false;
                }
                XposedHelpers.callMethod(host, "dr", false);
                XposedHelpers.callMethod(host, "er");
                return true;
            }
        }
        return openOs4(owner);
    }

    private static boolean handleLegacy(Profile profile, Object owner, ClassLoader loader)
            throws Throwable {
        String protocolClass;
        switch (profile) {
            case PROMETHEUS_OS3_K3: protocolClass = "f4.j"; break;
            case OEM_OS3_370: protocolClass = "V3.j"; break;
            case OEM_OS3_530: protocolClass = "X3.j"; break;
            default: return false;
        }
        Object host = optionalHost(loader, protocolClass, "impl");
        if (host != null) {
            if (!"com.android.camera.fragment.FragmentBeauty".equals(host.getClass().getName())) {
                return false;
            }
            Method back = host.getClass().getMethod("onBackEvent", int.class);
            if (back.getReturnType() != boolean.class) return false;
            PanelState state = panelState(host);
            if (state.visible()) {
                if (!isCurrentLegacyFilterPage(host, loader)) {
                    return false;
                }
                // FragmentBeauty 自己的 onBackEvent 会进入 Ui/uh/ah，除卸载容器外还重置
                // 当前面板状态并通知顶部/底部协议。返回 false 也可能已经完成清理，因此只
                // 记录传播结果，不允许按键继续落入录像动作。
                XposedHelpers.callMethod(host, "onBackEvent", 6);
                return true;
            }
        }
        return openLegacy(profile, owner, loader);
    }

    private static Object optionalHost(ClassLoader loader, String protocolClass,
            String accessor) throws Throwable {
        Class<?> protocol = XposedHelpers.findClass(protocolClass, loader);
        Object result = XposedHelpers.callStaticMethod(protocol, accessor);
        return result instanceof Optional ? ((Optional<?>) result).orElse(null) : null;
    }

    private static PanelState panelState(Object host) {
        Object view = XposedHelpers.callMethod(host, "getView");
        boolean hidden = Boolean.TRUE.equals(XposedHelpers.callMethod(host, "isHidden"));
        boolean added = Boolean.TRUE.equals(XposedHelpers.callMethod(host, "isAdded"));
        boolean shown = view instanceof View && ((View) view).isShown();
        return new PanelState(added, hidden, shown);
    }

    private static boolean isCurrentOs4FilterPage(Object host, ClassLoader loader) {
        return findCurrentOs4FilterPage(host, loader) != null;
    }

    private static Object findCurrentOs4FilterPage(Object host, ClassLoader loader) {
        Object pager = XposedHelpers.getObjectField(host, "j");
        Object adapter = XposedHelpers.getObjectField(host, "k");
        if (pager == null || adapter == null) return null;
        int index = (Integer) XposedHelpers.callMethod(pager, "getCurrentItem");
        int count = (Integer) XposedHelpers.callMethod(adapter, "getItemCount");
        if (index < 0 || index >= count) return null;
        Object page = XposedHelpers.callMethod(adapter, "w", index);
        boolean filter = XposedHelpers.findClass("com.android.camera.fragment.Y", loader)
                .isInstance(page) || XposedHelpers.findClass("O9.l", loader).isInstance(page);
        return filter ? page : null;
    }

    private static boolean isCurrentLegacyFilterPage(Object host, ClassLoader loader) {
        // 旧族提供公开 getter；不读取 f/a/j/k 等随混淆变化的字段。
        Object pager = XposedHelpers.callMethod(host, "getViewPager2");
        Object adapter = XposedHelpers.callMethod(host, "getPagerAdapter");
        if (pager == null || adapter == null) return false;
        int index = (Integer) XposedHelpers.callMethod(pager, "getCurrentItem");
        int count = (Integer) XposedHelpers.callMethod(adapter, "getItemCount");
        if (index < 0 || index >= count) return false;
        Object page = XposedHelpers.callMethod(adapter, "createFragment", index);
        boolean filter = XposedHelpers.findClass("com.android.camera.fragment.FragmentFilter",
                loader).isInstance(page)
                || XposedHelpers.findClass(
                "com.android.camera2.compat.theme.custom.mm.filter.FragmentMasterFilterMM",
                loader).isInstance(page);
        return filter;
    }

    /** MotionEvent 仅作为相机既有方法参数，不进入系统输入队列。 */
    private static MotionEvent obtainProbe() {
        long now = SystemClock.uptimeMillis();
        return MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 0f, 0f, 0);
    }

    private static boolean openOs4(Object owner) {
        MotionEvent probe = obtainProbe();
        try {
            XposedHelpers.callMethod(owner, "S0", probe, false, "attr_filter");
            return true;
        } finally {
            probe.recycle();
        }
    }

    private static boolean openLegacy(Profile profile, Object owner, ClassLoader loader)
            throws Throwable {
        if (profile == Profile.PROMETHEUS_OS3_K3) {
            return openPrometheusLegacyFilter(loader);
        }
        return openOemOs3Filter(profile, loader);
    }

    /**
     * 走官方 OS3 相机的原生滤镜面板内层入口。
     *
     * <p>不能从模块直接调用顶栏总分发 {@code q7(212)}/{@code p7(212)}：顶栏按钮
     * 只会在相机已经为当前模式准备好 shine 列表时出现，而开发者映射允许在更多模式
     * 选择“滤镜选择”。总分发在这些模式中会把尚未初始化的 {@code l0/n0} 传给
     * {@code FragmentBeauty}，最终在 {@code initView()} 遍历 null 列表时崩溃。
     *
     * <p>这里复用总分发内部真正负责打开面板的协议方法：从当前
     * {@code ComponentRunningShine} 读取相机已经生成的条目，经当前主题的
     * {@code filterShineForBeauty()} 过滤后，再调用 370 的
     * {@code V3.B.Hh(List,int,String)} 或 530 的
     * {@code X3.B.Gh(List,int,String)}。任一必要对象或列表为空时返回 false，
     * 让录像键继续走相机原生逻辑，绝不创建半初始化 Fragment。</p>
     */
    private static boolean openOemOs3Filter(Profile profile, ClassLoader loader)
            throws Throwable {
        final boolean is370 = profile == Profile.OEM_OS3_370;
        if (!is370 && profile != Profile.OEM_OS3_530) return false;

        String repositoryClass = is370 ? "Z.a" : "b0.a";
        String repositoryAccessor = is370 ? "j" : "i";
        String componentClass = is370 ? "f0.d0" : "h0.c0";
        String protocolClass = is370 ? "V3.B" : "X3.B";
        String openMethod = is370 ? "Hh" : "Gh";

        Object repository = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass(repositoryClass, loader), repositoryAccessor);
        Class<?> componentType = XposedHelpers.findClass(componentClass, loader);
        Object component = XposedHelpers.callMethod(repository, "v", componentType);
        if (component == null
                || Boolean.TRUE.equals(XposedHelpers.callMethod(component, "isEmpty"))) {
            return false;
        }

        Object source = XposedHelpers.callMethod(component, "getItems");
        if (!(source instanceof java.util.List) || ((java.util.List<?>) source).isEmpty()) {
            return false;
        }
        Object theme = XposedHelpers.callStaticMethod(XposedHelpers.findClass(
                "com.android.camera2.compat.theme.MiThemeCompat", loader), "getImpl");
        Object topMenu = XposedHelpers.callMethod(theme, "getOperationNewTopMenu");
        Object filtered = XposedHelpers.callMethod(topMenu, "filterShineForBeauty",
                source, false);
        if (!(filtered instanceof java.util.List)
                || ((java.util.List<?>) filtered).isEmpty()) {
            return false;
        }

        int shineType = XposedHelpers.getIntField(component, "j");
        String pageType = Boolean.TRUE.equals(XposedHelpers.callMethod(component, "i", "16"))
                ? "16" : "7";
        Object protocol = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass(protocolClass, loader), "a");
        if (protocol == null) return false;
        Method method = protocol.getClass().getMethod(
                openMethod, java.util.List.class, int.class, String.class);
        if (method.getReturnType() != void.class) return false;
        XposedHelpers.callMethod(protocol, openMethod, filtered, shineType, pageType);
        return true;
    }

    /**
     * 走旧 Prometheus UI 的原生顶栏滤镜入口。
     *
     * <p>{@code K3.u1.i0(..., "attr_filter")} 是拨轮调整入口，内部会按模式和
     * 当前滤镜组件提前返回；因此它只能在部分模式顺带打开面板。
     * 不能直接调用顶栏总分发 {@code K3.k0.N9()}：原本不展示滤镜顶栏项的
     * 模式没有初始化 {@code b0.m0}，N9 会把 null 列表交给 FragmentBeauty 并导致
     * initView NPE。这里仅复用 {@code K3.b1 -> C2.a} 的原生内层打开步骤：
     * 从当前 {@code j0.b0.B()} 取数据，交给主题过滤器，再调用
     * {@code f4.B.Lh(List,int,String)}。所有面板数据和页类型仍由相机生成；
     * 任一必要值为空时直接放行原生按键，不创建不完整的 Fragment。</p>
     */
    private static boolean openPrometheusLegacyFilter(ClassLoader loader) throws Throwable {
        Object running = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("d0.a", loader), "j");
        Object component = XposedHelpers.callMethod(running, "v",
                XposedHelpers.findClass("j0.b0", loader));
        if (component == null
                || Boolean.TRUE.equals(XposedHelpers.callMethod(component, "isEmpty"))) {
            return false;
        }

        Object source = XposedHelpers.callMethod(component, "B");
        if (!(source instanceof java.util.List) || ((java.util.List<?>) source).isEmpty()) {
            return false;
        }
        Object theme = XposedHelpers.callStaticMethod(XposedHelpers.findClass(
                "com.android.camera2.compat.theme.MiThemeCompat", loader), "getImpl");
        Object topMenu = XposedHelpers.callMethod(theme, "getOperationNewTopMenu");
        Object filtered = XposedHelpers.callMethod(topMenu, "filterShineForBeauty",
                source, false);
        if (!(filtered instanceof java.util.List)
                || ((java.util.List<?>) filtered).isEmpty()) {
            return false;
        }

        int shineType = XposedHelpers.getIntField(component, "j");
        String pageType = Boolean.TRUE.equals(XposedHelpers.callMethod(component, "i", "16"))
                ? "16" : "7";
        Object protocol = optionalHost(loader, "f4.B", "impl");
        if (protocol == null) return false;
        Method open = protocol.getClass().getMethod(
                "Lh", java.util.List.class, int.class, String.class);
        if (open.getReturnType() != void.class) return false;
        XposedHelpers.callMethod(protocol, "Lh", filtered, shineType, pageType);
        return true;
    }

    private static final class PanelState {
        final boolean added;
        final boolean hidden;
        final boolean shown;

        PanelState(boolean added, boolean hidden, boolean shown) {
            this.added = added;
            this.hidden = hidden;
            this.shown = shown;
        }

        boolean visible() {
            return added && !hidden && shown;
        }
    }
}
