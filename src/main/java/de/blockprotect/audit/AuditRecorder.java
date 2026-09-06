package de.blockprotect.audit;

import de.blockprotect.config.ConfigService;
import de.blockprotect.storage.AuditRecord;
import de.blockprotect.storage.AuditStore;
import org.bukkit.Location;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;

import java.util.Locale;
import java.util.UUID;

public final class AuditRecorder {
    private final ConfigService config;
    private final AuditStore store;

    public AuditRecorder(org.bukkit.plugin.java.JavaPlugin plugin, ConfigService config, AuditStore store) {
        this.config = config;
        this.store = store;
    }

    public boolean enabled(String module, Event event) {
        if (!config.isModuleEnabled(module)) {
            return false;
        }
        if (event != null && event instanceof Cancellable cancellable
                && cancellable.isCancelled()
                && !config.getBoolean("tracking.options.record-cancelled", false)) {
            return false;
        }
        return true;
    }

    public void record(String module, Event event, AuditRecord record) {
        if (!enabled(module, event) || ignored(record)) {
            return;
        }
        store.enqueue(limit(record));
    }

    public void record(String module, Event event, AuditRecord record, boolean optionEnabled) {
        if (!optionEnabled) {
            return;
        }
        record(module, event, record);
    }

    public boolean option(String path, boolean fallback) {
        return config.getBoolean(path, fallback);
    }

    public ConfigService config() {
        return config;
    }

    private boolean ignored(AuditRecord record) {
        if (record.world() != null && containsIgnore("tracking.options.ignored-worlds", record.world())) {
            return true;
        }
        if (record.actorName() != null && containsIgnore("tracking.options.ignored-players", record.actorName())) {
            return true;
        }
        if (record.target() != null && containsIgnore("tracking.options.ignored-blocks", record.target())) {
            return true;
        }
        if (record.target() != null && record.source().equalsIgnoreCase("entities")
                && containsIgnore("tracking.options.ignored-entities", record.target())) {
            return true;
        }
        return false;
    }

    private boolean containsIgnore(String path, String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return config.getStringList(path).stream()
                .map(item -> item.toLowerCase(Locale.ROOT).trim())
                .anyMatch(item -> item.equals(normalized));
    }

    private AuditRecord limit(AuditRecord record) {
        return new AuditRecord(
                record.timestamp(),
                record.actorUuid(),
                clean(record.actorName()),
                clean(record.source()),
                clean(record.action()),
                clean(record.world()),
                record.x(),
                record.y(),
                record.z(),
                clean(record.target()),
                record.amount(),
                config.getBoolean("tracking.options.item-details", true) ? clean(record.itemType()) : null,
                config.getBoolean("tracking.options.block-state", true) ? clean(record.beforeState()) : null,
                config.getBoolean("tracking.options.block-state", true) ? clean(record.afterState()) : null,
                clean(record.details())
        );
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.replace('\u0000', ' ').replace('\n', ' ').replace('\r', ' ');
        return normalized.length() <= 4_000 ? normalized : normalized.substring(0, 4_000);
    }

    public static AuditRecord at(
            UUID actorUuid,
            String actorName,
            String module,
            String action,
            Location location,
            String target,
            int amount,
            String itemType,
            String before,
            String after,
            String details
    ) {
        return AuditRecord.at(actorUuid, actorName, module, action, location, target, amount, itemType, before, after, details);
    }

    public static String actorName(org.bukkit.entity.Player player) {
        return player == null ? null : player.getName();
    }

    public static UUID actorUuid(org.bukkit.entity.Player player) {
        return player == null ? null : player.getUniqueId();
    }
}
