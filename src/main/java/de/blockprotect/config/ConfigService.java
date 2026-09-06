package de.blockprotect.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Owns the live configuration snapshot. Event handlers never read Bukkit's mutable
 * YamlConfiguration directly; they read the immutable snapshot published here.
 */
public final class ConfigService {
    private final JavaPlugin plugin;
    private final CopyOnWriteArrayList<Consumer<Map<String, Object>>> listeners = new CopyOnWriteArrayList<>();
    private volatile FileConfiguration fileConfiguration;
    private volatile Map<String, Object> snapshot = Map.of();

    public ConfigService(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public synchronized void load() {
        plugin.saveDefaultConfig();
        this.fileConfiguration = plugin.getConfig();
        publish();
    }

    public void addListener(Consumer<Map<String, Object>> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public Map<String, Object> snapshot() {
        return snapshot;
    }

    public Object get(String path) {
        return snapshot.get(path.toLowerCase(Locale.ROOT));
    }

    public boolean getBoolean(String path, boolean fallback) {
        Object value = get(path);
        return value instanceof Boolean booleanValue ? booleanValue : fallback;
    }

    public int getInt(String path, int fallback) {
        Object value = get(path);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    public String getString(String path, String fallback) {
        Object value = get(path);
        return value instanceof String string ? string : fallback;
    }

    public List<String> getStringList(String path) {
        Object value = get(path);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    public boolean isModuleEnabled(String module) {
        return getBoolean("tracking.modules." + module, false)
                && getBoolean("tracking.enabled", true);
    }

    public synchronized String set(String path, String rawValue) {
        String normalizedPath = normalizePath(path);
        Object current = fileConfiguration.get(normalizedPath);
        if (current == null) {
            throw new IllegalArgumentException("Unbekannter Einstellungspfad: " + normalizedPath);
        }

        Object parsed = parseLike(current, rawValue);
        validate(normalizedPath, parsed);
        fileConfiguration.set(normalizedPath, parsed);
        plugin.saveConfig();
        publish();
        return format(parsed);
    }

    public String value(String path) {
        Object value = get(path);
        if (value == null) {
            return null;
        }
        return format(value);
    }

    public List<String> keys(String prefix) {
        String normalizedPrefix = prefix == null ? "" : normalizePath(prefix);
        return snapshot.keySet().stream()
                .filter(key -> normalizedPrefix.isBlank() || key.startsWith(normalizedPrefix))
                .sorted()
                .toList();
    }

    private synchronized void publish() {
        Map<String, Object> flattened = new LinkedHashMap<>();
        flatten(fileConfiguration, "", flattened);
        snapshot = Collections.unmodifiableMap(flattened);
        for (Consumer<Map<String, Object>> listener : listeners) {
            try {
                listener.accept(snapshot);
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("Konfigurations-Listener fehlgeschlagen: " + exception.getMessage());
            }
        }
    }

    private void flatten(ConfigurationSection section, String prefix, Map<String, Object> result) {
        for (String key : section.getKeys(false)) {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            Object value = section.get(key);
            if (value instanceof ConfigurationSection child) {
                flatten(child, path, result);
            } else if (value instanceof List<?> list) {
                result.put(path.toLowerCase(Locale.ROOT), List.copyOf(list));
            } else {
                result.put(path.toLowerCase(Locale.ROOT), value);
            }
        }
    }

    private static Object parseLike(Object current, String raw) {
        String value = raw.trim();
        if (current instanceof Boolean) {
            if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
                throw new IllegalArgumentException("Erwartet true oder false.");
            }
            return Boolean.parseBoolean(value);
        }
        if (current instanceof Integer) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Erwartet eine ganze Zahl.");
            }
        }
        if (current instanceof Long) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Erwartet eine ganze Zahl.");
            }
        }
        if (current instanceof Double || current instanceof Float) {
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Erwartet eine Zahl.");
            }
        }
        if (current instanceof List<?>) {
            if (value.isBlank() || value.equals("[]")) {
                return new ArrayList<>();
            }
            return List.of(value.split(","));
        }
        return raw;
    }

    private static void validate(String path, Object value) {
        if (path.equals("storage.database") && value instanceof String database) {
            java.nio.file.Path candidate = java.nio.file.Path.of(database);
            if (database.isBlank() || candidate.isAbsolute() || candidate.normalize().startsWith("..")) {
                throw new IllegalArgumentException("storage.database muss ein relativer Dateiname innerhalb des Plugin-Ordners sein.");
            }
        }
        if (value instanceof Number number) {
            int numeric = number.intValue();
            if (path.equals("storage.busy-timeout-ms") && (numeric < 0 || numeric > 120_000)) {
                throw new IllegalArgumentException("storage.busy-timeout-ms muss zwischen 0 und 120000 liegen.");
            }
            if (path.equals("storage.batch-size") && (numeric < 1 || numeric > 10_000)) {
                throw new IllegalArgumentException("storage.batch-size muss zwischen 1 und 10000 liegen.");
            }
            if (path.equals("storage.flush-interval-ticks") && (numeric < 1 || numeric > 72_000)) {
                throw new IllegalArgumentException("storage.flush-interval-ticks muss zwischen 1 und 72000 liegen.");
            }
            if (path.equals("storage.max-queue-size") && (numeric < 100 || numeric > 5_000_000)) {
                throw new IllegalArgumentException("storage.max-queue-size muss zwischen 100 und 5000000 liegen.");
            }
            if (path.equals("storage.retention-days") && numeric < 0) {
                throw new IllegalArgumentException("storage.retention-days darf nicht negativ sein.");
            }
            if (path.equals("lookup.default-radius") && (numeric < 0 || numeric > 10_000)) {
                throw new IllegalArgumentException("lookup.default-radius muss zwischen 0 und 10000 liegen.");
            }
            if (path.equals("lookup.default-limit") && (numeric < 1 || numeric > 10_000)) {
                throw new IllegalArgumentException("lookup.default-limit muss zwischen 1 und 10000 liegen.");
            }
            if (path.equals("lookup.max-limit") && (numeric < 1 || numeric > 50_000)) {
                throw new IllegalArgumentException("lookup.max-limit muss zwischen 1 und 50000 liegen.");
            }
            if (path.equals("rollback.default-radius") && (numeric < 0 || numeric > 200)) {
                throw new IllegalArgumentException("rollback.default-radius muss zwischen 0 und 200 liegen.");
            }
            if (path.equals("rollback.max-records") && (numeric < 1 || numeric > 50_000)) {
                throw new IllegalArgumentException("rollback.max-records muss zwischen 1 und 50000 liegen.");
            }
            if (path.equals("rollback.confirm-seconds") && (numeric < 10 || numeric > 600)) {
                throw new IllegalArgumentException("rollback.confirm-seconds muss zwischen 10 und 600 liegen.");
            }
        }
    }

    private static String normalizePath(String path) {
        return path.trim().toLowerCase(Locale.ROOT);
    }

    private static String format(Object value) {
        if (value instanceof List<?> list) {
            return String.join(",", list.stream().map(String::valueOf).toList());
        }
        return String.valueOf(value);
    }
}
