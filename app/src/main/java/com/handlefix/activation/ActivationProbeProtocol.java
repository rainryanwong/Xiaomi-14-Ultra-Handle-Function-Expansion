package com.handlefix.activation;

/** Constants only: app code must not load Xposed classes. */
public final class ActivationProbeProtocol {
    public static final String ACTION = "com.handlefix.all.action.PROBE_ACTIVATION";
    public static final String PERMISSION = "com.handlefix.all.permission.PROBE_ACTIVATION";
    public static final String CAMERA68_STREET_READY = "camera68StreetReady";
    public static final String CAMERA68_CLASSIC_MODES = "camera68_classic_lut_modes";
    private ActivationProbeProtocol() {}
}
