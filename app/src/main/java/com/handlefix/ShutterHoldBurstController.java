package com.handlefix;

import android.os.Process;
import android.os.SystemClock;
import android.view.KeyEvent;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 生产版快门长按高速连拍控制器。
 *
 * <p>控制器只接管具备完整原生 KeyEvent 决策链的相机、拍照/街拍模式，以及明确配置了
 * {@link ModeConfig#F_HOLD_BURST} 的快门长按手势。一次真实 {@code ACTION_DOWN}
 * 建立一个新的 {@code PRESSED_SINGLE} 事务；相机自己的 {@code startNormalCapture(I)Z}
 * 返回 {@code true} 后，当前事务跨过用户阈值的真实 repeat 才能进入
 * {@code BURST_ACTIVE}，并让相机继续执行其原生连拍/松手停止链。</p>
 *
 * <p>首拍接受门取代旧的跨手势释放门：旧门会把下一次真实 DOWN 变成等待状态，而该
 * DOWN 已经无法重新送回相机，正是快速再次按键无响应的根因。现在每轮手势都独立从
 * 单拍开始；模块不重放输入、不主动拍照、不注入触摸、不调用 {@code multiCapture}，
 * 也不等待或模拟原生释放回调。</p>
 */
public final class ShutterHoldBurstController {
    private static final String LOG_PREFIX = "[HandleFix][hold-burst]";
    private static final int PHOTO_MODULE = ModeConfig.MODE_CAPTURE;
    private static final int STREET_MODULE = ModeConfig.MODE_STREET;
    private static final int KEY_SHUTTER = 27;
    private static final int KEY_SHUTTER_ALT = 170;
    private static final long TRANSACTION_TIMEOUT_MS = 15000L;
    private static final Object STATE_LOCK = new Object();
    private static final Map<Object, Transaction> STATES = new WeakHashMap<>();
    private static final Set<String> HOOKED_METHODS = new HashSet<>();

    /** 静态 lambda 没有 thisObject，使用最新事务并按 downTime 做精确关联。 */
    private static Transaction activeTransaction;

    private ShutterHoldBurstController() {
    }

    /**
     * 在相机进程中安装正式控制器。版本号只筛选候选，真实入口仍须在相机 ClassLoader
     * 内完成完整 descriptor 预检；任何能力不完整都保持相机原生行为。
     */
    public static void installIfSupported(
            String packageName,
            ClassLoader classLoader,
            long cameraVersionCode,
            String mappingProfile,
            boolean configChainRecognized) {
        final boolean prometheusProfile = HandleFixModule.promFix();
        final boolean camera68 = CameraBuilds.isCamera68Port(cameraVersionCode);
        if (!HandleFixModule.CAM_PKG.equals(packageName)
                || (camera68 ? !ModeConfig.isCamera68Mapping()
                : !CameraBuilds.isModernHoldBurstCandidate(cameraVersionCode, prometheusProfile))) {
            return;
        }
        // Phoenix 的版本组只表示可能沿用 OS4 基线；配置 Profile 未确认时不安装。
        if (CameraBuilds.isPhoenixOs4Family(cameraVersionCode)
                && (!configChainRecognized || !"phoenix-os4".equals(mappingProfile))) {
            return;
        }
        HandleFixModule.install("ShutterHoldBurstController", new Runnable() {
            @Override
            public void run() {
                installHooks(classLoader, cameraVersionCode, mappingProfile,
                        prometheusProfile);
            }
        });
    }

    private static void installHooks(ClassLoader classLoader, long cameraVersionCode,
            String mappingProfile, boolean prometheusProfile) {
        Class<?> camera2Module = findClass("com.android.camera.module.Camera2Module", classLoader);
        if (camera2Module == null) {
            log("install aborted: Camera2Module unavailable");
            return;
        }

        final boolean phoenixOs4 = CameraBuilds.isPhoenixOs4Family(cameraVersionCode);
        final boolean officialOs4 = !prometheusProfile
                && cameraVersionCode == CameraBuilds.OFFICIAL_OS4_6_6_510_VERSION_CODE;
        final boolean camera68 = CameraBuilds.isCamera68Port(cameraVersionCode);
        final boolean oemDecisionBridge = phoenixOs4 || officialOs4;
        final String lambdaName = camera68 ? "lambda$performMiHandlePressed$51"
                : (phoenixOs4 || cameraVersionCode == CameraBuilds.OFFICIAL_OS4_6_6_510_VERSION_CODE)
                ? "lambda$performMiHandlePressed$50"
                : "lambda$performMiHandlePressed$49";

        /*
         * 先确认全部必要入口，再安装第一个 Hook。首拍门只依赖 startNormalCapture 的
         * 精确返回值；长按决策依赖 isNeedBurst 与对应版本的 synthetic lambda。释放
         * 由相机自己的 onKeyUp 完成，因此不再探测或挂载任何释放完成回调。
         */
        if (!hasCommonNativeChain(camera2Module)
                || !hasExactBurstDecision(camera2Module, classLoader, lambdaName,
                oemDecisionBridge, camera68)) {
            log("install aborted: native hold-burst descriptor unavailable"
                    + " versionCode=" + cameraVersionCode
                    + " profile=" + mappingProfile
                    + " lambda=" + lambdaName);
            return;
        }

        int hooked = 0;
        hooked += hookExact(camera2Module, "onKeyDown", boolean.class,
                new Class<?>[]{int.class, KeyEvent.class}, new KeyDownHook(classLoader));
        hooked += hookExact(camera2Module, "onKeyUp", boolean.class,
                new Class<?>[]{int.class, KeyEvent.class}, new KeyUpHook(classLoader));
        hooked += hookExact(camera2Module, "performKeyClicked", void.class,
                new Class<?>[]{int.class, String.class, KeyEvent.class, boolean.class},
                new PerformKeyClickedHook(classLoader));
        hooked += hookExact(camera2Module, "startNormalCapture", boolean.class,
                new Class<?>[]{int.class}, new StartNormalCaptureHook());

        // Phoenix/官方 OS4 的决策 Hook 已由 OemHooks 安装；其它相机由这里直接接入。
        if (!oemDecisionBridge) {
            hooked += hookExact(camera2Module, "isNeedBurst", boolean.class,
                    new Class<?>[]{int.class, KeyEvent.class},
                    new IsNeedBurstHook(classLoader));
            hooked += hookMiHandleLambda(camera2Module, lambdaName,
                    new MiHandleDecisionHook(classLoader));
        }
        log("install complete: versionCode=" + cameraVersionCode
                + " profile=" + mappingProfile
                + " prometheus=" + prometheusProfile
                + " oemDecisionBridge=" + oemDecisionBridge
                + " hookedMethods=" + hooked);
    }

    private static boolean hasCommonNativeChain(Class<?> camera2Module) {
        return findExactMethod(camera2Module, "onKeyDown", boolean.class,
                new Class<?>[]{int.class, KeyEvent.class}) != null
                && findExactMethod(camera2Module, "onKeyUp", boolean.class,
                new Class<?>[]{int.class, KeyEvent.class}) != null
                && findExactMethod(camera2Module, "performKeyClicked", void.class,
                new Class<?>[]{int.class, String.class, KeyEvent.class, boolean.class}) != null
                && findExactMethod(camera2Module, "startNormalCapture", boolean.class,
                new Class<?>[]{int.class}) != null
                && findExactMethod(camera2Module, "isNeedBurst", boolean.class,
                new Class<?>[]{int.class, KeyEvent.class}) != null;
    }

    /**
     * 对 OemHooks 已覆盖的 OS4 决策点，额外要求第二参数就是已核实的 Q6.L；其它
     * 版本要求唯一的 static Boolean(KeyEvent, interface) lambda，拒绝重载歧义。
     */
    private static boolean hasExactBurstDecision(Class<?> camera2Module,
            ClassLoader classLoader, String lambdaName, boolean oemDecisionBridge,
            boolean camera68) {
        if (camera68) {
            Class<?> miHandle = findClass("R6.M", classLoader);
            return miHandle != null
                    && findExactMethod(camera2Module, lambdaName, Boolean.class,
                    new Class<?>[]{KeyEvent.class, miHandle}, true) != null;
        }
        if (!oemDecisionBridge) {
            return hasExactMiHandleLambda(camera2Module, lambdaName);
        }
        Class<?> q6L = findClass("Q6.L", classLoader);
        return q6L != null
                && findExactMethod(camera2Module, lambdaName, Boolean.class,
                new Class<?>[]{KeyEvent.class, q6L}, true) != null;
    }

    private static boolean hasExactMiHandleLambda(Class<?> root, String methodName) {
        return findExactMiHandleLambda(root, methodName) != null;
    }

    private static Method findExactMiHandleLambda(Class<?> root, String methodName) {
        Method match = null;
        for (Class<?> current = root; current != null && current != Object.class;
                current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (!methodName.equals(method.getName())
                        || method.getReturnType() != Boolean.class
                        || !Modifier.isStatic(method.getModifiers())
                        || parameters.length != 2
                        || parameters[0] != KeyEvent.class
                        || !parameters[1].isInterface()) {
                    continue;
                }
                if (match != null) {
                    return null;
                }
                match = method;
            }
        }
        return match;
    }

    private static int hookExact(Class<?> root, String methodName, Class<?> returnType,
            Class<?>[] parameterTypes, XC_MethodHook callback) {
        Method method = findExactMethod(root, methodName, returnType, parameterTypes);
        if (method == null) {
            log("hook unavailable: " + root.getName() + "#" + methodName
                    + descriptor(returnType, parameterTypes));
            return 0;
        }
        String key = method.toGenericString();
        synchronized (STATE_LOCK) {
            if (!HOOKED_METHODS.add(key)) {
                return 0;
            }
        }
        try {
            XposedBridge.hookMethod(method, callback);
            return 1;
        } catch (Throwable throwable) {
            synchronized (STATE_LOCK) {
                HOOKED_METHODS.remove(key);
            }
            log("hook failed: " + root.getName() + "#" + methodName
                    + " error=" + throwable.getClass().getName());
            return 0;
        }
    }

    private static int hookMiHandleLambda(Class<?> root, String methodName,
            XC_MethodHook callback) {
        Method method = findExactMiHandleLambda(root, methodName);
        if (method == null) {
            log("hook unavailable: ambiguous or missing " + root.getName() + "#" + methodName);
            return 0;
        }
        String key = method.toGenericString();
        synchronized (STATE_LOCK) {
            if (!HOOKED_METHODS.add(key)) {
                return 0;
            }
        }
        try {
            method.setAccessible(true);
            XposedBridge.hookMethod(method, callback);
            return 1;
        } catch (Throwable throwable) {
            synchronized (STATE_LOCK) {
                HOOKED_METHODS.remove(key);
            }
            log("hook failed: " + method.toGenericString()
                    + " error=" + throwable.getClass().getName());
            return 0;
        }
    }

    private static Method findExactMethod(Class<?> root, String methodName, Class<?> returnType,
            Class<?>[] parameterTypes) {
        return findExactMethod(root, methodName, returnType, parameterTypes, false);
    }

    /** 严格按返回类型/参数类型查找；只对已核实的 synthetic lambda 放宽检查。 */
    private static Method findExactMethod(Class<?> root, String methodName, Class<?> returnType,
            Class<?>[] parameterTypes, boolean allowVerifiedSynthetic) {
        for (Class<?> current = root; current != null && current != Object.class;
                current = current.getSuperclass()) {
            try {
                Method method = current.getDeclaredMethod(methodName, parameterTypes);
                int modifiers = method.getModifiers();
                if (method.getReturnType() != returnType
                        || Modifier.isAbstract(modifiers)
                        || method.isBridge()
                        || (!allowVerifiedSynthetic && method.isSynthetic())) {
                    return null;
                }
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                // Continue through the verified superclass chain.
            } catch (Throwable throwable) {
                log("method lookup failed: " + root.getName() + "#" + methodName
                        + " error=" + throwable.getClass().getName());
                return null;
            }
        }
        return null;
    }

    private static Class<?> findClass(String name, ClassLoader classLoader) {
        try {
            return XposedHelpers.findClass(name, classLoader);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String descriptor(Class<?> returnType, Class<?>[] parameterTypes) {
        StringBuilder builder = new StringBuilder("(");
        for (Class<?> parameterType : parameterTypes) {
            builder.append(typeDescriptor(parameterType));
        }
        return builder.append(')').append(typeDescriptor(returnType)).toString();
    }

    private static String typeDescriptor(Class<?> type) {
        if (type == void.class) return "V";
        if (type == boolean.class) return "Z";
        if (type == int.class) return "I";
        if (type == long.class) return "J";
        if (type == byte.class) return "B";
        if (type == short.class) return "S";
        if (type == char.class) return "C";
        if (type == float.class) return "F";
        if (type == double.class) return "D";
        if (type.isArray()) return type.getName().replace('.', '/');
        return "L" + type.getName().replace('.', '/') + ";";
    }

    private static void log(String message) {
        XposedBridge.log(LOG_PREFIX + " pid=" + Process.myPid() + " " + message);
    }

    private static boolean isShutterEvent(KeyEvent event) {
        if (event == null) return false;
        int keyCode = event.getKeyCode();
        if (keyCode != KEY_SHUTTER && keyCode != KEY_SHUTTER_ALT) return false;
        return !CameraBuilds.isCamera68Port(HandleFixModule.CAMERA_VERSION_CODE)
                || (ModeConfig.isCamera68Mapping() && GripInputGuard.isGripEvent(event));
    }

    private static boolean isSupportedMode(int mode) {
        return mode == PHOTO_MODULE || mode == STREET_MODULE;
    }

    private static int currentMode(Object module, ClassLoader classLoader) {
        if (module == null) return -1;
        try {
            return HandleFixModule.currentModuleIndex(module, classLoader);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** 为每个新的真实 DOWN 清空旧事务，绝不继承上一轮 phase 或 downTime。 */
    private static Transaction begin(Object module, KeyEvent event, ClassLoader classLoader) {
        if (module == null || event == null || !isShutterEvent(event)
                || event.getAction() != KeyEvent.ACTION_DOWN
                || event.getRepeatCount() != 0) {
            return null;
        }

        synchronized (STATE_LOCK) {
            Iterator<Map.Entry<Object, Transaction>> iterator = STATES.entrySet().iterator();
            while (iterator.hasNext()) {
                iterator.next().getValue().phase = Phase.IDLE;
                iterator.remove();
            }
            activeTransaction = null;
        }

        int mode = currentMode(module, classLoader);
        if (!isSupportedMode(mode)) return null;

        String longAction;
        int thresholdMs;
        try {
            // 配置只在事务起点快照；repeat 热路径不重新读取 JSON。
            longAction = ModeConfig.getFuncLong(ModeConfig.KEY_SHUTTER, mode);
            thresholdMs = ModeConfig.getShutterLongPressMs();
        } catch (Throwable ignored) {
            return null;
        }
        if (!ModeConfig.F_HOLD_BURST.equals(longAction)) return null;

        Transaction transaction = new Transaction(
                System.identityHashCode(module),
                mode,
                event.getKeyCode(),
                event.getScanCode(),
                event.getDownTime(),
                thresholdMs);
        synchronized (STATE_LOCK) {
            STATES.put(module, transaction);
            activeTransaction = transaction;
        }
        return transaction;
    }

    private static void onRepeat(Object module, KeyEvent event, ClassLoader classLoader) {
        if (module == null || event == null || !isShutterEvent(event)
                || event.getAction() != KeyEvent.ACTION_DOWN
                || event.getRepeatCount() <= 0) {
            return;
        }
        Transaction transaction = stateForModule(module, event);
        if (transaction == null) return;
        if (currentMode(module, classLoader) != transaction.moduleId) {
            cleanup(module, transaction);
            return;
        }
        advanceIfThreshold(transaction, event);
    }

    private static Transaction stateForModule(Object module, KeyEvent event) {
        Transaction transaction;
        synchronized (STATE_LOCK) {
            transaction = STATES.get(module);
        }
        if (transaction == null || transaction.moduleIdentity != System.identityHashCode(module)
                || event == null || transaction.downTime != event.getDownTime()) {
            return null;
        }
        if (expired(transaction)) {
            cleanup(module, transaction);
            return null;
        }
        return transaction;
    }

    private static Transaction stateForEvent(KeyEvent event) {
        if (event == null) return null;
        Transaction transaction;
        synchronized (STATE_LOCK) {
            transaction = activeTransaction;
            if (transaction == null || transaction.downTime != event.getDownTime()) {
                transaction = null;
                for (Transaction candidate : STATES.values()) {
                    if (candidate.downTime == event.getDownTime()) {
                        transaction = candidate;
                        break;
                    }
                }
            }
        }
        if (transaction != null && expired(transaction)) {
            cleanup(null, transaction);
            return null;
        }
        return transaction;
    }

    private static boolean expired(Transaction transaction) {
        synchronized (STATE_LOCK) {
            return SystemClock.uptimeMillis() - transaction.lastActivityUptime
                    > TRANSACTION_TIMEOUT_MS;
        }
    }

    private static void advanceIfThreshold(Transaction transaction, KeyEvent event) {
        synchronized (STATE_LOCK) {
            transaction.lastActivityUptime = SystemClock.uptimeMillis();
            if (transaction.phase != Phase.PRESSED_SINGLE
                    || event.getRepeatCount() <= 0
                    || duration(event) < transaction.thresholdMs
                    || !transaction.firstCaptureAccepted) {
                return;
            }
            transaction.phase = Phase.BURST_ACTIVE;
        }
    }

    /**
     * {@code true} 表示让相机走原生长按连拍，{@code false} 表示保持原生单拍；null
     * 表示本控制器不拥有该事件。PRESSED_SINGLE 明确返回 false，避免相机回到其默认
     * 长按判断而跳过本轮首拍。
     */
    private static Boolean resolveDecision(Object module, KeyEvent event,
            ClassLoader classLoader) {
        if (event == null || !isShutterEvent(event)
                || event.getAction() != KeyEvent.ACTION_DOWN) {
            return null;
        }
        Transaction transaction = module == null
                ? stateForEvent(event) : stateForModule(module, event);
        if (transaction == null) return null;
        if (module != null && currentMode(module, classLoader) != transaction.moduleId) {
            cleanup(module, transaction);
            return null;
        }
        // 静态 lambda 没有实例；只有已知的当前模式变化才清理，未知时保留事务。
        if (module == null && HandleFixModule.CURRENT_MODE > 0
                && HandleFixModule.CURRENT_MODE != transaction.moduleId) {
            cleanup(null, transaction);
            return null;
        }
        if (event.getRepeatCount() > 0) {
            advanceIfThreshold(transaction, event);
        }
        synchronized (STATE_LOCK) {
            transaction.lastActivityUptime = SystemClock.uptimeMillis();
            if (transaction.phase == Phase.BURST_ACTIVE) {
                return Boolean.TRUE;
            }
            // PRESSED_SINGLE 与异常的释放尾态都放行相机单拍/原生停止语义。
            return Boolean.FALSE;
        }
    }

    /** OS4 lambda 决策点：返回值 true=原生连拍，false=原生单拍。 */
    public static Boolean overrideMiHandle(Object module, KeyEvent event,
            ClassLoader classLoader) {
        return resolveDecision(module, event, classLoader);
    }

    /** isNeedBurst 决策点：返回值 true=原生连拍，false=原生单拍。 */
    public static Boolean overrideIsNeedBurst(Object module, int triggerMode,
            KeyEvent event, ClassLoader classLoader) {
        return resolveDecision(module, event, classLoader);
    }

    private static void markRelease(Object module, KeyEvent event, ClassLoader classLoader) {
        if (event == null || !isShutterEvent(event)
                || event.getAction() != KeyEvent.ACTION_UP) {
            return;
        }
        Transaction transaction = stateForModule(module, event);
        if (transaction == null) return;
        if (currentMode(module, classLoader) != transaction.moduleId) {
            cleanup(module, transaction);
            return;
        }
        synchronized (STATE_LOCK) {
            transaction.phase = Phase.RELEASING;
            transaction.lastActivityUptime = SystemClock.uptimeMillis();
        }
    }

    /** 事务只保存模块侧状态；相机原生 onKeyUp 自己完成停止，随后立即移除状态。 */
    private static void cleanup(Object module, Transaction transaction) {
        if (transaction == null) return;
        synchronized (STATE_LOCK) {
            if (module != null && STATES.get(module) == transaction) {
                STATES.remove(module);
            } else if (module == null) {
                Iterator<Map.Entry<Object, Transaction>> iterator = STATES.entrySet().iterator();
                while (iterator.hasNext()) {
                    if (iterator.next().getValue() == transaction) {
                        iterator.remove();
                        break;
                    }
                }
            }
            if (activeTransaction == transaction) {
                activeTransaction = null;
            }
            transaction.phase = Phase.IDLE;
        }
    }

    private static long duration(KeyEvent event) {
        return Math.max(0L, event.getEventTime() - event.getDownTime());
    }

    private enum Phase {
        IDLE,
        PRESSED_SINGLE,
        BURST_ACTIVE,
        RELEASING
    }

    private static final class Transaction {
        final int moduleIdentity;
        final int moduleId;
        final int keyCode;
        final int scanCode;
        final long downTime;
        final int thresholdMs;
        Phase phase = Phase.PRESSED_SINGLE;
        boolean firstCaptureAccepted;
        long lastActivityUptime = SystemClock.uptimeMillis();

        Transaction(int moduleIdentity, int moduleId, int keyCode, int scanCode,
                long downTime, int thresholdMs) {
            this.moduleIdentity = moduleIdentity;
            this.moduleId = moduleId;
            this.keyCode = keyCode;
            this.scanCode = scanCode;
            this.downTime = downTime;
            this.thresholdMs = thresholdMs;
        }
    }

    private static final class KeyDownHook extends XC_MethodHook {
        private final ClassLoader classLoader;

        KeyDownHook(ClassLoader classLoader) {
            this.classLoader = classLoader;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (param.args.length < 2 || !(param.args[1] instanceof KeyEvent)) return;
            KeyEvent event = (KeyEvent) param.args[1];
            if (!isShutterEvent(event) || event.isCanceled()) return;
            if (event.getAction() != KeyEvent.ACTION_DOWN) return;
            if (event.getRepeatCount() == 0) {
                begin(param.thisObject, event, classLoader);
            } else {
                onRepeat(param.thisObject, event, classLoader);
            }
        }
    }

    private static final class KeyUpHook extends XC_MethodHook {
        private final ClassLoader classLoader;

        KeyUpHook(ClassLoader classLoader) {
            this.classLoader = classLoader;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (param.args.length < 2 || !(param.args[1] instanceof KeyEvent)) return;
            KeyEvent event = (KeyEvent) param.args[1];
            if (isShutterEvent(event) && event.getAction() == KeyEvent.ACTION_UP) {
                markRelease(param.thisObject, event, classLoader);
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (param.args.length < 2 || !(param.args[1] instanceof KeyEvent)) return;
            KeyEvent event = (KeyEvent) param.args[1];
            if (!isShutterEvent(event) || event.getAction() != KeyEvent.ACTION_UP) return;
            Transaction transaction = stateForModule(param.thisObject, event);
            if (transaction != null) {
                cleanup(param.thisObject, transaction);
            }
        }
    }

    /** 观察原生首拍入口；不改参数或返回值，只有 true 才打开当前事务的 burst 门。 */
    private static final class StartNormalCaptureHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            Object module = param.thisObject;
            Object result = param.getResult();
            if (module == null || !(result instanceof Boolean)) return;
            synchronized (STATE_LOCK) {
                Transaction transaction = STATES.get(module);
                if (transaction != null
                        && transaction.phase == Phase.PRESSED_SINGLE
                        && ((Boolean) result).booleanValue()) {
                    transaction.firstCaptureAccepted = true;
                    transaction.lastActivityUptime = SystemClock.uptimeMillis();
                }
            }
        }
    }

    /** 现代 KeyEvent 版 performKeyClicked 只用于推进真实 repeat，不主动执行拍摄。 */
    private static final class PerformKeyClickedHook extends XC_MethodHook {
        private final ClassLoader classLoader;

        PerformKeyClickedHook(ClassLoader classLoader) {
            this.classLoader = classLoader;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (param.args.length < 3 || !(param.args[2] instanceof KeyEvent)) return;
            KeyEvent event = (KeyEvent) param.args[2];
            if (!isShutterEvent(event) || event.getAction() != KeyEvent.ACTION_DOWN
                    || event.getRepeatCount() <= 0) return;
            Transaction transaction = stateForModule(param.thisObject, event);
            if (transaction == null) return;
            if (currentMode(param.thisObject, classLoader) != transaction.moduleId) {
                cleanup(param.thisObject, transaction);
            } else {
                advanceIfThreshold(transaction, event);
            }
        }
    }

    /** 非 OS4 Profile 的 isNeedBurst 精确入口。 */
    private static final class IsNeedBurstHook extends XC_MethodHook {
        private final ClassLoader classLoader;

        IsNeedBurstHook(ClassLoader classLoader) {
            this.classLoader = classLoader;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (param.args.length < 2 || !(param.args[0] instanceof Integer)
                    || !(param.args[1] instanceof KeyEvent)) return;
            Boolean decision = overrideIsNeedBurst(param.thisObject,
                    ((Integer) param.args[0]).intValue(), (KeyEvent) param.args[1], classLoader);
            if (decision != null) param.setResult(decision);
        }
    }

    /** 非 OS4 Profile 的真实 performMiHandlePressed lambda。 */
    private static final class MiHandleDecisionHook extends XC_MethodHook {
        private final ClassLoader classLoader;

        MiHandleDecisionHook(ClassLoader classLoader) {
            this.classLoader = classLoader;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (param.args.length < 1 || !(param.args[0] instanceof KeyEvent)) return;
            Boolean decision = resolveDecision(null, (KeyEvent) param.args[0], classLoader);
            if (decision != null) param.setResult(decision);
        }
    }
}
