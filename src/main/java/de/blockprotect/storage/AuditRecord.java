package de.blockprotect.storage;

import org.bukkit.Location;

import java.util.UUID;

public record AuditRecord(
        long timestamp,
        UUID actorUuid,
        String actorName,
        String source,
        String action,
        String world,
        Integer x,
        Integer y,
        Integer z,
        String target,
        int amount,
        String itemType,
        String beforeState,
        String afterState,
        String details
) {
    public static AuditRecord at(
            UUID actorUuid,
            String actorName,
            String source,
            String action,
            Location location,
            String target,
            int amount,
            String itemType,
            String beforeState,
            String afterState,
            String details
    ) {
        return new AuditRecord(
                System.currentTimeMillis(),
                actorUuid,
                actorName,
                source,
                action,
                location == null || location.getWorld() == null ? null : location.getWorld().getName(),
                location == null ? null : location.getBlockX(),
                location == null ? null : location.getBlockY(),
                location == null ? null : location.getBlockZ(),
                target,
                amount,
                itemType,
                beforeState,
                afterState,
                details
        );
    }
}

