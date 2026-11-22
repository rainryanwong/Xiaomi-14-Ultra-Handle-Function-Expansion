package com.handlefix;

import android.app.Activity;
import android.content.Context;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import static com.handlefix.Camera68InputCapabilities.*;
import static com.handlefix.ModeConfig.*;

/** Advanced actions for the 1770 native grip dispatcher. */
public final class Camera68InputHooks {
    private static final String OWNER = "r6.O0";
    private static final String CAMERA_SETTINGS = "com.android.camera.data.data.x";
    private static final int WORKSPACE_CONTAINER = 7;
    private static final int WORKSPACE_FRAGMENT = 209;
    private static final int WORKSPACE_LIST = 210;
    private static final int CLOSE_FROM_KEY = 4;
    private static final String NATIVE_FILTER_WHEEL = "attr_filter";
    private static final String CAM_STYLE_PAGE = "com.android.camera.features.mode.capture.T";
    private static final String CUSTOM_ACTION_HANDLED = "com.handlefix.camera68.handled";
    private static final String SCOPE = "com.handlefix.camera68.recordScope";
    private static final String APERTURE_SCOPE = "com.handlefix.camera68.apertureScope";
    private static final String MANUAL_WHEEL_SCOPE = "com.handlefix.camera68.manualWheelScope";
    private static final int NIGHT_MODE = 173;
    private static final int PRO_VIDEO_MODE = 180;
    private static final int APERTURE_CHANGE = 8;
    private static final int APERTURE_TOP_ITEM = 3392;

    private Camera68InputHooks() {}

    public static void install(ClassLoader loader, long versionCode) {
        if (!CameraBuilds.isCamera68Port(versionCode)) return;
        Camera68ClassicStyleHooks.installCatalogProbe(loader, versionCode);
        HandleFixModule.install("camera68-record", () -> installRecord(loader));
        HandleFixModule.install("camera68-wheel", () -> installWheel(loader));
        HandleFixModule.install("camera68-camstyle-wheel", () -> installCamStyleWheel(loader));
        HandleFixModule.install("camera68-rocker", () -> installZoom(loader));
        HandleFixModule.install("camera68-shutter", () -> installShutter(loader));
    }

    private static final class RecordScope {
        final RecordScope previous;
        final Object owner;
        final KeyEvent event;
        boolean handled;
        boolean professionalWhiteBalance;

        RecordScope(RecordScope previous, Object owner, KeyEvent event) {
            this.previous = previous;
            this.owner = owner;
            this.event = event;
        }
    }

