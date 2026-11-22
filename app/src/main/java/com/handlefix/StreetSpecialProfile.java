package com.handlefix;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Phoenix OS4 candidate plus verified 229 protocol; version similarity is not sufficient. */
public final class StreetSpecialProfile {
    private StreetSpecialProfile() { }

    public static Method resolveCapability(ClassLoader loader, long version) throws ReflectiveOperationException {
        if (!CameraBuilds.isPhoenixOs4Family(version)) {
            throw new IllegalArgumentException("not a Phoenix OS4 candidate: " + version);
        }
        // Raw 0.3.0/0.8.0: M()Z -> entry.support()Z. w.d()Z also reads the native
        // pref_camera_handle_equip_street: do not bypass that setting or touch mode 256.
        Method gate = method(loader, "Je.c", "M", boolean.class, false);
        method(loader, "com.android.camera.data.data.w", "d", boolean.class, true);
        String entry = "com.android.camera.features.mode.equipstreet.EquipStreetModuleEntry";
        method(loader, entry, "getModuleId", int.class, false);
        method(loader, entry, "support", boolean.class, false);
        Class<?> module = Class.forName(
                "com.android.camera.features.mode.equipstreet.EquipStreetModule", false, loader);
        if (!Class.forName("com.android.camera.module.Camera2Module", false, loader).isAssignableFrom(module)) {
            throw new NoSuchMethodException("229 module hierarchy changed");
        }
        return gate;
    }

    static Method method(ClassLoader loader, String owner, String name, Class<?> result,
                         boolean isStatic, Class<?>... args) throws ReflectiveOperationException {
        Method method = Class.forName(owner, false, loader).getDeclaredMethod(name, args);
        if (method.getReturnType() != result || Modifier.isStatic(method.getModifiers()) != isStatic) {
            throw new NoSuchMethodException(owner + "." + name + " return/static mismatch");
        }
        return method;
    }
}
