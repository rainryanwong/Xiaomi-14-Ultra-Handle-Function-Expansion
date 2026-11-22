package com.handlefix;

import java.lang.reflect.Method;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;

/** Unlock the native 229 entry/settings, never hook unrelated menu callbacks or mode 256. */
public final class StreetSpecialHooks {
    private StreetSpecialHooks() { }

    /**
     * Enable the 229 -> 225 configuration alias without forcing the hidden entry on.
     *
     * <p>New Phoenix builds may expose EquipStreetModule themselves. The alias must
     * therefore follow the verified 229 capability, not the user's legacy force-unlock
     * switch. This path only validates descriptors and changes HandleFix's config lookup;
     * it does not replace {@code Je.c.M()}, enter a mode, or bypass the grip setting.</p>
     */
    public static void enableConfigAliasIfSupported(ClassLoader loader, long version) {
        if (!CameraBuilds.isPhoenixOs4Family(version)) return;
        HandleFixModule.install("street-special Phoenix OS4 config alias", () -> {
            try {
                StreetSpecialProfile.resolveCapability(loader, version);
                ModeConfig.enableStreetSpecialConfigAlias();
                HandleFixModule.log("StreetSpecial: 229 -> 225 config alias enabled version="
                        + version);
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException(
                        "StreetSpecial config-alias descriptor preflight failed", error);
            }
        });
    }

    public static void installIfSupported(ClassLoader loader, long version) {
        if (!CameraBuilds.isPhoenixOs4Family(version)) return;
        HandleFixModule.install("street-special Phoenix OS4 capability", () -> {
            try {
                // Preflight the complete shared shape before installing. 0.8.0 was wrongly
                // skipped by the former exact-0.3.0 gate despite retaining these descriptors.
                Method gate = StreetSpecialProfile.resolveCapability(loader, version);
                XposedBridge.hookMethod(gate, XC_MethodReplacement.returnConstant(true));
                // Keep direct callers safe as well; this assignment is idempotent. Normal
                // startup has already enabled the alias after descriptor-only preflight.
                ModeConfig.enableStreetSpecialConfigAlias();
                HandleFixModule.log("StreetSpecial: Phoenix OS4 229 capability installed version=" + version);
                if (ModeConfig.isStreetSpecialWithoutGripEnabled()) {
                    StreetSpecialWithoutGripHooks.install(loader);
                }
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("StreetSpecial descriptor preflight failed", error);
            }
        });
    }
}
