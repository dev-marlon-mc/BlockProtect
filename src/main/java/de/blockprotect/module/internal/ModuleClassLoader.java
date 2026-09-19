package de.blockprotect.module.internal;

import java.net.URL;
import java.net.URLClassLoader;

/** Child-first loader with an explicit parent-first API boundary. */
public final class ModuleClassLoader extends URLClassLoader {
    static {
        ClassLoader.registerAsParallelCapable();
    }

    public ModuleClassLoader(URL moduleJar, ClassLoader parent) {
        super(new URL[]{moduleJar}, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (parentFirst(name)) {
            return super.loadClass(name, resolve);
        }
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                try {
                    loaded = findClass(name);
                } catch (ClassNotFoundException ignored) {
                    loaded = super.loadClass(name, false);
                }
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    private static boolean parentFirst(String name) {
        return name.startsWith("java.")
                || name.startsWith("javax.")
                || name.startsWith("jdk.")
                || name.startsWith("sun.")
                || name.startsWith("org.bukkit.")
                || name.startsWith("io.papermc.")
                || name.startsWith("net.kyori.")
                || name.startsWith("de.blockprotect.module.")
                || name.startsWith("de.blockprotect.config.")
                || name.startsWith("de.blockprotect.storage.")
                || name.equals("de.blockprotect.audit.AuditModule")
                || name.equals("de.blockprotect.audit.AuditRecorder")
                || name.equals("de.blockprotect.audit.AuditUtil")
                || name.equals("de.blockprotect.audit.InspectionState");
    }
}
