package com.handlefix;

import java.util.Set;

import static com.handlefix.ModeConfig.*;

/** Advanced actions with a native 1770 input or panel entry. */
public final class Camera68InputCapabilities {
    public static final int CINEMASTER = 164;
    public static final int AI_CAPTURE = 168;
    static final int PRO_PHOTO = 167;
    public static final int EQUIP_STREET = 229;
    private static final Set<String> AI_RECORD = Set.of(F_FILTER, F_PHOENIX_CLASSIC_STYLE,
            F_QUICK, F_SHUTTER, F_METER, F_AUTOEXP, F_50MP, F_ZOOM);
    private static final Set<String> DIRECTOR_RECORD = Set.of(F_FILTER, F_QUICK, F_AUTOEXP,
            F_AWB, F_EXPFEED, F_PEAK, F_SOUND, F_AUDIOT, F_RATION, F_ZOOM);
    private static final Set<String> DIRECTOR_WHEEL = Set.of(F_EV, F_APERT, F_ET, F_ISO,
            F_AWB, F_FOCUSP);

    private Camera68InputCapabilities() {}

    public static boolean supportsAction(String key, int mode, String action, boolean longPress) {
        if (action == null) return false;
        if (isScopedMode(mode)) return allowsModeAction(key, mode, action, longPress);
        int policyMode = ModeConfig.isStreetSpecialConfigAliasMode(mode) ? MODE_STREET : mode;
        if (!longPress && !GripModeWhitelist.allowsShortAction(key, policyMode, action)) return false;
        if (F_DEFAULT.equals(action)) return true;
        if (KEY_RECORD.equals(key)) {
            if (F_LEICA.equals(action)) return mode == 0 || ModeConfig.isLeicaStyleMode(mode);
            if (longPress) return F_PREVIEW.equals(action);
            if (F_PARAM_PANEL.equals(action)) return mode == 0 || mode == PRO_PHOTO;
            if (F_FILTER.equals(action)) return supportsFilterPanel(mode);
            if (F_FORMAT.equals(action) || F_PICSTYLE.equals(action)) {
                return mode == 0 || mode == PRO_PHOTO;
            }
            if (F_AWB.equals(action)) {
                return mode == 0 || mode == CINEMASTER || mode == PRO_PHOTO
                        || mode == 169 || mode == 180;
            }
            if (F_AUDIOT.equals(action)) return mode == 0 || mode == CINEMASTER || mode == 180;
            if (F_PEAK.equals(action) || F_EXPFEED.equals(action)) {
                return mode == 0 || mode == CINEMASTER || mode == PRO_PHOTO || mode == 180;
            }
            return recordAttr(action) != null;
        }
        if (KEY_WHEEL.equals(key) && !longPress) {
            if (F_WORKSP.equals(action)) return mode == 0 || mode == PRO_PHOTO;
            return wheelAttr(action) != null;
        }
        if (KEY_ZOOM.equals(key) && !longPress) {
            return F_DEFZOOM.equals(action) || F_ZOOM.equals(action) || F_CONTZOOM.equals(action);
        }
        if (KEY_SHUTTER.equals(key) && !longPress && (mode == 0 || mode == 163 || mode == 225)) {
            return F_SHUTTER.equals(action) || F_BURST.equals(action);
        }
        return false;
    }

    private static boolean isScopedMode(int mode) {
        return mode == AI_CAPTURE || mode == CINEMASTER;
    }

    /** Match the selected 6.8 options in both the picker and the runtime, including saved rules. */
    public static boolean allowsModeAction(String key, int mode, String action, boolean longPress) {
        if (action == null) return false;
        if (!isScopedMode(mode) || F_DEFAULT.equals(action)) return true;
        if (KEY_RECORD.equals(key)) {
            if (longPress) return F_PREVIEW.equals(action)
                    || (mode == AI_CAPTURE && F_LEICA.equals(action));
            return (mode == AI_CAPTURE ? AI_RECORD : DIRECTOR_RECORD).contains(action);
        }
        if (KEY_WHEEL.equals(key) && !longPress) {
            return mode == AI_CAPTURE ? F_EV.equals(action) || F_APERT.equals(action)
                    : DIRECTOR_WHEEL.contains(action);
        }
        return false;
    }

    private static boolean supportsFilterPanel(int mode) {
        switch (mode) {
            case 0: case 162: case MODE_CAPTURE: case PRO_PHOTO: case AI_CAPTURE:
            case 171: case 175: case 180: case MODE_STREET: case EQUIP_STREET:
                return true;
            default: return false;
        }
    }

    static String recordAttr(String action) {
        switch (action) {
            case F_LEICA: case F_QUICK: case F_SHUTTER: case F_AWB:
            case F_ZOOM: case F_EIS: case F_METER: case F_PEAK:
            case F_FORMAT: case F_AUTOEXP: case F_PICSTYLE: case F_SOUND:
            case F_AUDIOT: case F_RATION: case F_50MP: case F_EXPFEED:
                return ModeConfig.funcToAttrOem(KEY_RECORD, action);
            default: return null;
        }
    }

    static String wheelAttr(String action) {
        switch (action) {
            case F_EV: case F_APERT: case F_ET: case F_ISO: case F_AWB:
            case F_FOCUSP: case F_BOKEH: case F_WORKSP: case F_ZOOM:
                return ModeConfig.funcToAttr(action);
            default: return null;
        }
    }
}
