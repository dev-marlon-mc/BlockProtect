package de.blockprotect.module;

public record ModuleSnapshot(String id,
                             String name,
                             String version,
                             boolean enabled,
                             ModuleStatus status,
                             String availableUpdate,
                             String message) {
}