    private static void installRecord(ClassLoader loader) {
        Method release = method(loader, OWNER, "onKeyUp", boolean.class, int.class, KeyEvent.class);
        Method readAction = method(loader, CAMERA_SETTINGS, "d", String.class);
        ThreadLocal<RecordScope> current = new ThreadLocal<>();
        XposedBridge.hookMethod(method(loader, "R6.z0", "a", Optional.class), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                RecordScope scope = current.get();
                if (scope == null || !scope.professionalWhiteBalance || param.hasThrowable()) return;
                scope.professionalWhiteBalance = false;
                // Reached after the native WB guards. The legacy z0 receiver has an empty x1.
                if (openProfessionalWhiteBalance(loader)) {
                    scope.handled = true;
                    param.setResult(Optional.empty());
                }
            }
        });
        XposedBridge.hookMethod(readAction, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                RecordScope scope = current.get();
                if (scope == null || param.hasThrowable()) return;
                if (scope.handled) {
                    param.setResult(CUSTOM_ACTION_HANDLED);
                    return;
                }
                // d() is reached only after the native connection, device and mode-state checks.
                int mode = currentMode(scope.owner);
                if (mode <= 0) return;
                long duration = scope.event.getEventTime() - scope.event.getDownTime();
                boolean longPress = duration >= ModeConfig.getLongPressMs();
                String action = longPress ? ModeConfig.getRecordLongAction(mode, duration)
                        : ModeConfig.getFunc(KEY_RECORD, mode);
                if (!allowsModeAction(KEY_RECORD, mode, action, longPress)) return;
                if (!longPress && F_PHOENIX_CLASSIC_STYLE.equals(action)) {
                    scope.handled = Camera68ClassicStyleHooks.handlePress(loader, mode);
                } else if (F_DEFAULT.equals(action)
                        || !supportsAction(KEY_RECORD, mode, action, longPress)) {
                    return;
                } else if (F_PREVIEW.equals(action)) {
                    scope.handled = previewLastPhoto(loader);
                } else if (F_PARAM_PANEL.equals(action)) {
                    scope.handled = toggleWorkspace(loader, mode);
                } else if (F_FILTER.equals(action)) {
                    scope.handled = toggleFilter(loader, mode);
                } else if (mode == CINEMASTER && F_AWB.equals(action)
                        && openDirectorWhiteBalance(loader)) {
                    scope.handled = true;
                } else if (F_LEICA.equals(action)) {
                    LeicaStateAdapter.cycleCamera68CvType(loader, mode);
                    scope.handled = true;
                } else if (mode == AI_CAPTURE && F_RATION.equals(action)
                        && cycleAiPictureRatio(loader, mode)) {
                    scope.handled = true;
                } else {
                    String attr = recordAttr(action);
                    if (attr != null) {
                        scope.professionalWhiteBalance = F_AWB.equals(action)
                                && (mode == PRO_PHOTO || mode == PRO_VIDEO_MODE);
                        param.setResult(attr);
                    }
                }
                if (scope.handled) param.setResult(CUSTOM_ACTION_HANDLED);
            }
        });
        XposedBridge.hookMethod(release, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                KeyEvent event = (KeyEvent) param.args[1];
                if (!isCamera68Mapping() || (Integer) param.args[0] != 313 || !isN1g(event)
                        || event.isCanceled() || event.getAction() != KeyEvent.ACTION_UP) return;
                RecordScope scope = new RecordScope(current.get(), param.thisObject, event);
                param.setObjectExtra(SCOPE, scope);
                current.set(scope);
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                RecordScope scope = (RecordScope) param.getObjectExtra(SCOPE);
                if (scope == null) return;
                if (scope.previous == null) current.remove();
                else current.set(scope.previous);
                if (scope.handled && !param.hasThrowable()) param.setResult(true);
            }
        });
    }

    private static void installWheel(ClassLoader loader) {
        Method wheel = method(loader, OWNER, "T0", void.class,
                MotionEvent.class, boolean.class, String.class);
        ThreadLocal<Boolean> filterDirection = new ThreadLocal<>();
        ThreadLocal<ApertureScroll> apertureScroll = new ThreadLocal<>();
        ThreadLocal<ManualWheel> manualWheel = new ThreadLocal<>();
        installPhotoFilterWheel(loader, filterDirection);
        installApertureWheel(loader, apertureScroll);
        installManualWheel(loader, manualWheel);
        XposedBridge.hookMethod(wheel, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                MotionEvent event = (MotionEvent) param.args[0];
                if (!isCamera68Mapping() || !isN1g(event)
                        || event.getActionMasked() != MotionEvent.ACTION_SCROLL) return;
                int mode = currentMode(param.thisObject);
                if (mode <= 0) return;
                String action = ModeConfig.getFunc(KEY_WHEEL, mode);
                if (mode == MODE_CAPTURE && usesPhotoFilterWheel(action)) {
                    Object panel = visibleBeautyPanel(loader);
                    if (panel != null && stepFilterPage(XposedHelpers.getObjectField(panel, "r"))) {
                        // Enter the stock filter branch after its camera and input guards.
                        // The panel can have been opened by touch or with the grip.
                        param.setObjectExtra(SCOPE, new Boolean[] {filterDirection.get()});
                        filterDirection.set((Boolean) param.args[1]);
                        param.args[2] = NATIVE_FILTER_WHEEL;
                        return;
                    }
                }
                if (!supportsAction(KEY_WHEEL, mode, action, false)) return;
                String attr = wheelAttr(action);
                if (attr != null) param.args[2] = attr;
                if (mode == AI_CAPTURE && F_EV.equals(action)) {
                    param.setObjectExtra(MANUAL_WHEEL_SCOPE, new ManualWheel[] {manualWheel.get()});
                    manualWheel.set(new ManualWheel((Boolean) param.args[1]));
                }
                if ((mode == NIGHT_MODE || mode == AI_CAPTURE) && F_APERT.equals(action)) {
                    param.setObjectExtra(APERTURE_SCOPE, new ApertureScroll[] {apertureScroll.get()});
                    apertureScroll.set(new ApertureScroll(param.thisObject, mode, (Boolean) param.args[1]));
                }
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                ManualWheel[] previousManual = (ManualWheel[]) param.getObjectExtra(MANUAL_WHEEL_SCOPE);
                if (previousManual != null) {
                    if (previousManual[0] == null) manualWheel.remove();
                    else manualWheel.set(previousManual[0]);
                }
                ApertureScroll[] previousAperture =
                        (ApertureScroll[]) param.getObjectExtra(APERTURE_SCOPE);
                if (previousAperture != null) {
                    if (previousAperture[0] == null) apertureScroll.remove();
                    else apertureScroll.set(previousAperture[0]);
                }
                Boolean[] previous = (Boolean[]) param.getObjectExtra(SCOPE);
                if (previous == null) return;
                if (previous[0] == null) filterDirection.remove();
                else filterDirection.set(previous[0]);
            }
        });
    }

    private static final class ManualWheel {
        final boolean forward;
        boolean visited;

        ManualWheel(boolean forward) {
            this.forward = forward;
        }
    }

    private static void installManualWheel(ClassLoader loader, ThreadLocal<ManualWheel> current) {
        Class<?> ev = XposedHelpers.findClass("r2.F0", loader);
        XposedBridge.hookMethod(method(loader, "Xh.b", "x", Object.class, Class.class), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                ManualWheel scroll = current.get();
                if (scroll == null || scroll.visited || param.args[0] != ev || param.hasThrowable()) return;
                scroll.visited = true;
                // These lookups follow the native T0 input and preview guards.
                if (stepAiExposureCompensation(loader, ev, scroll.forward)) {
                    // The remaining generic EV branch must not apply a second step.
                    param.setResult(null);
                }
            }
        });
    }

    private static boolean stepAiExposureCompensation(ClassLoader loader, Class<?> type,
                                                       boolean forward) {
        Object panel = visibleAiManualPanel(loader);
        if (panel == null) return false;
        Object component = ((List<?>) XposedHelpers.callMethod(panel, "zg")).stream()
                .filter(type::isInstance).findFirst().orElse(null);
        if (component == null) return false;
        Object action = protocol(loader, "R6.a1");
        Object receiver = protocol(loader, "R6.C0");
        if (action == null || receiver == null || moduleMode(receiver) != AI_CAPTURE
                || Boolean.TRUE.equals(XposedHelpers.callMethod(action, "isDoingAction"))
                || protocol(loader, "R6.o1") == null) return false;
        Object previous = XposedHelpers.callMethod(component, "getComponentValue", AI_CAPTURE);
        Object next = XposedHelpers.callMethod(component, "getComponentNextValue", AI_CAPTURE, forward);
        if (!(previous instanceof String) || !(next instanceof String)) return false;
        if (next.equals(previous)) return true;
        XposedHelpers.callMethod(component, "setComponentValue", AI_CAPTURE, next);
        XposedHelpers.callMethod(panel, "Vh", component, previous, next, false, AI_CAPTURE, 0);
        return true;
    }

    private static Object visibleAiManualPanel(ClassLoader loader) {
        Object panel = protocol(loader, "R6.B0");
        return panel != null && XposedHelpers.findClass("Q4.D", loader).isInstance(panel)
                && isVisiblePanel(panel) && XposedHelpers.getIntField(panel, "mCurrentMode") == AI_CAPTURE
                && Boolean.TRUE.equals(XposedHelpers.callMethod(panel, "isEnableClick")) ? panel : null;
    }

    private static final class ApertureScroll {
        final Object owner;
        final int mode;
        final boolean forward;
        boolean visited;

        ApertureScroll(Object owner, int mode, boolean forward) {
            this.owner = owner;
            this.mode = mode;
            this.forward = forward;
        }
    }

    private static void installApertureWheel(ClassLoader loader, ThreadLocal<ApertureScroll> current) {
        Class<?> proAperture = XposedHelpers.findClass("r2.o0", loader);
        XposedBridge.hookMethod(method(loader, "Xh.b", "x", Object.class, Class.class), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.args[0] != proAperture || param.hasThrowable()) return;
                ApertureScroll scroll = current.get();
                if (scroll == null || scroll.visited) return;
                scroll.visited = true;
                // T0 reaches this lookup after checking that the preview can accept the wheel.
                // AI and Night use the running aperture component outside the Pro panel.
                stepRunningAperture(loader, scroll);
            }
        });
    }

    private static void stepRunningAperture(ClassLoader loader, ApertureScroll scroll) {
        Object module = currentModule(scroll.owner);
        Object action = protocol(loader, "R6.a1");
        if (module == null || moduleMode(module) != scroll.mode || action == null
                || Boolean.TRUE.equals(XposedHelpers.callMethod(action, "isDoingAction"))
                || Boolean.TRUE.equals(XposedHelpers.callMethod(action, "isRecording"))
                || XposedHelpers.callMethod(module, "getApertureManager") == null) return;
        Object popup = protocol(loader, "R6.s1");
        if (popup != null && Integer.valueOf(176).equals(XposedHelpers.callMethod(popup, "lm"))) return;

        Class<?> repository = XposedHelpers.findClass("g2.a", loader);
        Object running = XposedHelpers.callStaticMethod(repository, "j");
        Object aperture = XposedHelpers.callMethod(running, "x", XposedHelpers.findClass("v2.k", loader));
        if (aperture == null || !Boolean.TRUE.equals(XposedHelpers.callMethod(aperture, "J"))) return;
        if (scroll.mode == AI_CAPTURE) {
            stepAiAperture(loader, aperture, scroll.forward);
            return;
        }
        Object config = protocol(loader, "R6.D");
        if (config == null) return;
        Object next = XposedHelpers.callMethod(aperture, "getComponentNextValue", scroll.mode, scroll.forward);
        String previous = String.valueOf(XposedHelpers.callMethod(aperture, "A", scroll.mode));
        if (!(next instanceof String) || next.equals(previous)) return;
        XposedHelpers.callMethod(aperture, "setComponentValue", scroll.mode, next);
        XposedHelpers.callMethod(config, "co", APERTURE_CHANGE, next);
        Object topBar = protocol(loader, "R6.o1");
        if (topBar != null) XposedHelpers.callMethod(topBar, "X0", new int[] {APERTURE_TOP_ITEM});
    }

    private static void stepAiAperture(ClassLoader loader, Object aperture, boolean forward) {
        Object panel = visibleAiManualPanel(loader);
        if (panel == null || !((List<?>) XposedHelpers.callMethod(panel, "zg")).contains(aperture)) return;
        Object previous = XposedHelpers.callMethod(aperture, "getComponentValue", AI_CAPTURE);
        Object next = XposedHelpers.callMethod(aperture, "getComponentNextValue", AI_CAPTURE, forward);
        if (!(previous instanceof String) || !(next instanceof String) || next.equals(previous)
                || !Boolean.TRUE.equals(XposedHelpers.callMethod(aperture, "checkValueValid", AI_CAPTURE, next))) return;
        // Compat mounts the running component in the AI panel and owns its touch submission.
        XposedHelpers.callMethod(panel, "Vh", aperture, previous, next, false, AI_CAPTURE, 0);
        Object slider = protocol(loader, "R6.V0");
        if (slider != null && XposedHelpers.findClass("Q4.v", loader).isInstance(slider)
                && isVisiblePanel(slider)
                && XposedHelpers.getObjectField(slider, "j") == aperture) {
            int title = (Integer) XposedHelpers.callMethod(aperture, "getDisplayTitleString");
            boolean automatic = Boolean.TRUE.equals(XposedHelpers.callMethod(aperture, "b"));
            XposedHelpers.callMethod(slider, "c9", title, next, automatic);
        }
    }

    private static boolean openProfessionalWhiteBalance(ClassLoader loader) {
        Object panel = protocol(loader, "R6.B0");
        if (panel == null || !XposedHelpers.findClass("Q4.w0", loader).isInstance(panel)
                || !isVisiblePanel(panel)) return false;
        return openWhiteBalance(loader, panel, (List<?>) XposedHelpers.callMethod(panel, "zg"));
    }

    private static boolean openDirectorWhiteBalance(ClassLoader loader) {
        Object panel = directorPanel(loader);
        return panel != null && openWhiteBalance(loader, panel,
                (List<?>) XposedHelpers.getObjectField(panel, "h"));
    }

    private static Object directorPanel(ClassLoader loader) {
        Object panel = protocol(loader, "R6.v");
        if (!XposedHelpers.findClass("M9.k", loader).isInstance(panel) || !isVisiblePanel(panel)
                || XposedHelpers.getIntField(panel, "mCurrentMode") != CINEMASTER
                || !Boolean.TRUE.equals(XposedHelpers.callMethod(panel, "isEnableClick"))
                || Boolean.TRUE.equals(XposedHelpers.callStaticMethod(
                        XposedHelpers.findClass("V6.b", loader), "b"))) return null;
        return panel;
    }

    private static boolean openWhiteBalance(ClassLoader loader, Object panel, List<?> components) {
        Class<?> whiteBalance = XposedHelpers.findClass("r2.k1", loader);
        for (Object component : components) {
            if (!whiteBalance.isInstance(component)) continue;
            Object title = XposedHelpers.callMethod(component, "getDisplayTitleString");
            XposedHelpers.callMethod(panel, "x1", title, component, false);
            return true;
        }
        return false;
    }

    private static boolean usesPhotoFilterWheel(String action) {
        return usesFilterWheel(MODE_CAPTURE, action);
    }

    private static boolean usesFilterWheel(int mode, String action) {
        return isKeyEnabled(KEY_WHEEL)
                && ((!F_DEFAULT.equals(action) && supportsAction(KEY_WHEEL, mode, action, false))
                    || F_FILTER.equals(getFunc(KEY_RECORD, mode)));
    }

    private static void installCamStyleWheel(ClassLoader loader) {
        Class<?> camera = XposedHelpers.findClass("com.android.camera.Camera", loader);
        Class<?> filterPage = XposedHelpers.findClass("P9.i", loader);
        Class<?> decoration = XposedHelpers.findClass("com.android.camera.fragment.N", loader);
        Method dispatch = XposedHelpers.findMethodExact(Activity.class, "dispatchGenericMotionEvent",
                MotionEvent.class);
        XposedBridge.hookMethod(dispatch, new XC_MethodHook(XC_MethodHook.PRIORITY_LOWEST) {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                MotionEvent event = (MotionEvent) param.args[0];
                if (!camera.isInstance(param.thisObject) || !isCamera68Mapping() || !isN1g(event)
                        || event.getActionMasked() != MotionEvent.ACTION_SCROLL) return;
                float delta = event.getAxisValue(MotionEvent.AXIS_SCROLL);
                if (delta == 0f) delta = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                if (delta == 0f) return;
                if (stepVisibleFrontFilter(loader, filterPage, decoration, delta > 0f)) {
                    param.setResult(true);
                    return;
                }
                if (!usesPhotoFilterWheel(getFunc(KEY_WHEEL, MODE_CAPTURE))) return;
                // An open workspace can consume generic motion in its view before Camera.T0.
                Object panel = visiblePhotoCamStyle(loader);
                if (panel == null || XposedHelpers.getObjectField(panel, "A0") != null) return;
                param.setResult(true);
                try {
                    stepPhotoCamStyle(panel, delta > 0f);
                } catch (RuntimeException | LinkageError failure) {
                    HandleFixModule.log("camera68: CamStyle wheel failed", failure);
                }
            }
        });
    }

    private static boolean stepVisibleFrontFilter(ClassLoader loader, Class<?> filterPage,
            Class<?> decoration, boolean forward) {
        Object panel = visibleBeautyPanel(loader);
        if (panel == null) return false;
        int mode = XposedHelpers.getIntField(panel, "mCurrentMode");
        if ((mode != MODE_CAPTURE && mode != 162) || !isFrontCamera(loader)
                || !usesFilterWheel(mode, getFunc(KEY_WHEEL, mode))) return false;
        Object page = activeFilterPage(panel);
        if (!filterPage.isInstance(page)) return false;
        // Both front-facing entry points use the visible adapter's order and selection callback.
        stepFilterPage(page, decoration, forward, true);
        return true;
    }

    private static void stepPhotoCamStyle(Object panel, boolean forward) {
        Object recycler = XposedHelpers.getObjectField(panel, "x0");
        Object adapter = XposedHelpers.getObjectField(panel, "Q");
        if (!(recycler instanceof View) || !((View) recycler).isShown()
                || !((View) recycler).isEnabled() || adapter == null) return;
        List<?> items = (List<?>) XposedHelpers.callMethod(panel, "getData");
        if (items == null || items.isEmpty()) return;
        int selected = (Integer) XposedHelpers.callMethod(panel, "B0");
        if (selected < 0 || selected >= items.size()) return;
        int direction = forward ? 1 : -1;
        for (int next = selected + direction; next >= 0 && next < items.size(); next += direction) {
            // Native CamStyle excludes add/manage cards and unavailable styles from swipe selection.
            if (Boolean.TRUE.equals(XposedHelpers.callMethod(adapter, "L", next))
                    || XposedHelpers.getBooleanField(items.get(next), "n")) continue;
            Object holder = XposedHelpers.callMethod(recycler, "findViewHolderForAdapterPosition", next);
            if (holder != null) {
                XposedHelpers.callMethod(recycler, "stopScroll");
                XposedHelpers.callMethod(panel, "ub", next, false);
            }
            return;
        }
    }

    private static void installPhotoFilterWheel(ClassLoader loader, ThreadLocal<Boolean> direction) {
        Method dispatch = method(loader, "r6.B0", "accept", void.class, Object.class);
        Class<?> filterPage = XposedHelpers.findClass("P9.i", loader);
        Class<?> centerDecoration = XposedHelpers.findClass("com.android.camera.fragment.N", loader);
        XposedBridge.hookMethod(dispatch, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Boolean forward = direction.get();
                if (forward == null) return;
                Object panel = visibleBeautyPanel(loader);
                if (panel == null || !stepFilterPage(XposedHelpers.getObjectField(panel, "r"))) return;
                param.setResult(null);
                try {
                    stepPhotoFilter(panel, filterPage, centerDecoration, forward);
                } catch (RuntimeException | LinkageError failure) {
                    HandleFixModule.log("camera68: photo filter wheel failed", failure);
                }
            }
        });
    }

    private static void stepPhotoFilter(Object panel, Class<?> filterPage,
            Class<?> centerDecoration, boolean forward) {
        Object page = activeFilterPage(panel);
        if (filterPage.isInstance(page)) stepFilterPage(page, centerDecoration, forward, false);
    }

    private static Object activeFilterPage(Object panel) {
        Object pager = XposedHelpers.getObjectField(panel, "j");
        Object pages = XposedHelpers.getObjectField(panel, "k");
        if (pager == null || pages == null) return null;
        return XposedHelpers.callMethod(pages, "w",
                XposedHelpers.callMethod(pager, "getCurrentItem"));
    }

    private static void stepFilterPage(Object page, Class<?> centerDecoration, boolean forward,
            boolean refreshSelection) {
        Object recycler = XposedHelpers.getObjectField(page, "M");
        Object items = XposedHelpers.getObjectField(page, "R");
        if (!(recycler instanceof View) || !((View) recycler).isShown()
                || !((View) recycler).isEnabled() || !(items instanceof List<?>)) return;
        int count = ((List<?>) items).size();
        int selected = (Integer) XposedHelpers.callMethod(page, "B0");
        if (selected < 0 || selected >= count) return;
        int target = Math.max(0, Math.min(count - 1, selected + (forward ? 1 : -1)));
        if (target == selected) return;

        Object layout = XposedHelpers.callMethod(recycler, "getLayoutManager");
        if (layout == null) return;
        Object decoration = (Integer) XposedHelpers.callMethod(recycler, "getItemDecorationCount") == 0
                ? null : XposedHelpers.callMethod(recycler, "getItemDecorationAt", 0);
        // Use the visible list order and one native selection. The stock wheel calculates
        // a target in a separate list and starts a smooth scroll from the last snapped card.
        XposedHelpers.callMethod(recycler, "setOnclickStatus", true);
        try {
            XposedHelpers.callMethod(recycler, "stopScroll");
            XposedHelpers.callMethod(page, "ub", target, true);
            if (!Integer.valueOf(target).equals(XposedHelpers.callMethod(page, "B0"))) return;
            if (refreshSelection) {
                // The front list can keep recycled holders after the native index commits.
                Object adapter = XposedHelpers.callMethod(recycler, "getAdapter");
                if (adapter != null) {
                    XposedHelpers.callMethod(adapter, "notifyItemChanged", selected);
                    XposedHelpers.callMethod(adapter, "notifyItemChanged", target);
                }
            }
            XposedHelpers.setIntField(recycler, "b", target);
            // FilterSelectViewCV uses this decoration offset when centering a filter on layout.
            if (centerDecoration.isInstance(decoration)) {
                int offset = (Integer) XposedHelpers.callMethod(decoration, "i", recycler);
                XposedHelpers.callMethod(layout, "scrollToPositionWithOffset", target, offset);
            } else {
                XposedHelpers.callMethod(recycler, "scrollToPosition", target);
            }
        } finally {
            XposedHelpers.callMethod(recycler, "setOnclickStatus", false);
        }
    }

    private static void installZoom(ClassLoader loader) {
        Method rocker = method(loader, OWNER, "M", void.class, int.class, KeyEvent.class);
        Method read = method(loader, "Xh.a", "m", String.class, String.class, String.class);
        int defaultZoom = resource(loader, "camera_handle_customize_zoom_value_default");
        int continuousZoom = resource(loader, "camera_handle_customize_zoom_value_common");
        int segmentZoom = resource(loader, "camera_handle_customize_zoom_value_segment");
        ThreadLocal<String> value = new ThreadLocal<>();
        XposedBridge.hookMethod(read, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                String scoped = value.get();
                if (scoped != null && !param.hasThrowable()
                        && "pref_camera_handle_zoom".equals(param.args[0])) {
                    param.setResult(scoped);
                }
            }
        });
        XposedBridge.hookMethod(rocker, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                int keyCode = (Integer) param.args[0];
                KeyEvent event = (KeyEvent) param.args[1];
                if (!isCamera68Mapping() || !isN1g(event) || event.isCanceled()
                        || (keyCode != KeyEvent.KEYCODE_ZOOM_IN && keyCode != KeyEvent.KEYCODE_ZOOM_OUT)) {
                    return;
                }
                int mode = currentMode(param.thisObject);
                if (mode <= 0) return;
                String action = ModeConfig.getFunc(KEY_ZOOM, mode);
                if (!supportsAction(KEY_ZOOM, mode, action, false)) return;
                if (F_DEFZOOM.equals(action)) {
                    setScope(param, value, nativeString(loader, defaultZoom));
                } else if (F_ZOOM.equals(action)) {
                    setScope(param, value, nativeString(loader, segmentZoom));
                } else if (F_CONTZOOM.equals(action)) {
                    setScope(param, value, nativeString(loader, continuousZoom));
                }
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                restoreScope(param, value);
            }
        });
    }

    private static void installShutter(ClassLoader loader) {
        Method shutter = method(loader, "com.android.camera.module.Camera2Module",
                "performMiHandlePressed", void.class, int.class, KeyEvent.class);
        Method read = method(loader, CAMERA_SETTINGS, "f", String.class, boolean.class);
        int single = resource(loader, "camera_handle_customize_snap_value_single");
        int burst = resource(loader, "camera_handle_customize_snap_value_burst");
        ThreadLocal<String> value = new ThreadLocal<>();
        XposedBridge.hookMethod(read, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                String scoped = value.get();
                if (scoped != null && !param.hasThrowable() && Boolean.FALSE.equals(param.args[0])) {
                    param.setResult(scoped);
                }
            }
        });
        XposedBridge.hookMethod(shutter, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                KeyEvent event = (KeyEvent) param.args[1];
                if (!isCamera68Mapping() || !isN1g(event)) return;
                int mode = moduleMode(param.thisObject);
                if (DualStyleCaptureHooks.ownsShutterGesture(mode, event)) {
                    setScope(param, value, nativeString(loader, single));
                    return;
                }
                // The native recording-key action changes this preference itself.
                String recordAction = ModeConfig.getFunc(KEY_RECORD, mode);
                if (F_SHUTTER.equals(recordAction)
                        && supportsAction(KEY_RECORD, mode, recordAction, false)) return;
                String action = ModeConfig.getFunc(KEY_SHUTTER, mode);
                if (F_DEFAULT.equals(action) || !supportsAction(KEY_SHUTTER, mode, action, false)) {
                    return;
                }
                boolean useBurst = F_BURST.equals(action);
                if (useBurst && F_HOLD_BURST.equals(getFuncLong(KEY_SHUTTER, mode))) {
                    // Long-press burst owns the transition from native single capture.
                    return;
                }
                setScope(param, value, nativeString(loader, useBurst ? burst : single));
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                restoreScope(param, value);
            }
        });
    }

    private static void setScope(XC_MethodHook.MethodHookParam param,
            ThreadLocal<String> current, String value) {
        param.setObjectExtra(SCOPE, new String[] {current.get()});
        current.set(value);
    }

    private static void restoreScope(XC_MethodHook.MethodHookParam param,
            ThreadLocal<String> current) {
        String[] previous = (String[]) param.getObjectExtra(SCOPE);
        if (previous == null) return;
        if (previous[0] == null) current.remove();
        else current.set(previous[0]);
    }

    private static int resource(ClassLoader loader, String name) {
        return XposedHelpers.getStaticIntField(XposedHelpers.findClass("Rh.e", loader), name);
    }

    private static String nativeString(ClassLoader loader, int id) {
        Context context = (Context) XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("com.xiaomi.camera.basic.Global", loader), "getContext");
        return context.getString(id);
    }

    private static boolean previewLastPhoto(ClassLoader loader) {
        Object thumbnail = protocol(loader, "R6.r");
        if (thumbnail == null) return false;
        // Use the main-screen thumbnail path; the two-argument route targets the rear display.
        XposedHelpers.callMethod(thumbnail, "onThumbnailClicked",
                new Class<?>[] {View.class}, (Object) null);
        return true;
    }

    private static boolean cycleAiPictureRatio(ClassLoader loader, int mode) {
        Object config = protocol(loader, "R6.D");
        if (config == null || !Boolean.TRUE.equals(XposedHelpers.callMethod(config, "kc"))) {
            return false;
        }
        Object repository = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("g2.a", loader), "a");
        Object ratios = XposedHelpers.callMethod(repository, "x", XposedHelpers.findClass("r2.T", loader));
        if (ratios == null) return false;
        Object current = XposedHelpers.callMethod(ratios, "getComponentValue", mode);
        Object next = XposedHelpers.callMethod(ratios, "p", mode);
        if (next == null || next.equals(current)) return false;
        // AI can omit the ratio card while its native ratio component still has a next value.
        @SuppressWarnings("unchecked")
        Consumer<Object> apply = (Consumer<Object>) XposedHelpers.newInstance(
                XposedHelpers.findClass("r6.s0", loader), mode);
        apply.accept(ratios);
        return true;
    }

    private static boolean filterPage(Object value) {
        return stepFilterPage(value) || "18".equals(value);
    }

    private static boolean stepFilterPage(Object value) {
        return "7".equals(value) || "16".equals(value);
    }

    private static Object visibleBeautyPanel(ClassLoader loader) {
        Object panel = protocol(loader, "R6.j");
        return panel != null && "com.android.camera.fragment.Y".equals(panel.getClass().getName())
                && isVisiblePanel(panel) ? panel : null;
    }

    private static Object visiblePhotoCamStyle(ClassLoader loader) {
        Object panel = protocol(loader, "R6.t1");
        return XposedHelpers.findClass(CAM_STYLE_PAGE, loader).isInstance(panel)
                && XposedHelpers.getIntField(panel, "mCurrentMode") == MODE_CAPTURE
                && isVisiblePanel(panel) ? panel : null;
    }

    private static boolean isVisiblePanel(Object panel) {
        Object view = XposedHelpers.callMethod(panel, "getView");
        return view instanceof View && ((View) view).isShown()
                && !Boolean.TRUE.equals(XposedHelpers.callMethod(panel, "isHidden"));
    }

    private static boolean isFrontCamera(ClassLoader loader) {
        Class<?> repository = XposedHelpers.findClass("g2.a", loader);
        Object global = XposedHelpers.callStaticMethod(repository, "g");
        return Boolean.TRUE.equals(XposedHelpers.callMethod(global, "N"));
    }

    private static Object shineComponent(ClassLoader loader) {
        Class<?> repository = XposedHelpers.findClass("g2.a", loader);
        Object running = XposedHelpers.callStaticMethod(repository, "j");
        return XposedHelpers.callMethod(running, "x", XposedHelpers.findClass("v2.m0", loader));
    }

    private static boolean toggleFilter(ClassLoader loader, int mode) {
        if (mode == EQUIP_STREET) return toggleEquipStreetFilter(loader);
        if (mode == MODE_CAPTURE && visiblePhotoCamStyle(loader) != null) {
            return togglePhotoCamStyle(loader);
        }
        Object panel = visibleBeautyPanel(loader);
        if (panel != null) {
            if (!filterPage(XposedHelpers.getObjectField(panel, "r"))) return false;
            // The panel can close even when onBackEvent reports that Back may propagate.
            XposedHelpers.callMethod(panel, "onBackEvent", 6);
            return true;
        }
        Object config = protocol(loader, "R6.D");
        if (config == null || !Boolean.TRUE.equals(XposedHelpers.callMethod(config, "kc"))) {
            return false;
        }
        Object shine = shineComponent(loader);
        if (shine == null) return false;
        if (mode == MODE_CAPTURE && !isFrontCamera(loader)
                && Boolean.TRUE.equals(XposedHelpers.callMethod(shine, "f0"))
                && Boolean.TRUE.equals(XposedHelpers.callStaticMethod(
                        XposedHelpers.findClass("com.android.camera.data.data.F", loader), "R0"))) {
            return togglePhotoCamStyle(loader);
        }
        List<?> nativePages = (List<?>) XposedHelpers.callMethod(shine, "L");
        if (nativePages == null || nativePages.isEmpty()) return false;
        // Mirror X9.d0's theme-filtered data model. Pass only a page that is present.
        Object themeRouter = XposedHelpers.getStaticObjectField(
                XposedHelpers.findClass("q9.a", loader), "a");
        Object theme = XposedHelpers.callMethod(themeRouter, "e");
        List<?> pages;
        try {
            // X9.d0 dispatches this default method on the interface.
            Method filterPages = XposedHelpers.findClass("r9.p", loader)
                    .getMethod("s", List.class, boolean.class);
            pages = (List<?>) filterPages.invoke(theme, nativePages, false);
        } catch (ReflectiveOperationException | LinkageError failure) {
            HandleFixModule.log("camera68: cannot resolve theme filter pages", failure);
            return false;
        }
        if (pages == null || pages.isEmpty()) return false;
        for (Object entry : pages) {
            Object page = XposedHelpers.getObjectField(entry, "r");
            if (!filterPage(page)) continue;
            XposedHelpers.callMethod(config, "Pa",
                    new Class<?>[] {int.class, List.class, String.class},
                    XposedHelpers.getIntField(shine, "j"), pages, page);
            return true;
        }
        return false;
    }

    private static boolean togglePhotoCamStyle(ClassLoader loader) {
        if (protocol(loader, "R6.j0") == null) return false;
        Object global = XposedHelpers.callStaticMethod(XposedHelpers.findClass("g2.a", loader), "g");
        boolean frontOrFlipped = Boolean.TRUE.equals(XposedHelpers.callMethod(global, "N"))
                || Boolean.TRUE.equals(XposedHelpers.callStaticMethod(
                        XposedHelpers.findClass("K2.b", loader), "a0"));
        // The stock Photo bottom button dispatches to this same CamStyle toggle.
        XposedHelpers.callStaticMethod(XposedHelpers.findClass(
                "com.android.camera.features.mode.capture.p", loader), "f", MODE_CAPTURE, frontOrFlipped);
        return true;
    }

    private static boolean toggleEquipStreetFilter(ClassLoader loader) {
        Object street = protocol(loader, "R6.K");
        if (street == null || !XposedHelpers.findClass("q4.u", loader).isInstance(street)
                || !Boolean.TRUE.equals(XposedHelpers.callMethod(street, "isEnableClick"))) return false;
        Object button = XposedHelpers.getObjectField(street, "S");
        if (!(button instanceof View) || !((View) button).isAttachedToWindow()) return false;
        Object ui = protocol(loader, "R6.j0");
        if (ui == null) return false;
        if (!Boolean.TRUE.equals(XposedHelpers.callMethod(ui, "e", 7, 251))) {
            Object shine = shineComponent(loader);
            if (shine == null || ((List<?>) XposedHelpers.callMethod(shine, "S")).isEmpty()) return false;
        }
        // The legendary layout also forwards its visible filter button to the attached native one.
        return ((View) button).performClick();
    }

    private static boolean toggleWorkspace(ClassLoader loader, int mode) {
        if (mode != PRO_PHOTO) return false;
        Object ui = protocol(loader, "R6.j0");
        if (ui == null) return false;
        boolean open = Boolean.TRUE.equals(XposedHelpers.callMethod(ui, "e",
                WORKSPACE_CONTAINER, WORKSPACE_FRAGMENT));
        boolean listOpen = Boolean.TRUE.equals(XposedHelpers.callMethod(ui, "e",
                WORKSPACE_CONTAINER, WORKSPACE_LIST));
        if (open || listOpen) {
            Object workspace = protocol(loader, "R6.t1");
            if (workspace == null) return false;
            int fragmentId = (Integer) XposedHelpers.callMethod(workspace, "getFragmentId");
            if (fragmentId != WORKSPACE_FRAGMENT && fragmentId != WORKSPACE_LIST) return false;
            return Boolean.TRUE.equals(XposedHelpers.callMethod(workspace, "onBackEvent", CLOSE_FROM_KEY));
        }
        Class<?> repo = XposedHelpers.findClass("g2.a", loader);
        Class<?> component = XposedHelpers.findClass("r2.S", loader);
        Object data = XposedHelpers.callStaticMethod(repo, "a");
        Object workspace = XposedHelpers.callMethod(data, "x", component);
        if (workspace == null) return false;
        // Same consumer as T0's attr_workspace case, without manufacturing a MotionEvent.
        @SuppressWarnings("unchecked")
        Consumer<Object> openWorkspace = (Consumer<Object>) XposedHelpers.newInstance(
                XposedHelpers.findClass("r6.F0", loader), workspace, false, mode);
        openWorkspace.accept(ui);
        return true;
    }

    private static Object protocol(ClassLoader loader, String type) {
        return ((Optional<?>) XposedHelpers.callStaticMethod(
                XposedHelpers.findClass(type, loader), "a")).orElse(null);
    }

    private static Method method(ClassLoader loader, String owner, String name,
            Class<?> returnType, Class<?>... parameters) {
        Method method = XposedHelpers.findMethodExact(
                XposedHelpers.findClass(owner, loader), name, (Object[]) parameters);
        if (method.getReturnType() != returnType) {
            throw new IllegalStateException(owner + "." + name + " has an unexpected return type");
        }
        return method;
    }

    private static Object currentModule(Object owner) {
        return ((Optional<?>) XposedHelpers.callMethod(owner, "o0")).orElse(null);
    }

    private static int currentMode(Object owner) {
        Object module = currentModule(owner);
        return module == null ? -1 : moduleMode(module);
    }

    private static int moduleMode(Object module) {
        return (Integer) XposedHelpers.callMethod(module, "getModuleIndex");
    }

    private static boolean isN1g(android.view.InputEvent event) {
        InputDevice device = event == null ? null : event.getDevice();
        return device != null && device.getVendorId() == 0x2717 && device.getProductId() == 0x5083;
    }
}
