package de.blockprotect.module.internal;

import de.blockprotect.module.ModuleCommand;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Command multiplexer owned by the core; registrations disappear with a module. */
public final class ModuleCommandRegistry {
    private final Map<String, Registration> registrations = new ConcurrentHashMap<>();

    public AutoCloseable register(String moduleId, String name, String permission, ModuleCommand command) {
        String key = normalize(name);
        Registration registration = new Registration(moduleId, permission, command);
        Registration previous = registrations.putIfAbsent(key, registration);
        if (previous != null) {
            throw new IllegalArgumentException("Modul-Befehl ist bereits registriert: " + key);
        }
        return () -> registrations.remove(key, registration);
    }

    public boolean dispatch(String name, CommandSender sender, String[] args) {
        Registration registration = registrations.get(normalize(name));
        if (registration == null) {
            return false;
        }
        if (registration.permission() != null && !registration.permission().isBlank()
                && !sender.hasPermission(registration.permission())) {
            sender.sendMessage("§cDafür fehlt dir die Berechtigung: " + registration.permission());
            return true;
        }
        return registration.command().execute(sender, args);
    }

    public List<String> names() {
        List<String> names = new ArrayList<>(registrations.keySet());
        Collections.sort(names);
        return names;
    }

    private static String normalize(String name) {
        String normalized = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9_-]{0,31}")) {
            throw new IllegalArgumentException("Ungültiger Modul-Befehl: " + name);
        }
        return normalized;
    }

    private record Registration(String moduleId, String permission, ModuleCommand command) {
    }
}
