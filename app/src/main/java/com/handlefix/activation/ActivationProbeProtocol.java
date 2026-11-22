package com.handlefix.activation;

/** Constants only: app code must not load Xposed classes. */
public final class ActivationProbeProtocol {
    public static final String ACTION = "com.handlefix.all.action.PROBE_ACTIVATION";
    public static final String PERMISSION = "com.handlefix.all.permission.PROBE_ACTIVATION";
    private ActivationProbeProtocol() {}
}
