package de.blockprotect.storage;

public record AuditQuery(
        String world,
        int minX,
        int maxX,
        int minY,
        int maxY,
        int minZ,
        int maxZ,
        String actorName,
        String action,
        String source,
        String target,
        Long sinceTimestamp,
        Long untilTimestamp,
        int limit
) {
    public AuditQuery(
            String world,
            int minX,
            int maxX,
            int minY,
            int maxY,
            int minZ,
            int maxZ,
            String actorName,
            String action,
            String source,
            String target,
            int limit
    ) {
        this(world, minX, maxX, minY, maxY, minZ, maxZ,
                actorName, action, source, target, null, null, limit);
    }
}
