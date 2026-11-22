package com.handlefix;

import java.util.Map;
import java.util.Set;

import static com.handlefix.ModeConfig.*;
import static java.util.Map.entry;

/** Shared Phoenix mode policy. Each camera profile also checks its native entry points. */
public final class GripModeWhitelist {
    private static final Map<Integer, Set<String>> RECORD = Map.ofEntries(
            entry(163, Set.of(F_DEFAULT, F_LEICA, F_QUICK, F_SHUTTER, F_RATION,
                    F_PHOENIX_CLASSIC_STYLE, F_FILTER)),
            entry(162, Set.of(F_DEFAULT, F_QUICK, F_EIS, F_RATION, F_FILTER)),
            entry(167, Set.of(F_DEFAULT, F_LEICA, F_METER, F_AUTOEXP, F_AWB, F_PICSTYLE,
                    F_EXPFEED, F_PEAK, F_RATION, F_50MP, F_PARAM_PANEL,
                    F_PHOENIX_CLASSIC_STYLE, F_FILTER)),
            entry(180, Set.of(F_DEFAULT, F_QUICK, F_AUTOEXP, F_AWB, F_EXPFEED, F_PEAK,
                    F_SOUND, F_AUDIOT, F_RATION, F_FILTER)),
            entry(173, Set.of(F_DEFAULT, F_LEICA, F_RATION, F_PHOENIX_CLASSIC_STYLE)),
            entry(171, Set.of(F_DEFAULT, F_LEICA, F_RATION, F_PHOENIX_CLASSIC_STYLE, F_FILTER)),
            entry(186, Set.of(F_DEFAULT, F_RATION)),
            entry(225, Set.of(F_DEFAULT, F_LEICA, F_SHUTTER, F_RATION,
                    F_PHOENIX_CLASSIC_STYLE, F_FILTER)),
            entry(175, Set.of(F_DEFAULT, F_FILTER)),
            entry(256, Set.of(F_DEFAULT, F_RATION)));

    private static final Set<String> SINGLE_SHUTTER = Set.of(F_DEFAULT, F_SHUTTER);
    private static final Set<String> BURST_SHUTTER = Set.of(F_DEFAULT, F_SHUTTER, F_BURST);
    private static final Map<Integer, Set<String>> SHUTTER = Map.ofEntries(
            entry(163, BURST_SHUTTER), entry(225, BURST_SHUTTER),
            entry(162, SINGLE_SHUTTER), entry(167, SINGLE_SHUTTER), entry(180, SINGLE_SHUTTER),
            entry(173, SINGLE_SHUTTER), entry(171, SINGLE_SHUTTER), entry(186, SINGLE_SHUTTER),
            entry(175, SINGLE_SHUTTER), entry(256, SINGLE_SHUTTER));

    private static final Set<String> EV_APERTURE = Set.of(F_DEFAULT, F_EV, F_APERT);
    private static final Map<Integer, Set<String>> WHEEL = Map.ofEntries(
            entry(163, EV_APERTURE), entry(162, EV_APERTURE), entry(173, EV_APERTURE),
            entry(167, Set.of(F_DEFAULT, F_EV, F_APERT, F_ET, F_ISO, F_AWB, F_FOCUSP, F_WORKSP)),
            entry(180, Set.of(F_DEFAULT, F_EV, F_APERT, F_ET, F_ISO, F_AWB, F_FOCUSP)),
            entry(171, Set.of(F_DEFAULT, F_BOKEH)),
            entry(186, Set.of(F_DEFAULT, F_APERT)),
            entry(225, Set.of(F_DEFAULT, F_EV, F_APERT, F_FOCUSP)),
            entry(175, Set.of(F_EV)),
            entry(256, Set.of(F_DEFAULT, F_EV)));

    private static final Set<String> ZOOM_STANDARD = Set.of(F_DEFAULT, F_DEFZOOM, F_CONTZOOM, F_ZOOM);
    private static final Set<String> ZOOM_CONTINUOUS = Set.of(F_DEFAULT, F_CONTZOOM);
    private static final Map<Integer, Set<String>> ZOOM = Map.ofEntries(
            entry(163, Set.of(F_DEFAULT, F_DEFZOOM, F_CONTZOOM, F_ZOOM, F_BURST)),
            entry(225, Set.of(F_DEFAULT, F_ZOOM, F_BURST)),
            entry(171, Set.of(F_DEFAULT, F_ZOOM)),
            entry(162, ZOOM_STANDARD), entry(167, ZOOM_CONTINUOUS), entry(180, ZOOM_CONTINUOUS),
            entry(173, ZOOM_STANDARD), entry(186, ZOOM_STANDARD),
            entry(175, Set.of(F_DEFAULT, F_DEFZOOM, F_ZOOM)),
            entry(256, ZOOM_STANDARD));

    private GripModeWhitelist() {}

    /** Null means the shared policy has no per-mode restriction for this key. */
    public static Set<String> shortActions(String key, int mode) {
        switch (key) {
            case KEY_RECORD: return RECORD.get(mode);
            case KEY_SHUTTER: return SHUTTER.get(mode);
            case KEY_WHEEL: return WHEEL.get(mode);
            case KEY_ZOOM: return ZOOM.get(mode);
            default: return null;
        }
    }

    static boolean allowsShortAction(String key, int mode, String action) {
        Set<String> allowed = shortActions(key, mode);
        return allowed == null || allowed.contains(action);
    }
}
