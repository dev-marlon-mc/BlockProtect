package de.blockprotect.module.internal;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

public final class ModuleDescriptor {
    public static final String RESOURCE = "blockprotect-module.yml";
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{1,31}");

    private final String id;
    private final String name;
    private final ModuleVersion version;
    private final String entrypoint;
    private final int apiVersion;
    private final String paperApiMin;
    private final String paperApiMax;
    private final String minecraftMin;
    private final String minecraftMax;

    public ModuleDescriptor(String id,
                            String name,
                            ModuleVersion version,
                            String entrypoint,
                            int apiVersion,
                            String paperApiMin,
                            String paperApiMax,
                            String minecraftMin,
                            String minecraftMax) {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Ungültige Modul-ID: " + id);
        }
        this.id = id;
        this.name = name == null || name.isBlank() ? id : name.trim();
        this.version = Objects.requireNonNull(version, "version");
        this.entrypoint = requireText(entrypoint, "entrypoint");
        this.apiVersion = apiVersion;
        this.paperApiMin = blankToNull(paperApiMin);
        this.paperApiMax = blankToNull(paperApiMax);
        this.minecraftMin = blankToNull(minecraftMin);
        this.minecraftMax = blankToNull(minecraftMax);
    }

    public static ModuleDescriptor fromJar(Path jarPath) throws IOException {
        if (!Files.isRegularFile(jarPath) || Files.isSymbolicLink(jarPath)) {
            throw new IOException("Modul-JAR ist keine reguläre Datei: " + jarPath);
        }
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            var entry = jar.getJarEntry(RESOURCE);
            if (entry == null) {
                throw new IOException("Modul-JAR enthält keine " + RESOURCE);
            }
            YamlConfiguration yaml;
            try (InputStreamReader reader = new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8)) {
                yaml = YamlConfiguration.loadConfiguration(reader);
            }
            return new ModuleDescriptor(
                    requireText(yaml.getString("id"), "id"),
                    yaml.getString("name", yaml.getString("id", "module")),
                    ModuleVersion.parse(requireText(yaml.getString("version"), "version")),
                    requireText(yaml.getString("entrypoint"), "entrypoint"),
                    yaml.getInt("api-version", -1),
                    yaml.getString("paper-api-min", ""),
                    yaml.getString("paper-api-max", ""),
                    yaml.getString("minecraft-min", ""),
                    yaml.getString("minecraft-max", "")
            );
        }
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public ModuleVersion version() {
        return version;
    }

    public String entrypoint() {
        return entrypoint;
    }

    public int apiVersion() {
        return apiVersion;
    }

    public String paperApiMin() {
        return paperApiMin;
    }

    public String paperApiMax() {
        return paperApiMax;
    }

    public String minecraftMin() {
        return minecraftMin;
    }

    public String minecraftMax() {
        return minecraftMax;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Modul-Descriptor benötigt: " + field);
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
