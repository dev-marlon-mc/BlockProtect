package de.blockprotect.module.internal;

import org.bukkit.Bukkit;
import de.blockprotect.module.ModuleApi;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CompatibilityChecker {
    private static final Pattern MINECRAFT_VERSION = Pattern.compile("(\\d+\\.\\d+(?:\\.\\d+)?)");

    private CompatibilityChecker() {
    }

    public static void requireCompatible(JavaPlugin plugin, ModuleDescriptor descriptor) {
        List<String> problems = new ArrayList<>();
        if (descriptor.apiVersion() != ModuleApi.VERSION) {
            problems.add("Core-API " + descriptor.apiVersion() + " benötigt, installiert ist " + ModuleApi.VERSION);
        }

        String paperApi = plugin.getDescription().getAPIVersion();
        if (paperApi != null && !paperApi.isBlank()) {
            compareRange("Paper-API", paperApi, descriptor.paperApiMin(), descriptor.paperApiMax(), problems);
        }

        String minecraft = minecraftVersion();
        if (minecraft != null) {
            compareRange("Minecraft", minecraft, descriptor.minecraftMin(), descriptor.minecraftMax(), problems);
        }

        if (!Bukkit.getName().toLowerCase(java.util.Locale.ROOT).contains("paper")) {
            problems.add("Server ist nicht Paper (erkannt: " + Bukkit.getName() + ")");
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException(String.join("; ", problems));
        }
    }

    private static void compareRange(String label, String actual, String min, String max, List<String> problems) {
        try {
            ModuleVersion current = ModuleVersion.parse(actual);
            if (min != null && current.compareTo(ModuleVersion.parse(min)) < 0) {
                problems.add(label + " " + actual + " ist kleiner als " + min);
            }
            if (max != null && current.compareTo(ModuleVersion.parse(max)) > 0) {
                problems.add(label + " " + actual + " ist größer als " + max);
            }
        } catch (IllegalArgumentException exception) {
            problems.add("Unbekannte " + label + "-Version " + actual);
        }
    }

    private static String minecraftVersion() {
        String[] candidates = {Bukkit.getBukkitVersion(), Bukkit.getVersion()};
        for (String candidate : candidates) {
            Matcher matcher = MINECRAFT_VERSION.matcher(candidate == null ? "" : candidate);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }
}
