package de.blockprotect.module;

/** Entry point implemented by every dynamically loaded BlockProtect module. */
public interface BlockProtectModule {
    void enable(ModuleContext context) throws Exception;

    default void disable() throws Exception {
        // Optional module-specific shutdown hook. Core cleanup always follows.
    }
}
