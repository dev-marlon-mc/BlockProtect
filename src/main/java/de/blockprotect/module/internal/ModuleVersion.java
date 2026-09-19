package de.blockprotect.module.internal;

import java.util.Arrays;
import java.util.Objects;

/** Small numeric version type used for release and rollback decisions. */
public final class ModuleVersion implements Comparable<ModuleVersion> {
    private final int[] parts;
    private final String text;

    private ModuleVersion(String text, int[] parts) {
        this.text = text;
        this.parts = parts;
    }

    public static ModuleVersion parse(String raw) {
        String value = Objects.requireNonNull(raw, "version").trim();
        if (value.startsWith("v") || value.startsWith("V")) {
            value = value.substring(1);
        }
        if (!value.matches("\\d+(?:\\.\\d+){0,3}")) {
            throw new IllegalArgumentException("Ungültige Modulversion: " + raw);
        }
        int[] parts = Arrays.stream(value.split("\\."))
                .mapToInt(Integer::parseInt)
                .toArray();
        return new ModuleVersion(value, parts);
    }

    public String text() {
        return text;
    }

    @Override
    public int compareTo(ModuleVersion other) {
        int length = Math.max(parts.length, other.parts.length);
        for (int index = 0; index < length; index++) {
            int left = index < parts.length ? parts[index] : 0;
            int right = index < other.parts.length ? other.parts[index] : 0;
            int comparison = Integer.compare(left, right);
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    @Override
    public String toString() {
        return text;
    }
}
